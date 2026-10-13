package com.mhmh2.bujirun.ui

import com.mhmh2.bujirun.Lang
import java.time.format.DateTimeFormatter

import com.mhmh2.bujirun.data.WorkoutEntity
import java.time.Instant
import java.time.ZoneId
import java.time.format.TextStyle
import java.util.Locale

/** "오늘 아침 러닝", "어제 저녁 걷기", "10월 2일 낮 러닝". */
fun workoutTitle(w: WorkoutEntity, now: Long = System.currentTimeMillis()): String {
    val zone = ZoneId.systemDefault()
    val at = Instant.ofEpochMilli(w.startedAt).atZone(zone)
    val today = Instant.ofEpochMilli(now).atZone(zone).toLocalDate()
    if (Lang.english) {
        val day = when (at.toLocalDate()) {
            today -> "Today"
            today.minusDays(1) -> "Yesterday"
            else -> at.format(DateTimeFormatter.ofPattern("MMM d", Locale.US))
        }
        val part = when (at.hour) {
            in 0..4 -> "Early morning"
            in 5..10 -> "Morning"
            in 11..16 -> "Afternoon"
            in 17..20 -> "Evening"
            else -> "Night"
        }
        return "$part ${if (w.isRun) "run" else "walk"} · $day" + if (w.simulated) TEST_SUFFIX_EN else ""
    }
    val day = when (at.toLocalDate()) {
        today -> "오늘"
        today.minusDays(1) -> "어제"
        else -> "${at.monthValue}월 ${at.dayOfMonth}일"
    }
    val part = when (at.hour) {
        in 0..4 -> "새벽"
        in 5..10 -> "아침"
        in 11..16 -> "낮"
        in 17..20 -> "저녁"
        else -> "밤"
    }
    return "$day $part ${if (w.isRun) "러닝" else "걷기"}" + if (w.simulated) TEST_SUFFIX_KO else ""
}

private const val TEST_SUFFIX_KO = " (테스트)"
private const val TEST_SUFFIX_EN = " (test)"

/** A workout title without the test-run mark (for a course named after it). */
fun String.withoutTestMark() = removeSuffix(TEST_SUFFIX_KO).removeSuffix(TEST_SUFFIX_EN)

/** "2026년 10월 5일 (월) 오전 7:12" / "Mon, Oct 5, 2026 7:12 AM". */
fun workoutDate(w: WorkoutEntity): String {
    val at = Instant.ofEpochMilli(w.startedAt).atZone(ZoneId.systemDefault())
    if (Lang.english) return at.format(DateTimeFormatter.ofPattern("EEE, MMM d, yyyy h:mm a", Locale.US))
    val dow = at.dayOfWeek.getDisplayName(TextStyle.SHORT, Locale.KOREAN)
    val ampm = if (at.hour < 12) "오전" else "오후"
    val h12 = (at.hour % 12).let { if (it == 0) 12 else it }
    return "${at.year}년 ${at.monthValue}월 ${at.dayOfMonth}일 ($dow) $ampm $h12:${"%02d".format(at.minute)}"
}

/** "오후 3:12" / "3:12 PM". */
fun clockTime(ms: Long): String {
    val t = Instant.ofEpochMilli(ms).atZone(ZoneId.systemDefault())
    if (Lang.english) return t.format(DateTimeFormatter.ofPattern("h:mm a", Locale.US))
    return "${if (t.hour < 12) "오전" else "오후"} ${(t.hour + 11) % 12 + 1}:${"%02d".format(t.minute)}"
}
