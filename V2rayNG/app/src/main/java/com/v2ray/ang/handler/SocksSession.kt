package com.v2ray.ang.handler

import java.net.InetAddress
import java.net.ServerSocket
import java.security.SecureRandom

/**
 * The local SOCKS inbound of one core start: a free port and a random user name and password.
 * The core's inbound and hev-socks5-tunnel read the same values from [SettingsManager], so other apps
 * cannot open TCP connections through the inbound. Limit: Xray checks UDP datagrams only by the source
 * address of an authenticated UDP ASSOCIATE (127.0.0.1, shared by every app), not by password; those
 * packets still leave through the same VPN. Never log these values; the tunnel's config file holding
 * them is deleted right after it starts (TProxyService).
 */
class SocksSession(val port: Int, val username: String, val password: String) {

    // The default toString of a class lists no fields, but make that explicit so a log line can never leak them.
    override fun toString(): String = "SocksSession"

    companion object {
        private const val ALPHABET = "abcdefghijklmnopqrstuvwxyzABCDEFGHIJKLMNOPQRSTUVWXYZ0123456789"
        private const val USERNAME_LENGTH = 16
        private const val PASSWORD_LENGTH = 32

        /** A new session: a free loopback port picked by the OS and fresh random credentials. */
        fun create(random: SecureRandom = SecureRandom(), freePort: () -> Int = ::findFreeLoopbackPort): SocksSession =
            SocksSession(freePort(), randomText(random, USERNAME_LENGTH), randomText(random, PASSWORD_LENGTH))

        internal fun randomText(random: SecureRandom, length: Int): String =
            buildString(length) { repeat(length) { append(ALPHABET[random.nextInt(ALPHABET.length)]) } }

        private fun findFreeLoopbackPort(): Int =
            ServerSocket(0, 1, InetAddress.getLoopbackAddress()).use { it.localPort }
    }
}
