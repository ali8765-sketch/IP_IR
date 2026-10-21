package com.v2ray.ang.ui.chapar

import com.v2ray.ang.AppConfig
import com.v2ray.ang.BuildConfig
import com.v2ray.ang.handler.MmkvManager

/**
 * "Apps that skip the VPN" (D-047): the owner's per-app bypass list. Chosen apps use the phone's own
 * connection and real address. It is stored in upstream's per-app settings, which CoreVpnService already
 * applies when it builds the VPN interface (PREF_PER_APP_PROXY on, bypass mode, the chosen packages).
 */
interface BypassStore {
    fun selected(): Set<String>
    fun save(packages: Set<String>)
}

class MmkvBypassStore : BypassStore {
    override fun selected(): Set<String> =
        if (MmkvManager.decodeSettingsBool(AppConfig.PREF_PER_APP_PROXY) && MmkvManager.decodeSettingsBool(AppConfig.PREF_BYPASS_APPS)) {
            MmkvManager.decodeSettingsStringSet(AppConfig.PREF_PER_APP_PROXY_SET).orEmpty().toSet()
        } else {
            emptySet()
        }

    override fun save(packages: Set<String>) {
        // Bypass mode: the chosen apps are left out of the VPN, everything else goes through it.
        MmkvManager.encodeSettings(AppConfig.PREF_BYPASS_APPS, true)
        MmkvManager.encodeSettings(AppConfig.PREF_PER_APP_PROXY_SET, packages.toMutableSet())
        MmkvManager.encodeSettings(AppConfig.PREF_PER_APP_PROXY, packages.isNotEmpty())
    }
}

/** Pure rules of the bypass list, kept free of Android so they run as JVM tests. */
object ChaparBypass {

    /** Chosen apps first, then by name; [filter] matches the name or the package. Chapar itself is never offered. */
    fun visible(apps: List<InstalledApp>, selected: Set<String>, filter: String, self: String = SELF): List<InstalledApp> {
        val needle = filter.trim()
        return apps
            .filter { it.packageName != self }
            .filter { needle.isEmpty() || it.label.contains(needle, true) || it.packageName.contains(needle, true) }
            .sortedWith(compareBy<InstalledApp>({ it.packageName !in selected }, { it.label.lowercase() }))
    }

    fun toggle(selected: Set<String>, packageName: String): Set<String> =
        if (packageName in selected) selected - packageName else selected + packageName

    /** Packages that are no longer installed are dropped when the list is saved. */
    fun cleaned(selected: Set<String>, installed: List<InstalledApp>, self: String = SELF): Set<String> =
        selected.intersect(installed.mapTo(HashSet()) { it.packageName }) - self

    private val SELF = BuildConfig.APPLICATION_ID
}
