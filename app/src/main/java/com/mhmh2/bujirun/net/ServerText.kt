package com.mhmh2.bujirun.net

import com.mhmh2.bujirun.Lang

/**
 * Texts from the server (course titles, course parts, notes, place names) in English. The
 * server writes Korean from a handful of fixed sentence patterns, so they're turned into
 * English here rather than taught to the server (its cached plans stay as they are); place
 * names are romanized (Revised Romanization) with their kind in English: 중앙공원 -> Jungang
 * Park, 안양천 -> Anyangcheon Stream. Pure, tested.
 */
object ServerText {
    /** [s] as shown: unchanged in Korean, English in English. */
    fun show(s: String): String = if (Lang.english) en(s) else s

    fun en(s0: String): String {
        var s = s0
        for ((ko, en) in PHRASES) s = s.replace(ko, en)
        if (!HANGUL.containsMatchIn(s)) return s
        for ((re, make) in PATTERNS) {
            val m = re.matchEntire(s.trim()) ?: continue
            return make(m.groupValues.drop(1))
        }
        return romanizeAll(s)
    }

    private val HANGUL = Regex("[가-힣]")

    // Fixed phrases (data credits), replaced wherever they appear.
    private val PHRASES = listOf(
        "공공데이터포털 전국도시공원정보표준데이터" to "Public Data Portal (city parks)",
        "한국관광공사 두루누비" to "Korea Tourism Organization (Durunubi)",
        "OpenStreetMap 기여자" to "OpenStreetMap contributors",
    )
    private val KM = "([0-9.]+km)"
    private val ANY = "(.+?)"

    // Most specific first. Groups that are place names go through place().
    private val PATTERNS: List<Pair<Regex, (List<String>) -> String>> = listOf(
        Regex("$ANY 트랙 ([0-9]+)바퀴 \\(한 바퀴 $KM\\)") to { g -> "${g[1]} laps of the ${place(g[0])} track (${g[2]} a lap)" },
        Regex("$ANY ([0-9]+)바퀴 \\(한 바퀴 $KM\\)") to { g -> "${g[1]} laps of ${place(g[0])} (${g[2]} a lap)" },
        Regex("$ANY 트랙 ([0-9]+)바퀴") to { g -> "${g[1]} laps of the ${place(g[0])} track" },
        Regex("$ANY ([0-9]+)바퀴") to { g -> "${g[1]} laps of ${place(g[0])}" },
        Regex("$ANY 트랙 한 바퀴") to { g -> "Once round the ${place(g[0])} track" },
        Regex("$ANY 한 바퀴") to { g -> "Once round ${place(g[0])}" },
        Regex("$ANY 따라 가는 길") to { g -> "Along ${place(g[0])}" },
        Regex("$ANY 따라 순환") to { g -> "Loop along ${place(g[0])}" },
        Regex("$ANY 따라 왕복") to { g -> "Out and back along ${place(g[0])}" },
        Regex("$ANY 주변 순환") to { g -> "Loop around ${place(g[0])}" },
        Regex("$ANY 왕복") to { g -> "Out and back to ${place(g[0])}" },
        Regex("$ANY(?:까지 가는 길)") to { g -> "To ${place(g[0])}" },
        Regex("$ANY(?:까지)") to { g -> "To ${place(g[0])}" },
        Regex("건너편 천변으로 돌아오는 길") to { _ -> "Back along the far bank" },
        Regex("되돌아오는 길") to { _ -> "Back the same way" },
        Regex("돌아오는 길") to { _ -> "Back" },
        Regex("순환 $KM") to { g -> "Loop ${g[0]}" },
        Regex("추천 순환 $KM") to { g -> "Suggested loop ${g[0]}" },
        Regex("내 코스 $KM") to { g -> "My course ${g[0]}" },
        Regex("$ANY 순환") to { g -> "Loop via " + g[0].split("·").joinToString(" and ") { place(it) } },
        // Notes and messages.
        Regex("'$ANY'을\\(를\\) 찾지 못했어요\\.") to { g -> "Couldn't find '${place(g[0])}'." },
        Regex("오르막 정보는 아직 반영하지 못했어요\\.") to { _ -> "Hills aren't taken into account yet." },
        Regex("지금 해가 높아요 \\(고도 ([0-9]+)°\\)\\. 나무 그늘이 많은 길 위주로 골랐어요\\.") to { g -> "The sun is high (${g[0]}°), so shady paths were preferred." },
        Regex("명소를 넣은 코스는 아직 준비 중이에요\\. 거리에 맞춘 순환 코스예요\\.") to { _ -> "Courses through sights aren't ready yet. Here's a loop of your distance." },
        Regex("알맞은 코스를 찾지 못했어요\\.") to { _ -> "Couldn't find a suitable course." },
        Regex("말씀하신 ${KM}가 아니라 ${KM}예요\\. 목적지까지의 거리라서요\\.") to { g -> "It's ${g[1]}, not ${g[0]}: that's how far the destination is." },
        Regex("근처에 강변이 없어요\\.") to { _ -> "There's no riverside nearby." },
        Regex("근처에 호수가 없어요\\.") to { _ -> "There's no lake nearby." },
        Regex("근처에 바닷가가 없어요\\.") to { _ -> "There's no seaside nearby." },
        Regex("${ANY}의 위치 정보가 없어요\\.") to { g -> "There's no location data for ${place(g[0])}." },
        Regex("${ANY}까지 편도 ${KM}라 강변 구간이 짧아요\\.") to { g -> "${place(g[0])} is ${g[1]} away, so the riverside part is short." },
        Regex("${ANY}까지 가고 오는 ${KM}가 거리에 포함돼 있어요\\.") to { g -> "The ${g[1]} to ${place(g[0])} and back is included." },
        Regex("근처에 공원이 없어서 거리에 맞춘 순환 코스예요\\.") to { _ -> "No park nearby, so here's a loop of your distance." },
        Regex("다시 로그인해 주세요\\.") to { _ -> "Please log in again." },
        Regex("잘못된 위치예요\\.") to { _ -> "That location isn't valid." },
        Regex("1~21km 사이로 골라 주세요\\.") to { _ -> "Please choose 1 to 21 km." },
        Regex("요청을 이해하지 못했어요\\.") to { _ -> "Couldn't understand the request." },
        Regex("코스를 찾을 수 없어요\\.") to { _ -> "Course not found." },
        Regex("오늘 경로 계산 횟수를 다 썼어요\\. 내일 다시 해 주세요\\.") to { _ -> "Today's route limit is used up. Please try again tomorrow." },
        Regex("경로를 찾지 못했어요\\.") to { _ -> "Couldn't find a route." },
        Regex("경로 계산을 지금 쓸 수 없어요\\.") to { _ -> "Routing isn't available right now." },
        Regex("경로 계산이 잠시 몰렸어요\\. 잠시 후 다시 해 주세요\\.") to { _ -> "Routing is busy. Please try again shortly." },
        Regex("이 두 지점 사이의 걷는 길을 찾지 못했어요\\.") to { _ -> "Couldn't find a walking path between these points." },
        Regex("경로를 계산하지 못했어요\\. 잠시 후 다시 해 주세요\\.") to { _ -> "Couldn't work out a route. Please try again shortly." },
        Regex("이 근처에서는 알맞은 순환 코스를 찾지 못했어요\\. 다른 거리로 해 보세요\\.") to { _ -> "No suitable loop here. Try another distance." },
    )

    // The kind of place at the end of a name, in English (longest first).
    private val KINDS = listOf(
        "호수공원" to "Lake Park", "근린공원" to "Park", "체육공원" to "Sports Park", "공원" to "Park",
        "대학교" to "University", "초등학교" to "Elementary School", "중학교" to "Middle School",
        "고등학교" to "High School", "학교" to "School", "운동장" to "Sports Ground", "해수욕장" to "Beach",
        "저수지" to "Reservoir", "호수" to "Lake", "광장" to "Plaza", "트랙" to "Track", "둘레길" to "Trail",
        "역" to "Station", "숲" to "Forest",
    )

    /** 중앙공원 -> Jungang Park, 안양천 -> Anyangcheon Stream, 한강 -> Hangang River. */
    fun place(name: String): String {
        val n = name.trim()
        if (!HANGUL.containsMatchIn(n)) return n
        for ((ko, en) in KINDS) {
            if (n.endsWith(ko) && n.length > ko.length) return "${romanizeAll(n.removeSuffix(ko).trim())} $en"
        }
        if (n.endsWith("천") && n.length > 1) return "${romanizeAll(n)} Stream"
        if (n.endsWith("강") && n.length > 1) return "${romanizeAll(n)} River"
        if (n.endsWith("산") && n.length > 1) return "${romanizeAll(n)} Mountain"
        return romanizeAll(n)
    }

    /** Every run of Hangul romanized, each word capitalized; the rest kept. */
    fun romanizeAll(s: String): String =
        Regex("[가-힣]+").replace(s) { m -> romanize(m.value).replaceFirstChar { it.uppercase() } }

    private val INITIAL = listOf("g", "kk", "n", "d", "tt", "r", "m", "b", "pp", "s", "ss", "", "j", "jj", "ch", "k", "t", "p", "h")
    private val MEDIAL = listOf("a", "ae", "ya", "yae", "eo", "e", "yeo", "ye", "o", "wa", "wae", "oe", "yo", "u", "wo", "we", "wi", "yu", "eu", "ui", "i")
    private val FINAL = listOf("", "k", "k", "k", "n", "n", "n", "t", "l", "k", "m", "l", "l", "l", "p", "l", "m", "p", "p", "t", "t", "ng", "t", "t", "k", "t", "p", "t")
    // A final consonant before a syllable starting with ㅇ carries over as that syllable's sound.
    private val CARRY = mapOf(1 to "g", 4 to "n", 7 to "d", 8 to "r", 16 to "m", 17 to "b", 19 to "s", 20 to "ss", 22 to "j", 23 to "ch", 25 to "t", 26 to "p")

    /** Revised Romanization, syllable by syllable with the common sound rules. */
    fun romanize(word: String): String {
        val syl = word.map { c -> (c - '가').let { Triple(it / 588, (it % 588) / 28, it % 28) } }
        val sb = StringBuilder()
        for (k in syl.indices) {
            val (i, m, f) = syl[k]
            val prevFinal = if (k > 0) syl[k - 1].third else 0
            val initial = when {
                i == 11 && k > 0 && prevFinal in CARRY -> CARRY.getValue(prevFinal)
                i == 5 && k > 0 && (prevFinal == 8 || prevFinal == 4) -> "l"  // ㄹㄹ, ㄴㄹ -> ll
                i == 5 && k > 0 && prevFinal != 0 -> "n"                     // 종로 -> Jongno
                else -> INITIAL[i]
            }
            sb.append(initial).append(MEDIAL[m])
            val next = syl.getOrNull(k + 1)
            val final = when {
                f == 0 -> ""
                next != null && next.first == 11 && f in CARRY -> ""         // carried over
                next != null && next.first == 5 && f == 4 -> "l"             // 신림 -> Sillim
                next != null && next.first == 2 && f == 8 -> "l"             // ㄹ+ㄴ -> ll
                else -> FINAL[f]
            }
            sb.append(final)
        }
        return sb.toString()
    }
}

/** A server text as shown in the current language (ServerText.show). */
fun srv(s: String): String = ServerText.show(s)

/** A place name as shown: romanized with its kind in English (ServerText.place). */
fun srvPlace(s: String): String = if (Lang.english) ServerText.place(s) else s
