package com.mhmh2.bujirun.tracking

import com.mhmh2.bujirun.course.CourseRoute
import com.mhmh2.bujirun.course.GeoPoint
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.sin
import kotlin.random.Random

/**
 * A made-up but believable run, one GPS fix per (virtual) second, for admin test runs: a warm-up
 * walk, running stretches at varying pace, stops at "traffic lights", walking breaks, gentle
 * curves and occasional turns, rolling hills, and a couple of metres of GPS noise - enough to
 * exercise walk/run detection, auto-pause, splits, calories and the route drawing.
 * Endless: it keeps going until the workout is stopped. Pure and seeded, so it's testable.
 *
 * [strays]: on a course, now and then it doesn't do as it's told, the way people really run - the
 * pavement across the road for a while, a detour along another street, straight on past a turn,
 * a loop started the other way round - and then follows whatever route the app guides it onto
 * ([follow]). That's the guidance a test run is for: getting a runner back, not a perfect line.
 */
class FakeRun(
    seed: Long,
    private var lat: Double,
    private var lon: Double,
    path: List<GeoPoint>? = null,
    private val strays: Boolean = false,
) {
    /** With a course: run along it (laps of a loop; stand at the finish of a one-way course). */
    private var route = path?.takeIf { it.size >= 2 }?.let { CourseRoute(it) }
    private var along = 0.0

    private sealed interface Stray
    /** Alongside the course, [sideM] off it (the other pavement, a path beside it, or a whole street over). */
    private data class Aside(val fromM: Double, val lengthM: Double, val sideM: Double) : Stray
    /** Straight on past a turn, [leftM] still to go, then back to where they left the course. */
    private data class Straight(val headingDeg: Double, var leftM: Double, val backTo: GeoPoint) : Stray
    private data class Back(val to: GeoPoint) : Stray

    private var stray: Stray? = null
    private var missTurnAt: Double? = null
    private var nextStrayAt = 0.0

    /** What it's doing off the course right now, for the log of a test run (null: on it). */
    val straying: String? get() = when (val s = stray) {
        is Aside -> if (s.sideM.let { kotlin.math.abs(it) } > 100) "detour" else "alongside"
        is Straight -> "missed a turn"
        is Back -> "back to the course"
        null -> null
    }

    init {
        route?.pointAt(0.0)?.let { lat = it.lat; lon = it.lon }
    }

    /** The app guided it onto [path] (a reroute): from here on that's what it runs. */
    fun follow(path: List<GeoPoint>) {
        if (path.size < 2) return
        val r = CourseRoute(path)
        route = r
        along = r.locate(GeoPoint(lat, lon), 0.0, minOf(r.lengthM, 300.0)).first
        stray = null
        missTurnAt = null
    }

    data class Fix(val lat: Double, val lon: Double, val altitudeM: Double, val accuracyM: Float, val speedMps: Double)

    private enum class Kind { WALK, RUN, STOP }
    private data class Phase(val kind: Kind, var secondsLeft: Int, val baseSpeed: Double)

    private val rnd = Random(seed)
    private var heading = rnd.nextDouble(0.0, 360.0)
    private var distance = 0.0
    private var nextTurnAt = turnGap()
    private val phases = ArrayDeque<Phase>()
    private var started = false

    /** Where the pretend runner stands, not moving (during the countdown: they haven't set off). */
    fun standing(): Fix = Fix(lat, lon, 20.0, accuracyM = rnd.nextDouble(4.0, 9.0).toFloat(), speedMps = 0.0)

    fun next(): Fix {
        val phase = currentPhase()
        phase.secondsLeft--
        val finished = route?.let { !it.isLoop && along >= it.lengthM } == true
        val speed = when {
            phase.kind == Kind.STOP || finished -> 0.0
            else -> phase.baseSpeed * (1 + rnd.nextDouble(-0.05, 0.05))
        }
        val route = route
        if (route != null && stray is Straight) {
            val s = stray as Straight
            step(s.headingDeg, speed)
            s.leftM -= speed
            if (s.leftM <= 0) stray = Back(s.backTo)
            distance += speed
        } else if (route != null && stray is Back) {
            // Not rerouted (offline, or before the app noticed): back to where they went wrong.
            val to = (stray as Back).to
            val d = metres(lat, lon, to.lat, to.lon)
            if (d <= speed + 1) { lat = to.lat; lon = to.lon; stray = null } else step(bearingTo(to), speed)
            distance += speed
        } else if (route != null) {
            along += speed
            if (along >= route.lengthM) along = if (route.isLoop) along - route.lengthM else route.lengthM
            val on = route.pointAt(along)
            lat = on.lat; lon = on.lon
            (stray as? Aside)?.let { a ->
                val t = along - a.fromM
                if (t > a.lengthM || t < 0) stray = null
                else {
                    // Eased out to the side and back over the first and last 40 m.
                    val ramp = minOf(1.0, t / 40, (a.lengthM - t) / 40)
                    shift(route.headingAt(along) + 90, a.sideM * ramp)
                }
            }
            if (strays && speed > 0) maybeStray(route)
            distance += speed
        } else if (speed > 0) {
            heading += gaussian() * 3
            if (distance >= nextTurnAt) {
                heading += (if (rnd.nextBoolean()) 1 else -1) * rnd.nextDouble(60.0, 110.0)
                nextTurnAt = distance + turnGap()
            }
            val rad = heading * PI / 180
            lat += speed * cos(rad) / M_PER_DEG
            lon += speed * sin(rad) / (M_PER_DEG * cos(lat * PI / 180))
            distance += speed
        }
        // GPS noise around the true position. Phones smooth their fused location, so the
        // fix-to-fix jitter is well under a metre; at 1.5 m the jagged track inflated distance by
        // ~10% and made every test run look faster than it was.
        val noiseLat = gaussian() * NOISE_M / M_PER_DEG
        val noiseLon = gaussian() * NOISE_M / (M_PER_DEG * cos(lat * PI / 180))
        val altitude = 20 + 12 * sin(distance / 700) + 6 * sin(distance / 230)
        return Fix(
            lat + noiseLat, lon + noiseLon, altitude,
            accuracyM = rnd.nextDouble(4.0, 9.0).toFloat(),
            speedMps = abs(speed + gaussian() * 0.15).let { if (phase.kind == Kind.STOP) it * 0.2 else it },
        )
    }

    private fun currentPhase(): Phase {
        while (phases.firstOrNull()?.let { it.secondsLeft <= 0 } == true) phases.removeFirst()
        if (phases.isEmpty()) plan()
        return phases.first()
    }

    private fun plan() {
        if (!started) {
            started = true
            phases += walk(rnd.nextInt(40, 80))
        }
        // A running stretch, sometimes interrupted by a traffic light...
        val runPace = rnd.nextDouble(290.0, 400.0) // 4'50" - 6'40" per km
        val runSeconds = rnd.nextInt(180, 480)
        if (rnd.nextDouble() < 0.35) {
            val split = rnd.nextInt(60, runSeconds - 60)
            phases += Phase(Kind.RUN, split, 1000 / runPace)
            phases += Phase(Kind.STOP, rnd.nextInt(12, 30), 0.0)
            phases += Phase(Kind.RUN, runSeconds - split, 1000 / runPace)
        } else {
            phases += Phase(Kind.RUN, runSeconds, 1000 / runPace)
        }
        // ...and sometimes followed by a walking breather.
        if (rnd.nextDouble() < 0.4) phases += walk(rnd.nextInt(30, 90))
    }

    // Every 500-900 m one of: the pavement alongside (should be "following", no nagging), a
    // detour a street over (off the course: rerouted), or straight on at the next turn.
    private fun maybeStray(route: CourseRoute) {
        if (nextStrayAt == 0.0) {
            nextStrayAt = distance + rnd.nextDouble(300.0, 600.0)
            // A loop now and then set off the wrong way round.
            if (route.isLoop && rnd.nextDouble() < 0.25) {
                this.route = CourseRoute(route.points.reversed())
                along = 0.0
            }
            return
        }
        missTurnAt?.let { at ->
            if (along >= at - 3) {
                missTurnAt = null
                stray = Straight(route.headingAt(maxOf(0.0, at - 20)), rnd.nextDouble(120.0, 220.0), route.pointAt(at))
            }
            return
        }
        if (stray != null || distance < nextStrayAt) return
        nextStrayAt = distance + rnd.nextDouble(500.0, 900.0)
        val side = if (rnd.nextBoolean()) 1.0 else -1.0
        val nextTurn = route.turns.firstOrNull { it.atM > along + 40 && it.atM < along + 400 }
        when {
            nextTurn != null && rnd.nextDouble() < 0.35 -> missTurnAt = nextTurn.atM
            rnd.nextBoolean() -> stray = Aside(along, rnd.nextDouble(150.0, 300.0), side * rnd.nextDouble(35.0, 60.0))
            else -> stray = Aside(along, rnd.nextDouble(250.0, 450.0), side * rnd.nextDouble(140.0, 220.0))
        }
    }

    private fun step(headingDeg: Double, m: Double) = shift(headingDeg, m)

    private fun shift(headingDeg: Double, m: Double) {
        val rad = headingDeg * PI / 180
        lat += m * cos(rad) / M_PER_DEG
        lon += m * sin(rad) / (M_PER_DEG * cos(lat * PI / 180))
    }

    private fun bearingTo(p: GeoPoint): Double {
        val dy = (p.lat - lat) * M_PER_DEG
        val dx = (p.lon - lon) * M_PER_DEG * cos(lat * PI / 180)
        return Math.toDegrees(kotlin.math.atan2(dx, dy))
    }

    private fun metres(aLat: Double, aLon: Double, bLat: Double, bLon: Double): Double {
        val dy = (bLat - aLat) * M_PER_DEG
        val dx = (bLon - aLon) * M_PER_DEG * cos(aLat * PI / 180)
        return kotlin.math.hypot(dx, dy)
    }

    private fun walk(seconds: Int) = Phase(Kind.WALK, seconds, rnd.nextDouble(1.2, 1.5))
    private fun turnGap() = rnd.nextDouble(150.0, 500.0)

    private fun gaussian(): Double {
        // Box-Muller.
        val u = rnd.nextDouble(1e-9, 1.0)
        val v = rnd.nextDouble()
        return kotlin.math.sqrt(-2 * kotlin.math.ln(u)) * cos(2 * PI * v)
    }

    /** How far the runner has really gone, without GPS noise (for tests). */
    val trueDistanceM: Double get() = distance

    companion object {
        private const val M_PER_DEG = 111_195.0
        private const val NOISE_M = 0.5

        /** Well-known running spots in Seoul, so test routes land somewhere recognisable. */
        val SEOUL_STARTS = listOf(
            37.5115 to 126.9960, // 반포한강공원
            37.5284 to 126.9330, // 여의도한강공원
            37.5206 to 127.1215, // 올림픽공원
            37.5444 to 127.0374, // 서울숲
            37.5094 to 127.1003, // 석촌호수
        )
    }
}
