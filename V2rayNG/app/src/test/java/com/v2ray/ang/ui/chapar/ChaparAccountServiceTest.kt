package com.v2ray.ang.ui.chapar

import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Protocol
import okhttp3.Response
import okhttp3.ResponseBody.Companion.toResponseBody
import okio.Buffer
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.net.ConnectException
import java.net.SocketTimeoutException

/** The account flow against a fake backend: an OkHttp interceptor answers, nothing goes to the network. */
class ChaparAccountServiceTest {

    private class FakeBackend {
        /** (method, path) -> (HTTP status, body). Missing routes answer 404. */
        val routes = mutableMapOf<Pair<String, String>, Pair<Int, String>>()
        val requests = mutableListOf<String>()
        /** The request never leaves the phone (no route): the last key may be tried. */
        var offline = false

        /** The request was sent but the answer never came: the server may have replaced the key already. */
        var lostAnswer = false

        val client: OkHttpClient = OkHttpClient.Builder().addInterceptor { chain ->
            if (offline) throw ConnectException("offline")
            if (lostAnswer) throw SocketTimeoutException("read timed out")
            val request = chain.request()
            val body = request.body?.let { b -> Buffer().also { b.writeTo(it) }.readUtf8() }.orEmpty()
            requests += "${request.method} ${request.url.encodedPath} ${request.header("Authorization").orEmpty()} $body".trim()
            val (code, text) = routes[request.method to request.url.encodedPath] ?: (404 to """{"error":"not_found"}""")
            Response.Builder().request(request).protocol(Protocol.HTTP_1_1).code(code).message("x")
                .body(text.toResponseBody("application/json".toMediaType())).build()
        }.build()
    }

    private class MemorySession : SessionStore {
        var token: String? = null
        var nick: String? = null
        var keystoreBusy = false
        var keystoreBroken = false
        override fun token(): String? {
            if (keystoreBusy) throw SessionUnavailable(IllegalStateException("busy"))
            return token
        }
        override fun nickname() = nick
        override fun save(token: String, nickname: String) {
            if (keystoreBroken) throw IllegalStateException("no keystore")
            this.token = token; nick = nickname
        }
        override fun clear() { token = null; nick = null }
    }

    private val backend = FakeBackend()
    private val session = MemorySession()
    /** Keys in the links the phone imported, in order. */
    private val importedKeys = mutableListOf<String>()
    private val store = FakeLinkStore().apply {
        // Share links -> their names, like upstream's importer.
        shareLinkRemarks = { text ->
            val links = text.lines().filter { it.startsWith("vless://") }
            importedKeys += links.map { it.substringAfter("vless://").substringBefore('@') }.distinct()
            links.map { it.substringAfter('#') }
        }
    }
    private val repository = ChaparRepository(store) { it.startsWith("https://") }
    private val service = ChaparAccountService(ChaparApi("https://api.example.com", backend.client), session, repository, "api.example.com")

    private val me = """{"nickname":"sunrise_42","status":"active","used_bytes":0,"quota_bytes":10737418240,"resets_at":"2026-11-01T00:00:00+00:00"}"""
    private val query = "encryption=none&flow=xtls-rprx-vision&security=reality&sni=www.example.org&fp=chrome&pbk=PUB&sid=ab12&type=tcp"
    private fun profiles(key: String, host: String = "api.example.com") =
        "vless://$key@$host:8443?$query#Chapar-Iran\nvless://$key@$host:8444?$query#Chapar-Tor"

    private fun signedInBackend(key: String = "k1") {
        backend.routes["GET" to "/api/v1/me"] = 200 to me
        backend.routes["POST" to "/api/v1/connect"] = 200 to profiles(key)
    }

    @Test
    fun newUserIsAskedForANicknameThenSignedIn() {
        backend.routes["POST" to "/api/v1/auth/google"] = 409 to """{"error":"nickname_required"}"""
        assertEquals(ChaparAccountService.SignInOutcome.NeedsNickname, service.signIn("id-token", null))
        assertNull(session.token)

        backend.routes["POST" to "/api/v1/auth/google"] = 200 to """{"token":"sess","nickname":"sunrise_42"}"""
        assertEquals(ChaparAccountService.SignInOutcome.Done, service.signIn("id-token", "sunrise_42"))
        assertEquals("sess", session.token)
        assertTrue(backend.requests.last().contains(""""nickname":"sunrise_42""""))
    }

    @Test
    fun signInErrorsAreReportedAndNothingIsStored() {
        backend.routes["POST" to "/api/v1/auth/google"] = 403 to """{"error":"gmail_only"}"""
        assertEquals(ChaparAccountService.SignInOutcome.Failed(AccountError.GMAIL_ONLY), service.signIn("t", null))
        backend.offline = true
        assertEquals(ChaparAccountService.SignInOutcome.Failed(AccountError.NETWORK), service.signIn("t", null))
        assertNull(session.token)
    }

    @Test
    fun refreshReadsTheAccountButNeverAKey() {
        session.save("sess", "sunrise_42")
        signedInBackend()
        val outcome = service.refresh() as ChaparAccountService.RefreshOutcome.SignedIn
        assertEquals("active", outcome.info?.status)
        assertTrue(backend.requests.none { it.contains("/connect") })
        assertTrue(repository.profiles().isEmpty())
    }

    @Test
    fun aMalformedAccountAnswerIsAnErrorNotACrash() {
        session.save("sess", "sunrise_42")
        for (body in listOf(
            me.replace("\"used_bytes\":0", "\"used_bytes\":\"n/a\""),
            me.replace("\"quota_bytes\":10737418240", "\"quota_bytes\":true"),
            me.replace("\"used_bytes\":0,", ""),
        )) {
            backend.routes["GET" to "/api/v1/me"] = 200 to body
            val outcome = service.refresh() as ChaparAccountService.RefreshOutcome.SignedIn
            assertNull(outcome.info, body) // shown like "server not reachable"; the session stays
            assertEquals("sess", session.token)
        }
    }

    @Test
    fun anOversizedAnswerIsRefused() {
        session.save("sess", "sunrise_42")
        backend.routes["POST" to "/api/v1/connect"] = 200 to profiles("k1") + "\n" + "x".repeat(70_000)
        assertEquals(ChaparAccountService.ConnectOutcome.Failed(AccountError.FAILED), service.connect())
        assertTrue(repository.profiles().isEmpty())
    }

    @Test
    fun everyConnectStoresTheNewKeyWithTheSession() {
        session.save("sess", "sunrise_42")
        signedInBackend("k1")
        assertEquals(ChaparAccountService.ConnectOutcome.Ready(newKey = true), service.connect())
        assertEquals(listOf("Chapar-Iran", "Chapar-Tor"), repository.profiles().map { it.remarks })
        assertTrue(backend.requests.single().startsWith("POST /api/v1/connect Bearer sess"))

        signedInBackend("k2")
        assertEquals(ChaparAccountService.ConnectOutcome.Ready(newKey = true), service.connect())
        assertEquals(2, store.servers.size) // the profiles with the old key are gone from the phone
        assertEquals(listOf("k1", "k2"), importedKeys)
    }

    @Test
    fun noSessionMeansSignedOutAndNoRequest() {
        assertEquals(ChaparAccountService.RefreshOutcome.SignedOut, service.refresh())
        assertEquals(ChaparAccountService.ConnectOutcome.SignedOut, service.connect())
        assertTrue(backend.requests.isEmpty())
    }

    @Test
    fun aRejectedSessionSignsOutAndRemovesTheProfiles() {
        session.save("sess", "sunrise_42")
        signedInBackend()
        service.connect()
        backend.routes["POST" to "/api/v1/connect"] = 401 to """{"error":"signed_out"}"""
        assertEquals(ChaparAccountService.ConnectOutcome.SignedOut, service.connect())
        assertNull(session.token)
        assertTrue(repository.profiles().isEmpty())

        session.save("sess", "sunrise_42")
        backend.routes["GET" to "/api/v1/me"] = 401 to """{"error":"signed_out"}"""
        assertEquals(ChaparAccountService.RefreshOutcome.SignedOut, service.refresh())
        assertNull(session.token)
    }

    @Test
    fun offlineTriesTheLastKeyButNeverConnectsWithoutOne() {
        session.save("sess", "sunrise_42")
        backend.offline = true
        assertEquals(ChaparAccountService.ConnectOutcome.Failed(AccountError.NETWORK), service.connect())

        backend.offline = false
        signedInBackend()
        service.connect()
        backend.offline = true
        assertEquals(ChaparAccountService.ConnectOutcome.Ready(newKey = false), service.connect())
        assertEquals(2, repository.profiles().size)
        val outcome = service.refresh() as ChaparAccountService.RefreshOutcome.SignedIn
        assertEquals("sunrise_42", outcome.nickname)
        assertNull(outcome.info)
        assertEquals("sess", session.token)
    }

    @Test
    fun overQuotaOrDisabledAccountsGetNoKey() {
        session.save("sess", "sunrise_42")
        backend.routes["POST" to "/api/v1/connect"] = 403 to """{"error":"over_quota"}"""
        assertEquals(ChaparAccountService.ConnectOutcome.Blocked, service.connect())
        backend.routes["POST" to "/api/v1/connect"] = 403 to """{"error":"disabled"}"""
        assertEquals(ChaparAccountService.ConnectOutcome.Blocked, service.connect())
        backend.routes["POST" to "/api/v1/connect"] = 503 to """{"error":"node_unavailable"}"""
        assertEquals(ChaparAccountService.ConnectOutcome.Failed(AccountError.SERVER_BUSY), service.connect())
        backend.routes["POST" to "/api/v1/connect"] = 429 to """{"error":"too_many_attempts"}"""
        assertEquals(ChaparAccountService.ConnectOutcome.Failed(AccountError.RATE_LIMITED), service.connect())
        backend.routes["POST" to "/api/v1/connect"] = 403 to "<html>blocked by a proxy</html>"
        assertEquals(ChaparAccountService.ConnectOutcome.Failed(AccountError.FAILED), service.connect())
        assertTrue(repository.profiles().isEmpty())
        assertEquals("sess", session.token)
    }

    @Test
    fun signOutForgetsEverythingEvenOffline() {
        session.save("sess", "sunrise_42")
        signedInBackend()
        service.connect()
        backend.offline = true
        service.signOut()
        assertNull(session.token)
        assertTrue(repository.profiles().isEmpty())
    }

    @Test
    fun deleteAccountClearsOnlyOnSuccess() {
        session.save("sess", "sunrise_42")
        signedInBackend()
        service.connect()

        backend.offline = true
        assertEquals(AccountError.NETWORK, service.deleteAccount())
        assertEquals("sess", session.token)

        backend.offline = false
        backend.routes["DELETE" to "/api/v1/me"] = 204 to ""
        assertNull(service.deleteAccount())
        assertNull(session.token)
        assertTrue(repository.profiles().isEmpty())
    }

    @Test
    fun unconfiguredApiNeverSendsAnything() {
        val unconfigured = ChaparAccountService(ChaparApi("", backend.client), session, repository, "")
        assertEquals(ChaparAccountService.SignInOutcome.Failed(AccountError.NETWORK), unconfigured.signIn("t", null))
        assertTrue(backend.requests.isEmpty())
    }

    @Test
    fun onlyLinksForChaparsOwnServerAreImported() {
        session.save("sess", "sunrise_42")
        signedInBackend("k1")
        service.connect()
        for (answer in listOf(
            profiles("k2", host = "evil.example.com"),
            "https://evil.example/sub",
            "vmess://eyJhZGQiOiJldmlsIn0=",
            "<html>captive portal</html>",
            """{"outbounds": []}""",
            profiles("k2") + "\nhttps://evil.example/sub",
            profiles("k2").replace("security=reality", "security=none"),
            profiles("k2").replace("type=tcp", "type=tcp&allowInsecure=1"),
            "",
        )) {
            backend.routes["POST" to "/api/v1/connect"] = 200 to answer
            assertEquals(ChaparAccountService.ConnectOutcome.Failed(AccountError.LINK_REFUSED), service.connect(), answer)
        }
        assertEquals(listOf("k1"), importedKeys) // nothing else was ever handed to the importer
        assertEquals(2, repository.profiles().size)
    }

    @Test
    fun aLostAnswerNeverFallsBackToTheOldKey() {
        session.save("sess", "sunrise_42")
        signedInBackend()
        service.connect()
        backend.lostAnswer = true
        assertEquals(ChaparAccountService.ConnectOutcome.Failed(AccountError.NETWORK), service.connect())
    }

    @Test
    fun aKeystoreHiccupNeitherSignsOutNorConnects() {
        session.save("sess", "sunrise_42")
        signedInBackend()
        service.connect()
        session.keystoreBusy = true
        val outcome = service.refresh() as ChaparAccountService.RefreshOutcome.SignedIn
        assertNull(outcome.info)
        assertEquals(ChaparAccountService.ConnectOutcome.Failed(AccountError.FAILED), service.connect())
        assertEquals(2, repository.profiles().size) // nothing was thrown away
        session.keystoreBusy = false
        assertEquals("sess", session.token)
    }

    @Test
    fun aSessionThatCannotBeStoredIsClosedOnTheServer() {
        backend.routes["POST" to "/api/v1/auth/google"] = 200 to """{"token":"sess","nickname":"sunrise_42"}"""
        backend.routes["POST" to "/api/v1/logout"] = 204 to ""
        session.keystoreBroken = true
        assertEquals(ChaparAccountService.SignInOutcome.Failed(AccountError.FAILED), service.signIn("id-token", null))
        assertTrue(backend.requests.last().startsWith("POST /api/v1/logout Bearer sess"))
        assertNull(session.token)
    }

    @Test
    fun aMissingSessionAlsoRemovesStoredProfiles() {
        session.save("sess", "sunrise_42")
        signedInBackend()
        service.connect()
        session.clear() // for example after the Keystore key was lost
        assertEquals(ChaparAccountService.RefreshOutcome.SignedOut, service.refresh())
        assertTrue(repository.profiles().isEmpty())
    }
}
