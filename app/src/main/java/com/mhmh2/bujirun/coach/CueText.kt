package com.mhmh2.bujirun.coach

import java.math.BigDecimal
import java.math.RoundingMode
import kotlin.math.roundToLong

/** Korean sentences for the TTS engine - written to be heard, not read. */
object CueText {
    fun of(cue: Cue): String = when (cue) {
        is Cue.Countdown -> when (cue.n) { 3 -> "셋"; 2 -> "둘"; 1 -> "하나"; else -> cue.n.toString() }
        is Cue.Started -> "운동을 시작합니다." + goalPhrase(cue.goal)
        is Cue.Split ->
            "${km(cue.distanceM)}. 이번 구간 ${duration(cue.splitMs)}. 총 시간 ${duration(cue.totalMs)}."
        is Cue.TimeMark ->
            "${duration(cue.totalMs)} 경과. ${km(cue.distanceM)}." +
                (cue.averagePaceSecPerKm?.let { " 평균 페이스 ${pace(it)}." } ?: "")
        is Cue.HalfwayDistance -> "절반 왔어요. 지금 페이스면 ${duration(cue.projectedTotalMs)}에 끝나요."
        is Cue.HalfwayTime -> "절반 지났어요. 지금까지 ${km(cue.distanceM)}."
        is Cue.RemainingDistance -> "목표까지 ${cue.meters}미터."
        is Cue.RemainingTime -> "${duration(cue.ms)} 남았어요."
        is Cue.PaceAdvice ->
            (if (cue.speedUp) "조금 빠르게. " else "조금 천천히. ") + "지금 ${pace(cue.currentPaceSecPerKm)} 페이스예요."
        is Cue.GoalReached -> "목표 달성! ${km(cue.distanceM)}, ${duration(cue.totalMs)}."
        Cue.Paused -> "일시정지."
        Cue.AutoPaused -> "자동 일시정지."
        Cue.Resumed -> "다시 시작합니다."
        is Cue.Finished ->
            if (cue.distanceM < 10) "운동을 마쳤습니다. 총 시간 ${duration(cue.totalMs)}."
            else "운동을 마쳤습니다. ${km(cue.distanceM)}, ${duration(cue.totalMs)}." +
                (cue.averagePaceSecPerKm?.let { " 평균 페이스 ${pace(it)}." } ?: "") +
                (if (cue.calories >= 1) " 약 ${cue.calories.roundToLong()}킬로칼로리." else "")
    }

    private fun goalPhrase(goal: Goal) = when (goal) {
        Goal.Free -> ""
        is Goal.Distance -> " 목표 ${km(goal.meters)}."
        is Goal.Time -> " 목표 ${duration(goal.ms)}."
        is Goal.Pace -> " 목표 페이스 ${pace(goal.secPerKm.toDouble())}."
    }

    /** "1시간 2분 3초", "31분 4초", "45초", "30분" (zero parts are dropped). */
    fun duration(ms: Long): String {
        val total = (ms / 1000.0).roundToLong()
        val h = total / 3600
        val m = (total % 3600) / 60
        val s = total % 60
        val parts = buildList {
            if (h > 0) add("${h}시간")
            if (m > 0) add("${m}분")
            if (s > 0 || (h == 0L && m == 0L)) add("${s}초")
        }
        return parts.joinToString(" ")
    }

    fun pace(secPerKm: Double) = duration((secPerKm * 1000).roundToLong())

    /** "5킬로미터", "1.5킬로미터", "1.62킬로미터". */
    fun km(meters: Double): String {
        val km = BigDecimal(meters / 1000.0).setScale(2, RoundingMode.HALF_UP).stripTrailingZeros()
        return "${km.toPlainString()}킬로미터"
    }
}
