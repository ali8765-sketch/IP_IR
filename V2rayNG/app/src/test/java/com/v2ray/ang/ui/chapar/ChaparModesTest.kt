package com.v2ray.ang.ui.chapar

import com.v2ray.ang.ui.main.MainServiceEvent
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Test

class ChaparModesTest {

    private val iran = ChaparProfile("g-iran", "Chapar-Iran-u1")
    private val tor = ChaparProfile("g-tor", "Chapar-Tor-u1")

    @Test
    fun namesFromTheServerMapToTheirModes() {
        assertEquals(ChaparMode.IRAN, ChaparModes.classify("Chapar-Iran-u1"))
        assertEquals(ChaparMode.TOR, ChaparModes.classify("Chapar-Tor-u1"))
        assertEquals(ChaparMode.TOR, ChaparModes.classify("chapar-tor"))
    }

    @Test
    fun olderFreeLinksAreIranMode() {
        assertEquals(ChaparMode.IRAN, ChaparModes.classify("Chapar-Free-u1"))
    }

    @Test
    fun onlyWholeWordsCount() {
        assertEquals(ChaparMode.IRAN, ChaparModes.classify("Toronto"))
        assertEquals(ChaparMode.IRAN, ChaparModes.classify("Tortoise"))
        assertEquals(ChaparMode.IRAN, ChaparModes.classify(""))
    }

    @Test
    fun availableModesFollowTheStoredProfiles() {
        assertEquals(setOf(ChaparMode.IRAN, ChaparMode.TOR), ChaparModes.available(listOf(iran, tor)))
        assertEquals(emptySet<ChaparMode>(), ChaparModes.available(emptyList()))
    }

    @Test
    fun profileForReturnsTheGuidOrNull() {
        assertEquals("g-tor", ChaparModes.profileFor(ChaparMode.TOR, listOf(iran, tor)))
        assertNull(ChaparModes.profileFor(ChaparMode.TOR, listOf(iran)))
    }

    @Test
    fun switchTargetIsTheOtherModesProfileOrNull() {
        val state = ChaparUiState(loaded = true, profiles = listOf(iran, tor), selectedGuid = "g-iran")
        assertEquals("g-tor", ChaparModes.switchTarget(state, ChaparMode.TOR))
        assertNull(ChaparModes.switchTarget(state, ChaparMode.IRAN)) // already selected: no restart
        assertNull(ChaparModes.switchTarget(state.copy(profiles = listOf(iran)), ChaparMode.TOR)) // not in the link
    }

    @Test
    fun defaultSelectionPrefersIranThenFirst() {
        assertEquals("g-iran", ChaparModes.defaultSelection(listOf(tor, iran)))
        assertEquals("g-tor", ChaparModes.defaultSelection(listOf(tor)))
        assertNull(ChaparModes.defaultSelection(emptyList()))
    }

    @Test
    fun selectedModeComesFromTheSelectedProfile() {
        val state = ChaparUiState(loaded = true, profiles = listOf(iran, tor), selectedGuid = "g-tor")
        assertEquals(ChaparMode.TOR, state.selectedMode)
        assertEquals(ChaparMode.IRAN, state.copy(selectedGuid = null).selectedMode)
    }

    @Test
    fun serviceEventsDriveTheConnectionState() {
        val d = ConnectionState.Disconnected
        assertEquals(ConnectionState.Connected, ChaparModes.onServiceEvent(d, MainServiceEvent.StateStartSuccess))
        assertEquals(ConnectionState.Connected, ChaparModes.onServiceEvent(d, MainServiceEvent.StateRunning))
        assertEquals(d, ChaparModes.onServiceEvent(ConnectionState.Connected, MainServiceEvent.StateStopSuccess))
        assertEquals(d, ChaparModes.onServiceEvent(ConnectionState.Connected, MainServiceEvent.StateNotRunning))
        assertEquals(
            ConnectionState.Failed("boom"),
            ChaparModes.onServiceEvent(ConnectionState.Connecting, MainServiceEvent.StateStartFailure("boom")),
        )
        assertEquals(
            ConnectionState.Failed(null),
            ChaparModes.onServiceEvent(ConnectionState.Connecting, MainServiceEvent.StateStartFailure(" ")),
        )
    }

    @Test
    fun notRunningKeepsAStartInProgressOrAnUnseenFailure() {
        assertEquals(
            ConnectionState.Connecting,
            ChaparModes.onServiceEvent(ConnectionState.Connecting, MainServiceEvent.StateNotRunning),
        )
        val failed = ConnectionState.Failed("x")
        assertEquals(failed, ChaparModes.onServiceEvent(failed, MainServiceEvent.StateNotRunning))
    }

    @Test
    fun testEventsDoNotTouchTheConnection() {
        assertNull(ChaparModes.onServiceEvent(ConnectionState.Connected, MainServiceEvent.MeasureConfigFinish("r")))
    }

    @Test
    fun aFailedStartStaysVisibleWhenTheServiceReportsItStopped() {
        val failed = ConnectionState.Failed("port in use")
        // A start failure is followed by the service's own "stopped": the failure must not flash away.
        val afterFailure = ChaparModes.onServiceEvent(ConnectionState.Connecting, MainServiceEvent.StateStartFailure("port in use"))
        assertEquals(failed, afterFailure)
        assertEquals(failed, ChaparModes.onServiceEvent(failed, MainServiceEvent.StateStopSuccess))
    }

    @Test
    fun theTunnelMayBeHeldInEveryStateButANormalStop() {
        assertEquals(false, ChaparUiState(connection = ConnectionState.Disconnected).mayHoldTunnel)
        for (c in listOf(ConnectionState.Connecting, ConnectionState.Connected, ConnectionState.Disconnecting, ConnectionState.Failed(null))) {
            assertEquals(true, ChaparUiState(connection = c).mayHoldTunnel)
        }
        assertEquals(false, ChaparUiState(connection = ConnectionState.Connecting).accountActionsEnabled)
        assertEquals(true, ChaparUiState(connection = ConnectionState.Connected).accountActionsEnabled)
    }
}
