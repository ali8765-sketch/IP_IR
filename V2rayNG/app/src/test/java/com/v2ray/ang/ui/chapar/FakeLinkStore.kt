package com.v2ray.ang.ui.chapar

/** In-memory [LinkStore] that behaves like upstream's storage for the calls Chapar makes. */
internal class FakeLinkStore : LinkStore {
    val subs = linkedMapOf<String, String>()                 // id -> url ("" = default group)
    val servers = linkedMapOf<String, Pair<String, String>>() // id -> (subscription id, remarks)
    var selected: String? = null
    var shareLinkRemarks: (text: String) -> List<String> = { emptyList() }
    private var next = 0
    private fun newId(prefix: String) = "$prefix${next++}"

    init { subs[DEFAULT] = "" }

    fun addServer(sub: String, remarks: String) = newId("s").also { servers[it] = sub to remarks }

    var compactions = 0

    override fun serverIds() = servers.keys.toList()
    fun serverIdsOf(subscriptionId: String) = servers.filterValues { it.first == subscriptionId }.keys.toList()
    override fun remarksOf(serverId: String) = servers[serverId]?.second
    override fun subscriptions() = subs.map { StoredSubscription(it.key, it.value) }
    /** A subscription stored by an older version of the app (Connect used to allow them). */
    fun addSubscription(url: String) = newId("sub").also { subs[it] = url }

    override fun importShareLinks(text: String): Int {
        val remarks = shareLinkRemarks(text)
        remarks.forEach { addServer(DEFAULT, it) }
        return remarks.size
    }

    override fun removeSubscription(subscriptionId: String) {
        subs.remove(subscriptionId)
        serverIdsOf(subscriptionId).forEach { servers.remove(it) }
    }

    // Like MmkvManager.removeServer: removing the selected profile clears the selection.
    override fun removeServer(serverId: String) {
        servers.remove(serverId)
        if (selected == serverId) selected = null
    }

    override fun compact() { compactions++ }
    override fun selectedServer() = selected
    override fun selectServer(serverId: String) { selected = serverId }

    companion object { const val DEFAULT = "default" }
}
