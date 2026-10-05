package com.mhmh2.bujirun.coach

/** What the user set out to do before pressing start. */
sealed interface Goal {
    data object Free : Goal
    data class Distance(val meters: Double) : Goal
    data class Time(val ms: Long) : Goal
    data class Pace(val secPerKm: Int) : Goal

    /** 0..1 progress for distance/time goals; null when the goal has no end point. */
    fun progress(distanceM: Double, elapsedMs: Long): Float? = when (this) {
        is Distance -> (distanceM / meters).toFloat().coerceIn(0f, 1f)
        is Time -> (elapsedMs.toDouble() / ms).toFloat().coerceIn(0f, 1f)
        else -> null
    }

    // Flat (type, value) form, for intent extras and SharedPreferences.
    fun encode(): Pair<String, Double> = when (this) {
        Free -> "free" to 0.0
        is Distance -> "distance" to meters
        is Time -> "time" to ms.toDouble()
        is Pace -> "pace" to secPerKm.toDouble()
    }

    companion object {
        fun decode(type: String?, value: Double): Goal = when (type) {
            "distance" -> Distance(value)
            "time" -> Time(value.toLong())
            "pace" -> Pace(value.toInt())
            else -> Free
        }
    }
}

/** How often the regular progress announcement comes. */
sealed interface CueInterval {
    data class EveryDistance(val meters: Double) : CueInterval
    data class EveryTime(val ms: Long) : CueInterval
}
