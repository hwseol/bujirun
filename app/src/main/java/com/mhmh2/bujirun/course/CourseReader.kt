package com.mhmh2.bujirun.course

import com.mhmh2.bujirun.tracking.Activity

/**
 * Turns a course request into a [CourseIntent], combining the two readers by what each is good at:
 *  - numbers and units always come from the rules ([IntentParser]); they don't guess
 *  - places, their roles, the shape, themes, run/walk and "flat" come from the on-device model
 *    ([NluModel]), which copes with spacing, word order and names the rules don't know
 * Without a model (failed to load) it is the rules alone.
 *
 * [model] is a function so tests can stand in for the real network.
 */
class CourseReader(private val model: ((String) -> NluModel.Reading)?) {

    fun read(text: String): CourseIntent {
        val rules = IntentParser.parse(text)
        val m = model?.invoke(text) ?: return rules
        val s = NluModel.normalize(text)

        fun spanText(kind: String) = m.spans.firstOrNull { it.kind == kind }?.let { s.substring(it.start, it.end) }
        // The model can find a quantity the rules skipped ("오킬로" glued to a word); read the
        // number out of its span with the same rules.
        val distanceM = rules.distanceM ?: spanText("DIST")?.let { IntentParser.parse(it).distanceM }
        val durationMin = rules.durationMin ?: spanText("TIME")?.let { IntentParser.parse(it).durationMin }

        val places = m.spans.mapNotNull { sp ->
            val role = when (sp.kind) {
                "PS" -> PlaceRole.START
                "PD" -> PlaceRole.DEST
                "PA" -> PlaceRole.AROUND
                else -> return@mapNotNull null
            }
            val name = s.substring(sp.start, sp.end).replace(" ", "")
            if (name.length < 2 || name in HERE) null else PlaceMention(name, role)
        }.distinctBy { it.name }

        val hasDestination = places.any { it.role == PlaceRole.DEST }
        val shape = when (m.shape) {
            "OUT_AND_BACK" -> CourseShape.OUT_AND_BACK
            // One way needs somewhere to end; without a destination it's a loop.
            "ONE_WAY" -> if (hasDestination) CourseShape.ONE_WAY else CourseShape.LOOP
            else -> CourseShape.LOOP
        }
        return CourseIntent(
            distanceM = distanceM,
            durationMin = durationMin,
            shape = shape,
            // Rules only fire on explicit theme words, so they can only add what was said.
            themes = m.themes.map { CourseTheme.valueOf(it) }.toSet() + rules.themes,
            places = places,
            activity = when (m.activity) {
                "RUN" -> Activity.RUN
                "WALK" -> Activity.WALK
                else -> rules.activity
            },
            flat = m.flat || rules.flat,
        )
    }

    companion object {
        /** Where the user is now; the course starts here anyway, nothing to look up. */
        private val HERE = setOf("집", "우리집", "회사", "숙소", "호텔", "학교", "여기", "거기", "지금위치", "현위치")
    }
}
