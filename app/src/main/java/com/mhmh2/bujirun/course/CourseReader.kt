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
            val name = wholeName(s, sp.start, sp.end)
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
            laps = rules.laps,
            repeat = rules.repeat,
            shade = rules.shade,
        )
    }

    companion object {
        /**
         * The place a span points at, as the whole word(s) it falls in: the model sometimes stops
         * inside a name ("맘스" of "맘스터치"), so the span is widened to the spaces either side,
         * then a particle or a glued role word at the end comes off ("반포까지" -> "반포").
         */
        internal fun wholeName(s: String, start: Int, end: Int): String {
            var a = start
            var b = end
            while (a > 0 && !s[a - 1].isWhitespace()) a--
            while (b < s.length && !s[b].isWhitespace()) b++
            var name = s.substring(a, b).replace(" ", "")
            for (tail in TAILS) {
                if (name.length > tail.length && name.endsWith(tail)) { name = name.dropLast(tail.length); break }
            }
            return name
        }

        // Longest first, so "에서부터" goes before "에서" and "부터".
        private val TAILS = listOf(
            "에서부터", "까지만", "근처에서", "주변에서", "따라서", "에서", "까지", "부터", "으로", "이랑", "근처", "주변", "따라",
            "로", "을", "를", "은", "는", "이", "가", "에", "랑", "와", "과", "도", "만", "쪽",
        ).sortedByDescending { it.length }

        /** Where the user is now; the course starts here anyway, nothing to look up. */
        private val HERE = setOf("집", "우리집", "회사", "숙소", "호텔", "학교", "여기", "거기", "지금위치", "현위치")
    }
}
