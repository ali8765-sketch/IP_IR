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

    // The exact shape the backend sends (backend profiles).
    private val query = "encryption=none&flow=xtls-rprx-vision&security=reality&sni=www.example.org&fp=chrome&pbk=PUB_key-1&sid=ab12&type=tcp"
    private val iran = "vless://11111111-2222-3333-4444-555555555555@vpn.example.com:8443?$query#Chapar-Iran"
    private val tor = "vless://11111111-2222-3333-4444-555555555555@VPN.example.com:8444?$query#Chapar-Tor"

    @Test
    fun acceptsTheServersOwnLinks() {
        assertEquals("$iran\n$tor", ChaparLinks.accept("\n$iran\r\n$tor\n", host))
        assertEquals(iran, ChaparLinks.accept(iran, host))
        val tagged = iran.replace("#Chapar-Iran", "#Chapar-Tor-u1")
        assertEquals(tagged, ChaparLinks.accept(tagged, host))
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
    fun refusesLinksWithoutReality() {
        for (link in listOf(
            iran.replace("security=reality", "security=none"),
            iran.replace("security=reality", "security=tls"),
            iran.replace("&security=reality", ""),
            iran.replace("&pbk=PUB_key-1", ""),
            iran.replace("pbk=PUB_key-1", "pbk="),
            iran.replace("&sid=ab12", ""),
            iran.replace("&fp=chrome", ""),
            iran.replace("&sni=www.example.org", ""),
            iran.replace("encryption=none", "encryption=aes"),
            iran.substringBefore('?') + "#Chapar-Iran",
        )) {
            assertNull(ChaparLinks.accept(link, host), link)
        }
    }

    @Test
    fun refusesSettingsThatWeakenOrRedirectTheConnection() {
        for (extra in listOf(
            "allowInsecure=1",
            "insecure=1",
            "allow_insecure=1",
            "extra=%7B%22downloadSettings%22%3A%7B%7D%7D",
            "fm=%7B%7D",
            "headerType=http",
            "host=other.example.com",
            "path=%2Fx",
            "pcs=abc",
            "security=reality", // the same key twice
            "novalue",
            "=x",
        )) {
            val link = iran.replace("#", "&$extra#")
            assertNull(ChaparLinks.accept(link, host), link)
        }
        for (type in listOf("xhttp", "ws", "grpc", "kcp")) {
            val link = iran.replace("type=tcp", "type=$type")
            assertNull(ChaparLinks.accept(link, host), link)
        }
    }

    @Test
    fun theNameMustFollowTheContract() {
        for (name in listOf("", "Chapar", "Chapar-Iran (Tor-proof)", "Tor", "My-Chapar-Tor", "Chapar-Tor-")) {
            val link = iran.substringBefore('#') + if (name.isEmpty()) "" else "#$name"
            assertNull(ChaparLinks.accept(link, host), link)
        }
        val older = iran.replace("#Chapar-Iran", "#Chapar-Free")
        assertEquals(older, ChaparLinks.accept(older, host))
    }

    @Test
    fun queryParamsAreDecodedAndStrict() {
        assertEquals(mapOf("a" to "1", "b" to "x=y z"), ChaparLinks.queryParams("a=1&b=x%3Dy%20z"))
        assertEquals(mapOf("a" to "b=c"), ChaparLinks.queryParams("a=b=c"))
        assertNull(ChaparLinks.queryParams("a=1&"))
        assertNull(ChaparLinks.queryParams("a=1&a=2"))
        assertNull(ChaparLinks.queryParams(""))
        assertNull(ChaparLinks.queryParams(null))
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
