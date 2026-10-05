package com.mhmh2.bujirun.tracking

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class FakeRunTest {
    @Test fun producesABelievableWorkoutThroughTheRealPipeline() {
        val fake = FakeRun(seed = 42, lat = 37.5115, lon = 126.9960)
        val tracker = WorkoutTracker()
        val speed = SpeedMeter()
        val classifier = ActivityClassifier()
        val autoPause = AutoPauseDetector()
        tracker.start(0)
        var autoPaused = false
        var pauses = 0
        val seen = mutableSetOf<Activity>()
        for (t in 1..3600) {
            val f = fake.next()
            val s = LocationSample(t * 1000L, f.lat, f.lon, f.accuracyM, f.altitudeM, f.speedMps)
            speed.add(s)
            tracker.onLocation(s)
            if (tracker.status == WorkoutStatus.RUNNING) {
                tracker.activity = classifier.update(t * 1000L, speed.speed(10_000, t * 1000L), null)
                seen += tracker.activity
                tracker.tick(t * 1000L)
            }
            when (autoPause.update(t * 1000L, autoPaused, speed.speed(8_000, t * 1000L), speed.speed(3_000, t * 1000L), null)) {
                AutoPauseDetector.Decision.PAUSE -> { tracker.pause(t * 1000L); autoPaused = true; pauses++ }
                AutoPauseDetector.Decision.RESUME -> { tracker.resume(t * 1000L); autoPaused = false }
                AutoPauseDetector.Decision.NONE -> {}
            }
        }
        val snap = tracker.snapshot(3_600_000)
        // An hour of mostly running at 5-6:40/km: somewhere around 8-12 km.
        assertTrue("distance ${snap.distanceM}", snap.distanceM in 7_000.0..13_000.0)
        // GPS noise mustn't inflate the recorded distance (it did, ~10%, with noisier fixes).
        // Recorded can be a little short of true: auto-paused stretches don't count.
        val ratio = snap.distanceM / fake.trueDistanceM
        assertTrue("recorded/true $ratio", ratio in 0.92..1.04)
        assertTrue(Activity.RUN in seen && Activity.WALK in seen)
        assertTrue("run ${snap.runMs} walk ${snap.walkMs}", snap.runMs > snap.walkMs)
        assertTrue("climb ${snap.ascentM}", snap.ascentM > 10)
        assertTrue(snap.calories > 300)
        // Traffic-light stops show up as auto-pauses (not guaranteed per seed, but this one has them).
        assertTrue("pauses $pauses", pauses >= 1)
    }

    @Test fun followsACourseAndStopsAtTheFinish() {
        val path = listOf(
            com.mhmh2.bujirun.course.GeoPoint(37.5, 127.0),
            com.mhmh2.bujirun.course.GeoPoint(37.5 + 800 / 111_195.0, 127.0),
        )
        val fake = FakeRun(3, 0.0, 0.0, path)
        var maxOff = 0.0
        var last = fake.next()
        repeat(1200) {
            last = fake.next()
            maxOff = maxOf(maxOff, kotlin.math.abs(last.lon - 127.0) * 111_195.0 * 0.79)
        }
        assertTrue("strays ${maxOff}m from the course", maxOff < 3)
        // 20 minutes is far more than 800 m takes: it ends standing at the finish.
        assertEquals(37.5 + 800 / 111_195.0, last.lat, 3 / 111_195.0)
        assertTrue(last.speedMps < 0.5)
    }

    @Test fun sameSeedSameRun() {
        val a = FakeRun(7, 37.5, 127.0)
        val b = FakeRun(7, 37.5, 127.0)
        repeat(500) { assertEquals(a.next(), b.next()) }
    }
}
