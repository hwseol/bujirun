package com.mhmh2.bujirun.data

import com.mhmh2.bujirun.tracking.TrackPoint

/** Where on the route a photo was taken: found from when, so no photo location is needed. */
data class PhotoPlace(val lat: Double, val lon: Double, val distanceM: Double)

object PhotoPlaces {
    /** Photos this long before the start or after the end still count (the start line, the finish). */
    const val MARGIN_MS = 3 * 60_000L

    /**
     * The point of the track at [takenAt] (wall clock), between the fixes either side of it;
     * the first/last point just outside the workout; null if it's not near the workout at all.
     */
    fun at(points: List<TrackPoint>, takenAt: Long): PhotoPlace? {
        val timed = points.filter { it.sample.epochMs > 0 }
        if (timed.isEmpty()) return null
        val first = timed.first()
        val last = timed.last()
        if (takenAt < first.sample.epochMs - MARGIN_MS || takenAt > last.sample.epochMs + MARGIN_MS) return null
        if (takenAt <= first.sample.epochMs) return first.place()
        if (takenAt >= last.sample.epochMs) return last.place()
        val i = timed.indexOfFirst { it.sample.epochMs >= takenAt }
        val a = timed[i - 1]
        val b = timed[i]
        val span = (b.sample.epochMs - a.sample.epochMs).toDouble()
        val f = if (span <= 0) 0.0 else (takenAt - a.sample.epochMs) / span
        return PhotoPlace(
            a.sample.lat + (b.sample.lat - a.sample.lat) * f,
            a.sample.lon + (b.sample.lon - a.sample.lon) * f,
            a.distanceM + (b.distanceM - a.distanceM) * f,
        )
    }

    private fun TrackPoint.place() = PhotoPlace(sample.lat, sample.lon, distanceM)
}
