package com.mhmh2.bujirun.tracking

import com.mhmh2.bujirun.course.CourseNavigator
import com.mhmh2.bujirun.course.CourseRoute
import com.mhmh2.bujirun.course.GeoPoint
import org.junit.Assert.assertEquals
import org.junit.Test
import kotlin.math.cos

/**
 * A course run end to end as WorkoutService wires it: fake GPS along the course, the tracker
 * counting distance (with the course progress), the navigator guiding, auto-pause at the fake
 * runner's stops, stopping at "finished". The distance must be the course's.
 */
class CourseDistanceTest {
    private val lat0 = 37.5
    private val kx = 111_195.0 * cos(Math.toRadians(lat0))
    private fun at(e: Double, n: Double) = GeoPoint(lat0 + n / 111_195.0, 127.0 + e / kx)

    @Test fun aLoopCourseRunToTheFinishMeasuresTheCourse() {
        // 1.5 km: out 500 m, a 400 m spur in and out, and back along a path 20 m to the side.
        val pts = listOf(at(0.0, 0.0), at(0.0, 500.0), at(200.0, 500.0), at(0.0, 500.0), at(-20.0, 480.0), at(-20.0, 20.0), at(0.0, 0.0))
        val route = CourseRoute(pts)
        for (seed in 1L..5L) {
            val fake = FakeRun(seed, 0.0, 0.0, pts)
            val nav = CourseNavigator("c", route, startsHere = true)
            val t = WorkoutTracker()
            val speed = SpeedMeter()
            val auto = AutoPauseDetector()
            var autoPaused = false
            var now = 0L
            t.start(now)
            var finished = false
            while (!finished && now < 3_600_000) {
                now += 1000
                val f = fake.next()
                val s = LocationSample(now, f.lat, f.lon, f.accuracyM, f.altitudeM, f.speedMps)
                speed.add(s)
                t.onLocation(s, nav.matchedAlongM())
                val d = auto.update(now, autoPaused, speed.speed(8_000, now), speed.speed(3_000, now), null)
                if (d == AutoPauseDetector.Decision.PAUSE && !autoPaused) { t.pause(now, keepPlace = true); autoPaused = true }
                if (d == AutoPauseDetector.Decision.RESUME && autoPaused) { t.resume(now); autoPaused = false }
                finished = com.mhmh2.bujirun.course.CourseCue.Finished in nav.update(GeoPoint(f.lat, f.lon), now)
            }
            println("seed $seed: course ${route.lengthM.toInt()} m, measured ${t.distanceM.toInt()} m, finished=$finished")
            assertEquals("seed $seed", route.lengthM, t.distanceM, route.lengthM * 0.05)
        }
    }
}
