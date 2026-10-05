package com.mhmh2.bujirun.course

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class CourseTest {
    private fun gpx(body: String) = """<?xml version="1.0" encoding="UTF-8"?>
        <gpx version="1.1" creator="StravaGPX" xmlns="http://www.topografix.com/GPX/1/1">$body</gpx>""".byteInputStream()

    @Test fun parsesStravaStyleTrack() {
        val parsed = CourseGpx.parse(gpx("""
            <metadata><name>Morning Run</name><time>2026-10-01T06:00:00Z</time></metadata>
            <trk><name>Han river 5k</name><type>running</type>
              <trkseg>
                <trkpt lat="37.5115" lon="126.9960"><ele>12.4</ele><time>2026-10-01T06:00:00Z</time></trkpt>
                <trkpt lat="37.5120" lon="126.9970"><ele>13.0</ele></trkpt>
              </trkseg>
              <trkseg>
                <trkpt lat="37.5125" lon="126.9980"></trkpt>
              </trkseg>
            </trk>"""))
        assertEquals("Morning Run", parsed.name)
        assertEquals(3, parsed.points.size)           // segments joined
        assertEquals(12.4, parsed.points[0].altitudeM!!, 0.001)
        assertNull(parsed.points[2].altitudeM)
    }

    @Test fun fallsBackToRoutePointsThenWaypoints() {
        val rte = CourseGpx.parse(gpx("""<rte><name>Planned</name>
            <rtept lat="37.50" lon="127.00"/><rtept lat="37.51" lon="127.01"/></rte>"""))
        assertEquals("Planned", rte.name)
        assertEquals(2, rte.points.size)
        val wpt = CourseGpx.parse(gpx("""<wpt lat="37.50" lon="127.00"><name>A</name></wpt>
            <wpt lat="37.51" lon="127.01"><name>B</name></wpt>"""))
        assertEquals(2, wpt.points.size)
        assertNull(wpt.name) // point names don't name the course
    }

    @Test(expected = IllegalArgumentException::class)
    fun rejectsFilesWithoutALine() {
        CourseGpx.parse(gpx("<metadata><name>empty</name></metadata>"))
    }

    @Test fun roundTripsThroughOwnWriter() {
        val pts = listOf(GeoPoint(37.5, 127.0, 10.0), GeoPoint(37.501, 127.001, null), GeoPoint(37.502, 127.0005, 12.0))
        val back = CourseGpx.parse(CourseGpx.write("내 코스 & <테스트>", pts).byteInputStream())
        assertEquals("내 코스 & <테스트>", back.name)
        assertEquals(pts.map { it.lat to it.lon }, back.points.map { it.lat to it.lon })
    }

    @Test fun simplifyKeepsShapeAndDropsRedundantPoints() {
        // A straight 1 km line sampled every metre, then a right-angle turn and 500 m more.
        val line = (0..1000).map { GeoPoint(37.5 + it / 111_195.0, 127.0) }
        val turn = (1..500).map { GeoPoint(37.5 + 1000 / 111_195.0, 127.0 + it / (111_195.0 * 0.7934)) }
        val pts = line + turn
        val simple = CourseGeometry.simplify(pts)
        assertTrue("kept ${simple.size}", simple.size <= 5)
        assertEquals(CourseGeometry.distanceM(pts), CourseGeometry.distanceM(simple), 2.0)
        assertEquals(pts.first(), simple.first()); assertEquals(pts.last(), simple.last())
    }

    @Test fun loopAndAscent() {
        val loop = listOf(GeoPoint(37.5, 127.0, 10.0), GeoPoint(37.505, 127.0, 25.0), GeoPoint(37.5003, 127.0, 11.0))
        assertTrue(CourseGeometry.isLoop(loop))
        assertFalse(CourseGeometry.isLoop(loop.take(2)))
        assertEquals(15.0, CourseGeometry.ascentM(loop), 0.001)
    }
}
