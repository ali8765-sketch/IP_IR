package com.v2ray.ang.ui.chapar

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test

class ChaparBypassTest {

    private val apps = listOf(
        InstalledApp("com.bank.app", "Bank"),
        InstalledApp("org.chapar.vpn", "Chapar"),
        InstalledApp("com.chat.app", "chat"),
        InstalledApp("com.alpha", "Alpha"),
    )

    @Test
    fun chosenAppsComeFirstThenByNameAndChaparIsNeverOffered() {
        val visible = ChaparBypass.visible(apps, setOf("com.chat.app"), "", self = "org.chapar.vpn")
        assertEquals(listOf("com.chat.app", "com.alpha", "com.bank.app"), visible.map { it.packageName })
    }

    @Test
    fun searchMatchesNameOrPackage() {
        assertEquals(listOf("com.bank.app"), ChaparBypass.visible(apps, emptySet(), "BAN", self = "x").map { it.packageName })
        assertEquals(listOf("com.chat.app"), ChaparBypass.visible(apps, emptySet(), "chat.app", self = "x").map { it.packageName })
        assertEquals(emptyList<InstalledApp>(), ChaparBypass.visible(apps, emptySet(), "nothing", self = "x"))
    }

    @Test
    fun toggleAndCleanUp() {
        val chosen = ChaparBypass.toggle(ChaparBypass.toggle(emptySet(), "com.bank.app"), "com.alpha")
        assertEquals(setOf("com.bank.app", "com.alpha"), chosen)
        assertEquals(setOf("com.alpha"), ChaparBypass.toggle(chosen, "com.bank.app"))
        // Uninstalled apps and Chapar itself are dropped when the list is saved.
        assertEquals(
            setOf("com.alpha"),
            ChaparBypass.cleaned(setOf("com.alpha", "gone.app", "org.chapar.vpn"), apps, self = "org.chapar.vpn"),
        )
    }
}
