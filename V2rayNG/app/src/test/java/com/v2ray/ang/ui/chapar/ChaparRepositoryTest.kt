package com.v2ray.ang.ui.chapar

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/**
 * Replace rules (Review finding 1, 2026-10-17; D-047): nothing stored may be lost unless the new links
 * stored at least one profile, and only share links are accepted.
 */
class ChaparRepositoryTest {

    private val store = FakeLinkStore()
    private val repo = ChaparRepository(store) { it.startsWith("https://") }

    private fun remarks() = store.servers.values.map { it.second }.sorted()

    @Test
    fun newShareLinksReplaceTheOldOnes() {
        store.addServer(FakeLinkStore.DEFAULT, "Chapar-Iran")
        store.shareLinkRemarks = { listOf("Chapar-Iran", "Chapar-Tor") }

        assertTrue(repo.replaceLink("vless://a\nvless://b"))

        assertEquals(listOf("Chapar-Iran", "Chapar-Tor"), remarks())
        assertEquals(2, store.servers.size)
    }

    @Test
    fun shareLinksAlsoRemoveASubscriptionLeftByAnOlderVersion() {
        val oldSub = store.addSubscription("https://old.example/sub/a")
        store.addServer(oldSub, "Chapar-Iran-old")
        store.shareLinkRemarks = { listOf("Chapar-Iran", "Chapar-Tor") }

        assertTrue(repo.replaceLink("vless://a\nvless://b"))

        assertEquals(listOf("Chapar-Iran", "Chapar-Tor"), remarks())
        assertFalse(oldSub in store.subs.keys)
    }

    @Test
    fun aSubscriptionUrlIsRefusedAndChangesNothing() {
        store.addServer(FakeLinkStore.DEFAULT, "Chapar-Iran-old")
        store.shareLinkRemarks = { listOf("anything") }

        assertFalse(repo.replaceLink("https://evil.example/sub"))
        assertFalse(repo.replaceLink("vless://a\nhttps://evil.example/sub"))

        assertEquals(listOf("Chapar-Iran-old"), remarks())
        assertEquals(setOf(FakeLinkStore.DEFAULT), store.subs.keys)
    }

    @Test
    fun textWithoutALinkChangesNothing() {
        store.addServer(FakeLinkStore.DEFAULT, "Chapar-Iran-old")
        store.shareLinkRemarks = { emptyList() }

        assertFalse(repo.replaceLink("hello"))

        assertEquals(listOf("Chapar-Iran-old"), remarks())
    }

    @Test
    fun clearAllRemovesEverything() {
        val oldSub = store.addSubscription("https://old.example/sub/a")
        store.addServer(oldSub, "x")
        store.addServer(FakeLinkStore.DEFAULT, "y")
        repo.clearAll()
        assertTrue(store.servers.isEmpty())
        assertEquals(setOf(FakeLinkStore.DEFAULT), store.subs.keys)
        assertEquals(1, store.compactions) // the removed keys must not stay readable in the storage file
    }

    @Test
    fun replacingTheSelectedProfileLeavesNoSelectionForTheCallerToFill() {
        val old = store.addServer(FakeLinkStore.DEFAULT, "Chapar-Iran")
        repo.select(old)
        store.shareLinkRemarks = { listOf("Chapar-Iran", "Chapar-Tor") }

        assertTrue(repo.replaceLink("vless://a\nvless://b"))

        // ChaparViewModel.reloadProfiles selects the new profile of the chosen mode right after this.
        assertEquals(null, repo.selectedGuid())
        assertTrue(old !in store.servers.keys)
    }

    @Test
    fun profilesAndSelectionComeFromTheStore() {
        val id = store.addServer(FakeLinkStore.DEFAULT, "Chapar-Tor")
        repo.select(id)
        assertEquals(listOf(ChaparProfile(id, "Chapar-Tor")), repo.profiles())
        assertEquals(id, repo.selectedGuid())
    }
}
