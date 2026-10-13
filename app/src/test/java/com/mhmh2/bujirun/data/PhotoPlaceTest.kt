package com.mhmh2.bujirun.data

import com.mhmh2.bujirun.tracking.LocationSample
import com.mhmh2.bujirun.tracking.TrackPoint
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class PhotoPlaceTest {
    private val t0 = 1_800_000_000_000L
    private val track = (0..10).map { i ->
        TrackPoint(LocationSample(i * 10_000L, 37.5 + i * 0.001, 127.0, 5f, epochMs = t0 + i * 10_000L), 0, distanceM = i * 100.0)
    }

    @Test fun placedBetweenTheFixesAround() {
        val p = PhotoPlaces.at(track, t0 + 25_000)!!
        assertEquals(37.5025, p.lat, 1e-9)
        assertEquals(250.0, p.distanceM, 1e-6)
    }

    @Test fun justBeforeOrAfterIsTheStartOrFinish() {
        assertEquals(0.0, PhotoPlaces.at(track, t0 - 60_000)!!.distanceM, 0.0)
        assertEquals(1000.0, PhotoPlaces.at(track, t0 + 100_000 + 120_000)!!.distanceM, 0.0)
        assertNull(PhotoPlaces.at(track, t0 + 100_000 + 10 * 60_000))
        assertNull(PhotoPlaces.at(emptyList(), t0))
    }
}
