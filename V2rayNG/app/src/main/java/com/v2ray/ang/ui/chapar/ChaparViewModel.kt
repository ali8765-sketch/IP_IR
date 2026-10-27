package com.v2ray.ang.ui.chapar

import android.app.Application
import android.content.Intent
import android.os.Build
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import com.v2ray.ang.AngApplication
import com.v2ray.ang.AppConfig
import com.v2ray.ang.BuildConfig
import com.v2ray.ang.R
import com.v2ray.ang.ui.main.MainServiceEvent
import com.v2ray.ang.util.LogUtil
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

/**
 * State for the Chapar screen. Service events (running, started, failed) and the VPN controls come from
 * [ChaparPlatform]; the account and its profiles from [ChaparAccountService]; stored profiles and the
 * selected mode from [ChaparRepository].
 *
 * Long operations (Connect, sign-in, sign-out, deletion) run in [viewModelScope], so rotating the
 * phone or switching dark mode never leaves the screen stuck. Only the steps that need an Activity
 * (VPN permission, Google's account picker) happen in the activity.
 */
class ChaparViewModel(
    private val platform: ChaparPlatform,
    private val repository: ChaparRepository,
    private val account: ChaparAccountService,
    private val diagnostics: ChaparDiagnostics,
    private val bypass: BypassStore,
    private val installedApps: () -> List<InstalledApp>,
    /** Storage, network, and package-manager work; a test passes its own dispatcher. */
    private val io: CoroutineDispatcher = Dispatchers.IO,
) : ViewModel() {

    private val _uiState = MutableStateFlow(ChaparUiState())
    val uiState: StateFlow<ChaparUiState> = _uiState.asStateFlow()

    /** "Start the VPN now": the activity asks Android for VPN permission and starts the service. */
    private val _startRequests = Channel<Unit>(Channel.CONFLATED)
    val startRequests: Flow<Unit> = _startRequests.receiveAsFlow()

    /** One account operation at a time: refreshes, sign-in, sign-out, and deletion all write the same storage. */
    private val accountLock = Mutex()

    /** The Google ID token waiting for a nickname. In memory only; it expires after about an hour. */
    private var pendingIdToken: String? = null

    private var connectJob: Job? = null
    private var watchdog: Job? = null

    /**
     * Set when the user cancels a start whose request may still reach the daemon (the daemon process may not
     * exist yet, so the stop sent with the cancel can arrive first and be lost). A start reported after
     * that is stopped again. Cleared by the next Connect, by any stop or failure report, or after [WATCHDOG_MS].
     */
    private var cancelledStart: Job? = null

    /** The activity asked the service to start during the current Connect. */
    private var startSent = false

    init {
        viewModelScope.launch {
            platform.serviceEvents.collect(::onServiceEvent)
        }
        viewModelScope.launch(io) {
            try {
                // Geo files for routing: same start-up work as upstream's main screen.
                platform.initAssets()
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (e: Exception) {
                LogUtil.e(AppConfig.TAG, "Chapar: start-up initialization failed", e)
            }
        }
        // The account is loaded by refresh(), which the activity calls from onResume.
    }

    private fun onServiceEvent(event: MainServiceEvent) {
        if (cancelledStart?.isActive == true) {
            when (event) {
                MainServiceEvent.StateStartSuccess, MainServiceEvent.StateRunning -> {
                    // The cancelled start went through after all: stop it again.
                    platform.stopVpn()
                    _uiState.update { it.copy(connection = ConnectionState.Disconnecting) }
                    startWatchdog()
                    return
                }

                MainServiceEvent.StateStopSuccess, MainServiceEvent.StateNotRunning, is MainServiceEvent.StateStartFailure ->
                    cancelledStart?.cancel()

                else -> Unit
            }
        }
        val before = _uiState.value
        _uiState.update { state ->
            ChaparModes.onServiceEvent(state.connection, event)?.let { state.copy(connection = it) } ?: state
        }
        val after = _uiState.value.connection
        if (after != ConnectionState.Connecting && after != ConnectionState.Disconnecting) watchdog?.cancel()
        recordConnectionChange(before.connection, after, before.selectedMode)
    }

    /** Re-reads the account and usage, and asks the daemon whether the VPN still runs. Called on start and on resume. */
    fun refresh() {
        platform.queryServiceState()
        viewModelScope.launch { refreshAccount() }
    }

    private suspend fun refreshAccount() {
        val before = _uiState.value.account
        val outcome = accountLock.withLock { withContext(io) { account.refresh() } }
        when (outcome) {
            ChaparAccountService.RefreshOutcome.SignedOut -> {
                if (before is AccountState.SignedIn) diagnostics.record(ChaparEvent.SESSION_ENDED)
                // The session ended elsewhere (deleted, expired, signed out by a newer device): the profiles are
                // gone, so the VPN must not keep running behind the sign-in screen.
                stopIfHoldingTunnel()
                if (_uiState.value.account != AccountState.NeedsNickname) {
                    _uiState.update { it.copy(account = AccountState.SignedOut) }
                }
            }

            is ChaparAccountService.RefreshOutcome.SignedIn -> {
                when (outcome.info?.status) {
                    null -> diagnostics.record(ChaparEvent.SERVER_UNREACHABLE)
                    "over_quota" -> diagnostics.record(ChaparEvent.OVER_QUOTA)
                    "disabled" -> diagnostics.record(ChaparEvent.DISABLED)
                }
                _uiState.update { it.copy(account = AccountState.SignedIn(outcome.nickname, outcome.info)) }
            }
        }
        reloadProfiles()
    }

    /** The big button. */
    fun toggleConnection() {
        val state = _uiState.value
        when (state.connection) {
            ConnectionState.Connecting -> cancelConnection()
            ConnectionState.Disconnecting -> Unit
            ConnectionState.Connected -> {
                _uiState.update { it.copy(connection = ConnectionState.Disconnecting) }
                startWatchdog()
                platform.stopVpn()
            }

            ConnectionState.Disconnected, is ConnectionState.Failed -> connect(afterFailure = state.connection is ConnectionState.Failed)
        }
    }

    /**
     * Gets a new key from the server (D-043), selects the profile for the chosen mode, and asks the
     * activity to start the VPN. A failed start may still hold the VPN interface, so it is released first;
     * its "stopped" answer keeps the screen on Connecting (ChaparModes.onServiceEvent).
     */
    private fun connect(afterFailure: Boolean) {
        if (connectJob?.isActive == true) return
        cancelledStart?.cancel()
        startSent = false
        val mode = _uiState.value.selectedMode
        _uiState.update { it.copy(connection = ConnectionState.Connecting) }
        connectJob = viewModelScope.launch {
            var starting = false
            try {
                if (afterFailure) platform.stopVpn()
                val outcome = accountLock.withLock { withContext(io) { account.connect() } }
                if (outcome is ChaparAccountService.ConnectOutcome.Ready) {
                    diagnostics.record(if (outcome.newKey) ChaparEvent.KEY_RECEIVED else ChaparEvent.KEY_REUSED, mode)
                    reloadProfiles(mode)
                    if (ChaparModes.profileFor(mode, _uiState.value.profiles) == null) {
                        // The server sent no link for the chosen mode: never start a different mode silently.
                        diagnostics.record(ChaparEvent.MODE_MISSING, mode)
                        platform.showError(R.string.chapar_mode_missing)
                        return@launch
                    }
                    starting = true
                    // The watchdog starts when the activity really asks for the start (onStartRequested): the
                    // request waits while the screen is in the background.
                    _startRequests.send(Unit)
                    return@launch
                }
                handleConnectFailure(outcome)
            } finally {
                if (!starting) _uiState.update {
                    if (it.connection == ConnectionState.Connecting) it.copy(connection = ConnectionState.Disconnected) else it
                }
            }
        }
    }

    private suspend fun handleConnectFailure(outcome: ChaparAccountService.ConnectOutcome) {
        _uiState.update { it.copy(connection = ConnectionState.Disconnected) }
        when (outcome) {
            ChaparAccountService.ConnectOutcome.SignedOut -> {
                diagnostics.record(ChaparEvent.SESSION_ENDED)
                _uiState.update { it.copy(account = AccountState.SignedOut) }
                reloadProfiles()
            }

            ChaparAccountService.ConnectOutcome.Blocked -> {
                diagnostics.record(ChaparEvent.KEY_REFUSED)
                refreshAccount() // shows the over-quota or disabled notice
            }

            is ChaparAccountService.ConnectOutcome.Failed -> {
                diagnostics.record(ChaparEvent.KEY_FAILED, error = outcome.error)
                platform.showError(connectErrorText(outcome.error))
            }

            is ChaparAccountService.ConnectOutcome.Ready -> Unit
        }
    }

    /** Tap while connecting: stop waiting, and stop the service if the start already went out. */
    private fun cancelConnection() {
        connectJob?.cancel()
        watchdog?.cancel()
        platform.stopVpn()
        cancelledStart?.cancel()
        if (startSent) cancelledStart = viewModelScope.launch { delay(WATCHDOG_MS) }
        startSent = false
        _uiState.update { it.copy(connection = ConnectionState.Disconnected) }
    }

    /** Connecting or disconnecting that never finishes ends as a visible failure after 45 seconds. */
    private fun startWatchdog() {
        watchdog?.cancel()
        watchdog = viewModelScope.launch {
            delay(WATCHDOG_MS)
            val connection = _uiState.value.connection
            if (connection == ConnectionState.Connecting || connection == ConnectionState.Disconnecting) {
                diagnostics.record(ChaparEvent.START_FAILED, _uiState.value.selectedMode)
                platform.stopVpn()
                _uiState.update { it.copy(connection = ConnectionState.Failed(null)) }
            }
        }
    }

    /** True if the activity should still start the VPN (the request may have waited while the screen was away). */
    fun startStillWanted(): Boolean = _uiState.value.connection == ConnectionState.Connecting

    /** Re-reads the stored profiles; [mode] picks that mode's profile, for example after a new key. */
    private suspend fun reloadProfiles(mode: ChaparMode? = null) {
        val (profiles, selected) = withContext(io) {
            val profiles = repository.profiles()
            val stored = repository.selectedGuid()
            val selected = mode?.let { ChaparModes.profileFor(it, profiles) }
                ?: stored?.takeIf { guid -> profiles.any { it.guid == guid } }
                ?: ChaparModes.defaultSelection(profiles)
            if (selected != null && selected != stored) repository.select(selected)
            profiles to selected
        }
        _uiState.update { it.copy(loaded = true, profiles = profiles, selectedGuid = selected) }
    }

    /** Called by the activity with the ID token Google returned for the chosen account. */
    fun signIn(idToken: String) = submit(idToken, nickname = null)

    fun submitNickname(nickname: String) {
        val token = pendingIdToken
        if (token == null) {
            _uiState.update { it.copy(account = AccountState.SignedOut, accountError = AccountError.FAILED) }
            return
        }
        if (!ChaparAccount.NICKNAME.matches(nickname)) {
            _uiState.update { it.copy(accountError = AccountError.INVALID_NICKNAME) }
            return
        }
        submit(token, nickname)
    }

    fun cancelNickname() {
        pendingIdToken = null
        _uiState.update { it.copy(account = AccountState.SignedOut, accountError = null) }
    }

    /** The Google account picker failed, was dismissed, or was interrupted: no request reached our backend. */
    fun onGoogleSignInFailed(error: AccountError?) {
        _uiState.update { it.copy(busy = false, accountError = error) }
    }

    fun onGoogleSignInStarted() {
        _uiState.update { it.copy(busy = true, accountError = null) }
    }

    private fun submit(idToken: String, nickname: String?) {
        viewModelScope.launch {
            _uiState.update { it.copy(busy = true, accountError = null) }
            try {
                val outcome = accountLock.withLock { withContext(io) { account.signIn(idToken, nickname) } }
                when (outcome) {
                    ChaparAccountService.SignInOutcome.Done -> {
                        diagnostics.record(ChaparEvent.SIGNED_IN)
                        pendingIdToken = null
                        refreshAccount()
                    }

                    ChaparAccountService.SignInOutcome.NeedsNickname -> {
                        pendingIdToken = idToken
                        _uiState.update { it.copy(account = AccountState.NeedsNickname) }
                    }

                    is ChaparAccountService.SignInOutcome.Failed -> {
                        diagnostics.record(ChaparEvent.SIGN_IN_FAILED, error = outcome.error)
                        val keepNickname = nickname != null &&
                            (outcome.error == AccountError.NICKNAME_TAKEN || outcome.error == AccountError.INVALID_NICKNAME)
                        if (!keepNickname) pendingIdToken = null
                        _uiState.update {
                            it.copy(
                                account = if (keepNickname) AccountState.NeedsNickname else AccountState.SignedOut,
                                accountError = outcome.error,
                            )
                        }
                    }
                }
            } finally {
                _uiState.update { it.copy(busy = false) }
            }
        }
    }

    /** Stops the VPN, ends the session, and removes the stored profiles and the local report. */
    fun signOut() {
        viewModelScope.launch {
            _uiState.update { it.copy(busy = true) }
            try {
                connectJob?.cancel()
                stopIfHoldingTunnel()
                accountLock.withLock {
                    withContext(io) {
                        account.signOut()
                        diagnostics.clear() // nothing about the account's use stays on the phone
                    }
                }
                _uiState.update { it.copy(account = AccountState.SignedOut, accountError = null) }
                reloadProfiles()
            } finally {
                _uiState.update { it.copy(busy = false) }
            }
        }
    }

    fun deleteAccount() {
        viewModelScope.launch {
            _uiState.update { it.copy(busy = true) }
            try {
                val error = accountLock.withLock { withContext(io) { account.deleteAccount() } }
                if (error == null) {
                    withContext(io) { diagnostics.clear() } // nothing about a deleted account stays on the phone
                    connectJob?.cancel()
                    stopIfHoldingTunnel()
                    _uiState.update { it.copy(account = AccountState.SignedOut, accountError = null) }
                    platform.showSuccess(R.string.chapar_account_deleted)
                    reloadProfiles()
                } else {
                    platform.showError(R.string.chapar_delete_failed)
                }
            } finally {
                _uiState.update { it.copy(busy = false) }
            }
        }
    }

    private fun stopIfHoldingTunnel() {
        if (_uiState.value.mayHoldTunnel) {
            watchdog?.cancel()
            platform.stopVpn()
            _uiState.update { it.copy(connection = ConnectionState.Disconnected) }
        }
    }

    /** Called by the activity right before it asks the service to start. */
    fun onStartRequested() {
        _uiState.update { it.copy(connection = ConnectionState.Connecting) }
        startSent = true
        startWatchdog()
    }

    /** The start request never reached the service (no profile, permission refused, start not allowed). */
    fun onStartRejected() {
        watchdog?.cancel()
        diagnostics.record(ChaparEvent.START_REFUSED)
        _uiState.update { it.copy(connection = ConnectionState.Disconnected) }
        platform.showError(R.string.chapar_start_refused)
    }

    private fun recordConnectionChange(before: ConnectionState, after: ConnectionState, mode: ChaparMode) {
        if (before == after) return
        when (after) {
            ConnectionState.Connected -> diagnostics.record(ChaparEvent.CONNECTED, mode)
            is ConnectionState.Failed -> diagnostics.record(ChaparEvent.START_FAILED, mode) // the core's message is not kept
            ConnectionState.Disconnected -> if (before == ConnectionState.Connected || before == ConnectionState.Disconnecting) {
                diagnostics.record(ChaparEvent.DISCONNECTED)
            }

            else -> Unit
        }
    }

    /** The first lines of a report: app, Android, account, mode, and connection now. Kept in English for the admin. */
    private fun reportHeader(): List<String> {
        val state = _uiState.value
        return listOf(
            "Chapar ${BuildConfig.VERSION_NAME}",
            "Android ${Build.VERSION.RELEASE} (API ${Build.VERSION.SDK_INT})",
            "Account: ${(state.account as? AccountState.SignedIn)?.nickname ?: "signed out"}",
            "Mode: ${state.selectedMode.label}",
            "Now: " + when (state.connection) {
                ConnectionState.Connected -> "connected"
                ConnectionState.Connecting -> "connecting"
                ConnectionState.Disconnecting -> "disconnecting"
                ConnectionState.Disconnected -> "not connected"
                is ConnectionState.Failed -> "start failed"
            },
        )
    }

    /** Opens the "Report a problem" dialog with the current report. */
    fun openReport() {
        viewModelScope.launch {
            val header = reportHeader()
            val text = withContext(io) { diagnostics.report(header) }
            _uiState.update { it.copy(report = text) }
        }
    }

    // ---- "Send a message" to the admin (D-056)

    fun openFeedback() {
        _uiState.update { it.copy(feedback = FeedbackState()) }
    }

    fun setFeedbackKind(kind: FeedbackKind) = updateFeedback { it.copy(kind = kind) }

    fun setFeedbackText(text: String) = updateFeedback { it.copy(text = text.take(FeedbackState.MAX_TEXT)) }

    fun setFeedbackReport(attach: Boolean) = updateFeedback { it.copy(attachReport = attach) }

    /** Closing is ignored while the message is being sent, so the result is never lost. */
    fun closeFeedback() {
        _uiState.update { if (it.feedback?.sending == true) it else it.copy(feedback = null) }
    }

    fun sendFeedback() {
        val draft = _uiState.value.feedback ?: return
        if (!draft.canSend) return
        updateFeedback { it.copy(sending = true) }
        viewModelScope.launch {
            val report = if (draft.attachReport) {
                val header = reportHeader()
                withContext(io) { FeedbackState.trimReport(diagnostics.report(header)) }
            } else {
                null
            }
            val outcome = accountLock.withLock { withContext(io) { account.sendFeedback(draft.kind, draft.text.trim(), report) } }
            when (outcome) {
                ChaparAccountService.FeedbackOutcome.Sent -> {
                    _uiState.update { it.copy(feedback = null) }
                    platform.showSuccess(R.string.chapar_feedback_sent)
                }

                ChaparAccountService.FeedbackOutcome.SignedOut -> {
                    diagnostics.record(ChaparEvent.SESSION_ENDED)
                    stopIfHoldingTunnel()
                    _uiState.update { it.copy(feedback = null, account = AccountState.SignedOut) }
                    reloadProfiles()
                }

                is ChaparAccountService.FeedbackOutcome.Failed -> {
                    updateFeedback { it.copy(sending = false) }
                    platform.showError(feedbackErrorText(outcome.error))
                }
            }
        }
    }

    private fun updateFeedback(change: (FeedbackState) -> FeedbackState) {
        _uiState.update { state -> state.feedback?.let { state.copy(feedback = change(it)) } ?: state }
    }

    fun closeReport() {
        _uiState.update { it.copy(report = null) }
    }

    /**
     * Selects [mode]. Before the first Connect it is only remembered. While connected, the core switches to
     * the new profile in place: the VPN interface stays up, so apps never use the real network (D-047).
     * If the daemon cannot switch, it reports a failure or "not running", which the screen then shows.
     */
    fun selectMode(mode: ChaparMode) {
        viewModelScope.launch {
            if (_uiState.value.profiles.isEmpty()) {
                _uiState.update { it.copy(chosenMode = mode) } // used by the first Connect
                return@launch
            }
            val guid = ChaparModes.switchTarget(_uiState.value, mode) ?: return@launch
            try {
                withContext(io) { repository.select(guid) }
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (e: Exception) {
                LogUtil.e(AppConfig.TAG, "Chapar: storing the selected mode failed", e)
                platform.showError(R.string.toast_failure)
                return@launch
            }
            _uiState.update { it.copy(selectedGuid = guid) }
            if (_uiState.value.connection == ConnectionState.Connected) {
                diagnostics.record(ChaparEvent.MODE_SWITCHED, mode)
                platform.reloadVpn()
            }
        }
    }

    // ---- "Apps that skip the VPN"

    fun openBypass() {
        _uiState.update { it.copy(bypass = BypassState(loading = true)) }
        viewModelScope.launch {
            val (apps, selected) = withContext(io) { installedApps() to bypass.selected() }
            _uiState.update { state ->
                state.bypass?.let { state.copy(bypass = it.copy(apps = apps, selected = selected, loading = false)) } ?: state
            }
        }
    }

    fun setBypassFilter(text: String) {
        _uiState.update { state -> state.bypass?.let { state.copy(bypass = it.copy(filter = text)) } ?: state }
    }

    fun toggleBypassApp(packageName: String) {
        _uiState.update { state ->
            state.bypass?.let { state.copy(bypass = it.copy(selected = ChaparBypass.toggle(it.selected, packageName))) } ?: state
        }
    }

    fun saveBypass() {
        val current = _uiState.value.bypass ?: return
        viewModelScope.launch {
            val chosen = ChaparBypass.cleaned(current.selected, current.apps)
            val changed = withContext(io) {
                val before = bypass.selected()
                bypass.save(chosen)
                before != chosen
            }
            _uiState.update { it.copy(bypass = null) }
            // The list is applied when the VPN interface is built. A running connection is restarted at once:
            // an app taken off the list must not keep using the real address until the next Connect.
            if (changed && _uiState.value.connection == ConnectionState.Connected) {
                diagnostics.record(ChaparEvent.BYPASS_APPLIED)
                _uiState.update { it.copy(connection = ConnectionState.Connecting) }
                startWatchdog()
                platform.restartVpn {
                    viewModelScope.launch {
                        watchdog?.cancel()
                        _uiState.update { it.copy(connection = ConnectionState.Disconnected) }
                    }
                }
            }
        }
    }

    fun closeBypass() {
        _uiState.update { it.copy(bypass = null) }
    }

    override fun onCleared() {
        platform.close()
        super.onCleared()
    }

    /** Builds every dependency only when the ViewModel itself is created, not on each activity recreation. */
    class Factory(private val application: Application) : ViewModelProvider.Factory {
        @Suppress("UNCHECKED_CAST")
        override fun <T : ViewModel> create(modelClass: Class<T>): T {
            if (!modelClass.isAssignableFrom(ChaparViewModel::class.java)) throw IllegalArgumentException("Unknown ViewModel class")
            val repository = ChaparRepository()
            val service = ChaparAccountService(
                ChaparApi(BuildConfig.CHAPAR_API_URL),
                KeystoreSessionStore(),
                repository,
                ChaparLinks.hostOf(BuildConfig.CHAPAR_API_URL),
            )
            return ChaparViewModel(
                AndroidChaparPlatform(application as AngApplication),
                repository,
                service,
                ChaparDiagnostics(),
                MmkvBypassStore(),
                { launchableApps(application) },
            ) as T
        }
    }

    companion object {
        internal const val WATCHDOG_MS = 45_000L

        /** Apps with a launcher icon (visible through the manifest's <queries>), Chapar itself excluded. */
        fun launchableApps(application: Application): List<InstalledApp> {
            val pm = application.packageManager
            val intent = Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER)
            return pm.queryIntentActivities(intent, 0)
                .map { it.activityInfo.applicationInfo }
                .distinctBy { it.packageName }
                .filter { it.packageName != application.packageName }
                .map { InstalledApp(it.packageName, pm.getApplicationLabel(it).toString()) }
        }

        fun connectErrorText(error: AccountError): Int = when (error) {
            AccountError.NETWORK -> R.string.chapar_error_network
            AccountError.RATE_LIMITED -> R.string.chapar_error_rate_limited
            AccountError.SERVER_BUSY -> R.string.chapar_error_server_busy
            AccountError.INSECURE -> R.string.chapar_error_insecure
            else -> R.string.chapar_connect_failed
        }

        fun feedbackErrorText(error: AccountError): Int = when (error) {
            AccountError.FEEDBACK_LIMIT -> R.string.chapar_error_feedback_limit
            AccountError.NETWORK -> R.string.chapar_error_network
            AccountError.SERVER_BUSY -> R.string.chapar_error_server_busy
            AccountError.INSECURE -> R.string.chapar_error_insecure
            else -> R.string.chapar_feedback_failed
        }
    }
}
