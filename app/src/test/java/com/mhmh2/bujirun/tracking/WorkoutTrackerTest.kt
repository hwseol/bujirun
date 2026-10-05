package com.mhmh2.bujirun.tracking

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class WorkoutTrackerTest {
    private fun at(timeMs: Long, northM: Double, accuracy: Float = 5f) =
        LocationSample(timeMs, 37.5 + northM / 111_195.0, 127.0, accuracy)

    /** Runs north at [mps] for [seconds], one fix per second, starting at [fromM]/[fromMs]. */
    private fun WorkoutTracker.run(fromMs: Long, fromM: Double, seconds: Int, mps: Double) {
        for (i in 0..seconds) onLocation(at(fromMs + i * 1000L, fromM + i * mps))
    }

    @Test fun countsDistanceAndTimeOfSteadyRun() {
        val t = WorkoutTracker()
        t.start(0)
        t.run(0, 0.0, seconds = 600, mps = 3.0) // 10 min at 3 m/s = 1800 m
        assertEquals(1800.0, t.distanceM, 5.0)
        assertEquals(600_000L, t.elapsedMs(600_000))
        // 3 m/s is 5'33"/km.
        assertEquals(333.3, t.averagePaceSecPerKm(600_000)!!, 2.0)
        assertEquals(333.3, t.currentPaceSecPerKm(600_000)!!, 5.0)
    }

    @Test fun gpsWanderWhileStandingStillAddsNoDistance() {
        val t = WorkoutTracker()
        t.start(0)
        val wobble = listOf(0.0, 1.5, -1.0, 2.0, 0.5, -1.5, 1.0, 0.0, 2.2, -0.8)
        wobble.forEachIndexed { i, m -> t.onLocation(at(i * 1000L, m)) }
        assertEquals(0.0, t.distanceM, 0.001)
    }

    @Test fun slowWalkStillAddsUp() {
        // 1.2 m/s moves less than the 3 m step threshold each second, but must still count.
        val t = WorkoutTracker()
        t.start(0)
        t.run(0, 0.0, seconds = 100, mps = 1.2)
        assertEquals(120.0, t.distanceM, 3.0)
    }

    @Test fun pausedTimeAndMovementDoNotCount() {
        val t = WorkoutTracker()
        t.start(0)
        t.run(0, 0.0, seconds = 60, mps = 3.0) // 180 m
        t.pause(60_000)
        // Walk 100 m while paused.
        t.run(61_000, 180.0, seconds = 60, mps = 100.0 / 60)
        t.resume(121_000)
        t.run(122_000, 280.0, seconds = 60, mps = 3.0) // another 180 m
        assertEquals(360.0, t.distanceM, 5.0)
        // 60 s before the pause + 60 s after resuming at 121 s.
        assertEquals(120_000L, t.elapsedMs(181_000))
        assertEquals(1, t.track.last().segment)
    }

    @Test fun currentPaceSlowsWhenRunnerStops() {
        val t = WorkoutTracker()
        t.start(0)
        t.run(0, 0.0, seconds = 60, mps = 3.0)
        val running = t.currentPaceSecPerKm(60_000)!!
        // No more fixes beyond the anchor: standing at a crossing for 20 s.
        val waiting = t.currentPaceSecPerKm(80_000)!!
        assertTrue(waiting > running * 1.5)
    }

    @Test fun noPaceBeforeEnoughDistance() {
        val t = WorkoutTracker()
        t.start(0)
        t.run(0, 0.0, seconds = 3, mps = 3.0)
        assertNull(t.currentPaceSecPerKm(3000))
        assertNull(t.averagePaceSecPerKm(3000))
    }

    @Test fun finishFreezesTime() {
        val t = WorkoutTracker()
        t.start(0)
        t.finish(30_000)
        assertEquals(WorkoutStatus.FINISHED, t.status)
        assertEquals(30_000L, t.elapsedMs(99_000))
    }
}
