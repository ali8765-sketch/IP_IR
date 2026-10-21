package com.v2ray.ang.ui.chapar

import com.v2ray.ang.handler.AngConfigManager
import com.v2ray.ang.handler.MmkvManager
import com.v2ray.ang.util.Utils

/** A stored subscription. [url] is blank for upstream's default group, which holds pasted share links. */
data class StoredSubscription(val id: String, val url: String)

/**
 * The storage operations [ChaparRepository] needs, so its replace rules run as JVM tests.
 * [MmkvLinkStore] is the real implementation on top of upstream's MMKV storage.
 */
interface LinkStore {
    fun serverIds(): List<String>
    fun serverIdsOf(subscriptionId: String): List<String>
    fun remarksOf(serverId: String): String?
    fun subscriptions(): List<StoredSubscription>

    /** Appends the share links in [text] to the default group. Returns the number stored. */
    fun importShareLinks(text: String): Int

    /** Removes a subscription record together with its profiles. */
    fun removeSubscription(subscriptionId: String)
    fun removeServer(serverId: String)

    fun selectedServer(): String?
    fun selectServer(serverId: String)
}

/**
 * Storage for the Chapar screen. Chapar keeps exactly one set of share links (one per mode);
 * importing a new set replaces the old. This owner exists because upstream's
 * MainRepository only appends, and the replace rule is specific to this fork.
 * Every call does disk or network I/O: call it off the main thread.
 */
class ChaparRepository(
    private val store: LinkStore = MmkvLinkStore(),
    private val isSubscriptionUrl: (String) -> Boolean = { Utils.isValidSubUrl(it) },
) {

    fun profiles(): List<ChaparProfile> =
        store.serverIds().mapNotNull { id -> store.remarksOf(id)?.let { ChaparProfile(id, it) } }

    fun selectedGuid(): String? = store.selectedServer()

    fun select(guid: String) = store.selectServer(guid)

    /**
     * Replaces the stored profiles with the share links in [text] (already checked by ChaparLinks: only
     * vless links for Chapar's server). Nothing old is removed until the new links stored at least one
     * profile; on failure the stored data is exactly as before. Returns true on success.
     * Subscription URLs are not accepted any more: Connect returns the links themselves (D-047).
     */
    fun replaceLink(text: String): Boolean {
        val oldServers = store.serverIds().toSet()
        if (text.lines().any { isSubscriptionUrl(it.trim()) }) return false
        if (store.importShareLinks(text) <= 0) return false
        val keepServers = store.serverIds().toSet() - oldServers
        if (keepServers.isEmpty()) return false
        store.subscriptions().filter { it.url.isNotBlank() }.forEach { store.removeSubscription(it.id) }
        store.serverIds().filter { it in oldServers && it !in keepServers }.forEach { store.removeServer(it) }
        return true
    }

    /** Removes every stored profile and subscription, e.g. after sign-out (D-042). */
    fun clearAll() {
        store.subscriptions().filter { it.url.isNotBlank() }.forEach { store.removeSubscription(it.id) }
        store.serverIds().forEach { store.removeServer(it) }
    }
}

/** [LinkStore] on upstream's MMKV storage and import code. */
class MmkvLinkStore : LinkStore {

    override fun serverIds(): List<String> = MmkvManager.decodeAllServerList()

    override fun serverIdsOf(subscriptionId: String): List<String> = MmkvManager.decodeServerList(subscriptionId)

    override fun remarksOf(serverId: String): String? = MmkvManager.decodeServerConfig(serverId)?.remarks

    override fun subscriptions(): List<StoredSubscription> =
        MmkvManager.decodeSubscriptions().map { StoredSubscription(it.guid, it.subscription.url) }

    override fun importShareLinks(text: String): Int = AngConfigManager.importBatchConfig(text, "", true).first

    override fun removeSubscription(subscriptionId: String) = MmkvManager.removeSubscription(subscriptionId)

    override fun removeServer(serverId: String) = MmkvManager.removeServer(serverId)

    override fun selectedServer(): String? = MmkvManager.getSelectServer()

    override fun selectServer(serverId: String) = MmkvManager.setSelectServer(serverId)
}
