package com.mhmh2.bujirun.course

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.cos

class CourseNavigatorTest {
    private val lat0 = 37.5
    private val kx = 111_195.0 * cos(Math.toRadians(lat0))
    /** Point [eastM]/[northM] metres from the origin. */
    private fun at(eastM: Double, northM: Double, alt: Double? = null) = GeoPoint(lat0 + northM / 111_195.0, 127.0 + eastM / kx, alt)

    // 500 m north, turn right, 300 m east, turn left, 400 m north.
    private val lShape = listOf(at(0.0, 0.0), at(0.0, 500.0), at(300.0, 500.0), at(300.0, 900.0))

    @Test fun findsTurnsWithDirections() {
        val r = CourseRoute(lShape)
        assertEquals(1200.0, r.lengthM, 1.0)
        assertEquals(2, r.turns.size)
        assertEquals(TurnDirection.RIGHT, r.turns[0].direction)
        assertEquals(500.0, r.turns[0].atM, 10.0)
        assertEquals(TurnDirection.LEFT, r.turns[1].direction)
        assertEquals(800.0, r.turns[1].atM, 10.0)
    }

    @Test fun straightOrGentlyCurvingLinesHaveNoTurns() {
        val curve = (0..100).map { at(it * 0.5 * kotlin.math.sin(it / 40.0) * 10, it * 10.0) }
        assertTrue(CourseRoute(curve).turns.isEmpty())
    }

    @Test fun uTurnOnOutAndBack() {
        val r = CourseRoute(listOf(at(0.0, 0.0), at(0.0, 400.0), at(5.0, 400.0), at(5.0, 0.0)))
        assertEquals(listOf(TurnDirection.U_TURN), r.turns.map { it.direction })
    }

    @Test fun locateStaysOnTheRightPassOfAnOutAndBack() {
        val r = CourseRoute(listOf(at(0.0, 0.0), at(0.0, 400.0), at(5.0, 400.0), at(5.0, 0.0)))
        // Standing 100 m up the path: on the way out that's 100 m in, on the way back ~710 m in.
        assertEquals(100.0, r.locate(at(2.0, 100.0), 0.0, 300.0).first, 3.0)
        assertEquals(705.0, r.locate(at(3.0, 100.0), 600.0, 900.0).first, 5.0)
    }

    @Test fun guidesThroughTheCourse() {
        val nav = CourseNavigator("L", CourseRoute(lShape))
        val said = mutableListOf<Pair<Double, CourseCue>>()
        // Run the course at 3 m/s with 3 m of sideways wobble.
        val r = nav.route
        var d = 0.0; var t = 0L
        while (d <= r.lengthM) {
            val p = r.pointAt(d).let { it.copy(lon = it.lon + (if ((t / 1000) % 2 == 0L) 3 else -3) / kx) }
            nav.update(p, t).forEach { said += d to it }
            d += 3; t += 1000
        }
        val cues = said.map { it.second }
        assertEquals(
            listOf(
                CourseCue.TurnAhead(TurnDirection.RIGHT, 120), CourseCue.TurnNow(TurnDirection.RIGHT, null),
                CourseCue.Halfway,
                CourseCue.TurnAhead(TurnDirection.LEFT, 120), CourseCue.TurnNow(TurnDirection.LEFT, null),
                CourseCue.Finished,
            ),
            cues,
        )
        assertFalse(cues.any { it == CourseCue.OffCourse })
        // The "now" cue for the right turn comes within 20 m before it.
        val now = said.first { it.second == CourseCue.TurnNow(TurnDirection.RIGHT, null) }.first
        assertTrue("at $now", now in 478.0..505.0)
    }

    @Test fun noLateHeadsUpForATurnRightAfterAnother() {
        // Right turn, then a left only 70 m later: no "70 m ahead" heads-up for the second.
        val r = CourseRoute(listOf(at(0.0, 0.0), at(0.0, 500.0), at(70.0, 500.0), at(70.0, 900.0)))
        val nav = CourseNavigator("zig", r)
        val cues = mutableListOf<CourseCue>()
        var d = 0.0; var t = 0L
        while (d <= r.lengthM) { cues += nav.update(r.pointAt(d), t); d += 3; t += 1000 }
        assertEquals(
            listOf(
                CourseCue.TurnAhead(TurnDirection.RIGHT, 120),
                CourseCue.TurnNow(TurnDirection.RIGHT, TurnDirection.LEFT), // "right. then left."
                CourseCue.TurnNow(TurnDirection.LEFT, null),
            ),
            cues.filter { it is CourseCue.TurnAhead || it is CourseCue.TurnNow },
        )
    }

    @Test fun remainingDistanceAtEachKmOfALongCourse() {
        val nav = CourseNavigator("long", CourseRoute(listOf(at(0.0, 0.0), at(0.0, 3500.0))))
        val remaining = mutableListOf<Double>()
        var d = 0.0; var t = 0L
        while (d <= 3500) {
            nav.update(nav.route.pointAt(d), t).filterIsInstance<CourseCue.Remaining>().forEach { remaining += it.meters }
            d += 3; t += 1000
        }
        assertEquals(3, remaining.size) // at 1, 2 and 3 km (500 m left at 3 km)
        assertEquals(2500.0, remaining[0], 5.0)
    }

    @Test fun offCourseAfterHoldThenBack() {
        val nav = CourseNavigator("L", CourseRoute(lShape))
        nav.update(at(0.0, 0.0), 0)
        nav.update(at(0.0, 50.0), 15_000)
        // Wander 80 m east of the path.
        var t = 20_000L
        val off = mutableListOf<CourseCue>()
        repeat(12) { off += nav.update(at(80.0, 60.0), t); t += 1000 }
        assertEquals(listOf<CourseCue>(CourseCue.OffCourse), off)
        assertFalse(nav.progress().onCourse)
        assertEquals(listOf<CourseCue>(CourseCue.BackOnCourse), nav.update(at(2.0, 70.0), t))
    }

    @Test fun announcesWalkToStartWhenStartingAway() {
        val nav = CourseNavigator("L", CourseRoute(lShape))
        val first = nav.update(at(-250.0, 0.0), 0)
        assertEquals(listOf<CourseCue>(CourseCue.ToStart(250)), first)
        assertFalse(nav.progress().started)
        assertEquals(listOf<CourseCue>(CourseCue.AtStart), nav.update(at(-5.0, 0.0), 90_000))
    }

    @Test fun announcesClimbsWhenAltitudeKnown() {
        // Flat 400 m, then 20 m up over 300 m, then flat.
        val pts = (0..40).map { at(0.0, it * 25.0, if (it <= 16) 10.0 else if (it <= 28) 10.0 + (it - 16) * 20.0 / 12 else 30.0) }
        val r = CourseRoute(pts)
        assertEquals(1, r.climbs.size)
        assertEquals(400.0, r.climbs[0].startM, 30.0)
        assertEquals(20.0, r.climbs[0].gainM, 2.0)
    }
}
