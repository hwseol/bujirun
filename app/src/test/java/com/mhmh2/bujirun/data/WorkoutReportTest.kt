package com.mhmh2.bujirun.data

import com.mhmh2.bujirun.tracking.Split
import org.junit.Assert.assertEquals
import org.junit.Test

class WorkoutReportTest {
    private val run = WorkoutEntity(
        startedAt = 0, endedAt = 1_900_000, status = WorkoutEntity.DONE, goalType = "FREE", goalValue = 0.0,
        durationMs = 1_880_000, distanceM = 5210.0, calories = 321.4, ascentM = 24.0, runMs = 1_700_000, walkMs = 180_000,
    )

    @Test fun reportSaysWhatWasDone() {
        val splits = listOf(Split(1, 1000.0, 358_000), Split(2, 1000.0, 362_000), Split(3, 210.0, 77_700))
        assertEquals(
            listOf(
                "🏃 부지런 러닝 5.21km · 31:20",
                "평균 페이스 6'01\"/km · 칼로리 약 321kcal · 오르막 24m",
                "코스: 안양천 따라 순환",
                "구간: 1km 5'58\" · 2km 6'02\" · +0.21km 6'10\"",
                "부지런으로 기록했어요",
            ).joinToString("\n"),
            WorkoutReport.text(run, "안양천 따라 순환", splits),
        )
    }

    @Test fun mapsLinkGoesThroughTheRoute() {
        val pts = (0..90).map { 37.5 + it * 0.0001 to 127.0 }
        val url = WorkoutReport.mapsUrl(pts)!!
        assertEquals(true, url.startsWith("https://www.google.com/maps/dir/?api=1&travelmode=walking&origin=37.500000,127.000000"))
        assertEquals(true, url.contains("&destination=37.509000,127.000000"))
        assertEquals(8, url.substringAfter("&waypoints=").split("%7C").size)
        assertEquals(null, WorkoutReport.mapsUrl(pts.take(1)))
        assertEquals(true, WorkoutReport.text(run, mapsUrl = url).contains("지도에서 경로 보기: $url"))
    }

    @Test fun aWalkIsCalledAWalk() {
        assertEquals("🚶 부지런 걷기 5.21km · 31:20", WorkoutReport.title(run.copy(runMs = 100, walkMs = 1_000_000)))
    }
}
