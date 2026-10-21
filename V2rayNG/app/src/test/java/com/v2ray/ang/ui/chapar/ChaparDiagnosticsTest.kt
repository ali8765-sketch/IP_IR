package com.v2ray.ang.ui.chapar

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.time.Instant

class ChaparDiagnosticsTest {

    private class MemoryStore : DiagnosticsStore {
        var lines = listOf<String>()
        override fun read() = lines
        override fun write(lines: List<String>) { this.lines = lines }
    }

    private val store = MemoryStore()
    private var now = Instant.parse("2026-10-18T09:40:12Z")
    private val diagnostics = ChaparDiagnostics(store) { now }

    @Test
    fun linesHaveTheTimeAndOnlyFixedTexts() {
        diagnostics.record(ChaparEvent.KEY_RECEIVED, ChaparMode.TOR)
        diagnostics.record(ChaparEvent.KEY_FAILED, error = AccountError.NETWORK)
        assertEquals(
            listOf(
                "2026-10-18 09:40:12 UTC  Got a new connection key · Tor",
                "2026-10-18 09:40:12 UTC  Could not get a connection key · network",
            ),
            diagnostics.lines(),
        )
    }

    @Test
    fun aRepeatedEventIsKeptOnce() {
        diagnostics.record(ChaparEvent.SERVER_UNREACHABLE)
        now = now.plusSeconds(60)
        diagnostics.record(ChaparEvent.SERVER_UNREACHABLE)
        diagnostics.record(ChaparEvent.CONNECTED, ChaparMode.IRAN)
        diagnostics.record(ChaparEvent.SERVER_UNREACHABLE)
        assertEquals(3, diagnostics.lines().size)
    }

    @Test
    fun onlyTheLastHundredEventsAreKept() {
        repeat(150) { i -> diagnostics.record(if (i % 2 == 0) ChaparEvent.CONNECTED else ChaparEvent.DISCONNECTED) }
        val lines = diagnostics.lines()
        assertEquals(ChaparDiagnostics.MAX_LINES, lines.size)
        assertTrue(lines.last().endsWith("Disconnected"))
    }

    @Test
    fun reportStartsWithTheHeaderAndClearEmptiesIt() {
        diagnostics.record(ChaparEvent.SIGNED_IN)
        val report = diagnostics.report(listOf("Chapar 0.1.0", "Android 14"))
        assertEquals("Chapar 0.1.0\nAndroid 14\n\n2026-10-18 09:40:12 UTC  Signed in", report)
        diagnostics.clear()
        assertTrue(diagnostics.lines().isEmpty())
    }

    @Test
    fun noEventTextCanCarryAnAddressOrKey() {
        // Fixed texts only: no URLs, IPs, or placeholders that could take a value.
        ChaparEvent.entries.forEach { event ->
            assertTrue(!event.text.contains("://") && !event.text.contains('%') && !event.text.contains('$'), event.name)
        }
    }
}
