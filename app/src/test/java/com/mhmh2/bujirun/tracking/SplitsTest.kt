package com.mhmh2.bujirun.tracking

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class SplitsTest {
    private fun point(distanceM: Double, activeMs: Long) =
        TrackPoint(LocationSample(activeMs, 37.5, 127.0, 5f), 0, Activity.RUN, distanceM, activeMs)

    @Test fun interpolatesKmCrossings() {
        // Points every 300 m at 6:00/km (1.8 s per 10 m... 108 s per 300 m), then 5:00/km.
        val pts = mutableListOf(point(0.0, 0))
        var d = 0.0; var t = 0L
        while (d < 1500) { d += 300; t += 108_000; pts += point(d, t) } // 1500 m in 540 s
        while (d < 2700) { d += 300; t += 90_000; pts += point(d, t) }  // 1200 m in 360 s
        val splits = Splits.compute(pts, d, t)
        assertEquals(3, splits.size)
        assertEquals(360.0, splits[0].paceSecPerKm, 0.5)
        // Second km: 500 m at 6:00 + 500 m at 5:00 = 330 s.
        assertEquals(330_000L, splits[1].durationMs)
        // Final 700 m at 5:00/km.
        assertEquals(700.0, splits[2].distanceM, 0.01)
        assertEquals(300.0, splits[2].paceSecPerKm, 0.5)
    }

    @Test fun tinyRemainderIsDropped() {
        val pts = listOf(point(0.0, 0), point(500.0, 150_000), point(1060.0, 318_000))
        val splits = Splits.compute(pts, 1060.0, 318_000)
        assertEquals(1, splits.size)
    }

    @Test fun shortWorkoutGetsOnePartialSplit() {
        val pts = listOf(point(0.0, 0), point(400.0, 120_000))
        val splits = Splits.compute(pts, 400.0, 120_000)
        assertEquals(1, splits.size)
        assertEquals(300.0, splits[0].paceSecPerKm, 0.5)
    }

    @Test fun trackerRecordsCumulativeDistanceAndActiveTime() {
        val t = WorkoutTracker()
        t.start(0)
        (0..100).forEach { t.onLocation(LocationSample(it * 1000L, 37.5 + it * 4.0 / 111_195.0, 127.0, 5f)) }
        val last = t.track.last()
        assertEquals(t.distanceM, last.distanceM, 0.001)
        assertEquals(100_000L, last.activeMs)
        assertTrue(t.track.zipWithNext().all { (a, b) -> b.activeMs >= a.activeMs && b.distanceM >= a.distanceM })
    }
}
