package com.mhmh2.bujirun.tracking

import kotlin.math.asin
import kotlin.math.cos
import kotlin.math.sin
import kotlin.math.sqrt

/**
 * One GPS fix, stripped down to what the tracker needs. Kept free of android.location so the
 * tracking logic can be unit-tested on the JVM.
 *
 * [timeMs] is on the elapsed-realtime clock (monotonic, keeps counting while the screen is off),
 * the same clock the service passes into [WorkoutTracker] as "now".
 */
data class LocationSample(
    val timeMs: Long,
    val lat: Double,
    val lon: Double,
    val accuracyM: Float,
    val altitudeM: Double? = null,
    // Speed as reported by the GPS chip itself (Doppler), when the phone provides it.
    val speedMps: Double? = null,
)

/**
 * An accepted fix, which run segment it belongs to (a new segment starts after each pause), and
 * whether the runner was walking or running there.
 */
data class TrackPoint(val sample: LocationSample, val segment: Int, val activity: Activity = Activity.UNKNOWN)

private const val EARTH_RADIUS_M = 6_371_000.0

/** Great-circle (haversine) distance - accurate to well under a metre at running distances. */
fun distanceMeters(a: LocationSample, b: LocationSample): Double {
    val dLat = Math.toRadians(b.lat - a.lat)
    val dLon = Math.toRadians(b.lon - a.lon)
    val h = sin(dLat / 2).let { it * it } +
        cos(Math.toRadians(a.lat)) * cos(Math.toRadians(b.lat)) * sin(dLon / 2).let { it * it }
    return 2 * EARTH_RADIUS_M * asin(sqrt(h))
}
