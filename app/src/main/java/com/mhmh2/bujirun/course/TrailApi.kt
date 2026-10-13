package com.mhmh2.bujirun.course

import com.mhmh2.bujirun.tr

import com.mhmh2.bujirun.account.Account
import com.mhmh2.bujirun.net.Api
import com.mhmh2.bujirun.net.ApiException
import org.json.JSONArray

/** An official walking trail (Durunubi), as listed near the user. */
data class Trail(
    val id: String,
    val name: String,
    val region: String,
    val distanceM: Double,
    val awayM: Double,
    val level: Int?,
    val hours: Double?,
    val loop: Boolean,
    val summary: String,
    val preview: List<GeoPoint>,
) {
    val levelLabel: String? get() = when (level) { 1 -> tr("쉬움", "Easy"); 2 -> tr("보통", "Moderate"); 3 -> tr("어려움", "Hard"); else -> null }
}

/** Official walking trails from our server (a copy of 한국관광공사 두루누비). */
object TrailApi {
    const val SOURCE = "한국관광공사 두루누비"

    suspend fun nearby(at: GeoPoint): List<Trail> {
        val json = Api.get("/trails/nearby?lat=${at.lat}&lon=${at.lon}", token())
        val arr = json.getJSONArray("trails")
        return (0 until arr.length()).map { i ->
            val t = arr.getJSONObject(i)
            Trail(
                id = t.getString("id"),
                name = t.getString("name"),
                region = t.optString("region"),
                distanceM = t.getDouble("distance_m"),
                awayM = t.optDouble("away_m", 0.0),
                level = if (t.isNull("level")) null else t.getInt("level"),
                hours = if (t.isNull("hours")) null else t.getDouble("hours"),
                loop = t.optBoolean("loop"),
                summary = t.optString("summary"),
                preview = points(t.getJSONArray("preview")),
            )
        }
    }

    /** The full trail line (with altitude where the GPX has it). */
    suspend fun points(id: String): List<GeoPoint> = points(Api.get("/trails/$id", token()).getJSONArray("points"))

    private fun token() = Account.token ?: throw ApiException(tr("다시 로그인해 주세요.", "Please log in again."), 401)

    private fun points(arr: JSONArray) = (0 until arr.length()).map { i ->
        val p = arr.getJSONArray(i)
        GeoPoint(p.getDouble(0), p.getDouble(1), if (p.length() > 2 && !p.isNull(2)) p.getDouble(2) else null)
    }
}
