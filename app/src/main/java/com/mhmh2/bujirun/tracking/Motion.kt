package com.mhmh2.bujirun.tracking

enum class Activity { UNKNOWN, STILL, WALK, RUN }

/**
 * Recent ground speed from GPS, independent of the workout's pause state (auto-pause needs to
 * know we're moving again while the tracker itself is ignoring fixes).
 *
 * Two estimates, and the higher one wins: the speed the phone reports with each fix (Doppler,
 * accurate while moving, slightly low on curves), and straight-line displacement over the
 * window. Taking the higher means a source that confidently reports 0 while moving (seen on the
 * emulator, with a claimed 0.5 m/s accuracy) can't freeze auto-pause. Displacement, not summed
 * path: while standing still GPS wanders back and forth, and a summed path would read that as
 * walking; displacement of that wander stays well under the "still" threshold.
 */
class SpeedMeter(private val keepMs: Long = 15_000, private val filter: TrackFilter = TrackFilter()) {
    private val samples = ArrayDeque<LocationSample>()

    fun add(sample: LocationSample) {
        if (!filter.accept(samples.lastOrNull(), sample)) return
        samples.addLast(sample)
        while (samples.size > 1 && samples.first().timeMs < sample.timeMs - keepMs) samples.removeFirst()
    }

    /** Average speed (m/s) over the last [windowMs], or null if there are no fixes in it. */
    fun speed(windowMs: Long, nowMs: Long): Double? {
        val recent = samples.filter { it.timeMs >= nowMs - windowMs }
        if (recent.isEmpty()) return null
        val reported = recent.mapNotNull { it.speedMps }.takeIf { it.size == recent.size }?.average()
        val seconds = (recent.last().timeMs - recent.first().timeMs) / 1000.0
        val displacement = if (recent.size >= 2 && seconds >= 1.0) {
            distanceMeters(recent.first(), recent.last()) / seconds
        } else null
        return listOfNotNull(reported, displacement).maxOrNull()
    }
}

/** Steps per minute from the phone's step detector. */
class CadenceMeter(private val windowMs: Long = 10_000) {
    private val steps = ArrayDeque<Long>()

    fun onStep(timeMs: Long) {
        steps.addLast(timeMs)
        while (steps.isNotEmpty() && steps.first() < timeMs - 60_000) steps.removeFirst()
    }

    fun cadence(nowMs: Long): Double = stepsSince(nowMs - windowMs) * 60_000.0 / windowMs

    fun stepsSince(timeMs: Long): Int = steps.count { it >= timeMs }
}

/**
 * Walking or running, from step cadence (primary) and GPS speed (tie-breaker, and the only signal
 * on phones without a step sensor). A new state only sticks after holding for a while, so one
 * slow stretch at a crossing doesn't flip a run to a walk.
 */
class ActivityClassifier(
    private val firstHoldMs: Long = 5_000,
    private val switchHoldMs: Long = 10_000,
) {
    var current = Activity.UNKNOWN
        private set
    private var candidate: Activity? = null
    private var candidateSinceMs = 0L

    fun update(nowMs: Long, speedMps: Double?, cadenceSpm: Double?): Activity {
        val raw = classify(speedMps, cadenceSpm) ?: return current
        if (raw == current) {
            candidate = null
            return current
        }
        if (raw != candidate) {
            candidate = raw
            candidateSinceMs = nowMs
        }
        val hold = if (current == Activity.UNKNOWN) firstHoldMs else switchHoldMs
        if (nowMs - candidateSinceMs >= hold) {
            current = raw
            candidate = null
        }
        return current
    }

    companion object {
        // Running cadence is typically 150-190 steps/min, walking 90-130. The band in between is
        // decided by speed: a brisk walk and a slow shuffle-jog can share a cadence.
        fun classify(speedMps: Double?, cadenceSpm: Double?): Activity? {
            if (cadenceSpm != null && cadenceSpm >= 50) {
                return when {
                    cadenceSpm >= 145 -> Activity.RUN
                    cadenceSpm >= 125 -> if ((speedMps ?: 0.0) >= 2.2) Activity.RUN else Activity.WALK
                    else -> Activity.WALK
                }
            }
            // No (or almost no) steps: either no step sensor, or the phone isn't seeing them.
            val speed = speedMps ?: return if (cadenceSpm != null) Activity.STILL else null
            return when {
                speed >= 2.3 -> Activity.RUN   // ~7'15"/km and faster
                speed >= 0.6 -> Activity.WALK
                else -> Activity.STILL
            }
        }
    }
}

/**
 * Pauses the workout while standing still (traffic lights, tying a shoe) and resumes it as soon
 * as the runner moves again. Quick to resume, slower to pause: missing a few seconds of running
 * is worse than counting a few seconds of standing.
 */
class AutoPauseDetector(
    private val stillSpeedMps: Double = 0.5,
    private val moveSpeedMps: Double = 1.2,
    private val stillHoldMs: Long = 3_000,
    private val moveHoldTicks: Int = 2,
) {
    enum class Decision { NONE, PAUSE, RESUME }

    private var stillSinceMs: Long? = null
    private var movingTicks = 0

    /**
     * @param slowSpeed speed over a longer window (~8 s), steady enough to call "still"
     * @param quickSpeed speed over a short window (~3 s), to notice moving again fast
     * @param recentSteps steps in the last 5 s, or null without a step sensor
     */
    fun update(nowMs: Long, autoPaused: Boolean, slowSpeed: Double?, quickSpeed: Double?, recentSteps: Int?): Decision {
        if (!autoPaused) {
            movingTicks = 0
            // No fixes at all (tunnel, underpass) is not evidence of standing still.
            val still = slowSpeed != null && slowSpeed < stillSpeedMps && (recentSteps == null || recentSteps <= 1)
            if (!still) {
                stillSinceMs = null
                return Decision.NONE
            }
            val since = stillSinceMs ?: nowMs.also { stillSinceMs = it }
            if (nowMs - since < stillHoldMs) return Decision.NONE
            stillSinceMs = null
            return Decision.PAUSE
        }
        stillSinceMs = null
        val moving = (quickSpeed != null && quickSpeed >= moveSpeedMps) || (recentSteps != null && recentSteps >= 6)
        movingTicks = if (moving) movingTicks + 1 else 0
        if (movingTicks < moveHoldTicks) return Decision.NONE
        movingTicks = 0
        return Decision.RESUME
    }
}
