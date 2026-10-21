package com.v2ray.ang.ui.chapar

import java.net.URI

/**
 * What the app accepts from Connect (D-043, D-047): only `vless://` share links that point at Chapar's own
 * server. The answer goes into upstream's importer, which would also take subscription URLs (and download
 * them), full JSON configs, and every other protocol; a tampered or misrouted answer must not be able to
 * install any of that. Pure, so it runs as a JVM test.
 */
object ChaparLinks {

    /** The accepted links, one per line, or null if [text] holds anything else or nothing. */
    fun accept(text: String, expectedHost: String): String? {
        if (expectedHost.isBlank()) return null
        val lines = text.lines().map { it.trim() }.filter { it.isNotEmpty() }
        if (lines.isEmpty() || lines.size > MAX_LINKS) return null
        for (line in lines) {
            if (!line.startsWith("vless://")) return null
            val host = runCatching { URI(line).host }.getOrNull() ?: return null
            if (!host.equals(expectedHost, ignoreCase = true)) return null
        }
        return lines.joinToString("\n")
    }

    /** The host of the API's base URL, which is also the VPN's address (one domain, ARCHITECTURE). */
    fun hostOf(baseUrl: String): String = runCatching { URI(baseUrl.trim()).host }.getOrNull().orEmpty()

    private const val MAX_LINKS = 8
}
