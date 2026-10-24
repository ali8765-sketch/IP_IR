package com.v2ray.ang

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.w3c.dom.Element
import java.io.File
import javax.xml.parsers.DocumentBuilderFactory

/**
 * Chapar keeps most of upstream's code but closes its entry points. An upstream merge that re-adds an exported
 * component, a launcher activity, the boot receiver, or the camera permission fails here.
 */
class ChaparManifestTest {

    private val android = "http://schemas.android.com/apk/res/android"
    private val tools = "http://schemas.android.com/tools"

    // Unit tests run with the app module as the working directory.
    private val manifest: Element = DocumentBuilderFactory.newInstance()
        .apply { isNamespaceAware = true }
        .newDocumentBuilder()
        .parse(File("src/main/AndroidManifest.xml"))
        .documentElement

    private fun components(): List<Element> = listOf("activity", "service", "receiver", "provider").flatMap { tag ->
        val nodes = manifest.getElementsByTagName(tag)
        (0 until nodes.length).map { nodes.item(it) as Element }
    }

    private val Element.name get() = getAttributeNS(android, "name")

    @Test
    fun onlyTheLauncherAndTheTileAreExported() {
        val exported = components().filter { it.getAttributeNS(android, "exported") != "false" }.map { it.name }.sorted()
        assertEquals(listOf(".service.QSTileService", ".ui.chapar.ChaparActivity"), exported)
    }

    @Test
    fun onlyChaparActivityHasAnIntentFilterAmongActivities() {
        val withFilter = components()
            .filter { it.tagName == "activity" && it.getElementsByTagName("intent-filter").length > 0 }
            .map { it.name }
        assertEquals(listOf(".ui.chapar.ChaparActivity"), withFilter)
    }

    @Test
    fun nothingStartsAtBootAndTheCameraIsNotRequested() {
        assertTrue(components().none { it.name.endsWith("BootReceiver") })
        val permissions = manifest.getElementsByTagName("uses-permission")
        val requested = (0 until permissions.length).map { permissions.item(it) as Element }
            .filter { it.getAttributeNS(tools, "node") != "remove" }
            .map { it.getAttributeNS(android, "name") }
        assertTrue("android.permission.RECEIVE_BOOT_COMPLETED" !in requested)
        assertTrue("android.permission.CAMERA" !in requested)
    }
}
