package com.v2ray.ang.ui.chapar

import com.v2ray.ang.R
import com.v2ray.ang.ui.main.MainServiceEvent
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Protocol
import okhttp3.Response
import okhttp3.ResponseBody.Companion.toResponseBody
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import java.time.Instant

/** The screen's state machine against a fake daemon and a fake backend (audit findings 5, 6, 11, 17, 20, B4, B5, B8, B9, B12). */
@OptIn(ExperimentalCoroutinesApi::class)
class ChaparViewModelTest {

    private class FakePlatform : ChaparPlatform {
        val events = Channel<MainServiceEvent>(Channel.UNLIMITED)
        override val serviceEvents: Flow<MainServiceEvent> = events.receiveAsFlow()
        var stops = 0
        var reloads = 0
        var queries = 0
        var restarts = mutableListOf<() -> Unit>()
        val errors = mutableListOf<Int>()
        val successes = mutableListOf<Int>()
        override fun initAssets() = Unit
        override fun queryServiceState() { queries++ }
        override fun stopVpn() { stops++ }
        override fun reloadVpn() { reloads++ }
        override fun restartVpn(onNotRunning: () -> Unit) { restarts += onNotRunning }
        override fun showError(resId: Int) { errors += resId }
        override fun showSuccess(resId: Int) { successes += resId }
        override fun close() = Unit
    }

    private class MemorySession : SessionStore {
        var token: String? = "sess"
        override fun token() = token
        override fun nickname() = "sunrise_42"
        override fun save(token: String, nickname: String) { this.token = token }
        override fun clear() { token = null }
    }

    private class MemoryDiagnostics : DiagnosticsStore {
        var lines = listOf<String>()
        override fun read() = lines
        override fun write(lines: List<String>) { this.lines = lines }
    }

    private class MemoryBypass : BypassStore {
        var chosen = setOf<String>()
        override fun selected() = chosen
        override fun save(packages: Set<String>) { chosen = packages }
    }

    private val query = "encryption=none&flow=xtls-rprx-vision&security=reality&sni=www.example.org&fp=chrome&pbk=PUB&sid=ab12&type=tcp"
    private val bothModes = "vless://k@api.example.com:8443?$query#Chapar-Iran\nvless://k@api.example.com:8444?$query#Chapar-Tor"
    private val me = """{"nickname":"sunrise_42","status":"active","used_bytes":0,"quota_bytes":10737418240,"resets_at":"2026-11-01T00:00:00+00:00"}"""

    /** (method, path) -> (HTTP status, body). */
    private val routes = mutableMapOf(
        ("GET" to "/api/v1/me") to (200 to me),
        ("POST" to "/api/v1/connect") to (200 to bothModes),
    )
    private val client = OkHttpClient.Builder().addInterceptor { chain ->
        val request = chain.request()
        val (code, text) = routes[request.method to request.url.encodedPath] ?: (404 to """{"error":"not_found"}""")
        Response.Builder().request(request).protocol(Protocol.HTTP_1_1).code(code).message("x")
            .body(text.toResponseBody("application/json".toMediaType())).build()
    }.build()

    private val dispatcher = StandardTestDispatcher()
    private val platform = FakePlatform()
    private val store = FakeLinkStore().apply {
        shareLinkRemarks = { text -> text.lines().filter { it.startsWith("vless://") }.map { it.substringAfter('#') } }
    }
    private val repository = ChaparRepository(store) { false }
    private val diagnosticsStore = MemoryDiagnostics()
    private val bypass = MemoryBypass()
    private lateinit var vm: ChaparViewModel

    @BeforeEach
    fun setUp() {
        Dispatchers.setMain(dispatcher)
        vm = ChaparViewModel(
            platform,
            repository,
            ChaparAccountService(ChaparApi("https://api.example.com", client), MemorySession(), repository, "api.example.com"),
            ChaparDiagnostics(diagnosticsStore) { Instant.EPOCH },
            bypass,
            { listOf(InstalledApp("com.bank", "Bank")) },
            dispatcher,
        )
    }

    @AfterEach
    fun tearDown() = Dispatchers.resetMain()

    private val state get() = vm.uiState.value
    private fun TestScope.emit(event: MainServiceEvent) {
        platform.events.trySend(event)
        runCurrent()
    }

    private fun TestScope.connected() {
        vm.refresh()
        advanceUntilIdle()
        vm.toggleConnection()
        advanceUntilIdle()
        vm.onStartRequested()
        emit(MainServiceEvent.StateStartSuccess)
        assertEquals(ConnectionState.Connected, state.connection)
    }

    private fun lastReportLine() = diagnosticsStore.lines.last().substringAfter("  ")

    @Test
    fun refreshAlsoAsksTheDaemonWhetherTheVpnStillRuns() = runTest(dispatcher) {
        vm.refresh()
        advanceUntilIdle()
        assertEquals(1, platform.queries)
        emit(MainServiceEvent.StateNotRunning)
        assertEquals(ConnectionState.Disconnected, state.connection)
    }

    @Test
    fun theWatchdogStartsOnlyWhenTheActivityReallyStarts() = runTest(dispatcher) {
        vm.refresh()
        advanceUntilIdle()
        vm.toggleConnection()
        advanceUntilIdle()
        assertEquals(ConnectionState.Connecting, state.connection)
        assertTrue(vm.startStillWanted())

        // The screen is in the background: the start request waits, and no false "start failed" appears.
        advanceTimeBy(ChaparViewModel.WATCHDOG_MS * 2)
        assertEquals(ConnectionState.Connecting, state.connection)

        vm.onStartRequested()
        advanceTimeBy(ChaparViewModel.WATCHDOG_MS + 1)
        assertEquals(ConnectionState.Failed(null), state.connection)
        assertEquals(1, platform.stops)
    }

    @Test
    fun connectAfterAFailureSurvivesTheQuickStopAnswer() = runTest(dispatcher) {
        vm.refresh()
        advanceUntilIdle()
        emit(MainServiceEvent.StateStartFailure("boom"))
        assertEquals(ConnectionState.Failed("boom"), state.connection)

        vm.toggleConnection()
        runCurrent()
        assertEquals(1, platform.stops) // the old service is released first
        emit(MainServiceEvent.StateStopSuccess)
        advanceUntilIdle()
        assertEquals(ConnectionState.Connecting, state.connection)
        assertTrue(vm.startStillWanted())
    }

    @Test
    fun aModeTheServerDidNotSendIsNeverStartedSilently() = runTest(dispatcher) {
        routes["POST" to "/api/v1/connect"] = 200 to bothModes.lines().first() // Iran only
        vm.refresh()
        advanceUntilIdle()
        vm.selectMode(ChaparMode.TOR)
        advanceUntilIdle()

        vm.toggleConnection()
        advanceUntilIdle()

        assertEquals(ConnectionState.Disconnected, state.connection)
        assertEquals(listOf(R.string.chapar_mode_missing), platform.errors)
        assertTrue(lastReportLine().startsWith(ChaparEvent.MODE_MISSING.text))
    }

    @Test
    fun aRefusedStartIsShown() = runTest(dispatcher) {
        vm.refresh()
        advanceUntilIdle()
        vm.toggleConnection()
        advanceUntilIdle()
        vm.onStartRejected()
        assertEquals(ConnectionState.Disconnected, state.connection)
        assertEquals(listOf(R.string.chapar_start_refused), platform.errors)
    }

    @Test
    fun aChangedBypassListIsAppliedAtOnceWhileConnected() = runTest(dispatcher) {
        bypass.chosen = setOf("com.bank")
        connected()

        vm.openBypass()
        advanceUntilIdle()
        vm.toggleBypassApp("com.bank") // the bank goes back into the VPN
        vm.saveBypass()
        runCurrent() // not advanceUntilIdle: that would also run the 45 s watchdog

        assertEquals(emptySet<String>(), bypass.chosen)
        assertEquals(1, platform.restarts.size)
        assertEquals(ConnectionState.Connecting, state.connection)
        emit(MainServiceEvent.StateStopSuccess)
        assertEquals(ConnectionState.Connecting, state.connection)
        emit(MainServiceEvent.StateStartSuccess)
        assertEquals(ConnectionState.Connected, state.connection)
    }

    @Test
    fun aBypassRestartWithNoDaemonEndsAsDisconnected() = runTest(dispatcher) {
        bypass.chosen = setOf("com.bank")
        connected()
        vm.openBypass()
        advanceUntilIdle()
        vm.toggleBypassApp("com.bank")
        vm.saveBypass()
        runCurrent()

        platform.restarts.single().invoke()
        runCurrent()
        assertEquals(ConnectionState.Disconnected, state.connection)
    }

    @Test
    fun anUnchangedBypassListDoesNotRestart() = runTest(dispatcher) {
        connected()
        vm.openBypass()
        advanceUntilIdle()
        vm.saveBypass()
        advanceUntilIdle()
        assertTrue(platform.restarts.isEmpty())
        assertEquals(ConnectionState.Connected, state.connection)
    }

    @Test
    fun aCancelledStartThatGoesThroughAnywayIsStopped() = runTest(dispatcher) {
        vm.refresh()
        advanceUntilIdle()
        vm.toggleConnection()
        advanceUntilIdle()
        vm.onStartRequested()
        vm.toggleConnection() // cancel
        assertEquals(ConnectionState.Disconnected, state.connection)
        assertEquals(1, platform.stops)

        // The stop reached the daemon before its start did.
        emit(MainServiceEvent.StateStartSuccess)
        assertEquals(2, platform.stops)
        assertEquals(ConnectionState.Disconnecting, state.connection)
        emit(MainServiceEvent.StateStopSuccess)
        assertEquals(ConnectionState.Disconnected, state.connection)

        // Later starts, for example from the quick-settings tile, are not touched.
        emit(MainServiceEvent.StateStartSuccess)
        assertEquals(ConnectionState.Connected, state.connection)
        assertEquals(2, platform.stops)
    }

    @Test
    fun aCancelBeforeTheStartWasSentDoesNotStopALaterStart() = runTest(dispatcher) {
        vm.refresh()
        advanceUntilIdle()
        vm.toggleConnection()
        vm.toggleConnection() // cancelled while the key was still being fetched
        advanceUntilIdle()
        emit(MainServiceEvent.StateStartSuccess)
        assertEquals(ConnectionState.Connected, state.connection)
        assertEquals(1, platform.stops)
    }

    @Test
    fun switchingModeWhileConnectedReloadsInPlace() = runTest(dispatcher) {
        connected()
        vm.selectMode(ChaparMode.TOR)
        advanceUntilIdle()
        assertEquals(1, platform.reloads)
        assertEquals(ChaparMode.TOR, state.selectedMode)
        assertEquals(0, platform.stops)
    }

    @Test
    fun aMalformedAccountAnswerDoesNotCrashTheScreen() = runTest(dispatcher) {
        routes["GET" to "/api/v1/me"] = 200 to me.replace("\"used_bytes\":0", "\"used_bytes\":\"n/a\"")
        vm.refresh()
        advanceUntilIdle()
        val account = state.account as AccountState.SignedIn
        assertNull(account.info)
        assertEquals("sunrise_42", account.nickname)
    }

    @Test
    fun refusedLinksAreNamedInTheReport() = runTest(dispatcher) {
        routes["POST" to "/api/v1/connect"] = 200 to bothModes.replace("security=reality", "security=none")
        vm.refresh()
        advanceUntilIdle()
        vm.toggleConnection()
        advanceUntilIdle()
        assertEquals(ConnectionState.Disconnected, state.connection)
        assertTrue(lastReportLine().endsWith("link_refused"), lastReportLine())
        assertEquals(listOf(R.string.chapar_connect_failed), platform.errors)
    }
}
