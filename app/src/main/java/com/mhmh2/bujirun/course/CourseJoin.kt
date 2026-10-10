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
        val way = approach?.takeIf { it.size >= 2 } ?: listOf(here, course.first())
        return if (loop) way + course.drop(1) + way.reversed().drop(1) else way + course.drop(1)
    }

    /**
     * Where a lost runner at [here] rejoins [route]: the closest point on the part not yet run
     * (from [progressM] on) - distance along and how far away.
     */
    fun rejoinPoint(route: CourseRoute, progressM: Double, here: GeoPoint): Pair<Double, Double> {
        // locate() takes whole segments that reach past progressM, so clamp onto the part ahead.
        val at = maxOf(route.locate(here, progressM, route.lengthM).first, progressM.coerceAtMost(route.lengthM))
        return at to distance(here, route.pointAt(at))
    }

    /**
     * The rest of the course as run from [here] after getting lost: [approach] (the walking route
     * to the rejoin point; null - a straight line) and then the course from there to its end.
     */
    fun rejoin(route: CourseRoute, progressM: Double, here: GeoPoint, approach: List<GeoPoint>?): List<GeoPoint> {
        val (at, _) = rejoinPoint(route, progressM, here)
        val rest = listOf(route.pointAt(at)) + route.points.filterIndexed { i, _ -> route.cum[i] > at + 1 }
        val way = approach?.takeIf { it.size >= 2 } ?: listOf(here, rest.first())
        val run = way + rest.drop(1)
        // Already at the very end: still a valid (tiny) line.
        return if (run.size >= 2) run else listOf(here, route.points.last())
    }

    private fun distance(a: GeoPoint, b: GeoPoint): Double {
        val kx = 111_195.0 * cos(Math.toRadians((a.lat + b.lat) / 2))
        return hypot((b.lon - a.lon) * kx, (b.lat - a.lat) * 111_195.0)
    }
}
