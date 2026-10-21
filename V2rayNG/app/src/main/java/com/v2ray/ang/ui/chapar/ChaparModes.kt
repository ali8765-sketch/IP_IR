package com.v2ray.ang.ui.chapar

import com.v2ray.ang.ui.main.MainServiceEvent

/**
 * Pure rules of the Chapar screen, kept free of Android so they run as JVM tests.
 */
object ChaparModes {

    // Links from the server are named "Chapar-Iran" and "Chapar-Tor" (backend/chapar/profiles.py;
    // test links from server/main/links.sh add "-<tag>"). Older links said "Free" for what is now
    // Iran mode; anything that is not Tor is Iran mode. Match whole words only, so a name like
    // "Toronto" is not Tor mode.
    private val torWord = Regex("(^|[^a-z])tor([^a-z]|$)", RegexOption.IGNORE_CASE)

    fun classify(remarks: String): ChaparMode =
        if (torWord.containsMatchIn(remarks)) ChaparMode.TOR else ChaparMode.IRAN

    fun available(profiles: List<ChaparProfile>): Set<ChaparMode> =
        profiles.mapTo(LinkedHashSet()) { classify(it.remarks) }

    /** The first stored profile for [mode], or null when the link has none. */
    fun profileFor(mode: ChaparMode, profiles: List<ChaparProfile>): String? =
        profiles.firstOrNull { classify(it.remarks) == mode }?.guid

    /** The profile to select for [mode], or null when the link has no such mode or it is already selected. */
    fun switchTarget(state: ChaparUiState, mode: ChaparMode): String? =
        profileFor(mode, state.profiles)?.takeIf { it != state.selectedGuid }

    /** After an import: Iran mode if the link has it, else the first profile. */
    fun defaultSelection(profiles: List<ChaparProfile>): String? =
        profileFor(ChaparMode.IRAN, profiles) ?: profiles.firstOrNull()?.guid

    /** Next connection state for a service event; null when the event is not about the connection. */
    fun onServiceEvent(current: ConnectionState, event: MainServiceEvent): ConnectionState? = when (event) {
        MainServiceEvent.StateRunning,
        MainServiceEvent.StateStartSuccess -> ConnectionState.Connected

        // "Not running" also answers the status query sent when the screen opens; it must not
        // hide a start that is still in progress or a failure the user has not seen yet.
        MainServiceEvent.StateNotRunning -> when (current) {
            ConnectionState.Connecting, is ConnectionState.Failed -> current
            else -> ConnectionState.Disconnected
        }

        is MainServiceEvent.StateStartFailure -> ConnectionState.Failed(event.message?.takeIf { it.isNotBlank() })

        // A failed start stops the service, which then reports "stopped": keep showing the failure until
        // the user acts, instead of flashing it away.
        MainServiceEvent.StateStopSuccess -> if (current is ConnectionState.Failed) current else ConnectionState.Disconnected
        else -> null
    }
}
