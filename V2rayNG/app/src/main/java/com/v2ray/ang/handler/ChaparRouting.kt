package com.v2ray.ang.handler

import com.v2ray.ang.AppConfig
import com.v2ray.ang.dto.entities.RulesetItem

/**
 * Chapar's routing preset and the rules for moving an install to it (kept free of Android so they
 * run as JVM tests). Upstream stores a China-oriented preset (`custom_routing_white`: geosite:cn
 * and geoip:cn direct, domestic DNS direct) at first start; that sends some traffic out from the
 * phone's real address even in Tor mode. Chapar sends everything through the proxy and keeps only
 * local network addresses direct. ChaparRoutingTest checks the shipped preset against these rules.
 */
object ChaparRouting {

    /** Bump when the shipped preset changes; installs below this version get the preset once. */
    const val VERSION = 1

    /** The only addresses a Chapar rule may send direct. */
    private val LAN_ONLY = setOf(AppConfig.GEOIP_PRIVATE)

    fun needsMigration(storedVersion: Int): Boolean = storedVersion < VERSION

    /**
     * The rules to store for an install whose marker is [storedVersion], or null when nothing changes
     * (already migrated, or the preset could not be read: the marker is then left alone so the next
     * start tries again).
     */
    fun migratedRulesets(storedVersion: Int, preset: List<RulesetItem>?): MutableList<RulesetItem>? =
        if (needsMigration(storedVersion) && !preset.isNullOrEmpty()) preset.toMutableList() else null

    /** Enabled rules that send traffic direct from the real address although it is not a local network address. */
    fun directRulesOutsideLan(rules: List<RulesetItem>): List<RulesetItem> =
        rules.filter { it.enabled && it.outboundTag == AppConfig.TAG_DIRECT && !isLanOnly(it) }

    /** True when the last enabled rule sends every remaining port through the proxy. */
    fun defaultsToProxy(rules: List<RulesetItem>): Boolean {
        val last = rules.lastOrNull { it.enabled } ?: return false
        return last.outboundTag == AppConfig.TAG_PROXY &&
            last.port == "0-65535" && last.ip.isNullOrEmpty() && last.domain.isNullOrEmpty() &&
            last.network.isNullOrEmpty() && last.protocol.isNullOrEmpty() && last.process.isNullOrEmpty()
    }

    /** True when the preset blocks QUIC (UDP 443), as upstream's global preset does. */
    fun blocksQuic(rules: List<RulesetItem>): Boolean = rules.any {
        it.enabled && it.outboundTag == AppConfig.TAG_BLOCKED && it.port == "443" && it.network == "udp"
    }

    /** True for the domestic (China) DNS server upstream stores as default; the migration replaces it. */
    fun isLegacyDomesticDns(stored: String?): Boolean = stored == LEGACY_DOMESTIC_DNS

    private const val LEGACY_DOMESTIC_DNS = "223.5.5.5"

    private fun isLanOnly(rule: RulesetItem): Boolean =
        rule.domain.isNullOrEmpty() && !rule.ip.isNullOrEmpty() && rule.ip!!.all { it in LAN_ONLY }
}
