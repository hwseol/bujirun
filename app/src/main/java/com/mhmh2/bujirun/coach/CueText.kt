package com.mhmh2.bujirun.coach

import com.mhmh2.bujirun.course.CourseCue
import com.mhmh2.bujirun.tr
import java.math.BigDecimal
import java.math.RoundingMode
import kotlin.math.roundToLong

/** Sentences for the TTS engine, Korean or English - written to be heard, not read. */
object CueText {
    fun of(cue: Cue): String = when (cue) {
        is Cue.Countdown -> when (cue.n) {
            3 -> tr("셋", "three"); 2 -> tr("둘", "two"); 1 -> tr("하나", "one"); else -> cue.n.toString()
        }
        is Cue.Started -> tr("운동을 시작합니다.", "Starting your workout.") + goalPhrase(cue.goal)
        is Cue.Split ->
            // How far, this kilometre's time (that is its pace), the average pace, the time so far.
            tr("${km(cue.distanceM)} 달렸어요. ", "${km(cue.distanceM)} done. ") +
                (if (cue.splitM == 1000.0) tr("이번 1킬로미터 ${duration(cue.splitMs)}. ", "This kilometer ${duration(cue.splitMs)}. ")
                else tr("이번 구간 ${duration(cue.splitMs)}, 페이스 ${pace(cue.splitMs / cue.splitM)}. ",
                    "This stretch ${duration(cue.splitMs)}, pace ${pace(cue.splitMs / cue.splitM)}. ")) +
                (if (cue.distanceM > 0) tr("평균 페이스 ${pace(cue.totalMs / cue.distanceM)}. ", "Average pace ${pace(cue.totalMs / cue.distanceM)}. ") else "") +
                tr("총 시간 ${duration(cue.totalMs)}.", "Total time ${duration(cue.totalMs)}.")
        is Cue.TimeMark ->
            tr("${duration(cue.totalMs)} 경과. ${km(cue.distanceM)}.", "${duration(cue.totalMs)} in. ${km(cue.distanceM)}.") +
                (cue.averagePaceSecPerKm?.let { tr(" 평균 페이스 ${pace(it)}.", " Average pace ${pace(it)}.") } ?: "")
        is Cue.HalfwayDistance -> tr("절반 왔어요. 지금 페이스면 ${duration(cue.projectedTotalMs)}에 끝나요.",
            "Halfway there. At this pace you'll finish in ${duration(cue.projectedTotalMs)}.")
        is Cue.HalfwayTime -> tr("절반 지났어요. 지금까지 ${km(cue.distanceM)}.", "Halfway through. ${km(cue.distanceM)} so far.")
        is Cue.RemainingDistance -> tr("목표까지 ${cue.meters}미터.", "${cue.meters} meters to go.")
        is Cue.RemainingTime -> tr("${duration(cue.ms)} 남았어요.", "${duration(cue.ms)} to go.")
        is Cue.PaceAdvice ->
            (if (cue.speedUp) tr("조금 빠르게. ", "A little faster. ") else tr("조금 천천히. ", "A little slower. ")) +
                tr("지금 ${pace(cue.currentPaceSecPerKm)} 페이스예요.", "You're at ${pace(cue.currentPaceSecPerKm)} pace.")
        is Cue.GoalReached -> tr("목표 달성! ${km(cue.distanceM)}, ${duration(cue.totalMs)}.", "Goal reached! ${km(cue.distanceM)}, ${duration(cue.totalMs)}.")
        is Cue.Course -> course(cue.cue)
        Cue.Paused -> tr("일시정지.", "Paused.")
        Cue.AutoPaused -> tr("자동 일시정지.", "Auto paused.")
        Cue.Resumed -> tr("다시 시작합니다.", "Resuming.")
        is Cue.Finished ->
            if (cue.distanceM < 10) tr("운동을 마쳤습니다. 총 시간 ${duration(cue.totalMs)}.", "Workout finished. Total time ${duration(cue.totalMs)}.")
            else tr("운동을 마쳤습니다. ${km(cue.distanceM)}, ${duration(cue.totalMs)}.", "Workout finished. ${km(cue.distanceM)}, ${duration(cue.totalMs)}.") +
                (cue.averagePaceSecPerKm?.let { tr(" 평균 페이스 ${pace(it)}.", " Average pace ${pace(it)}.") } ?: "") +
                (if (cue.calories >= 1) tr(" 약 ${cue.calories.roundToLong()}킬로칼로리.", " About ${cue.calories.roundToLong()} calories.") else "")
    }

    fun course(c: CourseCue): String = when (c) {
        is CourseCue.Begin -> (
            if (c.approachM >= 30) tr(
                "코스 안내를 시작합니다. 지금 위치에서 코스까지 이동 거리 ${(c.approachM / 10).roundToLong() * 10}미터를 포함해 총 ${km(c.lengthM)}.",
                "Starting course guidance. ${km(c.lengthM)} in all, including ${(c.approachM / 10).roundToLong() * 10} meters to reach the course.",
            )
            else tr("코스 안내를 시작합니다. 코스 ${km(c.lengthM)}.", "Starting course guidance. The course is ${km(c.lengthM)}.")
            ) + (if (c.laps >= 2) tr(" 한 바퀴 ${km(c.lapM)}, ${c.laps}바퀴예요.", " ${c.laps} laps of ${km(c.lapM)}.") else "")
        is CourseCue.ToStart -> tr("코스 출발점까지 ${c.meters}미터예요. 출발점으로 가 주세요.", "The course starts ${c.meters} meters away. Please head to the start.")
        CourseCue.AtStart -> tr("코스 출발점이에요. 안내를 시작할게요.", "You're at the start. Guidance begins now.")
        is CourseCue.TurnAhead ->
            c.landmark?.let { tr("${c.meters}미터 앞, ${it.at} ${c.direction.spoken}.", "In ${c.meters} meters, ${c.direction.spoken} ${it.at}.") }
                ?: tr("${c.meters}미터 앞에서 ${c.direction.spoken}.", "In ${c.meters} meters, ${c.direction.spoken}.")
        is CourseCue.TurnNow -> tr("${c.direction.spoken}입니다.", "${c.direction.spoken.replaceFirstChar { it.uppercase() }} now.") +
            (c.then?.let { tr(" 이어서 ${it.spoken}.", " Then ${it.spoken}.") } ?: "")
        is CourseCue.Climb -> tr("곧 오르막 ${c.lengthM}미터예요.", "A ${c.lengthM} meter climb is coming up.")
        is CourseCue.OffCourse ->
            tr("코스를 벗어났어요. ", "You're off the course. ") +
                (c.toward?.let { tr("코스는 ${it.spoken} ${c.meters}미터예요.", "The course is ${c.meters} meters ${it.spoken}.") }
                    ?: tr("코스까지 ${c.meters}미터예요.", "It's ${c.meters} meters away."))
        CourseCue.WrongWay -> tr("반대 방향으로 가고 있어요. 뒤로 돌아서 가세요.", "You're going the wrong way. Please turn around.")
        CourseCue.RightWayAgain -> tr("좋아요, 맞는 방향이에요.", "Good, that's the right way.")
        CourseCue.BackOnCourse -> tr("코스로 돌아왔어요.", "You're back on the course.")
        is CourseCue.Remaining -> tr("코스 ", "") + (if (c.doneM > 0) tr("${km(c.doneM)} 왔어요. ", "${km(c.doneM)} done. ") else "") +
            tr("남은 거리 ${km(c.meters)}.", "${km(c.meters)} to go.")
        is CourseCue.LoopLearned -> tr(
            "한 바퀴 ${km(c.lapM)}를 ${duration(c.lapMs)}에 돌았어요. 같은 길로 계속 돌면 바퀴마다 알려드릴게요.",
            "That lap was ${km(c.lapM)} in ${duration(c.lapMs)}. Keep going round and I'll call each lap.",
        )
        is CourseCue.LapDone ->
            (if (c.total <= 0) tr("${c.done}바퀴 완료. ", "Lap ${c.done} done. ")
            else if (c.done >= c.total) tr("${c.total}바퀴 모두 돌았어요! ", "All ${c.total} laps done! ")
            else tr("${c.done}바퀴 완료, ${c.total - c.done}바퀴 남았어요. ", "Lap ${c.done} done, ${c.total - c.done} to go. ")) +
                (if (c.lapMs > 0 && c.lapM > 0) tr("이번 바퀴 ${duration(c.lapMs)}, 페이스 ${pace(c.lapMs / c.lapM)}.",
                    "This lap ${duration(c.lapMs)}, pace ${pace(c.lapMs / c.lapM)}.") else "")
        CourseCue.Halfway -> tr("코스 절반 왔어요.", "Halfway through the course.")
        CourseCue.Finished -> tr("코스 완주!", "Course complete!")
        is CourseCue.Rerouted ->
            if (c.toCourseM >= 30) tr("새 경로를 찾았어요. 코스까지 ${c.toCourseM}미터, 안내를 따라오세요.",
                "New route found. ${c.toCourseM} meters back to the course, follow the guidance.")
            else tr("코스 바로 옆이에요. 이어서 안내할게요.", "You're right by the course. Guidance continues.")
        CourseCue.Locating -> tr("현재 위치를 찾고 있어요. 찾으면 바로 코스 안내를 시작할게요.",
            "Finding your location. Course guidance starts as soon as I have it.")
        CourseCue.RerouteNoFix -> tr("아직 현재 위치를 몰라서 경로를 찾을 수 없어요. 잠시 후 다시 눌러 주세요.",
            "I don't know where you are yet, so I can't find a route. Please try again in a moment.")
    }

    private fun goalPhrase(goal: Goal) = when (goal) {
        Goal.Free -> ""
        is Goal.Distance -> tr(" 목표 ${km(goal.meters)}.", " Goal ${km(goal.meters)}.")
        is Goal.Time -> tr(" 목표 ${duration(goal.ms)}.", " Goal ${duration(goal.ms)}.")
        is Goal.Pace -> tr(" 목표 페이스 ${pace(goal.secPerKm.toDouble())}.", " Target pace ${pace(goal.secPerKm.toDouble())}.")
    }

    /** "1시간 2분 3초", "31분 4초", "45초", "30분" (zero parts are dropped); in English, "31 minutes 4 seconds". */
    fun duration(ms: Long): String {
        val total = (ms / 1000.0).roundToLong()
        val h = total / 3600
        val m = (total % 3600) / 60
        val s = total % 60
        fun en(n: Long, unit: String) = "$n $unit" + if (n == 1L) "" else "s"
        val parts = buildList {
            if (h > 0) add(tr("${h}시간", en(h, "hour")))
            if (m > 0) add(tr("${m}분", en(m, "minute")))
            if (s > 0 || (h == 0L && m == 0L)) add(tr("${s}초", en(s, "second")))
        }
        return parts.joinToString(" ")
    }

    fun pace(secPerKm: Double) = duration((secPerKm * 1000).roundToLong())

    /** "5킬로미터", "1.5킬로미터", "1.62킬로미터" / "1.5 kilometers". */
    fun km(meters: Double): String {
        val km = BigDecimal(meters / 1000.0).setScale(2, RoundingMode.HALF_UP).stripTrailingZeros()
        val plain = km.toPlainString()
        return tr("${plain}킬로미터", "$plain " + if (plain == "1") "kilometer" else "kilometers")
    }
}
