package com.mhmh2.bujirun.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class GpxTest {
    private val workout = WorkoutEntity(
        id = 1, startedAt = 1_791_100_000_000, endedAt = 1_791_100_600_000, status = WorkoutEntity.DONE,
        goalType = "free", goalValue = 0.0, durationMs = 600_000, distanceM = 1800.0, runMs = 500_000, walkMs = 100_000,
    )

    private fun pt(i: Int, segment: Int, alt: Double? = 12.34) = TrackPointEntity(
        workoutId = 1, epochMs = 1_791_100_000_000 + i * 1000L + 456, lat = 37.5 + i * 1e-5, lon = 127.0,
        altitudeM = alt, accuracyM = 5f, segment = segment, activity = "RUN", distanceM = i * 3.0, activeMs = i * 1000L,
    )

    @Test fun structureAndContent() {
        val gpx = Gpx.write("오늘 아침 러닝 <테스트> & 'x'", workout, listOf(pt(0, 0), pt(1, 0), pt(2, 1, alt = null)))
        assertTrue(gpx.startsWith("<?xml version=\"1.0\" encoding=\"UTF-8\"?>"))
        assertTrue(gpx.contains("xmlns=\"http://www.topografix.com/GPX/1/1\""))
        assertTrue(gpx.contains("<type>running</type>"))
        // One track segment per pause segment.
        assertEquals(2, Regex("<trkseg>").findAll(gpx).count())
        assertEquals(3, Regex("<trkpt ").findAll(gpx).count())
        // Coordinates with 7 decimals, elevation, whole-second UTC times.
        assertTrue(gpx.contains("<trkpt lat=\"37.5000100\" lon=\"127.0000000\"><ele>12.3</ele><time>"))
        assertTrue(Regex("<time>\\d{4}-\\d\\d-\\d\\dT\\d\\d:\\d\\d:\\d\\dZ</time>").containsMatchIn(gpx))
        assertTrue(!gpx.contains(".456"))
        // Title is XML-escaped.
        assertTrue(gpx.contains("오늘 아침 러닝 &lt;테스트&gt; &amp; &apos;x&apos;"))
        assertTrue(gpx.trimEnd().endsWith("</gpx>"))
    }

    @Test fun walkingType() {
        val gpx = Gpx.write("걷기", workout.copy(runMs = 0, walkMs = 600_000), listOf(pt(0, 0)))
        assertTrue(gpx.contains("<type>walking</type>"))
    }
}
