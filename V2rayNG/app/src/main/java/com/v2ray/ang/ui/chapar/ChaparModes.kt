package com.v2ray.ang.ui.chapar

import com.v2ray.ang.ui.main.MainServiceEvent

/**
 * Pure rules of the Chapar screen, kept free of Android so they run as JVM tests.
 */
object ChaparModes {

    // The naming contract with the server (ChaparLinks refuses any other name): "Chapar-Iran" or
    // "Chapar-Tor", test links add "-<tag>". Older links said "Free" for what is now Iran mode. Only the
    // word right after "Chapar-" counts, so "Chapar-Iran-Tor1" is Iran mode; anything that is not Tor
    // is Iran mode.
    fun classify(remarks: String): ChaparMode {
        val words = remarks.split('-')
        val isTor = words.size >= 2 && words[0].equals("Chapar", ignoreCase = true) && words[1].equals("Tor", ignoreCase = true)
        return if (isTor) ChaparMode.TOR else ChaparMode.IRAN
    }

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
        // the user acts, instead of flashing it away. While connecting, "stopped" is the answer to the stop
        // sent before a new start (after a failure, or a restart for a new bypass list): the start follows.
        MainServiceEvent.StateStopSuccess -> when (current) {
            ConnectionState.Connecting, is ConnectionState.Failed -> current
            else -> ConnectionState.Disconnected
        }
        else -> null
    }
}
