package com.mhmh2.bujirun.tracking

import java.time.DayOfWeek
import java.time.LocalDate
import java.time.temporal.TemporalAdjusters

/** Personal bests and streaks, for the account page (as Strava shows them). Pure, tested. */
object Records {
    /**
     * The fastest time (ms, active time) over any stretch of [targetM] within one workout, from
     * its points as (distance so far, active time so far). Null if the workout is shorter.
     */
    fun fastestMs(points: List<Pair<Double, Long>>, targetM: Double): Long? {
        if (points.size < 2 || points.last().first - points.first().first < targetM) return null
        var best = Long.MAX_VALUE
        var j = 0
        for (i in points.indices) {
            val (d0, t0) = points[i]
            while (j < points.size && points[j].first - d0 < targetM) j++
            if (j >= points.size) break
            // The stretch ends between points j-1 and j: interpolate where targetM is reached.
            val (d1, t1) = points[j - 1]
            val (d2, t2) = points[j]
            val f = if (d2 > d1) (d0 + targetM - d1) / (d2 - d1) else 1.0
            val end = t1 + ((t2 - t1) * f).toLong()
            best = minOf(best, end - t0)
        }
        return best.takeIf { it != Long.MAX_VALUE && it > 0 }
    }

    /** Weeks in a row (Monday to Sunday) with a workout, counting back from this week (or last
     * week, when this one has none yet - it isn't over). */
    fun weekStreak(days: Collection<LocalDate>, today: LocalDate): Int {
        val weeks = days.map { it.with(TemporalAdjusters.previousOrSame(DayOfWeek.MONDAY)) }.toSet()
        var week = today.with(TemporalAdjusters.previousOrSame(DayOfWeek.MONDAY))
        if (week !in weeks) week = week.minusWeeks(1)
        var n = 0
        while (week in weeks) { n++; week = week.minusWeeks(1) }
        return n
    }
}
