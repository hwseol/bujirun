package com.mhmh2.bujirun.course

import com.mhmh2.bujirun.tracking.Activity
import kotlin.math.roundToInt

/**
 * Rule-based reading of a course request ("한강 따라 5km", "남산까지 갔다가 오기", "공원 돌아서
 * 한 시간 반"). Runs on the phone, no server or model. Numbers and units are always read here
 * (rules can't get "한 시간 반" wrong); the on-device model planned in docs/NL_COURSES.md takes
 * over the fuzzy parts - spacing, typos, word order, place names - and falls back to this.
 *
 * Conservative on place names: a wrong place sends the planner somewhere the user never meant,
 * while a missed one just shows no chip and the user can still get a nearby course.
 */
object IntentParser {

    fun parse(text: String): CourseIntent {
        val normalized = text.lowercase().replace("㎞", "km")
            .replace(Regex("""(\d),(\d{3})"""), "$1$2")
        val quantities = readQuantities(normalized)
        // Quantities are blanked out so "5km" or "삼십분" can't be mistaken for a place.
        val rest = quantities.spans.fold(StringBuilder(normalized)) { sb, r ->
            for (i in r) sb.setCharAt(i, ' '); sb
        }.toString()

        val places = readPlaces(rest)
        return CourseIntent(
            distanceM = quantities.distanceM,
            durationMin = quantities.durationMin,
            shape = readShape(rest, places),
            themes = readThemes(rest),
            places = places,
            activity = when {
                RUN_WORDS.containsMatchIn(rest) -> Activity.RUN
                WALK_WORDS.containsMatchIn(rest) -> Activity.WALK
                else -> null
            },
            flat = FLAT_WORDS.containsMatchIn(rest),
            laps = lapCount(text),
            repeat = REPEAT_WORDS.containsMatchIn(rest) || (lapCount(text) ?: 0) >= 2,
        )
    }

    // ---- numbers and units -------------------------------------------------------------

    private class Quantities(val distanceM: Int?, val durationMin: Int?, val spans: List<IntRange>)

    private const val SINO = "[일이삼사오육칠팔구십백천]+"
    private const val NATIVE = "(?:열|스물|스무|서른|마흔|쉰)?(?:하나|한|둘|두|셋|세|넷|네|다섯|여섯|일곱|여덟|아홉)|열|스물|스무|서른|마흔|쉰"
    private val QUANTITY = Regex(
        """(\d+(?:\.\d+)?|(?<![가-힣])(?:$NATIVE|$SINO))\s?""" +
            """(킬로미터|키로미터|킬로|키로|km|k(?![a-z])|미터|m(?![a-z])|시간|분)(\s?반)?"""
    )
    private val HALF_HOUR = Regex("""(?<![가-힣])반\s?시간""")
    private val NAMED_DISTANCES = listOf(
        Regex("하프\\s?(?:마라톤|코스)?") to 21_097,
        Regex("풀\\s?(?:마라톤|코스)|마라톤\\s?풀") to 42_195,
    )

    private fun readQuantities(s: String): Quantities {
        var meters: Double? = null
        var minutes: Double? = null
        val spans = mutableListOf<IntRange>()
        for (m in QUANTITY.findAll(s)) {
            val value = number(m.groupValues[1]) ?: continue
            val half = if (m.groupValues[3].isNotBlank()) 0.5 else 0.0
            when (m.groupValues[2]) {
                "시간" -> minutes = (minutes ?: 0.0) + (value + half) * 60
                "분" -> minutes = (minutes ?: 0.0) + value
                "미터", "m" -> meters = value
                else -> meters = (value + half) * 1000
            }
            spans += m.range
        }
        HALF_HOUR.find(s)?.let { minutes = (minutes ?: 0.0) + 30; spans += it.range }
        if (meters == null) for ((re, m) in NAMED_DISTANCES) re.find(s)?.let { meters = m.toDouble(); spans += it.range }
        return Quantities(
            meters?.takeIf { it in 100.0..100_000.0 }?.roundToInt(),
            minutes?.takeIf { it in 1.0..600.0 }?.roundToInt(),
            spans,
        )
    }

    private val SINO_DIGITS = "일이삼사오육칠팔구".withIndex().associate { (i, c) -> c to i + 1 }
    private val SINO_UNITS = mapOf('십' to 10, '백' to 100, '천' to 1000)
    private val NATIVE_TENS = mapOf("열" to 10, "스물" to 20, "스무" to 20, "서른" to 30, "마흔" to 40, "쉰" to 50)
    private val NATIVE_ONES = mapOf(
        "하나" to 1, "한" to 1, "둘" to 2, "두" to 2, "셋" to 3, "세" to 3, "넷" to 4, "네" to 4,
        "다섯" to 5, "여섯" to 6, "일곱" to 7, "여덟" to 8, "아홉" to 9,
    )

    /** "5", "1.5", "삼십", "이천오백", "한", "열다섯" -> the number; null if it isn't one. */
    internal fun number(word: String): Double? {
        word.toDoubleOrNull()?.let { return it }
        NATIVE_TENS.entries.firstOrNull { word.startsWith(it.key) }?.let { (tens, v) ->
            val ones = word.removePrefix(tens)
            return if (ones.isEmpty()) v.toDouble() else NATIVE_ONES[ones]?.let { (v + it).toDouble() }
        }
        NATIVE_ONES[word]?.let { return it.toDouble() }
        var total = 0
        var digit = 0
        for (c in word) {
            val d = SINO_DIGITS[c]
            val u = SINO_UNITS[c]
            when {
                d != null -> digit = d
                u != null -> { total += (if (digit == 0) 1 else digit) * u; digit = 0 }
                else -> return null
            }
        }
        return (total + digit).toDouble().takeIf { it > 0 }
    }

    // ---- shape, themes, activity -------------------------------------------------------

    private val OUT_AND_BACK = Regex("왕복|찍고|다녀오|다녀와|갔다\\s?(?:가|와|오)|갔다가|되돌아")
    private val LOOP_WORDS = Regex("순환|한\\s?바퀴|바퀴|돌기|돌아서|돌고|빙\\s?돌")
    private val COME_BACK = Regex("돌아오|돌아와")

    private fun readShape(s: String, places: List<PlaceMention>): CourseShape = when {
        OUT_AND_BACK.containsMatchIn(s) -> CourseShape.OUT_AND_BACK
        places.any { it.role == PlaceRole.DEST } ->
            if (COME_BACK.containsMatchIn(s)) CourseShape.OUT_AND_BACK else CourseShape.ONE_WAY
        else -> CourseShape.LOOP
    }

    private val THEME_WORDS = listOf(
        CourseTheme.RIVER to Regex("한강|강변|강가|강\\s?따라|하천|천변|냇가|개울|물길|[가-힣]천\\s?(?:따라|길|변)"),
        CourseTheme.PARK to Regex("공원|숲|수목원|둘레길"),
        CourseTheme.LAKE to Regex("호수|저수지"),
        CourseTheme.COAST to Regex("바다|해변|해안|바닷가|해수욕장"),
        CourseTheme.SIGHTS to Regex("명소|관광|구경|볼거리|유적|고궁"),
    )

    private fun readThemes(s: String): Set<CourseTheme> =
        THEME_WORDS.filter { (_, re) -> re.containsMatchIn(s) }.map { it.first }.toSet()

    private val RUN_WORDS = Regex("달리|달릴|뛰|뛸|러닝|런닝|조깅")
    private val WALK_WORDS = Regex("걷|걸어|걸을|산책|워킹|산보")
    // "한 바퀴" alone is just "a loop" (동네 한바퀴); two or more, or 뺑뺑이/트랙, means laps.
    private val REPEAT_WORDS = Regex("뺑뺑|빙빙|트랙|반복")
    private val LAP_COUNT = Regex("(\\d+|$NATIVE)\\s?바퀴")

    /** "세 바퀴", "3바퀴" -> 3; "한 바퀴" -> null (not a count, see REPEAT_WORDS). */
    internal fun lapCount(text: String): Int? =
        LAP_COUNT.find(text.lowercase())?.groupValues?.get(1)?.let { number(it) }?.toInt()?.takeIf { it in 2..30 }

    private val FLAT_WORDS = Regex("평지|평평|오르막\\s?(?:없|말고|피해|빼)|언덕\\s?(?:없|말고|피해|빼)|경사\\s?(?:없|말고|피해|빼)")

    // ---- places ------------------------------------------------------------------------

    /** Endings that make a word a place by itself ("서울숲", "석촌호수", "광화문", "남산"). */
    private val PLACE_SUFFIX = Regex(
        "(?:공원|숲|산|강|천|호수|저수지|역|궁|문|타워|시장|해변|해수욕장|대교|다리|섬|광장|수목원|" +
            "운동장|경기장|둘레길|항|포구|사|성|대학교|대학|학교|봉|폭포|계곡|정|원)$"
    )

    /** Particles stripped from a word's end, longest first, with the role they suggest. */
    private val PARTICLES = listOf(
        "에서부터" to PlaceRole.START, "까지만" to PlaceRole.DEST, "까지" to PlaceRole.DEST,
        "에서" to PlaceRole.AROUND, "부터" to PlaceRole.START, "으로" to PlaceRole.DEST,
        "이랑" to null, "하고" to null, "로" to PlaceRole.DEST, "을" to null, "를" to null,
        "은" to null, "는" to null, "이" to null, "가" to null, "에" to PlaceRole.DEST,
        "의" to null, "랑" to null, "와" to null, "과" to null, "도" to null, "만" to null,
    )

    /** Words after a place that say how it's used. */
    private val ROLE_WORDS = mapOf(
        "근처" to PlaceRole.AROUND, "주변" to PlaceRole.AROUND, "쪽" to PlaceRole.AROUND,
        "따라" to PlaceRole.AROUND, "따라서" to PlaceRole.AROUND, "앞" to PlaceRole.AROUND,
        "한바퀴" to PlaceRole.AROUND, "돌기" to PlaceRole.AROUND, "들러서" to PlaceRole.AROUND,
        "찍고" to PlaceRole.DEST, "가는" to PlaceRole.DEST, "갔다가" to PlaceRole.DEST,
        "갔다" to PlaceRole.DEST, "가기" to PlaceRole.DEST, "다녀오기" to PlaceRole.DEST,
        "출발" to PlaceRole.START, "출발해서" to PlaceRole.START, "시작" to PlaceRole.START,
    )

    /** Words that are never place names on their own, including theme words used generically. */
    private val NOT_PLACES = setOf(
        "집", "우리집", "여기", "거기", "근처", "주변", "동네", "우리", "가까운", "가까이", "근방", "회사",
        "숙소", "호텔", "지금", "현재", "위치", "내", "코스", "길", "달리기", "러닝", "조깅", "산책", "걷기",
        "뛰기", "따라", "한바퀴", "바퀴", "정도", "쯤", "가볍게", "천천히", "빠르게", "짜줘", "만들어줘",
        "추천", "해줘", "좀", "그냥", "오늘", "아침", "저녁", "점심", "밤", "새벽", "퇴근", "출근", "후",
        "전", "왕복", "순환", "편도", "출발", "도착", "다시", "평지", "오르막", "언덕", "없이", "명소", "관광",
        "구경", "공원", "숲", "강", "강변", "강가", "한강", "하천", "호수", "바다", "해변", "해안", "산",
        "역", "다리", "시장", "산책로", "트랙", "운동장", "큰", "작은", "예쁜", "조용한", "좋은", "유명한",
        "주요", "가장", "제일", "여러", "몇", "군데", "곳", "들러서", "들르기", "돌아서", "돌고", "돌기",
    )

    private fun readPlaces(s: String): List<PlaceMention> {
        val words = s.split(Regex("[\\s,.!?~]+")).filter { it.isNotEmpty() }
        val found = mutableListOf<PlaceMention>()
        var i = 0
        while (i < words.size) {
            var (core, role) = stripParticle(words[i])
            // "올림픽 공원", "서울 숲": a generic place word right after a name joins it.
            if (role == null && i + 1 < words.size && core !in NOT_PLACES && core.isHangulWord()) {
                val (nextCore, nextRole) = stripParticle(words[i + 1])
                if (nextCore in JOINABLE) { core += nextCore; role = nextRole; i++ }
            }
            val follower = words.getOrNull(i + 1)?.let { ROLE_WORDS[it] }
            val effectiveRole = role ?: follower
            val looksLikePlace = core.length >= 2 && core.isHangulWord() && core !in NOT_PLACES &&
                (PLACE_SUFFIX.containsMatchIn(core) || effectiveRole != null) &&
                ROLE_WORDS[core] == null && !isVerbish(core)
            if (looksLikePlace && found.none { it.name == core }) {
                found += PlaceMention(core, effectiveRole ?: PlaceRole.AROUND)
            }
            i++
        }
        return found
    }

    private val JOINABLE = setOf("공원", "숲", "호수", "역", "시장", "타워", "광장", "대교", "둘레길", "수목원", "해수욕장")

    private fun stripParticle(word: String): Pair<String, PlaceRole?> {
        for ((p, role) in PARTICLES) {
            if (word.length > p.length + 1 && word.endsWith(p)) {
                val core = word.dropLast(p.length)
                // "강가", "어린이" etc.: don't strip when the whole word is a known place word.
                if (word in NOT_PLACES || PLACE_SUFFIX.containsMatchIn(word) && !PLACE_SUFFIX.containsMatchIn(core)) {
                    return word to null
                }
                return core to role
            }
        }
        return word to null
    }

    private fun String.isHangulWord() = all { it in '가'..'힣' || it.isLetterOrDigit() } && any { it in '가'..'힣' }

    /** Verb and adverb forms ("뛰고", "걸을래", "달리고싶어") that the role check could let through. */
    private fun isVerbish(w: String) =
        RUN_WORDS.containsMatchIn(w) || WALK_WORDS.containsMatchIn(w) || OUT_AND_BACK.containsMatchIn(w) ||
            LOOP_WORDS.containsMatchIn(w) || COME_BACK.containsMatchIn(w) ||
            Regex("(?:고|게|서|요|줘|래|자|기|함|싶어|하는|하고|한|할)$").containsMatchIn(w) && !PLACE_SUFFIX.containsMatchIn(w)
}
