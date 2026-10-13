package com.mhmh2.bujirun.course

import com.mhmh2.bujirun.course.CourseOutline.Kind
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.cos

class CourseOutlineTest {
    private val lat0 = 37.5
    private val kx = 111_195.0 * cos(Math.toRadians(lat0))
    private fun at(e: Double, n: Double) = GeoPoint(lat0 + n / 111_195.0, 127.0 + e / kx)
    private fun line(vararg p: Pair<Double, Double>) = p.flatMapIndexed { i, (e, n) ->
        if (i == 0) listOf(at(e, n)) else {
            val (pe, pn) = p[i - 1]
            (1..10).map { k -> at(pe + (e - pe) * k / 10, pn + (n - pn) * k / 10) }
        }
    }

    @Test fun smallJogsDisappearBigTurnsStay() {
        // North 1 km with a 30 m jog every 200 m, then east 800 m, then back south and west home.
        val route = CourseRoute(line(
            0.0 to 0.0, 0.0 to 200.0, 30.0 to 210.0, 30.0 to 400.0, 0.0 to 410.0, 0.0 to 1000.0,
            800.0 to 1000.0, 800.0 to 0.0, 0.0 to 0.0,
        ))
        val steps = CourseOutline.of(route)
        assertEquals(listOf(Kind.START, Kind.TURN, Kind.TURN, Kind.TURN, Kind.FINISH), steps.map { it.kind })
        assertEquals("북쪽", steps[0].heading)
        assertEquals(1000.0, steps[0].legM, 60.0)
        assertEquals(TurnDirection.RIGHT, steps[1].direction)
        assertEquals("동쪽", steps[1].heading)
        assertEquals("남쪽", steps[2].heading)
        // The far corner (800, 1000) is where it turns for home.
        assertTrue(steps[1].farthest || steps[2].farthest)
    }

    @Test fun outAndBackHasItsUTurn() {
        val steps = CourseOutline.of(CourseRoute(line(0.0 to 0.0, 1500.0 to 0.0, 1500.0 to 10.0, 0.0 to 10.0)))
        assertEquals(listOf(Kind.START, Kind.U_TURN, Kind.FINISH), steps.map { it.kind })
        assertEquals("동쪽", steps[0].heading)
        assertEquals("서쪽", steps[1].heading)
        assertTrue(steps[1].farthest)
    }

    @Test fun neverMoreThanSevenLines() {
        // A zigzag of 40 corners 300 m apart.
        val pts = (0..40).map { i -> (if (i % 2 == 0) 0.0 else 300.0) to i * 300.0 }
        val steps = CourseOutline.of(CourseRoute(line(*pts.toTypedArray())))
        assertTrue(steps.size <= 7)
    }
}
