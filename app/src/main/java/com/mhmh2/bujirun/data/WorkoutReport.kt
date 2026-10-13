package com.mhmh2.bujirun.data

import com.mhmh2.bujirun.tracking.Split
import com.mhmh2.bujirun.tracking.formatDuration
import com.mhmh2.bujirun.tracking.formatKm
import com.mhmh2.bujirun.tracking.formatPace
import kotlin.math.roundToInt

/**
 * A workout in a few lines of text: the calendar event's title and notes, and what "결과 공유"
 * hands to any other app (messages, notes...). Pure, so it's tested like the rest.
 */
object WorkoutReport {
    fun title(w: WorkoutEntity): String =
        (if (w.isRun) "🏃 부지런 러닝" else "🚶 부지런 걷기") + " ${formatKm(w.distanceM)}km · ${formatDuration(w.durationMs)}"

    /**
     * The route in Google Maps (a directions link through points of it): opens in the Maps app
     * from the calendar event or a message. Maps finds its own walking way between the points,
     * so it's close to the route, not the GPS trace itself. Null for less than two points.
     */
    fun mapsUrl(points: List<Pair<Double, Double>>): String? {
        if (points.size < 2) return null
        fun f(p: Pair<Double, Double>) = "%.6f,%.6f".format(java.util.Locale.ROOT, p.first, p.second)
        // Up to 8 points in between (Maps takes 9; fewer keeps the link short).
        val inner = (1..8).map { points[(points.size - 1) * it / 9] }.distinct()
        return "https://www.google.com/maps/dir/?api=1&travelmode=walking" +
            "&origin=${f(points.first())}&destination=${f(points.last())}" +
            "&waypoints=" + inner.joinToString("%7C") { f(it) }
    }

    fun text(w: WorkoutEntity, courseName: String? = null, splits: List<Split> = emptyList(), mapsUrl: String? = null): String = buildList {
        add(title(w))
        val pace = if (w.distanceM >= 20) w.durationMs / 1000.0 / (w.distanceM / 1000.0) else null
        add("평균 페이스 ${formatPace(pace)}/km · 칼로리 약 ${w.calories.roundToInt()}kcal · 오르막 ${w.ascentM.roundToInt()}m")
        courseName?.let { add("코스: $it") }
        if (splits.isNotEmpty()) {
            // "1km 5'58"" for each full kilometre, the last part by its length ("+0.21km 6'10"").
            add("구간: " + splits.joinToString(" · ") {
                (if (it.distanceM >= 999) "${it.index}km" else "+${formatKm(it.distanceM)}km") + " ${formatPace(it.paceSecPerKm)}"
            })
        }
        mapsUrl?.let { add("지도에서 경로 보기: $it") }
        add("부지런으로 기록했어요")
    }.joinToString("\n")
}
