package com.mhmh2.bujirun.course

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.cos

class CourseJoinTest {
    private val lat0 = 37.5
    private val kx = 111_195.0 * cos(Math.toRadians(lat0))
    private fun at(eastM: Double, northM: Double) = GeoPoint(lat0 + northM / 111_195.0, 127.0 + eastM / kx)

    // A 400 m square loop, closed (last point = first).
    private val square = listOf(at(0.0, 0.0), at(100.0, 0.0), at(100.0, 100.0), at(0.0, 100.0), at(0.0, 0.0))

    @Test fun loopIsEnteredAtTheNearestPointAndClosesThere() {
        val here = at(105.0, 102.0) // by the far corner
        assertFalse(CourseJoin.needsApproach(square, here))
        val run = CourseJoin.join(square, here, null)
        assertEquals(square[2], run.first())
        assertEquals(square[2], run.last())
        assertEquals(400.0, CourseRoute(run).lengthM, 1.0)
    }

    @Test fun approachIsAddedThereAndBackForALoop() {
        val here = at(-200.0, 0.0)
        assertTrue(CourseJoin.needsApproach(square, here))
        val run = CourseJoin.join(square, here, null) // straight line when no walking route
        assertEquals(here, run.first())
        assertEquals(here, run.last())
        assertEquals(800.0, CourseRoute(run).lengthM, 1.0)
    }

    @Test fun rejoinGoesToTheNearestPartNotYetRun() {
        // L: 500 m north, then 300 m east. Lost 100 m west of the 400 m mark, 200 m done.
        val r = CourseRoute(listOf(at(0.0, 0.0), at(0.0, 500.0), at(300.0, 500.0)))
        val here = at(-100.0, 400.0)
        val run = CourseJoin.rejoin(r, 200.0, here, null)
        assertEquals(here, run.first())
        assertEquals(r.points.last(), run.last())
        // 100 m across, then the last 100 m north and 300 m east.
        assertEquals(500.0, CourseRoute(run).lengthM, 1.0)
        // Lost beside the part already run: still sent forward, not back to it.
        val (at, _) = CourseJoin.rejoinPoint(r, 450.0, at(-50.0, 100.0))
        assertTrue(at >= 450.0)
    }

    @Test fun rejoinKeepsTheLoopRatherThanHeadingStraightHome() {
        // 1.6 km square loop from (0,0): north, east, south, west home. 50 m done, then the
        // runner wandered to just south of the start - beside the last stretch home.
        val loop = CourseRoute(listOf(at(0.0, 0.0), at(0.0, 400.0), at(400.0, 400.0), at(400.0, 0.0), at(0.0, 0.0)))
        val (at, d) = CourseJoin.rejoinPoint(loop, 50.0, at(60.0, -20.0))
        // Back onto the first side, not onto the last 60 m before the finish.
        assertTrue("rejoined at $at", at < 200)
        assertTrue(d < 100)
        // But someone just beside the course ahead isn't sent back to where they left it.
        val (ahead, _) = CourseJoin.rejoinPoint(loop, 100.0, at(-20.0, 400.0))
        assertTrue("rejoined at $ahead", ahead > 350)
    }

    @Test fun lineStartsAtTheRunnerEvenWhenTheWalkingRouteStartsOnAPathAway() {
        val line = listOf(at(0.0, 0.0), at(0.0, 500.0))
        val here = at(-300.0, 0.0)
        val way = listOf(at(-200.0, 0.0), at(0.0, 0.0)) // routed from the nearest path, 100 m off
        val run = CourseJoin.join(line, here, way)
        assertEquals(here, run.first())
        assertEquals(800.0, CourseRoute(run).lengthM, 1.0)
    }

    @Test fun oneWayCourseStartsAtItsStart() {
        val line = listOf(at(0.0, 0.0), at(0.0, 500.0))
        val here = at(0.0, 400.0) // near the end: still goes to the start
        val way = listOf(here, at(0.0, 200.0), at(0.0, 0.0))
        val run = CourseJoin.join(line, here, way)
        assertEquals(here, run.first())
        assertEquals(line.last(), run.last())
        assertEquals(900.0, CourseRoute(run).lengthM, 1.0)
    }
}
