package com.v2ray.ang.ui.chapar

import java.net.URI
import java.net.URLDecoder

/**
 * What the app accepts from Connect (D-043, D-047): only `vless://` share links that point at Chapar's own
 * server and use REALITY. The answer goes into upstream's importer, which would also take subscription URLs
 * (and download them), full JSON configs, every other protocol, plain-text VLESS, switched-off certificate
 * checks, and raw stream settings that can name another host; a tampered or misrouted answer must not be
 * able to install any of that. Pure, so it runs as a JVM test.
 *
 * Contract with the server (backend profiles): one link per mode, named `Chapar-Iran` or `Chapar-Tor`
 * (test links add `-<tag>`), with the query parameters in [ALLOWED_PARAMS] only.
 */
object ChaparLinks {

    /** The accepted links, one per line, or null if [text] holds anything else or nothing. */
    fun accept(text: String, expectedHost: String): String? {
        if (expectedHost.isBlank()) return null
        val lines = text.lines().map { it.trim() }.filter { it.isNotEmpty() }
        if (lines.isEmpty() || lines.size > MAX_LINES) return null
        for (line in lines) {
            if (!line.startsWith("vless://")) return null
            val uri = runCatching { URI(line) }.getOrNull() ?: return null
            if (!uri.host.orEmpty().equals(expectedHost, ignoreCase = true)) return null
            if (!NAME.matches(uri.fragment.orEmpty())) return null
            val params = queryParams(uri.rawQuery) ?: return null
            if (!hasOnlyRealitySettings(params)) return null
        }
        return lines.joinToString("\n")
    }

    /** The host of the API's base URL, which is also the VPN's address (one domain, ARCHITECTURE). */
    fun hostOf(baseUrl: String): String = runCatching { URI(baseUrl.trim()).host }.getOrNull().orEmpty()

    /**
     * The decoded query, or null when it is missing, malformed, or names a parameter twice. Upstream's
     * parser (FmtBase.getQueryParam, after VlessFmt's fixIllegalUrl) reads the same text again when the
     * link is imported. The two may differ only on text refused here (a duplicate key, a part without a
     * value, a key outside the allowed set), so a link either passes both or is never imported: both fail
     * closed. ChaparRepository's refusal of subscription URLs is a second, independent guard on the import.
     */
    internal fun queryParams(rawQuery: String?): Map<String, String>? {
        if (rawQuery.isNullOrEmpty()) return null
        val params = LinkedHashMap<String, String>()
        for (part in rawQuery.split("&")) {
            val pieces = part.split("=", limit = 2)
            if (pieces.size != 2 || pieces[0].isEmpty()) return null
            val value = runCatching { URLDecoder.decode(pieces[1], "UTF-8") }.getOrNull() ?: return null
            if (params.put(pieces[0], value) != null) return null
        }
        return params
    }

    private fun hasOnlyRealitySettings(params: Map<String, String>): Boolean =
        params.keys.all { it in ALLOWED_PARAMS } &&
            params["security"] == "reality" &&
            params["encryption"].let { it == null || it == "none" } &&
            params["type"].let { it == null || it == "tcp" } &&
            REQUIRED_NON_EMPTY.all { !params[it].isNullOrBlank() }

    private const val MAX_LINES = 8

    /** `Chapar-Iran`, `Chapar-Tor`, `Chapar-Free` (older name of Iran mode), optionally `-<tag>`. */
    private val NAME = Regex("Chapar-(Iran|Tor|Free)(-[A-Za-z0-9_]+)?", RegexOption.IGNORE_CASE)

    /** Everything else (insecure, allowInsecure, extra, fm, headerType, host, path, …) is refused. */
    private val ALLOWED_PARAMS = setOf("encryption", "flow", "security", "sni", "fp", "pbk", "sid", "spx", "type")

    private val REQUIRED_NON_EMPTY = listOf("sni", "fp", "pbk", "sid")
}
