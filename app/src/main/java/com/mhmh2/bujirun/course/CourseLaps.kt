package com.mhmh2.bujirun.course

import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.hypot

/**
 * Laps of a course that goes round the same ring several times (공원 뺑뺑이): found from the line
 * itself, since saved courses don't record how they were made. [startsM]: distance along the
 * course where each lap begins, then where the last one ends (so laps = size - 1).
 */
data class Laps(val startsM: List<Double>) {
    val count: Int get() = startsM.size - 1
    val lapM: Double get() = (startsM.last() - startsM.first()) / count

    /** Laps completed at [progressM] along the course. */
    fun done(progressM: Double): Int = startsM.drop(1).count { progressM >= it - 5 }
}

object CourseLaps {
    private const val STEP = 10.0
    private const val NEAR_M = 15.0
    private const val MIN_LAP_M = 150.0

    /**
     * The laps of [route], or null if it isn't run round more than once. A lap is where the line
     * comes back past a point heading the same way (an out-and-back passes again too, but facing
     * the other way, and the way to a park and back the same).
     */
    fun detect(route: CourseRoute): Laps? {
        val n = (route.lengthM / STEP).toInt()
        if (n < 2 * (MIN_LAP_M / STEP)) return null
        val pts = (0..n).map { route.pointAt(it * STEP) }
        val heading = DoubleArray(n + 1) { route.headingAt(it * STEP) }
        val incoming = DoubleArray(n + 1) { route.headingAt(maxOf(0.0, it * STEP - 15)) }
        val minGap = (MIN_LAP_M / STEP).toInt()
        fun close(a: Double, b: Double): Boolean {
            var d = abs(a - b) % 360
            if (d > 180) d = 360 - d
            return d < 45
        }
        // Leaving the same way, or arriving the same way (the last lap's end leaves for home).
        fun sameWay(i: Int, j: Int) = close(heading[i], heading[j]) || close(incoming[i], incoming[j])
        for (i in 0..minOf(n - minGap, 400)) {
            val j = (i + minGap..n).firstOrNull { distance(pts[i], pts[it]) < NEAR_M && sameWay(i, it) } ?: continue
            // The closest pass near there, then each following pass about a lap further on.
            val starts = mutableListOf(i * STEP)
            var guess = j
            var prev = i
            while (true) {
                val best = (maxOf(0, guess - 4)..minOf(n, guess + 4))
                    .filter { distance(pts[i], pts[it]) < NEAR_M + 5 && sameWay(prev, it) }
                    .minByOrNull { distance(pts[i], pts[it]) } ?: break
                if (best * STEP - starts.last() < MIN_LAP_M) break
                starts += best * STEP
                prev = best
                guess = best + (best - i) / (starts.size - 1)
            }
            // Two laps or more: the start of the ring is passed at least three times.
            return if (starts.size >= 3) Laps(starts) else null
        }
        return null
    }

    private fun distance(a: GeoPoint, b: GeoPoint): Double {
        val kx = 111_195.0 * cos(Math.toRadians((a.lat + b.lat) / 2))
        return hypot((b.lon - a.lon) * kx, (b.lat - a.lat) * 111_195.0)
    }
}
