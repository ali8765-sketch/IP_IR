package com.v2ray.ang.ui.chapar

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Test
import java.io.IOException
import java.net.ConnectException
import java.net.SocketTimeoutException
import java.net.UnknownHostException
import javax.net.ssl.SSLHandshakeException

class ChaparLinksTest {

    private val host = "vpn.example.com"
    private val iran = "vless://11111111-2222-3333-4444-555555555555@vpn.example.com:8443?security=reality#Chapar-Iran"
    private val tor = "vless://11111111-2222-3333-4444-555555555555@VPN.example.com:8444?security=reality#Chapar-Tor"

    @Test
    fun acceptsTheServersOwnLinks() {
        assertEquals("$iran\n$tor", ChaparLinks.accept("\n$iran\r\n$tor\n", host))
    }

    @Test
    fun refusesAnythingElse() {
        for (text in listOf(
            "",
            "$iran\nvmess://x",
            iran.replace("vpn.example.com", "evil.example.com"),
            iran.replace("vpn.example.com", "vpn.example.com.evil.net"),
            "https://vpn.example.com/sub",
            "vless://not a uri@vpn.example.com:1#x",
            List(9) { iran }.joinToString("\n"),
        )) {
            assertNull(ChaparLinks.accept(text, host), text)
        }
        assertNull(ChaparLinks.accept(iran, ""))
    }

    @Test
    fun theApiHostIsTheVpnHost() {
        assertEquals("vpn.example.com", ChaparLinks.hostOf("https://vpn.example.com/"))
        assertEquals("", ChaparLinks.hostOf(""))
    }

    @Test
    fun onlyFailuresBeforeSendingCountAsOffline() {
        assertEquals(ChaparApiException.NETWORK, ChaparApi.classify(UnknownHostException()))
        assertEquals(ChaparApiException.NETWORK, ChaparApi.classify(ConnectException()))
        assertEquals(ChaparApiException.NETWORK_AFTER_SEND, ChaparApi.classify(SocketTimeoutException()))
        assertEquals(ChaparApiException.NETWORK_AFTER_SEND, ChaparApi.classify(IOException("reset")))
        assertEquals(ChaparApiException.INSECURE, ChaparApi.classify(SSLHandshakeException("bad cert")))
        assertEquals(AccountError.INSECURE, ChaparAccount.errorFor(ChaparApiException.INSECURE))
        assertEquals(AccountError.NETWORK, ChaparAccount.errorFor(ChaparApiException.NETWORK_AFTER_SEND))
    }
}
