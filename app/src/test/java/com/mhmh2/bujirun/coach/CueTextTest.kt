package com.mhmh2.bujirun.coach

import org.junit.Assert.assertEquals
import org.junit.Test

class CueTextTest {
    @Test fun durations() {
        assertEquals("31분 4초", CueText.duration(1_864_000))
        assertEquals("30분", CueText.duration(1_800_000))
        assertEquals("45초", CueText.duration(45_000))
        assertEquals("0초", CueText.duration(0))
        assertEquals("1시간 2분 3초", CueText.duration(3_723_000))
        assertEquals("1시간", CueText.duration(3_600_000))
    }

    @Test fun kilometres() {
        assertEquals("5킬로미터", CueText.km(5000.0))
        assertEquals("1.5킬로미터", CueText.km(1500.0))
        assertEquals("1.62킬로미터", CueText.km(1618.0))
    }

    @Test fun courseSentences() {
        assertEquals("100미터 앞에서 왼쪽.", CueText.course(com.mhmh2.bujirun.course.CourseCue.TurnAhead(com.mhmh2.bujirun.course.TurnDirection.LEFT, 100)))
        assertEquals(
            "오른쪽입니다. 이어서 왼쪽 방향.",
            CueText.course(com.mhmh2.bujirun.course.CourseCue.TurnNow(com.mhmh2.bujirun.course.TurnDirection.RIGHT, com.mhmh2.bujirun.course.TurnDirection.SLIGHT_LEFT))
        )
        assertEquals("코스 남은 거리 2.5킬로미터.", CueText.course(com.mhmh2.bujirun.course.CourseCue.Remaining(2500.0)))
    }

    @Test fun sentences() {
        assertEquals("운동을 시작합니다. 목표 5킬로미터.", CueText.of(Cue.Started(Goal.Distance(5000.0))))
        assertEquals("운동을 시작합니다. 목표 페이스 6분.", CueText.of(Cue.Started(Goal.Pace(360))))
        assertEquals(
            "1킬로미터. 이번 구간 6분 12초. 총 시간 6분 12초.",
            CueText.of(Cue.Split(1000.0, 372_000, 372_000))
        )
        assertEquals("조금 빠르게. 지금 6분 30초 페이스예요.", CueText.of(Cue.PaceAdvice(true, 390.0)))
        assertEquals("목표 달성! 5킬로미터, 31분 4초.", CueText.of(Cue.GoalReached(5000.0, 1_864_000)))
        assertEquals("운동을 마쳤습니다. 총 시간 12초.", CueText.of(Cue.Finished(2.0, 12_000, null)))
        assertEquals(
            "운동을 마쳤습니다. 5킬로미터, 31분 4초. 평균 페이스 6분 13초. 약 312킬로칼로리.",
            CueText.of(Cue.Finished(5000.0, 1_864_000, 372.8, 311.6))
        )
    }
}
