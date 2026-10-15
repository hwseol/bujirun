package com.mhmh2.bujirun.course

/**
 * Editing a drawn course point by point: the points the user placed and the line between each
 * pair ([segments][i] runs from point i to point i+1). Pure; the walking routes are fetched by
 * the caller.
 */
data class DrawnCourse(val points: List<GeoPoint>, val segments: List<List<GeoPoint>>, val names: List<String?>) {
    init { require(names.size == points.size && segments.size == (points.size - 1).coerceAtLeast(0)) }

    /** The whole line, the segments joined. */
    val line: List<GeoPoint> get() =
        if (segments.isEmpty()) points else segments.flatMapIndexed { i, s -> if (i == 0) s else s.drop(1) }

    /** How far along the line each point is (m). */
    fun pointDistances(): List<Double> {
        var d = 0.0
        return points.indices.map { i -> d.also { if (i < segments.size) d += CourseGeometry.distanceM(segments[i]) } }
    }

    /**
     * Without point [i]. Its neighbours are joined by a straight line for now - [rejoin] says
     * which segment that is, for the caller to replace with a walking route.
     */
    fun remove(i: Int): Pair<DrawnCourse, Int?> {
        require(i in points.indices)
        val pts = points.toMutableList().apply { removeAt(i) }
        val nms = names.toMutableList().apply { removeAt(i) }
        val segs = segments.toMutableList()
        var rejoin: Int? = null
        when {
            segs.isEmpty() -> {}
            i == 0 -> segs.removeAt(0)
            i == points.lastIndex -> segs.removeAt(segs.lastIndex)
            else -> {
                segs.removeAt(i)
                segs[i - 1] = listOf(points[i - 1], points[i + 1])
                rejoin = i - 1
            }
        }
        return DrawnCourse(pts, segs, nms) to rejoin
    }

    /** With segment [k] replaced (a walking route instead of the straight line). */
    fun withSegment(k: Int, route: List<GeoPoint>) =
        copy(segments = segments.toMutableList().also { it[k] = route })

    fun withName(i: Int, name: String?) = copy(names = names.toMutableList().also { it[i] = name })

    companion object {
        val EMPTY = DrawnCourse(emptyList(), emptyList(), emptyList())
    }
}
