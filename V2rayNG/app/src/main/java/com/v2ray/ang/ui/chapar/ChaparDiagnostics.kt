package com.v2ray.ang.ui.chapar

import com.v2ray.ang.handler.MmkvManager
import java.time.Instant
import java.time.ZoneOffset
import java.time.format.DateTimeFormatter

/**
 * The events the "Report a problem" screen can show. Every line is one of these fixed texts plus,
 * at most, a mode or an [AccountError] name, so a report can never contain a key, a server
 * address, a link, a site the user visited, or a message from the core (SECURITY.md).
 */
enum class ChaparEvent(val text: String) {
    SIGNED_IN("Signed in"),
    SIGN_IN_FAILED("Sign-in failed"),
    SIGNED_OUT("Signed out"),
    SESSION_ENDED("The server ended the session; signed out"),
    KEY_RECEIVED("Got a new connection key"),
    KEY_REUSED("Server not reachable; trying the last key"),
    KEY_REFUSED("No key: data used up or account turned off"),
    KEY_FAILED("Could not get a connection key"),
    START_REFUSED("VPN not started: permission refused or start not allowed"),
    CONNECTED("Connected"),
    DISCONNECTED("Disconnected"),
    START_FAILED("Connection failed to start"),
    MODE_SWITCHED("Switched mode while connected"),
    SERVER_UNREACHABLE("Could not reach the Chapar server"),
    OVER_QUOTA("Account: data used up"),
    DISABLED("Account: turned off"),
}

/** Where the report lines are kept. */
interface DiagnosticsStore {
    fun read(): List<String>
    fun write(lines: List<String>)
}

class MmkvDiagnosticsStore : DiagnosticsStore {
    override fun read(): List<String> =
        MmkvManager.decodeSettingsString(KEY).orEmpty().lines().filter { it.isNotBlank() }

    override fun write(lines: List<String>) {
        MmkvManager.encodeSettings(KEY, lines.joinToString("\n"))
    }

    private companion object {
        const val KEY = "chapar_diagnostics"
    }
}

/**
 * The last [MAX_LINES] events, kept on the phone only. The user sees them under Settings, "Report a
 * problem", and decides whether to copy and send them. Thread-safe.
 */
class ChaparDiagnostics(
    private val store: DiagnosticsStore = MmkvDiagnosticsStore(),
    private val clock: () -> Instant = Instant::now,
) {
    @Synchronized
    fun record(event: ChaparEvent, mode: ChaparMode? = null, error: AccountError? = null) {
        val detail = listOfNotNull(mode?.label, error?.name?.lowercase()).joinToString(" · ")
        val text = if (detail.isEmpty()) event.text else "${event.text} · $detail"
        val lines = store.read()
        // A repeat of the last event (for example "server not reachable" on every resume) adds nothing.
        if (lines.lastOrNull()?.substringAfter(SEPARATOR) == text) return
        store.write((lines + (TIME.format(clock()) + SEPARATOR + text)).takeLast(MAX_LINES))
    }

    @Synchronized
    fun lines(): List<String> = store.read()

    @Synchronized
    fun clear() = store.write(emptyList())

    /** The text the user copies: [header] lines (app, Android, account), then the events, oldest first. */
    fun report(header: List<String>): String = (header + "" + lines()).joinToString("\n")

    companion object {
        const val MAX_LINES = 100
        private const val SEPARATOR = "  "
        private val TIME = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss 'UTC'").withZone(ZoneOffset.UTC)
        private val ChaparMode.label get() = if (this == ChaparMode.TOR) "Tor" else "Iran"
    }
}
