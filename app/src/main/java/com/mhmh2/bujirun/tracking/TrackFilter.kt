package com.mhmh2.bujirun.tracking

/**
 * Drops GPS fixes that would corrupt distance: ones the phone itself reports as imprecise, and
 * ones that "jump" faster than a person can move (typical between tall buildings).
 */
class TrackFilter(
    private val maxAccuracyM: Float = 25f,
    private val maxSpeedMps: Double = 12.0,
    // After a long gap (tunnel, underpass) the next fix is accepted even if it implies a fast
    // jump - otherwise one bad fix before the gap would make every later fix look too far away
    // and the track would never recover.
    private val gapResetMs: Long = 30_000,
) {
    fun isPrecise(sample: LocationSample) = sample.accuracyM <= maxAccuracyM

    fun accept(previous: LocationSample?, sample: LocationSample): Boolean {
        if (!isPrecise(sample)) return false
        if (previous == null) return true
        val dtMs = sample.timeMs - previous.timeMs
        if (dtMs <= 0) return false
        if (dtMs >= gapResetMs) return true
        val speedMps = distanceMeters(previous, sample) / (dtMs / 1000.0)
        return speedMps <= maxSpeedMps
    }
}
