package com.mhmh2.bujirun.course

import kotlin.math.cos
import kotlin.math.hypot

/**
 * Makes a course start where the runner presses start (pure; the walking route there comes from
 * the caller). A loop is entered at its point nearest the runner and run round to that point
 * again; a one-way course is entered at its start. If that point is more than [JOIN_M] away, the
 * way there - and for a loop the way back - becomes part of the course, so the voice guides from
 * the first step and the distance counts it.
 */
object CourseJoin {
    const val JOIN_M = 30.0

    /** The point of [points] the runner at [here] joins at: index and distance (m). */
    fun joinPoint(points: List<GeoPoint>, here: GeoPoint): Pair<Int, Double> {
        if (!CourseGeometry.isLoop(points)) return 0 to distance(here, points[0])
        val i = points.indices.minBy { distance(here, points[it]) }
        return i to distance(here, points[i])
    }

    /** True when the runner is off the course's entry and needs a way there. */
    fun needsApproach(points: List<GeoPoint>, here: GeoPoint) = joinPoint(points, here).second > JOIN_M

    /**
     * The course as run from [here]. [approach] is the walking route from [here] to the join point
     * (null: none could be found, so a straight line is used - better than no guidance).
     */
    fun join(points: List<GeoPoint>, here: GeoPoint, approach: List<GeoPoint>?): List<GeoPoint> {
        val (i, d) = joinPoint(points, here)
        val loop = CourseGeometry.isLoop(points)
        // Rotate a loop to begin at i: from i to the end, then the start (minus its duplicate
        // of the end) round to i again.
        val course = if (loop && i > 0) points.subList(i, points.size) + points.subList(1, i + 1) else points
        if (d <= JOIN_M) return course
        val way = fromHere(here, approach) ?: listOf(here, course.first())
        return if (loop) way + course.drop(1) + way.reversed().drop(1) else way + course.drop(1)
    }

    /**
     * Where a lost runner at [here] rejoins [route], on the part not yet run (from [progressM]
     * on): distance along and how far away. Not simply the closest point: on a loop the closest
     * can be the last stretch home (the runner is near the start, or went round the other way),
     * which would end the course there. So each pass of the course near the runner is weighed
     * by how far it is plus a part of the course it would skip, and the one that keeps the most
     * course for a fair walk wins.
     */
    fun rejoinPoint(route: CourseRoute, progressM: Double, here: GeoPoint): Pair<Double, Double> {
        val from = progressM.coerceIn(0.0, route.lengthM)
        val step = 5.0
        val n = ((route.lengthM - from) / step).toInt()
        if (n < 1) return from to distance(here, route.pointAt(from))
        val along = (0..n).map { from + it * step } + route.lengthM
        val d = along.map { distance(here, route.pointAt(it)) }
        // Each pass: a local minimum of the distance (the ends count, with their one neighbour).
        val passes = d.indices.filter { k ->
            (k == 0 || d[k] <= d[k - 1]) && (k == d.lastIndex || d[k] <= d[k + 1])
        }
        val best = passes.minBy { k -> d[k] + SKIP_WEIGHT * (along[k] - from) }
        // Fine-tune within the pass.
        val at = route.locate(here, (along[best] - step).coerceAtLeast(from), along[best] + step).first.coerceAtLeast(from)
        return at to distance(here, route.pointAt(at))
    }

    /** Metres of walking worth one metre of course kept: skip 100 m of course to save 20 m of walk. */
    private const val SKIP_WEIGHT = 0.2

    /**
     * The rest of the course as run from [here] after getting lost: [approach] (the walking route
     * to the rejoin point; null - a straight line) and then the course from there to its end.
     */
    fun rejoin(route: CourseRoute, progressM: Double, here: GeoPoint, approach: List<GeoPoint>?): List<GeoPoint> =
        restFrom(route, rejoinPoint(route, progressM, here).first, here, approach)

    /** What "경로 다시 찾기" goes on with: [route] (the course, or the loop turned round) from [at], [distance] away. */
    data class Rejoin(val route: CourseRoute, val at: Double, val distance: Double)

    /**
     * Where to pick the course up again for a runner at [here] who has run [ranM] so far, the
     * navigator having them at [progressM]. Someone who set off round a loop the other way is
     * matched onto its last stretch home, far beyond what they've run - rejoining "ahead" from
     * there would just send them to the finish. Instead the loop is turned round and run on from
     * where they are, so the whole course is still run. Otherwise: the nearest part not yet run,
     * never counting more of the course as done than the runner has covered.
     */
    fun rerouteFrom(route: CourseRoute, progressM: Double, ranM: Double, here: GeoPoint): Rejoin {
        val done = minOf(progressM, ranM)
        if (route.isLoop) {
            // On the loop's first stretch the other way round, and nowhere near the part they
            // should be on going the right way: they're running it backwards. An out-and-back
            // is also closed, and its way home runs over the way out - someone on the way out
            // is on the course ahead too, and turning it round would skip the turnaround.
            val back = CourseRoute(route.points.reversed())
            val (at, d) = back.locate(here, 0.0, minOf(back.lengthM, ranM + 100))
            val ahead = route.locate(here, done, minOf(route.lengthM, maxOf(done, ranM) + 300)).second
            if (d <= 2 * JOIN_M && ahead > 2 * JOIN_M && d < distance(here, route.pointAt(done)) / 2) return Rejoin(back, at, d)
        }
        val at = aheadTarget(route, done, here)
        return Rejoin(route, at, distance(here, route.pointAt(at)))
    }

    /**
     * Where a runner who left the course picks it up again, as a car navigation would: never
     * back to where they left it, but ahead - the next big turn (or the turn for home, or the
     * finish) past where they've got to alongside the course. From there the course carries on
     * as planned. Their place alongside is the nearest point of the next stretch; a long
     * straight with no turn for a while rejoins [REJOIN_AHEAD_M] on.
     */
    fun aheadTarget(route: CourseRoute, done: Double, here: GeoPoint): Double {
        val from = done.coerceIn(0.0, route.lengthM)
        val alongside = firstPass(route, here, from, minOf(route.lengthM, from + 1_200))
        val next = CourseOutline.keyPoints(route).map { it.atM }.firstOrNull { it > alongside + 40 }
        return when {
            next != null && next <= alongside + MAX_TO_KEY_POINT_M -> next
            else -> minOf(route.lengthM, alongside + REJOIN_AHEAD_M)
        }
    }

    /**
     * Where [here] is alongside [route] between [from] and [to]: the first pass of the course
     * near them, not simply the nearest. On an out-and-back the way home runs a few metres from
     * the way out; the nearest point can be on the way home, which would skip the turnaround.
     */
    private fun firstPass(route: CourseRoute, here: GeoPoint, from: Double, to: Double): Double {
        val step = 5.0
        val n = ((to - from) / step).toInt()
        if (n < 1) return route.locate(here, from, to).first
        val along = (0..n).map { from + it * step } + to
        val d = along.map { distance(here, route.pointAt(it)) }
        val nearest = d.min()
        val k = d.indices.first { k ->
            d[k] <= nearest + PASS_SLACK_M && (k == d.lastIndex || d[k] <= d[k + 1]) && (k == 0 || d[k] <= d[k - 1])
        }
        return route.locate(here, (along[k] - step).coerceAtLeast(from), minOf(to, along[k] + step)).first
    }

    private const val PASS_SLACK_M = 40.0
    private const val MAX_TO_KEY_POINT_M = 800.0
    private const val REJOIN_AHEAD_M = 300.0

    /** [route] from [at] to its end, reached from [here] by [approach] (null: a straight line). */
    fun restFrom(route: CourseRoute, at: Double, here: GeoPoint, approach: List<GeoPoint>?): List<GeoPoint> {
        val rest = listOf(route.pointAt(at)) + route.points.filterIndexed { i, _ -> route.cum[i] > at + 1 }
        val way = fromHere(here, approach) ?: listOf(here, rest.first())
        val run = way + rest.drop(1)
        // Already at the very end: still a valid (tiny) line.
        return if (run.size >= 2) run else listOf(here, route.points.last())
    }

    // A walking route starts on the nearest path, which can be well away from a runner on a lawn
    // or in a plaza: the line starts where they are, so guidance begins at once.
    private fun fromHere(here: GeoPoint, approach: List<GeoPoint>?): List<GeoPoint>? {
        val way = approach?.takeIf { it.size >= 2 } ?: return null
        return if (distance(here, way.first()) > 5) listOf(here) + way else way
    }

    private fun distance(a: GeoPoint, b: GeoPoint): Double {
        val kx = 111_195.0 * cos(Math.toRadians((a.lat + b.lat) / 2))
        return hypot((b.lon - a.lon) * kx, (b.lat - a.lat) * 111_195.0)
    }
}
