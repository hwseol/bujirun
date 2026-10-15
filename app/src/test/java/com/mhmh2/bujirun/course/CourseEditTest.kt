package com.mhmh2.bujirun.course

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class CourseEditTest {
    private fun p(n: Double) = GeoPoint(37.5 + n * 0.001, 127.0)
    // Four points, each segment with a bend in the middle.
    private val course = DrawnCourse(
        listOf(p(0.0), p(1.0), p(2.0), p(3.0)),
        listOf(listOf(p(0.0), p(0.5), p(1.0)), listOf(p(1.0), p(1.5), p(2.0)), listOf(p(2.0), p(2.5), p(3.0))),
        listOf("출발", "A", "B", "C"),
    )

    @Test fun removingAMiddlePointJoinsItsNeighbours() {
        val (c, rejoin) = course.remove(1)
        assertEquals(listOf(p(0.0), p(2.0), p(3.0)), c.points)
        assertEquals(listOf("출발", "B", "C"), c.names)
        assertEquals(0, rejoin)
        assertEquals(listOf(p(0.0), p(2.0)), c.segments[0])
        assertEquals(listOf(p(2.0), p(2.5), p(3.0)), c.segments[1])
        val routed = c.withSegment(0, listOf(p(0.0), p(1.2), p(2.0)))
        assertEquals(listOf(p(0.0), p(1.2), p(2.0), p(2.5), p(3.0)), routed.line)
    }

    @Test fun removingAnEndDropsItsSegment() {
        val (last, r1) = course.remove(3)
        assertEquals(2, last.segments.size)
        assertNull(r1)
        val (first, r2) = course.remove(0)
        assertEquals(p(1.0), first.points.first())
        assertEquals(listOf(p(1.0), p(1.5), p(2.0)), first.segments.first())
        assertNull(r2)
        val (one, _) = DrawnCourse(listOf(p(0.0)), emptyList(), listOf(null)).remove(0)
        assertEquals(DrawnCourse.EMPTY, one)
    }

    @Test fun eachPointsDistanceAlong() {
        val d = course.pointDistances()
        assertEquals(0.0, d[0], 0.0)
        assertEquals(111.2, d[1], 1.0)
        assertEquals(333.6, d[3], 2.0)
    }
}
