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

    @Test fun onACourseDistanceFollowsTheCourseUnderPoorGps() {
        // Under trees: 40 m fixes (dropped off a course) and only every 10 s. Along the course
        // the runner covered 3 m/s, 300 m in 100 s, while the fixes saw a shorter chord.
        val t = WorkoutTracker()
        t.start(0)
        for (s in 0..100 step 10) {
            t.onLocation(at(s * 1000L, s * 2.0, accuracy = 40f), courseAlongM = s * 3.0)
        }
        assertEquals(300.0, t.distanceM, 1.0)
        // Without a course the same fixes count nothing (too imprecise).
        val free = WorkoutTracker()
        free.start(0)
        for (s in 0..100 step 10) free.onLocation(at(s * 1000L, s * 2.0, accuracy = 40f))
        assertEquals(0.0, free.distanceM, 0.1)
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

    @Test fun autoPauseAtACrossingLosesNoDistance() {
        // 180 m, stop at a crossing (auto-pause), then 12 m of moving again before the resume is
        // noticed, then 180 m more: all 372 m count, and the track isn't split.
        val t = WorkoutTracker()
        t.start(0)
        t.run(0, 0.0, seconds = 60, mps = 3.0)
        t.pause(60_000, keepPlace = true)
        t.run(80_000, 180.0, seconds = 4, mps = 3.0) // ignored while paused: 180 -> 192 m
        t.resume(84_000)
        t.run(85_000, 195.0, seconds = 59, mps = 3.0) // 195 -> 372 m
        assertEquals(372.0, t.distanceM, 5.0)
        assertEquals(1, t.track.map { it.segment }.distinct().size)
    }

    @Test fun aRideOrAGpsJumpIsNotDistanceRun() {
        // 300 m run, then 2 km in a car (14 m/s) with fixes, then 300 m run: only 600 m count.
        val t = WorkoutTracker()
        t.start(0)
        t.run(0, 0.0, seconds = 100, mps = 3.0)
        for (i in 1..143) t.onLocation(at(100_000 + i * 1000L, 300 + i * 14.0))
        t.run(244_000, 2302.0, seconds = 100, mps = 3.0)
        // (A few metres go at the hand-offs, where the first step after the ride starts afresh.)
        assertEquals(600.0, t.distanceM, 15.0)
        // A fix 900 m away after 40 s without any (accepted after the gap, but no runner).
        val j = WorkoutTracker()
        j.start(0)
        j.run(0, 0.0, seconds = 60, mps = 3.0)
        j.onLocation(at(100_000, 1080.0))
        j.run(101_000, 1083.0, seconds = 30, mps = 3.0)
        assertEquals(270.0, j.distanceM, 10.0)
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

    @Test fun currentPaceFollowsTheChipSpeedNotTheZigzag() {
        // Running north at 3 m/s (5'33"/km); positions zigzag 4 m east-west, which makes the
        // distance between fixes come out ~25% long. The chip's own speed reads true.
        val t = WorkoutTracker()
        t.start(0)
        for (i in 0..120) {
            val east = if (i % 2 == 0) 0.0 else 4.0
            t.onLocation(LocationSample(i * 1000L, 37.5 + i * 3.0 / 111_195.0, 127.0 + east / 88_200.0, 5f, speedMps = 3.0))
        }
        assertEquals(333.3, t.currentPaceSecPerKm(120_000)!!, 8.0)
    }

    @Test fun currentPaceIgnoresAChipStuckAtZero() {
        // Some devices (the emulator) report speed 0 with confidence while moving.
        val t = WorkoutTracker()
        t.start(0)
        for (i in 0..60) t.onLocation(at(i * 1000L, i * 3.0).copy(speedMps = 0.0))
        assertEquals(333.3, t.currentPaceSecPerKm(60_000)!!, 8.0)
    }

    @Test fun currentPaceFallsBackWhenSpeedReadingsStop() {
        val t = WorkoutTracker()
        t.start(0)
        for (i in 0..60) t.onLocation(at(i * 1000L, i * 3.0).copy(speedMps = 3.0))
        // Standing at a crossing: no fixes for 20 s; the old readings must not keep 5'33" showing.
        assertTrue(t.currentPaceSecPerKm(80_000)!! > 333.3 * 1.5)
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
