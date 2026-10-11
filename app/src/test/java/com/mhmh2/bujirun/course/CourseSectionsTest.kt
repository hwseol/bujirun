package com.mhmh2.bujirun.course

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.cos

class CourseSectionsTest {
    private val lat0 = 37.5
    private val kx = 111_195.0 * cos(Math.toRadians(lat0))
    private fun at(eastM: Double, northM: Double) = GeoPoint(lat0 + northM / 111_195.0, 127.0 + eastM / kx)
    private fun line(vararg p: Pair<Double, Double>) = p.flatMapIndexed { i, (e, n) ->
        if (i == 0) listOf(at(e, n)) else {
            val (pe, pn) = p[i - 1]
            (1..10).map { k -> at(pe + (e - pe) * k / 10, pn + (n - pn) * k / 10) }
        }
    }

    @Test fun cutAtEachTurn() {
        // North 400 m, right (east) 300 m, then left (north) 200 m.
        val route = CourseRoute(line(0.0 to 0.0, 0.0 to 400.0, 300.0 to 400.0, 300.0 to 600.0))
        val s = route.sections()
        assertEquals(3, s.size)
        assertEquals(400.0, s[0].lengthM, 15.0)
        assertEquals(TurnDirection.RIGHT, s[0].turn?.direction)
        assertEquals(TurnDirection.LEFT, s[1].turn?.direction)
        assertNull(s[2].turn)
        assertEquals(route.lengthM, s.last().toM, 0.01)
        val part = route.slice(s[1].fromM, s[1].toM)
        assertEquals(400.0, part.first().let { (it.lat - lat0) * 111_195.0 }, 15.0)
        assertEquals(300.0, (part.last().lon - 127.0) * kx, 15.0)
    }

    @Test fun outAndBackIsTold() {
        assertTrue(CourseRoute(line(0.0 to 0.0, 0.0 to 1000.0, 0.0 to 0.0)).isOutAndBack())
        assertFalse(CourseRoute(line(0.0 to 0.0, 0.0 to 500.0, 500.0 to 500.0, 500.0 to 0.0, 0.0 to 0.0)).isOutAndBack())
    }
}
