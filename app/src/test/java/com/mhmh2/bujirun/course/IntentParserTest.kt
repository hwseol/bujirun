package com.mhmh2.bujirun.course

import com.mhmh2.bujirun.tracking.Activity
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class IntentParserTest {
    private fun p(s: String) = IntentParser.parse(s)

    @Test fun distancesInEveryCommonSpelling() {
        mapOf(
            "5km" to 5000, "5 km" to 5000, "5키로" to 5000, "5킬로" to 5000, "3.5킬로미터" to 3500,
            "10k 뛰기" to 10000, "800m만" to 800, "800미터" to 800, "오 킬로" to 5000, "오천 미터" to 5000,
            "1.5키로 반" to 2000, "하프 마라톤" to 21_097, "풀코스" to 42_195, "5㎞" to 5000,
        ).forEach { (text, m) -> assertEquals(text, m, p(text).distanceM) }
    }

    @Test fun durations() {
        mapOf(
            "30분" to 30, "삼십분" to 30, "한 시간" to 60, "한시간 반" to 90, "1시간30분" to 90,
            "두 시간" to 120, "반시간" to 30, "열다섯 분" to 15, "45분 정도" to 45,
        ).forEach { (text, min) -> assertEquals(text, min, p(text).durationMin) }
    }

    @Test fun numbersInsideWordsAreNotQuantities() {
        // "이" in "어린이", "세" in "세종" are not numbers; no unit follows "한바퀴".
        assertNull(p("어린이공원 한바퀴").distanceM)
        assertNull(p("어린이공원 한바퀴").durationMin)
        assertNull(p("세종문화회관 구경").distanceM)
    }

    @Test fun durationBecomesDistanceAtTheUsersSpeed() {
        assertEquals(4500, p("30분 달리기").targetDistanceM(runPaceSecPerKm = 400))
        assertEquals(5000, p("30분 달리기").targetDistanceM(runPaceSecPerKm = 360))
        assertEquals(3000, p("30분 산책").targetDistanceM())
        assertEquals(5000, p("30분 5km").targetDistanceM()) // an explicit distance wins
        assertEquals(CourseIntent.DEFAULT_DISTANCE_M, p("공원 한바퀴").targetDistanceM())
        assertNull(p("남산까지").targetDistanceM()) // the destination decides
    }

    @Test fun typicalPaceIsTheMedianOfRecentRealRuns() {
        val runs = listOf(5000.0 to 1_800_000L, 3000.0 to 1_200_000L, 10000.0 to 3_300_000L, 500.0 to 60_000L)
        assertEquals(360, CourseIntent.typicalRunPace(runs)) // 360, 400, 330 s/km; the 500 m run is ignored
        assertNull(CourseIntent.typicalRunPace(runs.take(2)))
        val walks = listOf(3000.0 to 1_800_000L, 2000.0 to 1_500_000L, 4000.0 to 2_640_000L)
        assertEquals(660, CourseIntent.typicalWalkPace(walks)) // 600, 750, 660 s/km
    }

    @Test fun themes() {
        assertEquals(setOf(CourseTheme.RIVER), p("한강 따라 5km").themes)
        assertEquals(setOf(CourseTheme.RIVER), p("양재천 따라 걷기").themes)
        assertEquals(setOf(CourseTheme.PARK), p("근처 공원 몇 군데 돌아서 30분").themes)
        assertEquals(setOf(CourseTheme.COAST), p("숙소에서 출발해 해변 따라").themes)
        assertEquals(setOf(CourseTheme.SIGHTS, CourseTheme.PARK), p("가까운 명소랑 공원 들르기").themes)
        assertTrue(p("인천에서 5km").themes.isEmpty()) // "인천" is a city, not a stream
    }

    @Test fun shapes() {
        assertEquals(CourseShape.LOOP, p("5km").shape)
        assertEquals(CourseShape.LOOP, p("공원 한바퀴").shape)
        assertEquals(CourseShape.ONE_WAY, p("남산까지 달리기").shape)
        assertEquals(CourseShape.OUT_AND_BACK, p("남산까지 갔다가 돌아오기").shape)
        assertEquals(CourseShape.OUT_AND_BACK, p("남산 찍고 오기").shape)
        assertEquals(CourseShape.OUT_AND_BACK, p("한강 따라 왕복 6km").shape)
        assertEquals(CourseShape.OUT_AND_BACK, p("광화문까지 다녀오기").shape)
    }

    @Test fun placesAndRoles() {
        assertEquals(listOf(PlaceMention("남산", PlaceRole.DEST)), p("남산까지 갔다가 돌아오기").places)
        assertEquals(listOf(PlaceMention("남산", PlaceRole.DEST)), p("남산 찍고 오기").places)
        assertEquals(listOf(PlaceMention("서울숲", PlaceRole.AROUND)), p("서울숲 한바퀴 5키로").places)
        assertEquals(listOf(PlaceMention("올림픽공원", PlaceRole.AROUND)), p("올림픽 공원에서 30분 뛰기").places)
        assertEquals(listOf(PlaceMention("석촌호수", PlaceRole.AROUND)), p("석촌호수 두 바퀴").places)
        assertEquals(listOf(PlaceMention("양재천", PlaceRole.AROUND)), p("양재천 따라 걷기").places)
        assertEquals(
            listOf(PlaceMention("반포한강공원", PlaceRole.START), PlaceMention("잠수교", PlaceRole.DEST)),
            p("반포한강공원에서부터 잠수교까지").places
        )
        assertEquals(PlaceMention("해운대", PlaceRole.AROUND), p("해운대에서 5km").places.single())
    }

    @Test fun genericWordsAreNotPlaces() {
        listOf(
            "한강 따라 5km", "근처 공원 몇 군데 돌아서 30분", "집 근처 가볍게 30분 뛰고 싶어",
            "숙소에서 출발해 해변 따라", "가까운 명소 들르기", "평지로 5km 달리고 싶어요",
            "퇴근하고 동네 한바퀴", "오늘 저녁에 산책 코스 추천해줘", "공원 돌아서 한 시간",
        ).forEach { assertTrue("$it -> ${p(it).places}", p(it).places.isEmpty()) }
    }

    @Test fun activityAndFlat() {
        assertEquals(Activity.RUN, p("5km 뛰기").activity)
        assertEquals(Activity.RUN, p("조깅 30분").activity)
        assertEquals(Activity.WALK, p("한강 산책").activity)
        assertNull(p("5km").activity)
        assertTrue(p("오르막 없이 5km").flat)
        assertTrue(p("평지로 달리고 싶어").flat)
        assertTrue(p("언덕 피해서 30분").flat)
        assertFalse(p("남산 오르막 훈련").flat)
    }

    @Test fun lapsAndRepeat() {
        assertEquals(3, p("석촌호수 세 바퀴").laps)
        assertEquals(5, p("트랙 5바퀴").laps)
        assertTrue(p("석촌호수 세 바퀴").repeat)
        assertTrue(p("보라매공원 뺑뺑 5키로").repeat)
        assertTrue(p("공원 5km 뺑뺑이").repeat)
        assertNull(p("동네 한바퀴").laps)       // "a loop", not a lap count
        assertFalse(p("동네 한바퀴").repeat)
        assertFalse(p("공원 돌아서 30분").repeat)  // a tour through parks
    }

    @Test fun shadeWords() {
        assertEquals(true, p("그늘 많은 길로 5km").shade)
        assertEquals(true, p("햇빛 피해서 30분").shade)
        assertEquals(true, p("나무 많은 시원한 코스").shade)
        assertNull(p("한강 따라 5km").shade) // not said: the server decides by the sun
    }

    @Test fun koreanNumbers() {
        assertEquals(30.0, IntentParser.number("삼십")!!, 0.0)
        assertEquals(2500.0, IntentParser.number("이천오백")!!, 0.0)
        assertEquals(10.0, IntentParser.number("십")!!, 0.0)
        assertEquals(15.0, IntentParser.number("열다섯")!!, 0.0)
        assertEquals(20.0, IntentParser.number("스무")!!, 0.0)
        assertNull(IntentParser.number("공원"))
    }
}
