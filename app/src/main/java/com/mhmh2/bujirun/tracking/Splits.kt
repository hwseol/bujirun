package com.mhmh2.bujirun.tracking

/** One km (or the final partial stretch) of a workout. */
data class Split(val index: Int, val distanceM: Double, val durationMs: Long) {
    val paceSecPerKm: Double get() = durationMs / 1000.0 / (distanceM / 1000.0)
}

object Splits {
    /**
     * Per-km splits. The moment each km mark was crossed is interpolated between the two track
     * points around it, so a split isn't off by however far apart the fixes happened to be.
     * The final partial km is included if it's at least [minLastM].
     */
    fun compute(
        points: List<TrackPoint>,
        totalDistanceM: Double,
        totalActiveMs: Long,
        unitM: Double = 1000.0,
        minLastM: Double = 100.0,
    ): List<Split> {
        if (points.size < 2) return emptyList()
        val result = mutableListOf<Split>()
        var prevMs = 0L
        var k = 1
        var searchFrom = 1
        while (k * unitM <= totalDistanceM) {
            val mark = k * unitM
            while (searchFrom < points.size && points[searchFrom].distanceM < mark) searchFrom++
            if (searchFrom >= points.size) break
            val a = points[searchFrom - 1]
            val b = points[searchFrom]
            val span = b.distanceM - a.distanceM
            val f = if (span > 0) (mark - a.distanceM) / span else 1.0
            val atMs = a.activeMs + ((b.activeMs - a.activeMs) * f).toLong()
            result += Split(k, unitM, atMs - prevMs)
            prevMs = atMs
            k++
        }
        val rest = totalDistanceM - (k - 1) * unitM
        if (rest >= minLastM && totalActiveMs > prevMs) result += Split(k, rest, totalActiveMs - prevMs)
        return result
    }
}
