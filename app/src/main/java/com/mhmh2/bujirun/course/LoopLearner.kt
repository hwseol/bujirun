package com.mhmh2.bujirun.course

import kotlin.math.abs
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.hypot

/**
 * Notices the runner going round a loop of their own: fed positions as they run, it answers
 * with the loop once they come back past a spot they passed before, heading the same way (so
 * an out-and-back doesn't count) and at least [minLoopM] later. Pure: positions come from the
 * caller. The loop then becomes a course to lap (CourseNavigator with openLaps).
 */
class LoopLearner(
    private val nearM: Double = 20.0,
    private val minLoopM: Double = 300.0,
    private val maxLoopM: Double = 5_000.0,
) {
    private val pts = mutableListOf<GeoPoint>()
    private val cum = mutableListOf<Double>()
    private val times = mutableListOf<Long>()

    /** A loop the runner closed: its line (first point = last), and how long that lap took. */
    data class Loop(val points: List<GeoPoint>, val lapMs: Long)

    /** Forget the way so far (e.g. after a new course was set): only what comes next counts. */
    fun reset() { pts.clear(); cum.clear(); times.clear() }

    /** The loop just closed at [p] (at [timeMs]), or null. */
    fun add(p: GeoPoint, timeMs: Long): Loop? {
        val last = pts.lastOrNull()
        if (last != null && distance(last, p) < 5) return null
        cum += (cum.lastOrNull() ?: 0.0) + (last?.let { distance(it, p) } ?: 0.0)
        pts += p
        times += timeMs
        val n = pts.size - 1
        val here = cum[n]
        if (here < minLoopM) return null
        val heading = headingBack(n) ?: return null
        // Back over the way run, from a loop's length ago, for the latest spot passed close by
        // heading the same way; then the closest point of that pass.
        var i = cum.indexOfLast { it <= here - minLoopM }
        while (i >= 0 && here - cum[i] <= maxLoopM) {
            if (distance(pts[i], p) < nearM && headingAhead(i)?.let { similar(it, heading) } == true) {
                var best = i
                for (k in maxOf(0, i - 6)..minOf(n, i + 6)) {
                    if (here - cum[k] >= minLoopM && distance(pts[k], p) < distance(pts[best], p)) best = k
                }
                val line = pts.subList(best, n + 1).toList().let { it.dropLast(1) + it.first() }
                return Loop(line, timeMs - times[best])
            }
            i--
        }
        return null
    }

    // Direction over the last ~20 m into point n, and over the ~20 m after point i.
    private fun headingBack(n: Int): Double? {
        val k = (n downTo 0).firstOrNull { cum[n] - cum[it] >= 20 } ?: return null
        return bearing(pts[k], pts[n])
    }

    private fun headingAhead(i: Int): Double? {
        val k = (i until pts.size).firstOrNull { cum[it] - cum[i] >= 20 } ?: return null
        return bearing(pts[i], pts[k])
    }

    private fun similar(a: Double, b: Double): Boolean {
        var d = abs(a - b) % 360
        if (d > 180) d = 360 - d
        return d < 50
    }

    private fun bearing(a: GeoPoint, b: GeoPoint): Double {
        val kx = 111_195.0 * cos(Math.toRadians((a.lat + b.lat) / 2))
        return Math.toDegrees(atan2((b.lon - a.lon) * kx, (b.lat - a.lat) * 111_195.0))
    }

    private fun distance(a: GeoPoint, b: GeoPoint): Double {
        val kx = 111_195.0 * cos(Math.toRadians((a.lat + b.lat) / 2))
        return hypot((b.lon - a.lon) * kx, (b.lat - a.lat) * 111_195.0)
    }
}
