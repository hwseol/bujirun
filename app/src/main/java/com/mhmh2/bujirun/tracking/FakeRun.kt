package com.mhmh2.bujirun.tracking

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
 */
class FakeRun(seed: Long, private var lat: Double, private var lon: Double) {
    data class Fix(val lat: Double, val lon: Double, val altitudeM: Double, val accuracyM: Float, val speedMps: Double)

    private enum class Kind { WALK, RUN, STOP }
    private data class Phase(val kind: Kind, var secondsLeft: Int, val baseSpeed: Double)

    private val rnd = Random(seed)
    private var heading = rnd.nextDouble(0.0, 360.0)
    private var distance = 0.0
    private var nextTurnAt = turnGap()
    private val phases = ArrayDeque<Phase>()
    private var started = false

    fun next(): Fix {
        val phase = currentPhase()
        phase.secondsLeft--
        val speed = when (phase.kind) {
            Kind.STOP -> 0.0
            else -> phase.baseSpeed * (1 + rnd.nextDouble(-0.05, 0.05))
        }
        if (speed > 0) {
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
