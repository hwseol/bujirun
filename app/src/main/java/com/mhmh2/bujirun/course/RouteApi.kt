package com.mhmh2.bujirun.course

import com.mhmh2.bujirun.account.Account
import com.mhmh2.bujirun.net.Api
import com.mhmh2.bujirun.net.ApiException
import org.json.JSONArray
import org.json.JSONObject

/** Walking routes and courses from our server (which asks TMAP where it must). */
object RouteApi {
    suspend fun walk(from: GeoPoint, to: GeoPoint): List<GeoPoint> {
        val token = Account.token ?: throw ApiException("다시 로그인해 주세요.", 401)
        val json = Api.post(
            "/routes/pedestrian",
            JSONObject().put("start", JSONArray().put(from.lat).put(from.lon)).put("end", JSONArray().put(to.lat).put(to.lon)),
            token,
        )
        return points(json.getJSONArray("points"))
    }

    /** A loop from here; [shade]: percent of it under trees, when the server has the shade map. */
    data class Loop(val points: List<GeoPoint>, val shade: Int?)

    /** Suggested loops of about [distanceM] that start and end at [start]. */
    suspend fun loops(start: GeoPoint, distanceM: Int): List<Loop> {
        val token = Account.token ?: throw ApiException("다시 로그인해 주세요.", 401)
        val json = Api.post(
            "/routes/loops",
            JSONObject().put("start", JSONArray().put(start.lat).put(start.lon)).put("distance_m", distanceM),
            token,
        )
        val loops = json.getJSONArray("loops")
        return (0 until loops.length()).map {
            val l = loops.getJSONObject(it)
            Loop(points(l.getJSONArray("points")), l.optIntOrNull("shade"))
        }
    }

    /** [shade]: percent of the course under trees (docs/SHADE.md), when known. */
    data class PlannedCourse(val title: String, val distanceM: Int, val points: List<GeoPoint>, val via: List<String>, val shade: Int?)

    /**
     * Courses for a request, plus anything the user should know and the data sources to credit.
     * [preferShade]: whether shade decided the choice (asked for, or the sun is high).
     */
    data class Plan(
        val courses: List<PlannedCourse>,
        val notes: List<String>,
        val sources: List<String>,
        val preferShade: Boolean,
    )

    /**
     * A course built around what the user asked for (backend planner.py): along a river, laps of
     * a park or lake, to a place and back, through parks. [distanceM] is the target already
     * worked out on the phone (time at the user's pace, or a default); null to a destination.
     */
    suspend fun plan(start: GeoPoint, intent: CourseIntent, distanceM: Int?): Plan {
        val token = Account.token ?: throw ApiException("다시 로그인해 주세요.", 401)
        val body = JSONObject()
            .put("shape", intent.shape.name)
            .put("themes", JSONArray(intent.themes.map { it.name }))
            .put("places", JSONArray(intent.places.map { p ->
                JSONObject().put("name", p.name).put("role", p.role.name).also { o -> p.id?.let { o.put("id", it) } }
            }))
            .put("flat", intent.flat)
            .put("repeat", intent.repeat)
        intent.laps?.let { body.put("laps", it) }
        intent.shade?.let { body.put("shade", it) }
        if (distanceM != null) body.put("distance_m", distanceM)
        val json = Api.post(
            "/routes/plan",
            JSONObject().put("start", JSONArray().put(start.lat).put(start.lon)).put("intent", body),
            token,
        )
        val courses = json.getJSONArray("courses")
        return Plan(
            courses = (0 until courses.length()).map { i ->
                val c = courses.getJSONObject(i)
                val via = c.optJSONArray("via") ?: JSONArray()
                PlannedCourse(c.getString("title"), c.getInt("distance_m"), points(c.getJSONArray("points")),
                    (0 until via.length()).map { via.getString(it) }, c.optIntOrNull("shade"))
            },
            notes = json.optJSONArray("notes").strings(),
            sources = json.optJSONArray("sources").strings(),
            preferShade = json.optBoolean("prefer_shade"),
        )
    }

    /** A park or lake nearby that can be lapped. [lapM]: once around its outline; [shade]: % of it wooded. */
    data class LapPark(val id: String, val name: String, val kind: String, val awayM: Int, val lapM: Int, val paths: Boolean, val shade: Int?)

    suspend fun lapParks(near: GeoPoint): Pair<List<LapPark>, List<String>> {
        val token = Account.token ?: throw ApiException("다시 로그인해 주세요.", 401)
        val json = Api.get("/places/parks?lat=${near.lat}&lon=${near.lon}", token)
        val arr = json.getJSONArray("places")
        return (0 until arr.length()).map { i ->
            val p = arr.getJSONObject(i)
            LapPark(p.getString("id"), p.getString("name"), p.getString("kind"), p.getInt("away_m"), p.getInt("lap_m"),
                p.optBoolean("paths"), p.optIntOrNull("shade"))
        } to json.optJSONArray("sources").strings()
    }

    /** A place found by name or address, to start from. */
    data class FoundPlace(val name: String, val address: String, val point: GeoPoint, val awayM: Int)

    /** Places matching [query] (names, stations, addresses), the best match first. */
    suspend fun lookup(query: String, near: GeoPoint): List<FoundPlace> {
        val token = Account.token ?: throw ApiException("다시 로그인해 주세요.", 401)
        val q = java.net.URLEncoder.encode(query, "UTF-8")
        val arr = Api.get("/places/lookup?q=$q&lat=${near.lat}&lon=${near.lon}", token).getJSONArray("places")
        return (0 until arr.length()).map { i ->
            val p = arr.getJSONObject(i)
            FoundPlace(p.getString("name"), p.optString("address"), GeoPoint(p.getDouble("lat"), p.getDouble("lon")), p.getInt("away_m"))
        }
    }

    private fun JSONObject.optIntOrNull(key: String): Int? = if (has(key) && !isNull(key)) getInt(key) else null

    private fun JSONArray?.strings() = if (this == null) emptyList() else (0 until length()).map { getString(it) }

    private fun points(arr: JSONArray) =
        (0 until arr.length()).map { i -> arr.getJSONArray(i).let { GeoPoint(it.getDouble(0), it.getDouble(1)) } }
}
