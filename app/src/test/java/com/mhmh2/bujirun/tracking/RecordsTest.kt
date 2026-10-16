package com.mhmh2.bujirun.tracking

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import java.time.LocalDate

class RecordsTest {
    @Test fun fastestStretchInsideARun() {
        // 3 km: first km 6:00, second 5:00, third 5:30 - points every 100 m at an even pace each km.
        val pts = mutableListOf(0.0 to 0L)
        val kmMs = listOf(360_000L, 300_000L, 330_000L)
        var t = 0L
        for (k in 0 until 3) for (s in 1..10) { t += kmMs[k] / 10; pts += (k * 1000.0 + s * 100) to t }
        assertEquals(300_000L, Records.fastestMs(pts, 1000.0))
        assertEquals(360_000L + 300_000L + 330_000L, Records.fastestMs(pts, 3000.0))
        assertNull(Records.fastestMs(pts, 5000.0))
    }

    @Test fun weeksInARow() {
        val today = LocalDate.of(2026, 10, 17) // a Saturday
        val days = listOf(LocalDate.of(2026, 10, 13), LocalDate.of(2026, 10, 6), LocalDate.of(2026, 9, 29), LocalDate.of(2026, 9, 15))
        assertEquals(3, Records.weekStreak(days, today))
        // Nothing yet this week: the streak runs from last week.
        assertEquals(2, Records.weekStreak(days.drop(1), today))
        assertEquals(0, Records.weekStreak(listOf(LocalDate.of(2026, 9, 1)), today))
    }
}
