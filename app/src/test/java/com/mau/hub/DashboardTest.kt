package com.mau.hub

import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.LocalDate

/** The same checks as art-tiles --selftest on the Linux desktop: packing, fitting, tile values, side-column lines, dates. */
class DashboardTest {
    private fun j(s: String) = JSONObject(s)
    private fun List<IntArray>.rows() = map { it.toList() }

    @Test fun packsDenseLikeTheDesktop() {
        assertEquals(listOf(listOf(0, 0, 1, 1), listOf(0, 1, 2, 1), listOf(1, 0, 1, 1)), Dashboard.pack(listOf(1 to 1, 2 to 1, 1 to 1), 3).rows())
        assertEquals(listOf(listOf(0, 0, 2, 2), listOf(0, 2, 1, 1), listOf(1, 2, 1, 1), listOf(2, 0, 1, 1)),
            Dashboard.pack(listOf(2 to 2, 1 to 1, 1 to 1, 1 to 1), 3).rows())
        assertEquals(listOf(listOf(0, 0, 3, 1)), Dashboard.pack(listOf(5 to 1), 3).rows())
    }

    @Test fun fitsEverythingOnOneScreen() {
        val (boxes, side, k) = Dashboard.fit(List(48) { 1 to 1 } + List(2) { 2 to 1 } + listOf(4 to 2), 1024f, 600f)
        assertEquals(51, boxes.size)
        assertTrue(boxes.all { (x, y, w, h) -> x >= 0 && y >= 0 && x + w <= side[0] && y + h <= 600f + 1e-3f })
        assertTrue(side[0] + side[2] <= 1024f - Dashboard.PAD + 1e-3f && side[2] > 100 && k in 0.6f..1f)
    }

    @Test fun tileValues() {
        assertEquals("21 °C", Dashboard.value(j("""{"entity":"sensor.x"}"""), j("""{"state":"21","attributes":{"unit_of_measurement":"°C"}}"""), true))
        assertEquals("On", Dashboard.value(j("""{"entity":"switch.x"}"""), j("""{"state":"on","attributes":{}}"""), true))
        assertEquals("Unavailable", Dashboard.value(j("""{"entity":"switch.x"}"""), null, true))
        assertEquals("…", Dashboard.value(j("""{"entity":"switch.x"}"""), null, false))
    }

    @Test fun sideColumnLines() {
        val st = mapOf("switch.a" to j("""{"state":"on"}"""), "switch.b" to j("""{"state":"off"}"""), "todo.l" to j("""{"state":"3"}"""), "todo.m" to j("""{"state":"2"}"""))
        assertEquals("1/2 running" to true, Dashboard.serverInfo(j("""{"apps":[{"entity":"switch.a"},{"entity":"switch.b"}]}"""), st, null))
        assertEquals("5 pending" to true, Dashboard.serverInfo(j("""{"kind":"lists","lists":[{"entity":"todo.l"},{"entity":"todo.m"}]}"""), st, null))
        assertEquals("0 unread" to false, Dashboard.serverInfo(j("""{"kind":"notifications","list":"todo.x"}"""), st, null))
        assertEquals("2 notes" to true, Dashboard.serverInfo(j("""{"kind":"notes","folder":"30-projects"}"""), emptyMap(), mapOf("30-projects" to 2)))
    }

    @Test fun homeAddressesSwapOnlyWhenAway() {
        val pairs = mapOf("http://10.0.0.5:3006" to "https://video.example.com")
        assertEquals("http://10.0.0.5:3006/feed", Lan.swap("http://10.0.0.5:3006/feed", pairs, emptySet()))
        assertEquals("https://video.example.com/feed", Lan.swap("http://10.0.0.5:3006/feed", pairs, setOf("http://10.0.0.5:3006")))
        assertEquals("http://10.0.0.5:30060", Lan.swap("http://10.0.0.5:30060", pairs, setOf("http://10.0.0.5:3006"))) // another port
    }

    @Test fun scrcpyLinkLikeTheDesktop() {
        // what the desktop's Screens.jsx (URLSearchParams) builds for the same device
        assertEquals("http://10.0.0.5:8001/#!action=stream&udid=10.0.0.9%3A5555&player=mse" +
            "&ws=ws%3A%2F%2F10.0.0.5%3A8001%2F%3Faction%3Dproxy-adb%26remote%3Dtcp%253A8886%26udid%3D10.0.0.9%253A5555&fitToScreen=true",
            Dashboard.scrcpyLink("http://10.0.0.5:8001/", "10.0.0.9"))
    }

    @Test fun notesCountOnlyNotes() {
        val tree = listOf(Dashboard.Note("B", "30-projects/B", listOf(Dashboard.Note("c", "30-projects/B/c", null))), Dashboard.Note("a", "30-projects/a", null))
        assertEquals(2, Dashboard.countNotes(tree))
    }

    @Test fun screenSlidersPairWithTheirSwitch() {
        assertEquals("switch.pantalla_xperia", Dashboard.screenSwitch("switch.adb_xperia", false))
        assertEquals("switch.stream_tv_video", Dashboard.screenSwitch("switch.stream_tv_control", true))
    }

    @Test fun sidewaysScreensGetARowUprightOnesShareOne() {
        // Xperia sideways, two upright phones, a sideways TV, one more upright phone
        val rows = Dashboard.screenRows(listOf(16 / 9f to true, 16 / 9f to false, 20 / 9f to false, 16 / 9f to true, 16 / 9f to false), 508f, 52f)
        assertEquals(listOf(listOf(0), listOf(1, 2), listOf(3), listOf(4)), rows.map { r -> r.map { it.first } })
        assertEquals(508f, rows[0][0].second, 0.01f); assertEquals(456f * 9 / 16, rows[0][0].third, 0.01f) // full width, short
        assertEquals(250f, rows[1][0].second, 0.01f); assertEquals(198f * 16 / 9, rows[1][0].third, 0.01f) // half width, tall
    }

    @Test fun mauStationOnlyTilesSayItsOff() {
        assertTrue(Dashboard.pcOnly("camera.webcam_maustation") && Dashboard.pcOnly("switch.webcam_video") && Dashboard.pcOnly("sensor.maustation_cpu_load"))
        // its plug and its Tailscale status keep working while it's off
        assertTrue(!Dashboard.pcOnly("switch.kill_switch_maustation") && !Dashboard.pcOnly("binary_sensor.maustation_conectado_al_control"))
        val off = mapOf(Dashboard.PC_POWER to j("""{"state":"off"}"""))
        assertTrue(Dashboard.pcOff(off))
        assertTrue(!Dashboard.pcOff(mapOf(Dashboard.PC_POWER to j("""{"state":"unavailable"}""")))) // unknown: the normal unavailable look
        assertEquals("MauStation está apagado", Dashboard.value(j("""{"entity":"sensor.maustation_cpu_load"}"""), null, true, pcDown = true))
    }

    @Test fun masterEntriesReplaceOursKeepingOurOrder() {
        val mine = listOf(j("""{"name":"Tailscale","apps":[1]}"""), j("""{"name":"Mine only"}"""), j("""{"id":"t1","name":"Old"}"""))
        val master = listOf(j("""{"id":"t1","name":"New"}"""), j("""{"name":"Tailscale","apps":[1,2]}"""), j("""{"name":"Master only"}"""))
        val out = Dashboard.mergeMaster(mine, master)
        assertEquals(listOf("Tailscale", "Mine only", "New"), out.map { it.optString("name") })
        assertEquals(2, out[0].optJSONArray("apps")!!.length())
        assertEquals(mine, Dashboard.mergeMaster(mine, emptyList()))
    }

    @Test fun datesAndNotes() {
        val today = LocalDate.of(2026, 11, 7)
        assertEquals("Today", Dashboard.dayLabel(today, today))
        assertEquals("Tomorrow", Dashboard.dayLabel(today.plusDays(1), today))
        assertEquals("Mon 9 Nov", Dashboard.dayLabel(today.plusDays(2), today))
        assertEquals(LocalDate.of(2026, 11, 8), Dashboard.eventStart(j("""{"start":{"date":"2026-11-08"}}""")).toLocalDate())
        assertEquals("Disk full on the NAS", Dashboard.cleanDesc("**Disk full** on the NAS (id: 42)"))
    }
}
