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

    @Test fun headsUpNamesTheLandmarkAtTheTurn() {
        val nav = CourseNavigator("L", CourseRoute(lShape))
        nav.setLandmarks(listOf(Landmark("역삼역", "station"), null))
        val r = nav.route
        val ahead = mutableListOf<CourseCue.TurnAhead>()
        var d = 0.0; var t = 0L
        while (d <= r.lengthM) { ahead += nav.update(r.pointAt(d), t).filterIsInstance<CourseCue.TurnAhead>(); d += 3; t += 1000 }
        assertEquals(listOf("역삼역 근처에서", null), ahead.map { it.landmark?.at })
    }

    @Test fun gradualCurveOfAParkPathIsNoTurn() {
        // 300 m north, then curving 70 degrees right over ~50 m, then on: no instruction.
        val pts = mutableListOf(at(0.0, 0.0), at(0.0, 300.0))
        var x = 0.0; var y = 300.0
        for (k in 1..10) { val b = Math.toRadians(7.0 * k); x += 5 * kotlin.math.sin(b); y += 5 * kotlin.math.cos(b); pts += at(x, y) }
        pts += at(x + 300 * kotlin.math.sin(Math.toRadians(70.0)), y + 300 * kotlin.math.cos(Math.toRadians(70.0)))
        assertTrue(CourseRoute(pts).turns.toString(), CourseRoute(pts).turns.isEmpty())
    }

    @Test fun lapCourseAnnouncesEachLap() {
        // 50 m approach, then three laps of a 400 m square, then back.
        val ring = listOf(at(0.0, 0.0), at(100.0, 0.0), at(100.0, 100.0), at(0.0, 100.0))
        val pts = listOf(at(-50.0, 0.0)) + List(3) { ring }.flatten() + listOf(at(0.0, 0.0), at(-50.0, 0.0))
        val nav = CourseNavigator("laps", CourseRoute(pts))
        val laps = nav.laps!!
        assertEquals(3, laps.count)
        assertEquals(400.0, laps.lapM, 15.0)
        val r = nav.route
        val said = mutableListOf<CourseCue.LapDone>()
        var d = 0.0; var t = 0L
        while (d <= r.lengthM) { said += nav.update(r.pointAt(d), t).filterIsInstance<CourseCue.LapDone>(); d += 3; t += 1000 }
        assertEquals(listOf(1, 2, 3), said.map { it.done })
        // ~400 m at 3 m/s: about 133 s a lap.
        assertEquals(133.0, said[1].lapMs / 1000.0, 8.0)
    }

    @Test fun ordinaryLoopAndOutAndBackHaveNoLaps() {
        assertEquals(null, CourseLaps.detect(CourseRoute(lShape)))
        val outBack = listOf(at(0.0, 0.0), at(0.0, 600.0), at(3.0, 600.0), at(3.0, 0.0))
        assertEquals(null, CourseLaps.detect(CourseRoute(outBack)))
        val square = listOf(at(0.0, 0.0), at(300.0, 0.0), at(300.0, 300.0), at(0.0, 300.0), at(0.0, 0.0))
        assertEquals(null, CourseLaps.detect(CourseRoute(square)))
    }

    @Test fun jogAcrossTheRoadIsNoTurn() {
        // North, 12 m east across a crossing, north again: straight on, not "right".
        val r = CourseRoute(listOf(at(0.0, 0.0), at(0.0, 300.0), at(12.0, 300.0), at(12.0, 600.0)))
        assertTrue(r.turns.toString(), r.turns.isEmpty())
        // ...but a right turn whose corner is cut in two steps is still one right turn.
        val corner = CourseRoute(listOf(at(0.0, 0.0), at(0.0, 300.0), at(8.0, 308.0), at(300.0, 308.0)))
        assertEquals(listOf(TurnDirection.RIGHT), corner.turns.map { it.direction })
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
        assertFalse(cues.any { it is CourseCue.OffCourse || it == CourseCue.WrongWay })
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
        // Came north, then drifted east: the course (at x = 0) is behind-left of the way they're going.
        assertEquals(listOf<CourseCue>(CourseCue.OffCourse(Relative.BEHIND, 80)), off)
        assertFalse(nav.progress().onCourse)
        assertEquals(listOf<CourseCue>(CourseCue.BackOnCourse), nav.update(at(2.0, 70.0), t))
    }

    @Test fun offCourseSaysWhereTheCourseIsAndRemindsWhileDrifting() {
        val nav = CourseNavigator("L", CourseRoute(lShape))
        // Running north up the first leg, then veering off to the west.
        var t = 0L
        for (n in 0..30 step 3) { nav.update(at(0.0, n.toDouble()), t); t += 1000 }
        val cues = mutableListOf<CourseCue>()
        for (k in 1..90) { cues += nav.update(at(-3.0 * k, 33.0 + k), t); t += 1000 }
        val off = cues.filterIsInstance<CourseCue.OffCourse>()
        // Heading north-west, the course (to the east) is on their right; a reminder follows
        // once they've drifted further.
        assertTrue("toward ${off.first().toward}", off.first().toward in setOf(Relative.RIGHT, Relative.BEHIND_RIGHT, Relative.BEHIND))
        assertTrue("reminded: $off", off.size >= 2)
        assertTrue(off[1].meters > off[0].meters)
    }

    @Test fun wrongWayAndBack() {
        val nav = CourseNavigator("L", CourseRoute(lShape))
        var t = 0L
        // Run the course from 120 m to 200 m in, then turn back toward its start.
        for (n in 120..200 step 4) { nav.update(at(0.0, n.toDouble()), t); t += 1000 }
        val cues = mutableListOf<CourseCue>()
        for (n in 196 downTo 120 step 4) { cues += nav.update(at(0.0, n.toDouble()), t); t += 1000 }
        assertTrue(cues.contains(CourseCue.WrongWay))
        // Turn round.
        val back = mutableListOf<CourseCue>()
        for (n in 124..200 step 4) { back += nav.update(at(0.0, n.toDouble()), t); t += 1000 }
        assertTrue(back.contains(CourseCue.RightWayAgain))
    }

    @Test fun relativeDirections() {
        assertEquals(Relative.AHEAD, Relative.of(10.0))
        assertEquals(Relative.RIGHT, Relative.of(95.0))
        assertEquals(Relative.BEHIND, Relative.of(-175.0))
        assertEquals(Relative.AHEAD_LEFT, Relative.of(-40.0))
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
