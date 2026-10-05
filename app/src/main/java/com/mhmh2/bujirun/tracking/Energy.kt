package com.mhmh2.bujirun.tracking

/**
 * Calories from the ACSM metabolic equations, which account for speed, walking vs running, and
 * uphill grade:
 *   walking VO2 = 0.1 * v + 1.8 * v * grade + 3.5
 *   running VO2 = 0.2 * v + 0.9 * v * grade + 3.5     (v in m/min, VO2 in ml/kg/min)
 *   kcal/min = VO2 * weight(kg) / 200
 * Downhill is treated as flat - the equations aren't valid below zero grade, and it keeps the
 * estimate conservative.
 */
object Calories {
    fun kcal(activity: Activity, distanceM: Double, seconds: Double, grade: Double, weightKg: Double): Double {
        if (seconds <= 0) return 0.0
        val vMetersPerMin = distanceM / (seconds / 60.0)
        val g = grade.coerceIn(0.0, 0.15)
        val running = when (activity) {
            Activity.RUN -> true
            Activity.WALK -> false
            // Not classified yet: go by speed (~8 km/h is where people switch to running).
            Activity.UNKNOWN, Activity.STILL -> vMetersPerMin >= 134
        }
        val vo2 = if (running) 0.2 * vMetersPerMin + 0.9 * vMetersPerMin * g + 3.5
        else 0.1 * vMetersPerMin + 1.8 * vMetersPerMin * g + 3.5
        return vo2 * weightKg / 200.0 * (seconds / 60.0)
    }
}

/**
 * Total climb and current grade from GPS altitude. Phone GPS altitude is noisy by several metres,
 * so climb only counts once it exceeds a hysteresis band, and grade is measured over a stretch of
 * horizontal distance rather than fix to fix.
 */
class ElevationTracker(
    private val hysteresisM: Double = 3.0,
    private val gradeOverM: Double = 30.0,
) {
    var ascentM = 0.0
        private set
    var grade = 0.0
        private set

    private var climbRef: Double? = null
    private var gradeBase: Double? = null
    private var horizontal = 0.0

    fun add(altitudeM: Double?, horizontalStepM: Double) {
        if (altitudeM == null) return
        val ref = climbRef
        if (ref == null) {
            climbRef = altitudeM
        } else if (altitudeM > ref + hysteresisM) {
            ascentM += altitudeM - ref
            climbRef = altitudeM
        } else if (altitudeM < ref - hysteresisM) {
            climbRef = altitudeM
        }

        val base = gradeBase ?: altitudeM.also { gradeBase = it }
        horizontal += horizontalStepM
        if (horizontal >= gradeOverM) {
            grade = (altitudeM - base) / horizontal
            gradeBase = altitudeM
            horizontal = 0.0
        }
    }

    /** After a pause the runner may be somewhere else entirely; don't compute grade across it. */
    fun breakSegment() {
        gradeBase = null
        horizontal = 0.0
    }
}
