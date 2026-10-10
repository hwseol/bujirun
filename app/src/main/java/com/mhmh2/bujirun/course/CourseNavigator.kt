package com.mhmh2.bujirun.course

import kotlin.math.abs
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.roundToInt
import kotlin.math.sqrt

enum class TurnDirection(val spoken: String, val arrow: String) {
    SLIGHT_LEFT("왼쪽 방향", "↖"), LEFT("왼쪽", "←"), SHARP_LEFT("크게 왼쪽", "↙"),
    SLIGHT_RIGHT("오른쪽 방향", "↗"), RIGHT("오른쪽", "→"), SHARP_RIGHT("크게 오른쪽", "↘"),
    U_TURN("유턴", "↶"),
}

data class Turn(val atM: Double, val direction: TurnDirection)

/** A stretch of the course that climbs: starts at [startM], [lengthM] long, [gainM] up. */
data class Climb(val startM: Double, val lengthM: Double, val gainM: Double)

/**
 * A course line with distance along it: lets us find "the point 340 m in", the heading there,
 * where it turns, and where it climbs. All geometry is on a local flat projection - at course
 * scale (a few km) the error is centimetres.
 */
class CourseRoute(val points: List<GeoPoint>) {
    init { require(points.size >= 2) }

    private val kx = M_PER_DEG * cos(Math.toRadians(points[0].lat))
    private val xs = DoubleArray(points.size) { (points[it].lon - points[0].lon) * kx }
    private val ys = DoubleArray(points.size) { (points[it].lat - points[0].lat) * M_PER_DEG }
    /** Distance along the course at each point. */
    val cum: DoubleArray = DoubleArray(points.size).also { c ->
        for (i in 1 until points.size) c[i] = c[i - 1] + hypot(xs[i] - xs[i - 1], ys[i] - ys[i - 1])
    }
    val lengthM: Double get() = cum.last()
    val isLoop: Boolean get() = CourseGeometry.isLoop(points)

    fun pointAt(d: Double): GeoPoint {
        val (x, y, alt) = xyAt(d)
        return GeoPoint(points[0].lat + y / M_PER_DEG, points[0].lon + x / kx, alt)
    }

    private fun xyAt(d: Double): Triple<Double, Double, Double?> {
        val dd = d.coerceIn(0.0, lengthM)
        var i = cum.binarySearch(dd).let { if (it >= 0) it else -it - 2 }.coerceIn(0, points.size - 2)
        while (i < points.size - 2 && cum[i + 1] < dd) i++
        val seg = cum[i + 1] - cum[i]
        val t = if (seg > 0) (dd - cum[i]) / seg else 0.0
        val a1 = points[i].altitudeM; val a2 = points[i + 1].altitudeM
        val alt = if (a1 != null && a2 != null) a1 + (a2 - a1) * t else a1 ?: a2
        return Triple(xs[i] + (xs[i + 1] - xs[i]) * t, ys[i] + (ys[i + 1] - ys[i]) * t, alt)
    }

    /** Direction of travel (degrees clockwise from north) at [d] along the course. */
    fun headingAt(d: Double): Double =
        if (d + 15 <= lengthM) bearing(d, d + 15) else bearing(maxOf(0.0, d - 15), d)

    /** Compass-style bearing (degrees, clockwise from north) of travel between two distances. */
    private fun bearing(from: Double, to: Double): Double {
        val (x1, y1) = xyAt(from); val (x2, y2) = xyAt(to)
        return Math.toDegrees(atan2(x2 - x1, y2 - y1))
    }

    /**
     * Turns along the course: sampled every 5 m, comparing the heading over the 15 m before and
     * after; a local peak of change above 35 degrees is a turn. Peaks closer than 25 m merge
     * (a zig-zag through a crossing is one instruction, not three). The first and last 20 m
     * never turn - that's just where the line begins and ends.
     */
    val turns: List<Turn> by lazy {
        val step = 5.0; val window = 15.0
        val samples = mutableListOf<Pair<Double, Double>>() // (distance, signed change)
        var d = 20.0
        while (d <= lengthM - 20) {
            samples += d to normalize(bearing(d, d + window) - bearing(d - window, d))
            d += step
        }
        val result = mutableListOf<Turn>()
        var i = 0
        while (i < samples.size) {
            if (abs(samples[i].second) < 35) { i++; continue }
            // Take the strongest sample of this run of large changes.
            var j = i; var best = i
            while (j < samples.size && abs(samples[j].second) >= 35 && samples[j].first - samples[i].first <= 25) {
                if (abs(samples[j].second) > abs(samples[best].second)) best = j
                j++
            }
            val (at, change) = samples[best]
            if (result.isEmpty() || at - result.last().atM > 25) result += Turn(at, classify(change))
            i = j
        }
        result
    }

    /** Stretches climbing at least 12 m within 500 m (needs altitude in the course data). */
    val climbs: List<Climb> by lazy {
        if (points.count { it.altitudeM != null } < points.size / 2) return@lazy emptyList()
        val result = mutableListOf<Climb>()
        var d = 0.0
        while (d < lengthM) {
            val startAlt = pointAt(d).altitudeM
            if (startAlt == null) { d += 25; continue }
            var bestGain = 0.0; var bestEnd = d
            var e = d + 25
            while (e <= minOf(d + 500, lengthM)) {
                val gain = (pointAt(e).altitudeM ?: startAlt) - startAlt
                if (gain > bestGain) { bestGain = gain; bestEnd = e }
                e += 25
            }
            if (bestGain >= 12) {
                // The window can begin on the flat before the rise; start where it actually rises.
                var s = d
                while (s + 25 < bestEnd && (pointAt(s + 25).altitudeM ?: startAlt) - startAlt < 1.5) s += 25
                // ...and measure the climb again from there, so its top isn't cut off.
                val base = pointAt(s).altitudeM ?: startAlt
                var gain = 0.0; var end = s
                var e2 = s + 25
                while (e2 <= minOf(s + 500, lengthM)) {
                    val g = (pointAt(e2).altitudeM ?: base) - base
                    if (g > gain) { gain = g; end = e2 }
                    e2 += 25
                }
                result += Climb(s, end - s, gain)
                d = end
            } else d += 25
        }
        result
    }

    /**
     * Where [p] is relative to the course: the closest point on it (as distance along), and how
     * far off it is. Only segments overlapping [fromM]..[toM] are considered, so on a course that
     * passes the same spot twice (out-and-back, figure-of-eight) we stay on the right pass.
     */
    fun locate(p: GeoPoint, fromM: Double = 0.0, toM: Double = lengthM): Pair<Double, Double> {
        val px = (p.lon - points[0].lon) * kx; val py = (p.lat - points[0].lat) * M_PER_DEG
        var bestDist = Double.MAX_VALUE; var bestAlong = fromM.coerceIn(0.0, lengthM)
        for (i in 0 until points.size - 1) {
            if (cum[i + 1] < fromM || cum[i] > toM) continue
            val bx = xs[i + 1] - xs[i]; val by = ys[i + 1] - ys[i]
            val len2 = bx * bx + by * by
            val t = if (len2 == 0.0) 0.0 else (((px - xs[i]) * bx + (py - ys[i]) * by) / len2).coerceIn(0.0, 1.0)
            val dist = hypot(px - (xs[i] + t * bx), py - (ys[i] + t * by))
            if (dist < bestDist) { bestDist = dist; bestAlong = cum[i] + t * (cum[i + 1] - cum[i]) }
        }
        return bestAlong to bestDist
    }

    private fun classify(change: Double): TurnDirection {
        val a = abs(change); val right = change > 0
        return when {
            a >= 150 -> TurnDirection.U_TURN
            a >= 110 -> if (right) TurnDirection.SHARP_RIGHT else TurnDirection.SHARP_LEFT
            a >= 55 -> if (right) TurnDirection.RIGHT else TurnDirection.LEFT
            else -> if (right) TurnDirection.SLIGHT_RIGHT else TurnDirection.SLIGHT_LEFT
        }
    }

    private fun normalize(deg: Double): Double { var a = deg % 360; if (a > 180) a -= 360; if (a < -180) a += 360; return a }
    private fun hypot(a: Double, b: Double) = sqrt(a * a + b * b)

    private companion object { const val M_PER_DEG = 111_195.0 }
}

/** What the screen shows about the course right now. */
data class CourseProgress(
    val name: String,
    val lengthM: Double,
    val progressM: Double,
    val offCourseM: Double,
    val onCourse: Boolean,
    val started: Boolean,
    val finished: Boolean,
    val nextTurn: Turn?,
) {
    val remainingM: Double get() = (lengthM - progressM).coerceAtLeast(0.0)
    val toNextTurnM: Double? get() = nextTurn?.let { it.atM - progressM }
}

/** Where something is relative to the way the runner is heading, as said out loud. */
enum class Relative(val spoken: String) {
    AHEAD("앞쪽"), AHEAD_RIGHT("오른쪽 앞"), RIGHT("오른쪽"), BEHIND_RIGHT("오른쪽 뒤"),
    BEHIND("뒤쪽"), BEHIND_LEFT("왼쪽 뒤"), LEFT("왼쪽"), AHEAD_LEFT("왼쪽 앞");

    companion object {
        /** [angle]: bearing to the thing minus the runner's heading, degrees. */
        fun of(angle: Double): Relative {
            var a = angle % 360
            if (a < 0) a += 360
            return entries[((a + 22.5) / 45).toInt() % 8]
        }
    }
}

/** Something the course guidance wants said (turned into words by CueText). */
sealed interface CourseCue {
    /** [approachM]: how much of [lengthM] is the way from where the runner started to the course. */
    data class Begin(val lengthM: Double, val approachM: Double = 0.0) : CourseCue
    data class ToStart(val meters: Int) : CourseCue
    data object AtStart : CourseCue
    data class TurnAhead(val direction: TurnDirection, val meters: Int) : CourseCue
    data class TurnNow(val direction: TurnDirection, val then: TurnDirection?) : CourseCue
    data class Climb(val lengthM: Int) : CourseCue
    /** Off the course; it is [meters] away toward [toward] (null until the runner's heading is known). */
    data class OffCourse(val toward: Relative?, val meters: Int) : CourseCue
    data object BackOnCourse : CourseCue
    /** On the course but going the wrong way along it. */
    data object WrongWay : CourseCue
    data object RightWayAgain : CourseCue
    data class Remaining(val meters: Double) : CourseCue
    data object Halfway : CourseCue
    data object Finished : CourseCue
    /** The runner asked for the way back: a new route, [toCourseM] to where it rejoins the course. */
    data class Rerouted(val toCourseM: Int) : CourseCue
    /** Asked for the way back before there was a GPS position. */
    data object RerouteNoFix : CourseCue
    /** The workout began before the runner's position (and so the course from there) was known. */
    data object Locating : CourseCue
}

/**
 * Follows the runner along a course, one position a second, and decides what to say. Pure:
 * positions and timestamps come from the caller.
 */
class CourseNavigator(
    private val name: String,
    val route: CourseRoute,
    private val onCourseM: Double = 25.0,
    private val offCourseM: Double = 40.0,
    private val offCourseHoldMs: Long = 8_000,
    /** Metres at the course's start that are the way from where the runner started (CourseJoin). */
    private val approachM: Double = 0.0,
    /** False for a route rebuilt mid-run (CourseJoin.rejoin): its halfway isn't the course's. */
    announceHalfway: Boolean = true,
) {
    // Recent positions, for the runner's own heading (needed to say "the course is to your
    // right" and to notice running the wrong way).
    private val trail = ArrayDeque<GeoPoint>()
    private var lastOffReminderMs = 0L
    private var lastOffReminderM = 0.0
    private var wrongSinceMs: Long? = null
    private var wrongWay = false
    private var started = false
    private var progress = 0.0
    private var offSinceMs: Long? = null
    private var offCourse = false
    private var lastOffDistance = 0.0
    private var finished = false
    private var toStartAnnounced = false
    private val aheadAnnounced = mutableSetOf<Int>()
    private val nowAnnounced = mutableSetOf<Int>()
    private val climbsAnnounced = mutableSetOf<Int>()
    private var nextRemainingKm = 1
    private var halfwayAnnounced = !announceHalfway

    fun begin(): CourseCue = CourseCue.Begin(route.lengthM, approachM)

    /** The runner's heading from the last ~20 m moved, or null if they haven't moved that far. */
    private fun heading(): Double? {
        val now = trail.lastOrNull() ?: return null
        val back = trail.lastOrNull { distanceBetween(it, now) >= 20 } ?: return null
        return bearingBetween(back, now)
    }

    fun update(p: GeoPoint, nowMs: Long): List<CourseCue> {
        val cues = mutableListOf<CourseCue>()
        if (trail.isEmpty() || distanceBetween(trail.last(), p) >= 3) {
            trail.addLast(p)
            while (trail.size > 40) trail.removeFirst()
        }
        if (!started) {
            // Not on the course yet: the runner may still be walking to its start.
            val (along, dist) = route.locate(p, 0.0, minOf(300.0, route.lengthM))
            lastOffDistance = dist
            if (dist <= onCourseM) {
                started = true
                progress = along
                if (toStartAnnounced) cues += CourseCue.AtStart
            } else {
                if (!toStartAnnounced) {
                    toStartAnnounced = true
                    cues += CourseCue.ToStart(roundTo10(route.locate(p, 0.0, 1.0).second))
                }
                return cues
            }
        }

        // Normally search a window around where we were (stays on the right pass of a course
        // that crosses itself); after a while off course, look everywhere (a shortcut).
        val (along, dist) = if (offCourse && offSinceMs?.let { nowMs - it > 30_000 } == true) {
            route.locate(p)
        } else {
            // 150 m back, so a runner going the wrong way is still placed on the course (and told)
            // rather than called off it.
            route.locate(p, progress - 150, progress + 200)
        }
        lastOffDistance = dist
        if (dist <= onCourseM) {
            progress = maxOf(progress, along)
            offSinceMs = null
            if (offCourse) { offCourse = false; cues += CourseCue.BackOnCourse }
        } else if (dist > offCourseM) {
            val since = offSinceMs ?: nowMs.also { offSinceMs = it }
            // Said once on leaving, then again every 40 s while still drifting further away.
            val remind = offCourse && nowMs - lastOffReminderMs >= 40_000 && dist > lastOffReminderM + 10
            if ((!offCourse && nowMs - since >= offCourseHoldMs) || remind) {
                offCourse = true
                lastOffReminderMs = nowMs
                lastOffReminderM = dist
                val target = route.pointAt(along)
                val toward = heading()?.let { Relative.of(bearingBetween(p, target) - it) }
                cues += CourseCue.OffCourse(toward, roundTo10(dist))
            }
        }
        if (offCourse) return cues

        // Wrong way: on the course but heading against it for 10 s (after moving 20 m, so a
        // runner turning on the spot doesn't set it off).
        val h = heading()
        if (h != null && dist <= onCourseM) {
            var diff = (h - route.headingAt(along)) % 360
            if (diff < 0) diff += 360
            if (diff in 135.0..225.0) {
                val since = wrongSinceMs ?: nowMs.also { wrongSinceMs = it }
                if (!wrongWay && nowMs - since >= 10_000) { wrongWay = true; cues += CourseCue.WrongWay }
            } else {
                wrongSinceMs = null
                if (wrongWay) { wrongWay = false; cues += CourseCue.RightWayAgain }
            }
        }
        if (wrongWay) return cues

        // Turns: a heads-up 80-120 m before, then "now" at 20 m. A turn that comes up closer
        // than that (right after the previous one) gets no heads-up - "60 m ahead, left" straight
        // after another instruction is just noise; the "now" cue (or the previous turn's
        // "then ...") covers it.
        // Only the very next turn gets a heads-up: on the way to one turn, hearing about the one
        // after it first is confusing.
        val upcoming = route.turns.indices.firstOrNull { it !in nowAnnounced && route.turns[it].atM - progress > -5 }
        route.turns.forEachIndexed { i, t ->
            val toTurn = t.atM - progress
            if (i == upcoming && toTurn in 80.0..120.0 && i !in aheadAnnounced) {
                aheadAnnounced += i
                cues += CourseCue.TurnAhead(t.direction, roundTo10(toTurn))
            }
            if (toTurn in -5.0..20.0 && i !in nowAnnounced) {
                nowAnnounced += i
                aheadAnnounced += i
                // A turn soon after this one is chained here ("right. then left.") instead of
                // getting its own heads-up; it still gets its own "now" unless it's too close.
                val next = route.turns.getOrNull(i + 1)?.takeIf { it.atM - t.atM < 120 }
                if (next != null) {
                    aheadAnnounced += i + 1
                    if (next.atM - t.atM < 40) nowAnnounced += i + 1
                }
                cues += CourseCue.TurnNow(t.direction, next?.direction)
            }
        }
        route.climbs.forEachIndexed { i, c ->
            val toClimb = c.startM - progress
            if (toClimb in 0.0..150.0 && i !in climbsAnnounced) {
                climbsAnnounced += i
                cues += CourseCue.Climb(roundTo10(c.lengthM))
            }
        }
        if (!halfwayAnnounced && route.lengthM >= 1000 && progress >= route.lengthM / 2) {
            halfwayAnnounced = true
            cues += CourseCue.Halfway
        }
        if (progress >= nextRemainingKm * 1000.0 && route.lengthM - progress > 300) {
            nextRemainingKm = (progress / 1000).toInt() + 1
            cues += CourseCue.Remaining(route.lengthM - progress)
        }
        if (!finished && route.lengthM - progress <= 25) {
            finished = true
            cues += CourseCue.Finished
        }
        return cues
    }

    fun progress() = CourseProgress(
        name = name,
        lengthM = route.lengthM,
        progressM = progress,
        offCourseM = lastOffDistance,
        onCourse = started && !offCourse,
        started = started,
        finished = finished,
        nextTurn = if (started && !finished) route.turns.firstOrNull { it.atM > progress - 5 } else null,
    )

    private fun roundTo10(m: Double) = ((m / 10).roundToInt() * 10).coerceAtLeast(10)

    private companion object {
        fun distanceBetween(a: GeoPoint, b: GeoPoint): Double {
            val kx = 111_195.0 * cos(Math.toRadians((a.lat + b.lat) / 2))
            val dx = (b.lon - a.lon) * kx; val dy = (b.lat - a.lat) * 111_195.0
            return sqrt(dx * dx + dy * dy)
        }

        fun bearingBetween(a: GeoPoint, b: GeoPoint): Double {
            val kx = 111_195.0 * cos(Math.toRadians((a.lat + b.lat) / 2))
            return Math.toDegrees(atan2((b.lon - a.lon) * kx, (b.lat - a.lat) * 111_195.0))
        }
    }
}
