package com.mhmh2.bujirun.tracking

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class TrackFilterTest {
    private val filter = TrackFilter()
    private fun at(timeMs: Long, northM: Double, accuracy: Float = 5f) =
        LocationSample(timeMs, 37.5 + northM / 111_195.0, 127.0, accuracy)

    @Test fun haversineMatchesKnownDistance() {
        // 0.01 degrees of latitude is ~1112 m everywhere.
        val d = distanceMeters(LocationSample(0, 37.50, 127.0, 5f), LocationSample(0, 37.51, 127.0, 5f))
        assertEquals(1112.0, d, 1.0)
    }

    @Test fun rejectsImpreciseFix() {
        assertFalse(filter.accept(null, at(0, 0.0, accuracy = 40f)))
    }

    @Test fun acceptsRunningSpeed() {
        assertTrue(filter.accept(at(0, 0.0), at(1000, 4.0)))
    }

    @Test fun rejectsTeleportJump() {
        // 50 m in one second is 180 km/h.
        assertFalse(filter.accept(at(0, 0.0), at(1000, 50.0)))
    }

    @Test fun acceptsJumpAfterLongGap() {
        // A 200 m jump after 40 s without signal (tunnel) is plausible and must not get stuck.
        assertTrue(filter.accept(at(0, 0.0), at(40_000, 200.0)))
    }
}
