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

    // A 3.6 km course with turns: north 900, east 900, north 900, west 900.
    private fun zigzag(): List<com.mhmh2.bujirun.course.GeoPoint> {
        val kx = 111_195.0 * kotlin.math.cos(Math.toRadians(37.5))
        fun at(e: Double, n: Double) = com.mhmh2.bujirun.course.GeoPoint(37.5 + n / 111_195.0, 127.0 + e / kx)
        return listOf(at(0.0, 0.0), at(0.0, 900.0), at(900.0, 900.0), at(900.0, 1800.0), at(0.0, 1800.0))
    }

    @Test fun aStrayingRunnerLeavesTheCourseNowAndThen() {
        val route = com.mhmh2.bujirun.course.CourseRoute(zigzag())
        val fake = FakeRun(11, 0.0, 0.0, zigzag(), strays = true)
        var maxOff = 0.0
        val kinds = mutableSetOf<String>()
        repeat(2400) {
            val f = fake.next()
            maxOff = maxOf(maxOff, route.locate(com.mhmh2.bujirun.course.GeoPoint(f.lat, f.lon)).second)
            fake.straying?.let { kinds += it }
        }
        assertTrue("only ${maxOff}m off", maxOff > 30)
        assertTrue("strays: $kinds", kinds.isNotEmpty())
    }

    @Test fun aStrayingRunnerGuidedBackStillFinishesTheCourse() {
        // What a test run exercises: the runner strays, the navigator notices, a reroute from where
        // they are (as WorkoutService does, a straight approach here), and the runner follows it.
        for (seed in 1L..8L) {
            val fake = FakeRun(seed, 0.0, 0.0, zigzag(), strays = true)
            var nav = com.mhmh2.bujirun.course.CourseNavigator("Z", com.mhmh2.bujirun.course.CourseRoute(zigzag()), startsHere = true)
            var ran = 0.0
            var last: com.mhmh2.bujirun.course.GeoPoint? = null
            var reroutes = 0
            var lastReroute = -60_000L
            var finished = false
            var t = 0L
            while (t < 3 * 3600_000L && !finished) {
                val f = fake.next()
                val p = com.mhmh2.bujirun.course.GeoPoint(f.lat, f.lon)
                last?.let { ran += com.mhmh2.bujirun.tracking.distanceMeters(it.lat, it.lon, p.lat, p.lon) }
                last = p
                val cues = nav.update(p, t)
                if (com.mhmh2.bujirun.course.CourseCue.Finished in cues) finished = true
                val lost = cues.any { it is com.mhmh2.bujirun.course.CourseCue.OffCourse ||
                    (it == com.mhmh2.bujirun.course.CourseCue.WrongWay && nav.route.isLoop) }
                if (lost && reroutes < 8 && t - lastReroute >= 30_000) {
                    val r = com.mhmh2.bujirun.course.CourseJoin.rerouteFrom(nav.route, nav.progress().progressM, ran, p)
                    val line = com.mhmh2.bujirun.course.CourseJoin.restFrom(r.route, r.at, p, null)
                    nav = com.mhmh2.bujirun.course.CourseNavigator("Z", com.mhmh2.bujirun.course.CourseRoute(line), announceHalfway = false, startsHere = true)
                    fake.follow(line)
                    reroutes++; lastReroute = t
                }
                t += 1000
            }
            assertTrue("seed $seed: not finished after ${t / 60_000} min, $reroutes reroutes", finished)
        }
    }

    @Test fun sameSeedSameRun() {
        val a = FakeRun(7, 37.5, 127.0)
        val b = FakeRun(7, 37.5, 127.0)
        repeat(500) { assertEquals(a.next(), b.next()) }
    }
}
