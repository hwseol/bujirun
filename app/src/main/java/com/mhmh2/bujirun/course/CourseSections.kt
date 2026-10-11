package com.mhmh2.bujirun.course

/** A stretch of a course from one turn to the next; [turn]: the one it ends with (null: the end). */
data class CourseSection(val fromM: Double, val toM: Double, val turn: Turn?) {
    val lengthM: Double get() = toM - fromM
}

/** The course cut at its turns: what to follow, piece by piece, for the course's own page. */
fun CourseRoute.sections(): List<CourseSection> {
    val at = turns.filter { it.atM > 1 && it.atM < lengthM - 1 }
    val bounds = listOf(0.0) + at.map { it.atM } + lengthM
    return bounds.zipWithNext().mapIndexed { i, (from, to) -> CourseSection(from, to, at.getOrNull(i)) }
}

/** The line from [fromM] to [toM] along the course. */
fun CourseRoute.slice(fromM: Double, toM: Double): List<GeoPoint> =
    listOf(pointAt(fromM)) + points.filterIndexed { i, _ -> cum[i] > fromM && cum[i] < toM } + pointAt(toM)

/**
 * Whether the course comes back the way it went (an out-and-back): the point some way out and
 * the point the same way from the end are the same place, most of the way.
 */
fun CourseRoute.isOutAndBack(): Boolean {
    if (lengthM < 400) return false
    val samples = (1..9).map { lengthM / 2 * it / 10 }
    val same = samples.count { d ->
        val a = pointAt(d)
        val b = pointAt(lengthM - d)
        com.mhmh2.bujirun.tracking.distanceMeters(a.lat, a.lon, b.lat, b.lon) < 40
    }
    return same >= 7
}
