package com.v2ray.ang.ui.chapar

/**
 * The connection modes Chapar offers (Chapar decision D-038). The server publishes one link per
 * mode; the mode is read from the link's name (see [ChaparModes]).
 * IRAN: the default. Iranian sites go through the Iran server (live since D-046), everything else
 * leaves from the main server.
 * TOR: everything goes through Tor.
 */
enum class ChaparMode(
    /** The English name used in the "Report a problem" text, which is never translated. */
    val label: String,
) {
    IRAN("Iran"),
    TOR("Tor"),
}

/** A stored profile, reduced to what the Chapar screen needs. */
data class ChaparProfile(val guid: String, val remarks: String)

sealed interface ConnectionState {
    data object Disconnected : ConnectionState
    data object Connecting : ConnectionState
    data object Connected : ConnectionState
    data object Disconnecting : ConnectionState

    /** Start failed. [message] is the core's own text, or null when it gave none. */
    data class Failed(val message: String?) : ConnectionState
}

data class ChaparUiState(
    /** False until the stored profiles were read once; the screen shows nothing before that. */
    val loaded: Boolean = false,
    val profiles: List<ChaparProfile> = emptyList(),
    val selectedGuid: String? = null,
    val connection: ConnectionState = ConnectionState.Disconnected,
    val account: AccountState = AccountState.Loading,
    /** A sign-in, nickname, sign-out, or deletion request is in flight. */
    val busy: Boolean = false,
    /** The last account error, shown once on the sign-in or nickname screen. */
    val accountError: AccountError? = null,
    /** The mode chosen before the first Connect, when no profiles are stored yet (D-043). */
    val chosenMode: ChaparMode = ChaparMode.IRAN,
    /** The "Report a problem" text while its dialog is open; null when closed. */
    val report: String? = null,
    /** The "Apps that skip the VPN" dialog while open; null when closed. */
    val bypass: BypassState? = null,
) {
    /** Before the first Connect every mode is offered; afterwards, the modes the server sent. */
    val availableModes: Set<ChaparMode>
        get() = if (profiles.isEmpty()) ChaparMode.entries.toSet() else ChaparModes.available(profiles)
    val selectedMode: ChaparMode
        get() = profiles.firstOrNull { it.guid == selectedGuid }
            ?.let { ChaparModes.classify(it.remarks) }
            ?: chosenMode
    val isRunning: Boolean
        get() = connection == ConnectionState.Connected || connection == ConnectionState.Disconnecting

    /** The service may hold the VPN interface: anything but a clean "not connected". */
    val mayHoldTunnel: Boolean
        get() = connection != ConnectionState.Disconnected

    /** Account actions wait while a connection is being set up or taken down. */
    val accountActionsEnabled: Boolean
        get() = connection != ConnectionState.Connecting && connection != ConnectionState.Disconnecting

    /** Connecting is allowed only for an account in good standing (or while its status is unknown). */
    val canConnect: Boolean
        get() = (account as? AccountState.SignedIn)?.info?.status.let { it == null || it == "active" }
}

sealed interface ChaparAction {
    /** Big button: connect when stopped, disconnect when running. Handled by the activity. */
    data object ToggleConnection : ChaparAction
    data class SelectMode(val mode: ChaparMode) : ChaparAction

    /** Google sign-in (verified Gmail, D-041). Handled by the activity, which needs an Activity context. */
    data object SignIn : ChaparAction
    data class SubmitNickname(val nickname: String) : ChaparAction
    data object CancelNickname : ChaparAction
    data object SignOut : ChaparAction
    data object DeleteAccount : ChaparAction

    /** "Report a problem": open the report, copy it (handled by the activity), close it. */
    data object OpenReport : ChaparAction
    data object CopyReport : ChaparAction
    data object CloseReport : ChaparAction

    /** "Apps that skip the VPN" (D-047). */
    data object OpenBypass : ChaparAction
    data class SetBypassFilter(val text: String) : ChaparAction
    data class ToggleBypassApp(val packageName: String) : ChaparAction
    data object SaveBypass : ChaparAction
    data object CloseBypass : ChaparAction

    /** Opens Android's VPN settings, where Always-on VPN and "Block connections without VPN" are turned on. */
    data object OpenVpnSettings : ChaparAction
}

/** An installed app that can be chosen to skip the VPN. */
data class InstalledApp(val packageName: String, val label: String)

/** The bypass dialog: every launchable app, the chosen ones, and the search text. */
data class BypassState(
    val apps: List<InstalledApp> = emptyList(),
    val selected: Set<String> = emptySet(),
    val filter: String = "",
    val loading: Boolean = true,
) {
    val visible: List<InstalledApp>
        get() = ChaparBypass.visible(apps, selected, filter)
}
