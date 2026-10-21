package com.v2ray.ang.ui.chapar

import java.time.OffsetDateTime
import java.time.format.DateTimeFormatter
import java.util.Locale

sealed interface AccountState {
    /** Session not checked yet. */
    data object Loading : AccountState
    data object SignedOut : AccountState

    /** Google accepted the account; the backend needs a nickname before it creates it. */
    data object NeedsNickname : AccountState

    /** [info] is null while the backend has not answered yet (for example offline). */
    data class SignedIn(val nickname: String, val info: AccountInfo?) : AccountState
}

/** What went wrong, in the user's terms. The screen turns each into one short sentence. */
enum class AccountError {
    NETWORK,
    GMAIL_ONLY,
    SIGNUP_CLOSED,
    NICKNAME_TAKEN,
    INVALID_NICKNAME,
    DISABLED,

    /** Too many attempts in a short time; try again in a minute. */
    RATE_LIMITED,

    /** The server is up but busy or could not prepare the connection; try again shortly. */
    SERVER_BUSY,

    /** The connection to Chapar could not be verified as secure (possible interception). */
    INSECURE,
    FAILED,
}

/** Pure account rules, kept free of Android so they run as JVM tests. */
object ChaparAccount {

    /** Same rule as the backend (backend/chapar/store.py). */
    val NICKNAME = Regex("[A-Za-z0-9_]{3,20}")

    private const val GIB = 1024.0 * 1024 * 1024

    fun errorFor(code: String): AccountError = when (code) {
        ChaparApiException.NETWORK, ChaparApiException.NETWORK_AFTER_SEND -> AccountError.NETWORK
        ChaparApiException.INSECURE -> AccountError.INSECURE
        "too_many_attempts" -> AccountError.RATE_LIMITED
        "node_unavailable", "verifier_unavailable", "too_slow" -> AccountError.SERVER_BUSY
        "gmail_only" -> AccountError.GMAIL_ONLY
        "signup_closed" -> AccountError.SIGNUP_CLOSED
        "nickname_taken" -> AccountError.NICKNAME_TAKEN
        "invalid_nickname" -> AccountError.INVALID_NICKNAME
        "disabled" -> AccountError.DISABLED
        else -> AccountError.FAILED
    }

    /** The session is gone on the server (expired, signed out elsewhere, account deleted). */
    fun isSignedOut(e: ChaparApiException): Boolean = e.httpStatus == 401

    fun gigabytesLeft(info: AccountInfo): String =
        String.format(Locale.US, "%.1f", (info.quotaBytes - info.usedBytes).coerceAtLeast(0) / GIB)

    /** "1 Nov": the day usage resets, in UTC as the backend counts it; empty if the server sent something odd. */
    fun resetDay(info: AccountInfo): String =
        runCatching { OffsetDateTime.parse(info.resetsAt).format(DateTimeFormatter.ofPattern("d MMM", Locale.US)) }.getOrDefault("")

}
