package com.mhmh2.bujirun.course

import com.mhmh2.bujirun.tr

import kotlin.math.abs
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.hypot

/**
 * A course in a few big strokes, for its page: which way it sets off, the few real changes of
 * direction, where it turns back, where it ends. Every small kink (a crossing, a bend in the
 * path) is left to the voice guidance on the run; this is for seeing the whole at a glance.
 */
object CourseOutline {
    enum class Kind { START, TURN, U_TURN, FINISH }

    /**
     * One stroke: at [atM] along the course, [kind]; then off [heading] (a compass word, null at
     * the finish) for [legM]. [farthest]: the point of the course farthest from the start - where
     * a loop or an out-and-back turns for home.
     */
    data class Step(
        val atM: Double,
        val kind: Kind,
        val direction: TurnDirection?,
        val heading: String?,
        val legM: Double,
        val farthest: Boolean = false,
    )

    private const val SIMPLIFY_M = 40.0
    private const val MAX_STEPS = 7

    fun of(route: CourseRoute): List<Step> {
        val pts = route.points
        val lat0 = pts[0].lat
        val kx = M_PER_DEG * cos(Math.toRadians(lat0))
        val xy = pts.map { (it.lon - pts[0].lon) * kx to (it.lat - lat0) * M_PER_DEG }
        // The shape, then drop the corners with the shortest sides until only big ones are left.
        var keep = simplify(xy, SIMPLIFY_M)
        var minLeg = 150.0
        while (true) {
            keep = merge(keep, xy, route, minLeg)
            if (corners(keep, xy).size <= MAX_STEPS - 2 || minLeg > 3000) break
            minLeg *= 1.5
        }
        val turns = corners(keep, xy)
        val bounds = listOf(0) + turns.map { it.first } + (pts.size - 1)
        // Farthest from the start: the turn nearest to it carries "here it turns for home".
        val far = pts.indices.maxBy { hypot(xy[it].first, xy[it].second) }
        val farTurn = turns.minByOrNull { abs(route.cum[it.first] - route.cum[far]) }
            ?.takeIf { abs(route.cum[it.first] - route.cum[far]) < 300 }?.first
        return bounds.indices.map { k ->
            val i = bounds[k]
            val next = bounds.getOrNull(k + 1)
            val turn = turns.firstOrNull { it.first == i }
            Step(
                atM = route.cum[i],
                kind = when {
                    k == 0 -> Kind.START
                    next == null -> Kind.FINISH
                    turn != null && abs(turn.second) >= 150 -> Kind.U_TURN
                    else -> Kind.TURN
                },
                direction = turn?.let { classify(it.second) },
                heading = next?.let { compass(xy[i], xy[it]) },
                legM = next?.let { route.cum[it] - route.cum[i] } ?: 0.0,
                farthest = i == farTurn,
            )
        }
    }

    /** A point to mark on the map: a big change of direction, or where the course turns for
     * home ([turnaround]: a turn back the way it came, or the turn farthest from the start). */
    data class KeyPoint(val atM: Double, val turnaround: Boolean)

    /** The same strokes as [of], as points for the map (start and finish have their own pins). */
    fun keyPoints(route: CourseRoute): List<KeyPoint> =
        if (route.points.size < 3 || route.lengthM < 300) emptyList()
        else of(route).filter { it.kind == Kind.TURN || it.kind == Kind.U_TURN }
            .map { KeyPoint(it.atM, it.kind == Kind.U_TURN || it.farthest) }

    /** Douglas-Peucker: indices of the points that keep the shape within [tol] metres. */
    private fun simplify(xy: List<Pair<Double, Double>>, tol: Double): List<Int> {
        val keep = sortedSetOf(0, xy.size - 1)
        fun dp(a: Int, b: Int) {
            if (b <= a + 1) return
            var best = -1.0
            var at = -1
            for (i in a + 1 until b) {
                val d = segDist(xy[i], xy[a], xy[b])
                if (d > best) { best = d; at = i }
            }
            if (best > tol) { keep += at; dp(a, at); dp(at, b) }
        }
        dp(0, xy.size - 1)
        return keep.toList()
    }

    /** Removes inner corners whose shorter side is under [minLeg] (shortest first). */
    private fun merge(keep: List<Int>, xy: List<Pair<Double, Double>>, route: CourseRoute, minLeg: Double): List<Int> {
        val k = keep.toMutableList()
        while (k.size > 2) {
            var worst = -1
            var worstLeg = Double.MAX_VALUE
            for (j in 1 until k.size - 1) {
                val leg = minOf(route.cum[k[j]] - route.cum[k[j - 1]], route.cum[k[j + 1]] - route.cum[k[j]])
                // A turn back the way it came is kept unless it's tiny: that's where it turns for home.
                val uTurn = abs(angle(xy[k[j - 1]], xy[k[j]], xy[k[j + 1]])) >= 150
                val effective = if (uTurn) leg * 4 else leg
                if (effective < worstLeg) { worstLeg = effective; worst = j }
            }
            if (worstLeg >= minLeg) break
            k.removeAt(worst)
        }
        return k
    }

    /** Inner points where the way changes by 35 degrees or more, with the change (+ right). */
    private fun corners(keep: List<Int>, xy: List<Pair<Double, Double>>): List<Pair<Int, Double>> =
        (1 until keep.size - 1).mapNotNull { j ->
            val a = angle(xy[keep[j - 1]], xy[keep[j]], xy[keep[j + 1]])
            if (abs(a) >= 35) keep[j] to a else null
        }

    /** Change of heading at b going a -> b -> c, degrees, + to the right. */
    private fun angle(a: Pair<Double, Double>, b: Pair<Double, Double>, c: Pair<Double, Double>): Double {
        val h1 = atan2(b.first - a.first, b.second - a.second)
        val h2 = atan2(c.first - b.first, c.second - b.second)
        var d = Math.toDegrees(h2 - h1)
        while (d > 180) d -= 360
        while (d < -180) d += 360
        return d
    }

    private fun classify(change: Double): TurnDirection = when {
        abs(change) >= 150 -> TurnDirection.U_TURN
        change >= 120 -> TurnDirection.SHARP_RIGHT
        change >= 50 -> TurnDirection.RIGHT
        change > 0 -> TurnDirection.SLIGHT_RIGHT
        change <= -120 -> TurnDirection.SHARP_LEFT
        change <= -50 -> TurnDirection.LEFT
        else -> TurnDirection.SLIGHT_LEFT
    }

    /** "북동쪽": which way from a to b, in eight. */
    fun compass(a: Pair<Double, Double>, b: Pair<Double, Double>): String {
        val deg = (Math.toDegrees(atan2(b.first - a.first, b.second - a.second)) + 360) % 360
        val k = ((deg + 22.5) / 45).toInt() % 8
        return tr(listOf("북쪽", "북동쪽", "동쪽", "남동쪽", "남쪽", "남서쪽", "서쪽", "북서쪽")[k],
            listOf("north", "northeast", "east", "southeast", "south", "southwest", "west", "northwest")[k])
    }

    private fun segDist(p: Pair<Double, Double>, a: Pair<Double, Double>, b: Pair<Double, Double>): Double {
        val dx = b.first - a.first
        val dy = b.second - a.second
        val t = if (dx == 0.0 && dy == 0.0) 0.0
        else (((p.first - a.first) * dx + (p.second - a.second) * dy) / (dx * dx + dy * dy)).coerceIn(0.0, 1.0)
        return hypot(p.first - a.first - t * dx, p.second - a.second - t * dy)
    }

    private const val M_PER_DEG = 111_195.0
}
