package com.mhmh2.bujirun.coach

import com.mhmh2.bujirun.tracking.WorkoutSnapshot
import com.mhmh2.bujirun.tracking.WorkoutStatus
import kotlin.math.floor

/** Something worth saying out loud. Turned into words by [CueText]. */
sealed interface Cue {
    data class Countdown(val n: Int) : Cue
    data class Started(val goal: Goal) : Cue
    data class Split(val distanceM: Double, val splitMs: Long, val totalMs: Long, val splitM: Double = 1000.0) : Cue
    data class TimeMark(val totalMs: Long, val distanceM: Double, val averagePaceSecPerKm: Double?) : Cue
    data class HalfwayDistance(val projectedTotalMs: Long) : Cue
    data class HalfwayTime(val distanceM: Double) : Cue
    data class RemainingDistance(val meters: Int) : Cue
    data class RemainingTime(val ms: Long) : Cue
    data class PaceAdvice(val speedUp: Boolean, val currentPaceSecPerKm: Double) : Cue
    data class GoalReached(val distanceM: Double, val totalMs: Long) : Cue
    /** Course guidance (CourseNavigator), spoken through the same voice queue. */
    data class Course(val cue: com.mhmh2.bujirun.course.CourseCue) : Cue
    data object Paused : Cue
    data object AutoPaused : Cue
    data object Resumed : Cue
    data class Finished(
        val distanceM: Double,
        val totalMs: Long,
        val averagePaceSecPerKm: Double?,
        val calories: Double = 0.0,
    ) : Cue
}

/**
 * Decides when to speak during a workout, from the once-a-second snapshots. Pure logic - all
 * timing is the workout's own active elapsed time, so pauses don't trigger anything.
 */
class CoachEngine(
    private val goal: Goal,
    chosenInterval: CueInterval = CueInterval.EveryDistance(1000.0),
    private val paceToleranceSec: Double = 15.0,
    // Being told "faster" every few seconds would be maddening; also skip the first stretch,
    // where everyone is still finding their rhythm and the rolling pace is noisy.
    private val paceAdviceCooldownMs: Long = 60_000,
    private val paceAdviceMinDistanceM: Double = 300.0,
) {
    private val interval = chosenInterval.resolve(goal)
    private var nextSplitM = (interval as? CueInterval.EveryDistance)?.meters
    private var lastSplitTotalMs = 0L
    private var nextTimeMarkMs = (interval as? CueInterval.EveryTime)?.ms
    private var halfwayDone = false
    private var remainingDone = false
    private var goalDone = false
    private var lastPaceAdviceMs: Long? = null

    fun onTick(s: WorkoutSnapshot): List<Cue> {
        if (s.status != WorkoutStatus.RUNNING) return emptyList()
        val goalCues = mutableListOf<Cue>()
        val reached = goalCues(s, goalCues)
        val cues = mutableListOf<Cue>()
        // On the tick the goal is reached, its own announcement already says the distance and
        // time - a split saying nearly the same thing right before it would just be noise.
        // Otherwise the routine split goes first ("1 km ... 500 m to go" reads naturally).
        intervalCues(s, cues, suppress = reached)
        cues += goalCues
        paceCues(s, cues)
        return cues
    }

    /** Returns true if the goal was reached on this tick. */
    private fun goalCues(s: WorkoutSnapshot, cues: MutableList<Cue>): Boolean {
        if (goalDone) return false
        when (goal) {
            is Goal.Distance -> {
                val remaining = goal.meters - s.distanceM
                if (remaining <= 0) return reached(s, cues)
                if (!halfwayDone && goal.meters >= 1000 && s.distanceM >= goal.meters / 2) {
                    halfwayDone = true
                    val projected = (s.elapsedMs * goal.meters / s.distanceM).toLong()
                    cues += Cue.HalfwayDistance(projected)
                }
                if (!remainingDone && goal.meters >= 1500 && remaining <= 500) {
                    remainingDone = true
                    cues += Cue.RemainingDistance(500)
                }
            }
            is Goal.Time -> {
                val remaining = goal.ms - s.elapsedMs
                if (remaining <= 0) return reached(s, cues)
                if (!halfwayDone && goal.ms >= 10 * MINUTE && s.elapsedMs >= goal.ms / 2) {
                    halfwayDone = true
                    cues += Cue.HalfwayTime(s.distanceM)
                }
                if (!remainingDone && goal.ms >= 15 * MINUTE && remaining <= 5 * MINUTE) {
                    remainingDone = true
                    cues += Cue.RemainingTime(5 * MINUTE)
                }
            }
            else -> {}
        }
        return false
    }

    private fun reached(s: WorkoutSnapshot, cues: MutableList<Cue>): Boolean {
        goalDone = true
        halfwayDone = true
        remainingDone = true
        cues += Cue.GoalReached(s.distanceM, s.elapsedMs)
        return true
    }

    private fun intervalCues(s: WorkoutSnapshot, cues: MutableList<Cue>, suppress: Boolean) {
        when (interval) {
            is CueInterval.EveryDistance -> {
                val next = nextSplitM ?: return
                if (s.distanceM < next) return
                // A GPS gap can jump over several marks at once - announce only the latest.
                val reachedM = floor(s.distanceM / interval.meters) * interval.meters
                if (!suppress) cues += Cue.Split(reachedM, s.elapsedMs - lastSplitTotalMs, s.elapsedMs, interval.meters)
                lastSplitTotalMs = s.elapsedMs
                nextSplitM = reachedM + interval.meters
            }
            is CueInterval.EveryTime -> {
                val next = nextTimeMarkMs ?: return
                if (s.elapsedMs < next) return
                val markMs = s.elapsedMs / interval.ms * interval.ms
                if (!suppress) cues += Cue.TimeMark(markMs, s.distanceM, s.averagePaceSecPerKm)
                nextTimeMarkMs = markMs + interval.ms
            }
            CueInterval.Auto -> {} // resolved above
        }
    }

    private fun paceCues(s: WorkoutSnapshot, cues: MutableList<Cue>) {
        if (goal !is Goal.Pace) return
        val current = s.currentPaceSecPerKm ?: return
        if (s.distanceM < paceAdviceMinDistanceM) return
        val last = lastPaceAdviceMs
        if (last != null && s.elapsedMs - last < paceAdviceCooldownMs) return
        val delta = current - goal.secPerKm // positive = slower than target
        if (delta > paceToleranceSec || delta < -paceToleranceSec) {
            lastPaceAdviceMs = s.elapsedMs
            cues += Cue.PaceAdvice(speedUp = delta > 0, currentPaceSecPerKm = current)
        }
    }

    private companion object {
        const val MINUTE = 60_000L
    }
}
