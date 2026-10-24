package com.v2ray.ang.handler

import com.google.gson.Gson
import com.v2ray.ang.AppConfig
import com.v2ray.ang.dto.entities.RulesetItem
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.io.File

/**
 * The fork's central routing rule, checked on the preset file the app ships: everything goes through the
 * proxy, QUIC is blocked, and only local network addresses go direct. An upstream merge that changes the
 * preset fails here.
 */
class ChaparRoutingTest {

    // Unit tests run with the app module as the working directory.
    private val preset: List<RulesetItem> =
        Gson().fromJson(File("src/main/assets/custom_routing_chapar").readText(), Array<RulesetItem>::class.java).toList()

    @Test
    fun theShippedPresetSendsEverythingThroughTheProxy() {
        assertTrue(ChaparRouting.defaultsToProxy(preset))
        assertTrue(ChaparRouting.blocksQuic(preset))
        assertEquals(emptyList<RulesetItem>(), ChaparRouting.directRulesOutsideLan(preset))
    }

    @Test
    fun upstreamsChinaPresetFailsTheChecks() {
        val china = listOf(
            RulesetItem(outboundTag = AppConfig.TAG_DIRECT, domain = listOf("geosite:cn")),
            RulesetItem(outboundTag = AppConfig.TAG_DIRECT, ip = listOf("geoip:cn")),
            RulesetItem(outboundTag = AppConfig.TAG_PROXY, port = "0-65535"),
        )
        assertEquals(2, ChaparRouting.directRulesOutsideLan(china).size)
        assertFalse(ChaparRouting.blocksQuic(china))
    }

    @Test
    fun aDisabledOrNarrowLastRuleIsNotADefault() {
        val narrow = preset.dropLast(1) + RulesetItem(outboundTag = AppConfig.TAG_PROXY, port = "443")
        assertFalse(ChaparRouting.defaultsToProxy(narrow))
        val disabled = preset.map { it.copy(enabled = it.outboundTag != AppConfig.TAG_PROXY) }
        assertFalse(ChaparRouting.defaultsToProxy(disabled))
    }

    @Test
    fun migrationStoresThePresetOncePerVersion() {
        assertEquals(preset, ChaparRouting.migratedRulesets(0, preset))
        assertNull(ChaparRouting.migratedRulesets(ChaparRouting.VERSION, preset))
        assertNull(ChaparRouting.migratedRulesets(0, emptyList())) // unreadable preset: try again next start
        assertTrue(ChaparRouting.isLegacyDomesticDns("223.5.5.5"))
        assertFalse(ChaparRouting.isLegacyDomesticDns(AppConfig.DNS_DIRECT))
    }
}
