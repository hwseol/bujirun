package com.mhmh2.bujirun.coach

import com.mhmh2.bujirun.course.CourseCue
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
        is Cue.Course -> course(cue.cue)
        Cue.Paused -> "일시정지."
        Cue.AutoPaused -> "자동 일시정지."
        Cue.Resumed -> "다시 시작합니다."
        is Cue.Finished ->
            if (cue.distanceM < 10) "운동을 마쳤습니다. 총 시간 ${duration(cue.totalMs)}."
            else "운동을 마쳤습니다. ${km(cue.distanceM)}, ${duration(cue.totalMs)}." +
                (cue.averagePaceSecPerKm?.let { " 평균 페이스 ${pace(it)}." } ?: "") +
                (if (cue.calories >= 1) " 약 ${cue.calories.roundToLong()}킬로칼로리." else "")
    }

    fun course(c: CourseCue): String = when (c) {
        is CourseCue.Begin ->
            if (c.approachM >= 30) "코스 안내를 시작합니다. 지금 위치에서 코스까지 이동 거리 ${(c.approachM / 10).roundToLong() * 10}미터를 포함해 총 ${km(c.lengthM)}."
            else "코스 안내를 시작합니다. 코스 ${km(c.lengthM)}."
        is CourseCue.ToStart -> "코스 출발점까지 ${c.meters}미터예요. 출발점으로 가 주세요."
        CourseCue.AtStart -> "코스 출발점이에요. 안내를 시작할게요."
        is CourseCue.TurnAhead -> "${c.meters}미터 앞에서 ${c.direction.spoken}."
        is CourseCue.TurnNow -> "${c.direction.spoken}입니다." + (c.then?.let { " 이어서 ${it.spoken}." } ?: "")
        is CourseCue.Climb -> "곧 오르막 ${c.lengthM}미터예요."
        is CourseCue.OffCourse ->
            "코스를 벗어났어요. " + (c.toward?.let { "코스는 ${it.spoken} ${c.meters}미터예요." } ?: "코스까지 ${c.meters}미터예요.")
        CourseCue.WrongWay -> "반대 방향으로 가고 있어요. 뒤로 돌아서 가세요."
        CourseCue.RightWayAgain -> "좋아요, 맞는 방향이에요."
        CourseCue.BackOnCourse -> "코스로 돌아왔어요."
        is CourseCue.Remaining -> "코스 남은 거리 ${km(c.meters)}."
        CourseCue.Halfway -> "코스 절반 왔어요."
        CourseCue.Finished -> "코스 완주!"
        is CourseCue.Rerouted ->
            if (c.toCourseM >= 30) "새 경로를 찾았어요. 코스까지 ${c.toCourseM}미터, 안내를 따라오세요."
            else "코스 바로 옆이에요. 이어서 안내할게요."
        CourseCue.Locating -> "현재 위치를 찾고 있어요. 찾으면 바로 코스 안내를 시작할게요."
        CourseCue.RerouteNoFix -> "아직 현재 위치를 몰라서 경로를 찾을 수 없어요. 잠시 후 다시 눌러 주세요."
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
