package com.mhmh2.bujirun.course

import com.mhmh2.bujirun.tracking.Activity
import kotlin.math.roundToInt

/** Loop back to the start, go somewhere and come back the same way, or end somewhere else. */
enum class CourseShape(val label: String) { LOOP("순환"), OUT_AND_BACK("왕복"), ONE_WAY("편도") }

enum class CourseTheme(val label: String) { RIVER("강변"), PARK("공원"), LAKE("호수"), COAST("바닷가"), SIGHTS("명소") }

/** How a named place is used: where to start, where to go, or what to run in/around/along. */
enum class PlaceRole { START, DEST, AROUND }

/** [id]: a place picked from a list (공원 코스), so the server uses exactly it, not a name match. */
data class PlaceMention(val name: String, val role: PlaceRole, val id: String? = null)

/**
 * What the user asked for, in a form the course planner understands. Built from a sentence by
 * [IntentParser] (later also by the on-device model, docs/NL_COURSES.md section 6) and then
 * edited by the user through the chips, so every field is plain and independently changeable.
 */
data class CourseIntent(
    val distanceM: Int? = null,
    val durationMin: Int? = null,
    val shape: CourseShape = CourseShape.LOOP,
    val themes: Set<CourseTheme> = emptySet(),
    val places: List<PlaceMention> = emptyList(),
    val activity: Activity? = null,
    val flat: Boolean = false,
    /** Laps asked for by number ("세 바퀴"); null when not said. */
    val laps: Int? = null,
    /** Laps of one place rather than a tour (뺑뺑이, 트랙, or a lap count of two or more). */
    val repeat: Boolean = false,
    /** Prefer tree shade: true/false as asked, null = let the server decide by the sun. */
    val shade: Boolean? = null,
) {
    val destination: PlaceMention? get() = places.firstOrNull { it.role == PlaceRole.DEST }

    /**
     * The distance to plan for: as asked, else the time at the user's speed, else null when
     * heading to a destination (its distance decides), else [DEFAULT_DISTANCE_M].
     * [runPaceSecPerKm] is the user's own average when known.
     */
    fun targetDistanceM(runPaceSecPerKm: Int? = null): Int? {
        distanceM?.let { return it }
        durationMin?.let { min ->
            val secPerKm = if (activity == Activity.WALK) WALK_SEC_PER_KM else runPaceSecPerKm ?: RUN_SEC_PER_KM
            return (min * 60.0 / secPerKm * 1000 / 100).roundToInt() * 100
        }
        return if (destination != null) null else DEFAULT_DISTANCE_M
    }

    companion object {
        const val DEFAULT_DISTANCE_M = 3000
        const val WALK_SEC_PER_KM = 600 // 6 km/h
        const val RUN_SEC_PER_KM = 400  // 9 km/h, an easy jog

        /**
         * The user's usual running pace: median over their latest 10 runs of 1 km or more, given
         * as (distance m, active ms). Null with fewer than 3 such runs.
         */
        fun typicalRunPace(runs: List<Pair<Double, Long>>): Int? = typicalPace(runs, 180..900)

        /** The same for walks (a walk's plausible range is slower). */
        fun typicalWalkPace(walks: List<Pair<Double, Long>>): Int? = typicalPace(walks, 360..1500)

        private fun typicalPace(workouts: List<Pair<Double, Long>>, range: IntRange): Int? {
            val paces = workouts.filter { (m, ms) -> m >= 1000 && ms > 0 }.take(10)
                .map { (m, ms) -> ms / 1000.0 / (m / 1000) }.sorted()
            if (paces.size < 3) return null
            return paces[paces.size / 2].roundToInt().coerceIn(range)
        }
    }
}
