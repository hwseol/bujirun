package com.mhmh2.bujirun.tracking

import java.util.Locale
import kotlin.math.roundToLong

/** 18:42, or 1:02:03 past an hour. */
fun formatDuration(ms: Long): String {
    val total = ms / 1000
    val h = total / 3600
    val m = (total % 3600) / 60
    val s = total % 60
    return if (h > 0) String.format(Locale.US, "%d:%02d:%02d", h, m, s)
    else String.format(Locale.US, "%02d:%02d", m, s)
}

/** 3.02 (km, two decimals - the convention every running app uses). */
fun formatKm(meters: Double): String = String.format(Locale.US, "%.2f", meters / 1000.0)

fun activityLabel(activity: Activity) = when (activity) {
    Activity.RUN -> "달리는 중"
    Activity.WALK -> "걷는 중"
    Activity.STILL -> "멈춤"
    Activity.UNKNOWN -> "기록 중"
}

/** 6'11" per km, or --'--" when there isn't enough data yet. */
fun formatPace(secPerKm: Double?): String {
    // Slower than 60 min/km is standing still, not a pace worth showing.
    if (secPerKm == null || secPerKm >= 3600) return "--'--\""
    val s = secPerKm.roundToLong()
    return String.format(Locale.US, "%d'%02d\"", s / 60, s % 60)
}
