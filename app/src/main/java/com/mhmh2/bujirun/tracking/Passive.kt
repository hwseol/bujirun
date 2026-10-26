package com.mhmh2.bujirun.tracking

import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import kotlin.math.roundToLong

/**
 * Recording without the app open: the phone's step counter, read now and then, gives the
 * day's steps; the system's activity recognition (walking / running started or stopped) gives
 * walks and runs. Both are coarse on purpose - nothing is sampled often, there is no GPS - so
 * only what's worth a line in the history is kept. The logic here is pure; PassiveRecorder
 * feeds it.
 */
/**
 * The day's steps that no workout or 자동 기록 accounts for - a walk to the shop, around the
 * office - still covered ground. Taken as walking at a typical stride (~0.7 m) and cadence
 * (~100 a minute), so the home card's distance, time and calories never read 0 under 900 steps.
 */
object LooseSteps {
    data class Extra(val distanceM: Double, val activeMs: Long, val calories: Double)

    fun extra(daySteps: Long, recordedSteps: Long, weightKg: Double): Extra {
        val loose = daySteps - recordedSteps
        if (loose <= 0) return Extra(0.0, 0, 0.0)
        val distanceM = loose * 0.7
        val activeMs = loose * 60_000L / 100
        return Extra(distanceM, activeMs, Calories.kcal(Activity.WALK, distanceM, activeMs / 1000.0, 0.0, weightKg))
    }
}

object StepLedger {
    /**
     * Steps since the last reading of the cumulative counter. A lower reading means the phone
     * restarted and the counter began again at 0, so all of it is new.
     */
    fun delta(previous: Long?, now: Long): Long = when {
        previous == null -> 0
        now >= previous -> now - previous
        else -> now
    }

    /** [steps] taken between two readings, shared out over the days in between by time. */
    fun split(fromMs: Long, toMs: Long, steps: Long, zone: ZoneId): Map<LocalDate, Long> {
        if (steps <= 0) return emptyMap()
        val lastDay = Instant.ofEpochMilli(toMs).atZone(zone).toLocalDate()
        if (toMs <= fromMs) return mapOf(lastDay to steps)
        val out = linkedMapOf<LocalDate, Long>()
        var day = Instant.ofEpochMilli(fromMs).atZone(zone).toLocalDate()
        var given = 0L
        while (day < lastDay) {
            val dayEnd = day.plusDays(1).atStartOfDay(zone).toInstant().toEpochMilli()
            val start = maxOf(fromMs, day.atStartOfDay(zone).toInstant().toEpochMilli())
            val share = (steps * (dayEnd - start).toDouble() / (toMs - fromMs)).roundToLong()
            if (share > 0) out[day] = share
            given += share
            day = day.plusDays(1)
        }
        if (steps - given > 0) out[lastDay] = steps - given
        return out
    }
}

enum class FootKind { WALK, RUN }

/** A walk or run found in the background, ready to save. */
data class Bout(
    val startMs: Long,
    val endMs: Long,
    val kind: FootKind,
    val movingMs: Long,
    val steps: Long,
    val distanceM: Double,
)

/**
 * Walking/running transitions into bouts. A stop of up to [GAP_MS] (a crossing, a shop door)
 * doesn't end one; a walk switching to a run is one bout. Kept only when long enough to be a
 * record rather than a trip down the corridor: [MIN_WALK_MS] walking, [MIN_RUN_MS] running.
 * The state is a few numbers, saved between events (the process doesn't live that long).
 */
class BoutTracker(var state: State = State()) {
    data class State(
        val startMs: Long = 0,
        val kind: FootKind? = null,     // null: no bout open
        val kindSinceMs: Long = 0,
        val runMs: Long = 0,
        val walkMs: Long = 0,
        val exitMs: Long? = null,       // stopped at, if it may yet carry on
        val startSteps: Long? = null,
        val endSteps: Long? = null,
    ) {
        fun encode(): String = listOf(startMs, kind?.name ?: "", kindSinceMs, runMs, walkMs, exitMs ?: "", startSteps ?: "", endSteps ?: "")
            .joinToString(",")

        companion object {
            fun decode(s: String?): State = runCatching {
                val p = s!!.split(",")
                State(
                    p[0].toLong(), p[1].ifEmpty { null }?.let(FootKind::valueOf), p[2].toLong(), p[3].toLong(), p[4].toLong(),
                    p[5].toLongOrNull(), p[6].toLongOrNull(), p[7].toLongOrNull(),
                )
            }.getOrDefault(State())
        }
    }

    /** Started walking or running at [t]; returns a bout that this showed to be over. */
    fun enter(kind: FootKind, t: Long, steps: Long?): Bout? {
        val s = state
        if (s.kind == null) {
            state = State(startMs = t, kind = kind, kindSinceMs = t, startSteps = steps)
            return null
        }
        if (s.exitMs != null) {
            if (t - s.exitMs > GAP_MS) {
                val done = finish(s)
                state = State(startMs = t, kind = kind, kindSinceMs = t, startSteps = steps)
                return done
            }
            state = s.copy(kind = kind, kindSinceMs = t, exitMs = null, endSteps = null)
            return null
        }
        // Switched without a stop in between (walk -> run).
        state = accumulate(s, t).copy(kind = kind, kindSinceMs = t)
        return null
    }

    /** Stopped walking or running at [t]. */
    fun exit(kind: FootKind, t: Long, steps: Long?) {
        val s = state
        if (s.kind != kind || s.exitMs != null) return
        state = accumulate(s, t).copy(exitMs = t, endSteps = steps)
    }

    /** Now and then: a stop that has lasted, or a bout whose end was never reported, is over. */
    fun check(t: Long, steps: Long?): Bout? {
        val s = state
        if (s.kind == null) return null
        when {
            s.exitMs != null && t - s.exitMs > GAP_MS -> {
                state = State()
                return finish(s)
            }
            s.exitMs == null && t - s.startMs > MAX_MS -> {
                // The end never came (the system missed it): the hours since the start weren't
                // all walking. Only what the step counter proves counts - its steps, and the time
                // they'd take - and without counter readings nothing is kept.
                state = State()
                val counted = if (s.startSteps != null && steps != null) StepLedger.delta(s.startSteps, steps) else 0L
                if (counted <= 0) return null
                val run = s.kind == FootKind.RUN
                val moving = (counted / (if (run) 160.0 else 100.0) * 60_000).toLong().coerceAtMost(t - s.startMs)
                val proven = s.copy(
                    walkMs = if (run) 0 else moving, runMs = if (run) moving else 0,
                    exitMs = s.startMs + moving, startSteps = s.startSteps, endSteps = steps,
                )
                return finish(proven)
            }
            else -> return null
        }
    }

    private fun accumulate(s: State, t: Long): State {
        val d = (t - s.kindSinceMs).coerceAtLeast(0)
        return if (s.kind == FootKind.RUN) s.copy(runMs = s.runMs + d, kindSinceMs = t) else s.copy(walkMs = s.walkMs + d, kindSinceMs = t)
    }

    private fun finish(s: State): Bout? {
        val kind = if (s.runMs >= s.walkMs) FootKind.RUN else FootKind.WALK
        val moving = s.runMs + s.walkMs
        if (moving < (if (kind == FootKind.RUN) MIN_RUN_MS else MIN_WALK_MS)) return null
        val counted = if (s.startSteps != null && s.endSteps != null) StepLedger.delta(s.startSteps, s.endSteps) else null
        // No counter readings: a typical cadence (walking ~100, running ~160 a minute).
        val steps = counted?.takeIf { it > 0 } ?: (s.walkMs / 60_000.0 * 100 + s.runMs / 60_000.0 * 160).roundToLong()
        // Stride by the share of each: ~0.7 m walking, ~1.0 m running.
        val stride = (0.7 * s.walkMs + 1.0 * s.runMs) / moving
        return Bout(s.startMs, s.exitMs ?: s.kindSinceMs, kind, moving, steps, steps * stride)
    }

    companion object {
        const val GAP_MS = 3 * 60_000L
        const val MAX_MS = 4 * 3_600_000L
        const val MIN_WALK_MS = 15 * 60_000L
        const val MIN_RUN_MS = 5 * 60_000L
    }
}
