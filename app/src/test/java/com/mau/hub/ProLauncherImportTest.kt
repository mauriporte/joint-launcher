package com.mau.hub

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class ProLauncherImportTest {
    private fun zip(vararg files: Pair<String, String>): ByteArray {
        val out = java.io.ByteArrayOutputStream()
        java.util.zip.ZipOutputStream(out).use { z -> files.forEach { (n, t) -> z.putNextEntry(java.util.zip.ZipEntry(n)); z.write(t.toByteArray()); z.closeEntry() } }
        return out.toByteArray()
    }

    @Test fun readsHomeNamesCategoriesAndDock() {
        val apps = """[
            {"packageName":"com.beeper.android","labelRename":"Chat","homeRename":"","appCategory":"#","hidden":false,"appType":"app_model"},
            {"packageName":"com.x.watchface","labelRename":"","appCategory":"⌚","hidden":true,"appType":"app_model"},
            {"packageName":"app.gamenative","labelRename":"3dSen","appCategory":"Game","hidden":false,"appType":"web_shortcut"},
            {"packageName":"org.videolan.vlc","labelRename":"","appCategory":"Music","hidden":false,"appType":"app_model"}]"""
        // Pro Launcher keeps some lists as JSON inside a string
        val prefs = """{"HOME_ITEMS":"[{\"homePos\":2,\"appModel\":{\"packageName\":\"com.android.youtube.premium\",\"labelRename\":\"Videos\"}},{\"homePos\":0,\"categoryInfoModel\":{\"name\":\"#\"}},{\"homePos\":1,\"appModel\":{\"packageName\":\"com.beeper.android\"}}]",
            "APP_CATEGORIES":["All apps","#","⚡ ","Uncategorized"]}"""
        val store = """{"SWIPE_LEFT":"OPEN_APP","SWIPE_LEFT_APP":"{\"packageName\":\"com.beeper.android\"}",
            "HOME_ACTIONS":"[{\"show\":false,\"label\":\"CIRCLE\"},{\"show\":true,\"appModel\":{\"packageName\":\"com.android.camera\"}},{\"show\":true,\"appModel\":{\"packageName\":\"com.google.android.dialer\"}}]"}"""
        val r = ProLauncherImport.read(zip("apps.json" to apps, "shared_prefs.json" to prefs, "datastore.json" to store, "notes.json" to "[]"))!!
        assertEquals(listOf("cat:#", "com.beeper.android", "com.android.youtube.premium"), r.home)
        assertEquals(mapOf("com.beeper.android" to "Chat", "com.android.youtube.premium" to "Videos"), r.names)
        assertEquals(mapOf("com.beeper.android" to "#", "com.x.watchface" to "⌚", "org.videolan.vlc" to "🎵"), r.cats) // old "Music" folded in
        assertEquals(listOf("#", "⚡"), r.catOrder)
        assertEquals(setOf("com.x.watchface"), r.hidden)
        assertEquals(listOf("com.google.android.dialer", "com.android.camera"), r.dock) // shown left to right
        assertEquals("com.beeper.android", r.swipeLeft)
    }

    @Test fun notABackup() = assertNull(ProLauncherImport.read(zip("hello.txt" to "hi")))
}
