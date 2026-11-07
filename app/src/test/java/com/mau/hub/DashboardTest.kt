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

    @Test fun datesAndNotes() {
        val today = LocalDate.of(2026, 11, 7)
        assertEquals("Today", Dashboard.dayLabel(today, today))
        assertEquals("Tomorrow", Dashboard.dayLabel(today.plusDays(1), today))
        assertEquals("Mon 9 Nov", Dashboard.dayLabel(today.plusDays(2), today))
        assertEquals(LocalDate.of(2026, 11, 8), Dashboard.eventStart(j("""{"start":{"date":"2026-11-08"}}""")).toLocalDate())
        assertEquals("Disk full on the NAS", Dashboard.cleanDesc("**Disk full** on the NAS (id: 42)"))
    }
}
