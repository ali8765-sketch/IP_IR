package com.v2ray.ang.ui.chapar

/**
 * Sign-in, refresh, connect, sign-out, and deletion against the backend (D-041, D-042, D-043).
 * Profiles exist only after a Connect, which asks the server for a new key. Blocking: call from Dispatchers.IO.
 */
class ChaparAccountService(
    private val api: ChaparApi,
    private val session: SessionStore,
    private val repository: ChaparRepository,
    /** Chapar's server: the only host Connect may hand out links for (ChaparLinks). */
    private val expectedHost: String,
) {
    sealed interface SignInOutcome {
        data object Done : SignInOutcome
        data object NeedsNickname : SignInOutcome
        data class Failed(val error: AccountError) : SignInOutcome
    }

    sealed interface RefreshOutcome {
        data object SignedOut : RefreshOutcome

        /** [info] is null when the backend could not be reached. */
        data class SignedIn(val nickname: String, val info: AccountInfo?) : RefreshOutcome
    }

    sealed interface ConnectOutcome {
        /** Profiles are stored and the VPN may start; [newKey] is false when the last key is retried offline. */
        data class Ready(val newKey: Boolean) : ConnectOutcome
        data object SignedOut : ConnectOutcome

        /** The account is disabled or over its quota; refresh the account to show why. */
        data object Blocked : ConnectOutcome
        data class Failed(val error: AccountError) : ConnectOutcome
    }

    fun signIn(idToken: String, nickname: String?): SignInOutcome {
        val signedIn = try {
            api.signIn(idToken, nickname)
        } catch (e: ChaparApiException) {
            return if (e.code == "nickname_required") SignInOutcome.NeedsNickname else SignInOutcome.Failed(ChaparAccount.errorFor(e.code))
        }
        return try {
            session.save(signedIn.token, signedIn.nickname)
            SignInOutcome.Done
        } catch (e: Exception) {
            // The phone could not store the session (a faulty Keystore). Do not leave a session open on the server.
            runCatching { api.logout(signedIn.token) }
            SignInOutcome.Failed(AccountError.FAILED)
        }
    }

    /** Reads the account and its usage. Never fetches a key: keys come only from [connect]. */
    fun refresh(): RefreshOutcome {
        val token = try {
            session.token()
        } catch (e: SessionUnavailable) {
            return RefreshOutcome.SignedIn(session.nickname().orEmpty(), null)
        } ?: return signedOutLocally()
        return try {
            val info = api.account(token)
            RefreshOutcome.SignedIn(info.nickname, info)
        } catch (e: ChaparApiException) {
            if (ChaparAccount.isSignedOut(e)) signedOutLocally() else RefreshOutcome.SignedIn(session.nickname().orEmpty(), null)
        }
    }

    /**
     * Gets a new key from the server and stores it as the profiles (D-043). Only a signed-in app can
     * do this. The answer must be share links for Chapar's own server (ChaparLinks). Only when the request
     * certainly never reached the server is the last key tried: once the server has seen a Connect it may
     * already have replaced that key.
     */
    fun connect(): ConnectOutcome {
        val token = try {
            session.token()
        } catch (e: SessionUnavailable) {
            return ConnectOutcome.Failed(AccountError.FAILED)
        } ?: return ConnectOutcome.SignedOut.also { clearLocal() }
        return try {
            val links = ChaparLinks.accept(api.connect(token), expectedHost)
                ?: return ConnectOutcome.Failed(AccountError.LINK_REFUSED)
            if (repository.replaceLink(links)) ConnectOutcome.Ready(newKey = true) else ConnectOutcome.Failed(AccountError.FAILED)
        } catch (e: ChaparApiException) {
            when {
                ChaparAccount.isSignedOut(e) -> ConnectOutcome.SignedOut.also { clearLocal() }
                e.code == "disabled" || e.code == "over_quota" -> ConnectOutcome.Blocked
                e.code == ChaparApiException.NETWORK && repository.profiles().isNotEmpty() -> ConnectOutcome.Ready(newKey = false)
                else -> ConnectOutcome.Failed(ChaparAccount.errorFor(e.code))
            }
        }
    }

    /** Ends the session on the server if it can be reached; always forgets it locally. */
    fun signOut() {
        runCatching { session.token() }.getOrNull()?.let { token -> runCatching { api.logout(token) } }
        clearLocal()
    }

    /** Deletes the account on the server. Returns null on success, else the reason; nothing local changes then. */
    fun deleteAccount(): AccountError? {
        val token = try {
            session.token()
        } catch (e: SessionUnavailable) {
            return AccountError.FAILED
        } ?: return null.also { clearLocal() }
        return try {
            api.deleteAccount(token)
            clearLocal()
            null
        } catch (e: ChaparApiException) {
            if (ChaparAccount.isSignedOut(e)) null.also { clearLocal() } else ChaparAccount.errorFor(e.code)
        }
    }

    private fun signedOutLocally(): RefreshOutcome.SignedOut {
        clearLocal()
        return RefreshOutcome.SignedOut
    }

    /** Without an account the app keeps no connection profiles (D-042). */
    private fun clearLocal() {
        session.clear()
        repository.clearAll()
    }
}
