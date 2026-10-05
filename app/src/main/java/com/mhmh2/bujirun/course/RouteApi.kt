package com.mhmh2.bujirun.course

import com.mhmh2.bujirun.account.Account
import com.mhmh2.bujirun.net.Api
import com.mhmh2.bujirun.net.ApiException
import org.json.JSONArray
import org.json.JSONObject

/** Walking routes between two points, from our server (which asks TMAP). */
object RouteApi {
    suspend fun walk(from: GeoPoint, to: GeoPoint): List<GeoPoint> {
        val token = Account.token ?: throw ApiException("다시 로그인해 주세요.", 401)
        val json = Api.post(
            "/routes/pedestrian",
            JSONObject().put("start", JSONArray().put(from.lat).put(from.lon)).put("end", JSONArray().put(to.lat).put(to.lon)),
            token,
        )
        val pts = json.getJSONArray("points")
        return (0 until pts.length()).map { i -> pts.getJSONArray(i).let { GeoPoint(it.getDouble(0), it.getDouble(1)) } }
    }
}
