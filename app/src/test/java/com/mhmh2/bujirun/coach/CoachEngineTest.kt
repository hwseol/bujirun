package com.mhmh2.bujirun.coach

import com.mhmh2.bujirun.tracking.WorkoutSnapshot
import com.mhmh2.bujirun.tracking.WorkoutStatus
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class CoachEngineTest {
    /** Steady run at [mps]; returns every cue with the second it was spoken. */
    private fun simulate(
        engine: CoachEngine,
        seconds: Int,
        mps: Double = 3.0,
        pace: (Int) -> Double? = { 1000.0 / mps },
    ): List<Pair<Int, Cue>> = (1..seconds).flatMap { t ->
        val snap = WorkoutSnapshot(
            status = WorkoutStatus.RUNNING,
            elapsedMs = t * 1000L,
            distanceM = t * mps,
            currentPaceSecPerKm = pace(t),
            averagePaceSecPerKm = 1000.0 / mps,
        )
        engine.onTick(snap).map { t to it }
    }

    @Test fun splitEveryKilometreWithSplitTime() {
        val cues = simulate(CoachEngine(Goal.Free), seconds = 700).filter { it.second is Cue.Split }
        // 3 m/s: 1 km at 334 s, 2 km at 667 s.
        assertEquals(listOf(334, 667), cues.map { it.first })
        val second = cues[1].second as Cue.Split
        assertEquals(2000.0, second.distanceM, 0.01)
        assertEquals(333_000L, second.splitMs)
    }

    @Test fun timeIntervalMarks() {
        val engine = CoachEngine(Goal.Free, CueInterval.EveryTime(5 * 60_000L))
        val cues = simulate(engine, seconds = 650).filter { it.second is Cue.TimeMark }
        assertEquals(listOf(300, 600), cues.map { it.first })
    }

    @Test fun distanceGoalSequence() {
        val cues = simulate(CoachEngine(Goal.Distance(3000.0)), seconds = 1100).map { it.second }
        val halfway = cues.filterIsInstance<Cue.HalfwayDistance>().single()
        assertEquals(1_000_000L, halfway.projectedTotalMs) // 3 km at 3 m/s
        assertEquals(1, cues.filterIsInstance<Cue.RemainingDistance>().size)
        assertEquals(1, cues.filterIsInstance<Cue.GoalReached>().size)
        // Order: halfway, then 500 m to go, then reached.
        val order = cues.filter { it is Cue.HalfwayDistance || it is Cue.RemainingDistance || it is Cue.GoalReached }
        assertTrue(order[0] is Cue.HalfwayDistance && order[1] is Cue.RemainingDistance && order[2] is Cue.GoalReached)
        // The 3 km split is swallowed by the goal announcement; 1 km and 2 km still come.
        assertEquals(listOf(1000.0, 2000.0), cues.filterIsInstance<Cue.Split>().map { it.distanceM })
    }

    @Test fun splitComesBeforeGoalCueOnSameTick() {
        // 1.5 km goal: the 1 km split and "500 m to go" land on the same second.
        val cues = simulate(CoachEngine(Goal.Distance(1500.0)), seconds = 400)
        val tick = cues.first { it.second is Cue.RemainingDistance }.first
        val sameTick = cues.filter { it.first == tick }.map { it.second }
        assertTrue(sameTick[0] is Cue.Split && sameTick[1] is Cue.RemainingDistance)
    }

    @Test fun splitsContinueAfterGoal() {
        val cues = simulate(CoachEngine(Goal.Distance(1000.0)), seconds = 700).map { it.second }
        assertEquals(listOf(2000.0), cues.filterIsInstance<Cue.Split>().map { it.distanceM })
    }

    @Test fun timeGoalSequence() {
        val cues = simulate(CoachEngine(Goal.Time(20 * 60_000L)), seconds = 1300).map { it.second }
        assertEquals(1, cues.filterIsInstance<Cue.HalfwayTime>().size)
        assertEquals(1, cues.filterIsInstance<Cue.RemainingTime>().size)
        assertEquals(1, cues.filterIsInstance<Cue.GoalReached>().size)
    }

    @Test fun paceAdviceRespectsToleranceAndCooldown() {
        // Target 5'00", running 6'00" the whole way: too slow.
        val engine = CoachEngine(Goal.Pace(300))
        val advice = simulate(engine, seconds = 400, mps = 1000.0 / 360)
            .filter { it.second is Cue.PaceAdvice }
        assertTrue(advice.all { (it.second as Cue.PaceAdvice).speedUp })
        // First allowed once 300 m are covered (108 s), then at most once a minute.
        assertTrue(advice.first().first in 108..109) // float rounding decides 108 vs 109
        advice.zipWithNext().forEach { (a, b) -> assertTrue(b.first - a.first >= 60) }
    }

    @Test fun noPaceAdviceWithinTolerance() {
        val engine = CoachEngine(Goal.Pace(300))
        val cues = simulate(engine, seconds = 600, mps = 1000.0 / 310)
        assertTrue(cues.none { it.second is Cue.PaceAdvice })
    }

    @Test fun silentWhilePaused() {
        val engine = CoachEngine(Goal.Free)
        val cues = engine.onTick(WorkoutSnapshot(status = WorkoutStatus.PAUSED, elapsedMs = 400_000, distanceM = 1200.0))
        assertTrue(cues.isEmpty())
    }
}
