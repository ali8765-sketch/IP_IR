package com.v2ray.ang.ui.chapar

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class ChaparAccountTest {

    private val gib = 1024L * 1024 * 1024
    private fun info(used: Long, status: String = "active") =
        AccountInfo("sunrise_42", status, used, 10 * gib, "2026-11-01T00:00:00+00:00")

    @Test
    fun backendCodesMapToUserErrors() {
        assertEquals(AccountError.NETWORK, ChaparAccount.errorFor(ChaparApiException.NETWORK))
        assertEquals(AccountError.GMAIL_ONLY, ChaparAccount.errorFor("gmail_only"))
        assertEquals(AccountError.SIGNUP_CLOSED, ChaparAccount.errorFor("signup_closed"))
        assertEquals(AccountError.NICKNAME_TAKEN, ChaparAccount.errorFor("nickname_taken"))
        assertEquals(AccountError.INVALID_NICKNAME, ChaparAccount.errorFor("invalid_nickname"))
        assertEquals(AccountError.FAILED, ChaparAccount.errorFor("invalid_token"))
        assertEquals(AccountError.FAILED, ChaparAccount.errorFor("something_new"))
    }

    @Test
    fun onlyHttp401MeansSignedOut() {
        assertTrue(ChaparAccount.isSignedOut(ChaparApiException("signed_out", 401)))
        assertFalse(ChaparAccount.isSignedOut(ChaparApiException(ChaparApiException.NETWORK)))
        assertFalse(ChaparAccount.isSignedOut(ChaparApiException("disabled", 403)))
    }

    @Test
    fun usageLineValues() {
        assertEquals("6.8", ChaparAccount.gigabytesLeft(info(used = (3.2 * gib).toLong())))
        assertEquals("0.0", ChaparAccount.gigabytesLeft(info(used = 12 * gib)))
        assertEquals("1 Nov", ChaparAccount.resetDay(info(0)))
    }

    @Test
    fun nicknameRuleMatchesTheBackend() {
        assertTrue(ChaparAccount.NICKNAME.matches("sunrise_42"))
        assertFalse(ChaparAccount.NICKNAME.matches("ab"))
        assertFalse(ChaparAccount.NICKNAME.matches("with space"))
        assertFalse(ChaparAccount.NICKNAME.matches("a".repeat(21)))
    }


    @Test
    fun connectingNeedsAnActiveAccountOrUnknownStatus() {
        val base = ChaparUiState(loaded = true)
        assertTrue(base.copy(account = AccountState.SignedIn("n", null)).canConnect)
        assertTrue(base.copy(account = AccountState.SignedIn("n", info(0))).canConnect)
        assertFalse(base.copy(account = AccountState.SignedIn("n", info(10 * gib, "over_quota"))).canConnect)
        assertFalse(base.copy(account = AccountState.SignedIn("n", info(0, "disabled"))).canConnect)
    }

    @Test
    fun beforeTheFirstConnectEveryModeIsOfferedAndTheChoiceIsKept() {
        val noProfiles = ChaparUiState(loaded = true)
        assertEquals(setOf(ChaparMode.IRAN, ChaparMode.TOR), noProfiles.availableModes)
        assertEquals(ChaparMode.IRAN, noProfiles.selectedMode)
        assertEquals(ChaparMode.TOR, noProfiles.copy(chosenMode = ChaparMode.TOR).selectedMode)
        val withProfiles = noProfiles.copy(profiles = listOf(ChaparProfile("g1", "Chapar-Iran")), selectedGuid = "g1", chosenMode = ChaparMode.TOR)
        assertEquals(setOf(ChaparMode.IRAN), withProfiles.availableModes)
        assertEquals(ChaparMode.IRAN, withProfiles.selectedMode) // a stored profile wins
    }
}
