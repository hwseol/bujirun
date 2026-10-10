package com.mhmh2.bujirun.course

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.cos

class LoopLearnerTest {
    private val lat0 = 37.5
    private val kx = 111_195.0 * cos(Math.toRadians(lat0))
    private fun at(eastM: Double, northM: Double) = GeoPoint(lat0 + northM / 111_195.0, 127.0 + eastM / kx)

    // A 400 m track-ish square, walked every 3 m (one point a second).
    private fun square(): List<GeoPoint> {
        val out = mutableListOf<GeoPoint>()
        for (d in 0 until 400 step 3) {
            val s = d % 400
            out += when {
                s < 100 -> at(s.toDouble(), 0.0)
                s < 200 -> at(100.0, (s - 100).toDouble())
                s < 300 -> at((300 - s).toDouble(), 100.0)
                else -> at(0.0, (400 - s).toDouble())
            }
        }
        return out
    }

    @Test fun learnsTheLoopAfterOneLap() {
        val l = LoopLearner()
        // A 200 m way there, then round the square.
        var t = 0L
        for (n in 0..200 step 3) assertNull(l.add(at(-200.0 + n, -1.0), t).also { t += 1000 })
        var loop: LoopLearner.Loop? = null
        for (lap in 0..1) for (p in square()) { l.add(p, t)?.let { if (loop == null) loop = it }; t += 1000 }
        assertNotNull(loop)
        val route = CourseRoute(loop!!.points)
        assertEquals(400.0, route.lengthM, 20.0)
        assertEquals(133.0, loop!!.lapMs / 1000.0, 10.0)
    }

    @Test fun outAndBackIsNoLoop() {
        val l = LoopLearner()
        var t = 0L
        for (n in 0..600 step 3) assertNull(l.add(at(0.0, n.toDouble()), t).also { t += 1000 })
        for (n in 600 downTo 0 step 3) assertNull(l.add(at(2.0, n.toDouble()), t).also { t += 1000 })
    }

    @Test fun ownLoopIsLappedWithoutEnd() {
        val loop = square() + square().first()
        val nav = CourseNavigator("내 바퀴", CourseRoute(loop), announceHalfway = false, openLaps = true, lapsAlready = 1, lapStartMs = 0)
        val r = nav.route
        val laps = mutableListOf<CourseCue.LapDone>()
        val all = mutableListOf<CourseCue>()
        var t = 0L
        for (lap in 0..2) {
            var d = 0.0
            while (d < r.lengthM) { nav.update(r.pointAt(d), t).also { all += it; laps += it.filterIsInstance<CourseCue.LapDone>() }; d += 3; t += 1000 }
        }
        assertEquals(listOf(2, 3, 4), laps.map { it.done })
        assertTrue(all.none { it == CourseCue.Finished || it is CourseCue.OffCourse })
        assertEquals(5, nav.progress().lap)
        // Distance along keeps growing across laps.
        assertTrue(nav.matchedAlongM()!! > 2 * r.lengthM)
    }
}
