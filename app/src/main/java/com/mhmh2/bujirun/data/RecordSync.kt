package com.mhmh2.bujirun.data

import android.content.Context
import android.content.SharedPreferences
import android.util.Log
import androidx.room.withTransaction
import com.mhmh2.bujirun.Prefs
import com.mhmh2.bujirun.account.Account
import com.mhmh2.bujirun.net.Api
import java.net.URLEncoder
import java.security.MessageDigest
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import org.json.JSONArray
import org.json.JSONObject

/**
 * Keeps a copy of this phone's records on the server under the signed-in account (backend
 * records.py): finished workouts with their tracks, saved courses, auto-recorded walks and runs,
 * daily steps. A new phone or a reinstalled app signs in and gets them back.
 *
 * Items are keyed by when they began ("w:<start ms>", "c:<created ms>", "a:<start ms>", "steps"),
 * which doesn't change between installs, unlike the local row ids. Each item's hash comes from
 * its row alone (no track read), so a sync costs one list call; only what changed is sent.
 * Workout photos stay on the phone (they point at the phone's own picture files).
 *
 * Records on the phone aren't kept per account, so the phone belongs to the first account that
 * syncs it: another account signing in later neither uploads them nor mixes its own in.
 */
object RecordSync {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val lock = Mutex()
    private lateinit var app: Context
    private val sp: SharedPreferences get() = app.getSharedPreferences("sync", Context.MODE_PRIVATE)

    fun init(context: Context) {
        app = context.applicationContext
    }

    /** In the background; failures (offline) just wait for the next one. */
    fun requestLater() {
        if (!::app.isInitialized) return
        scope.launch { runCatching { sync() }.onFailure { Log.w("RecordSync", "sync failed", it) } }
    }

    suspend fun sync() = lock.withLock {
        val token = Account.token ?: return@withLock
        val email = Account.user.value?.email ?: return@withLock
        val owner = sp.getString("owner", null)
        if (owner != null && owner != email) return@withLock
        if (owner == null) sp.edit().putString("owner", email).apply()

        val db = WorkoutStore.database
        val workouts = db.workouts().allDone().associateBy { "w:${it.startedAt}" }
        val courses = db.courses().all().associateBy { "c:${it.createdAt}" }
        val activities = db.passive().allActivities().associateBy { "a:${it.startedAt}" }
        val steps = db.passive().allDaySteps()
        val courseKey = courses.entries.associate { (k, c) -> c.id to k }

        val local = buildMap {
            workouts.forEach { (k, w) -> put(k, hash("${w.endedAt}|${w.durationMs}|${w.distanceM}|${w.steps}|${w.calories}|${w.simulated}|${w.courseId?.let(courseKey::get)}")) }
            courses.forEach { (k, c) -> put(k, hash("${c.name}|${c.source}|${c.distanceM}|${c.isLoop}")) }
            activities.forEach { (k, a) -> put(k, hash("${a.endedAt}|${a.kind}|${a.steps}|${a.distanceM}|${a.movingMs}")) }
            if (steps.isNotEmpty()) put("steps", hash(steps.sortedBy { it.day }.joinToString(",") { "${it.day}=${it.steps}" }))
        }
        val listed = Api.get("/sync/list", token).getJSONArray("items")
        val remote = (0 until listed.length()).associate { listed.getJSONObject(it).let { o -> o.getString("key") to o.getString("hash") } }
        val synced = sp.getStringSet("keys", emptySet()).orEmpty()
        val plan = SyncPlan.of(local, remote, synced)

        // Courses first: downloaded workouts point at them.
        val (downCourses, downOthers) = plan.download.partition { it.startsWith("c:") }
        val newCourseIds = mutableMapOf<String, Long>()
        for (key in downCourses) {
            val d = item(key, token)
            newCourseIds[key] = db.courses().insertWithPoints(
                CourseEntity(
                    name = d.getString("name"), source = d.getString("source"), distanceM = d.getDouble("distanceM"),
                    ascentM = d.getDouble("ascentM"), isLoop = d.getBoolean("isLoop"), createdAt = d.getLong("createdAt"),
                ),
                d.getJSONArray("points").let { a ->
                    (0 until a.length()).map { i ->
                        val p = a.getJSONArray(i)
                        CoursePointEntity(courseId = 0, seq = i, lat = p.getDouble(0), lon = p.getDouble(1), altitudeM = p.optDoubleOrNull(2))
                    }
                },
            )
        }
        var stepsMerged = false
        for (key in downOthers) {
            val d = item(key, token)
            when {
                key.startsWith("w:") -> downloadWorkout(d, courses.mapValues { it.value.id } + newCourseIds)
                key.startsWith("a:") -> db.passive().insertActivity(
                    AutoActivityEntity(
                        startedAt = d.getLong("startedAt"), endedAt = d.getLong("endedAt"), kind = d.getString("kind"),
                        movingMs = d.getLong("movingMs"), steps = d.getLong("steps"), distanceM = d.getDouble("distanceM"),
                        calories = d.getDouble("calories"),
                    )
                )
                key == "steps" -> {
                    // A day keeps the higher count of the two.
                    val mine = steps.associate { it.day to it.steps }
                    d.keys().forEach { day ->
                        val theirs = d.getLong(day)
                        if (theirs > (mine[day] ?: -1)) db.passive().putDaySteps(DayStepsEntity(day, theirs))
                    }
                    stepsMerged = true
                }
            }
        }
        for (key in plan.upload) {
            val data = when {
                key.startsWith("w:") -> workoutJson(workouts.getValue(key), courseKey)
                key.startsWith("c:") -> courseJson(courses.getValue(key))
                key.startsWith("a:") -> activities.getValue(key).let { a ->
                    JSONObject().put("startedAt", a.startedAt).put("endedAt", a.endedAt).put("kind", a.kind)
                        .put("movingMs", a.movingMs).put("steps", a.steps).put("distanceM", a.distanceM).put("calories", a.calories)
                }
                else -> JSONObject().apply { steps.forEach { put(it.day, it.steps) } }
            }
            Api.put("/sync/items/${enc(key)}", JSONObject().put("hash", local.getValue(key)).put("data", data), token)
        }
        for (key in plan.deleteRemote) Api.delete("/sync/items/${enc(key)}", token)
        for (key in plan.deleteLocal) {
            when {
                key.startsWith("w:") -> workouts[key]?.let { db.workouts().delete(it.id) }
                key.startsWith("c:") -> courses[key]?.let { db.courses().delete(it.id) }
                key.startsWith("a:") -> activities[key]?.let { db.passive().deleteActivity(it.id) }
            }
        }
        // In step now: what's on both sides. Merged steps differ from the server's copy, so the
        // next sync sends them back up.
        val inStep = (local.keys - plan.deleteLocal) + plan.download - plan.deleteRemote - (if (stepsMerged) setOf("steps") else emptySet())
        sp.edit().putStringSet("keys", inStep.toSet()).putLong("last", System.currentTimeMillis()).apply()
        if (stepsMerged) requestLater()
    }

    /** After the owner deleted their account: the phone's records can go to whoever signs in next. */
    fun forgetOwner() {
        if (::app.isInitialized) sp.edit().remove("owner").remove("keys").apply()
    }

    /** When the last sync finished, or 0. */
    val lastSyncMs: Long get() = if (::app.isInitialized) sp.getLong("last", 0L) else 0L

    private suspend fun downloadWorkout(d: JSONObject, courseIds: Map<String, Long>) {
        val db = WorkoutStore.database
        val id = db.withTransaction {
            val id = db.workouts().insert(
                WorkoutEntity(
                    startedAt = d.getLong("startedAt"), endedAt = d.getLong("endedAt"), status = WorkoutEntity.DONE,
                    goalType = d.getString("goalType"), goalValue = d.getDouble("goalValue"), durationMs = d.getLong("durationMs"),
                    distanceM = d.getDouble("distanceM"), calories = d.getDouble("calories"), ascentM = d.getDouble("ascentM"),
                    runMs = d.getLong("runMs"), walkMs = d.getLong("walkMs"), simulated = d.optBoolean("simulated"),
                    courseId = d.optString("course").takeIf { it.isNotEmpty() }?.let(courseIds::get), steps = d.optInt("steps"),
                )
            )
            val pts = d.getJSONArray("points")
            db.workouts().insertPoints((0 until pts.length()).map { i ->
                val p = pts.getJSONArray(i)
                TrackPointEntity(
                    workoutId = id, epochMs = p.getLong(0), lat = p.getDouble(1), lon = p.getDouble(2), altitudeM = p.optDoubleOrNull(3),
                    accuracyM = p.getDouble(4).toFloat(), segment = p.getInt(5), activity = p.getString(6),
                    distanceM = p.getDouble(7), activeMs = p.getLong(8),
                )
            })
            id
        }
        // Already in the calendar / Health Connect from the phone that recorded it.
        Prefs.markExported("calendar", id)
        Prefs.markExported("health", id)
    }

    private suspend fun workoutJson(w: WorkoutEntity, courseKey: Map<Long, String>): JSONObject {
        val pts = JSONArray()
        WorkoutStore.points(w.id).forEach { p ->
            pts.put(JSONArray().put(p.epochMs).put(p.lat).put(p.lon).put(p.altitudeM ?: JSONObject.NULL).put(p.accuracyM.toDouble())
                .put(p.segment).put(p.activity).put(p.distanceM).put(p.activeMs))
        }
        return JSONObject().put("startedAt", w.startedAt).put("endedAt", w.endedAt).put("goalType", w.goalType)
            .put("goalValue", w.goalValue).put("durationMs", w.durationMs).put("distanceM", w.distanceM).put("calories", w.calories)
            .put("ascentM", w.ascentM).put("runMs", w.runMs).put("walkMs", w.walkMs).put("simulated", w.simulated)
            .put("steps", w.steps).put("course", w.courseId?.let(courseKey::get) ?: "").put("points", pts)
    }

    private suspend fun courseJson(c: CourseEntity): JSONObject {
        val pts = JSONArray()
        WorkoutStore.database.courses().points(c.id).forEach { p ->
            pts.put(JSONArray().put(p.lat).put(p.lon).put(p.altitudeM ?: JSONObject.NULL))
        }
        return JSONObject().put("name", c.name).put("source", c.source).put("distanceM", c.distanceM).put("ascentM", c.ascentM)
            .put("isLoop", c.isLoop).put("createdAt", c.createdAt).put("points", pts)
    }

    private suspend fun item(key: String, token: String) = Api.get("/sync/items/${enc(key)}", token).getJSONObject("data")

    private fun enc(key: String) = URLEncoder.encode(key, "UTF-8")

    private fun hash(s: String) = MessageDigest.getInstance("SHA-1").digest(s.toByteArray()).joinToString("") { "%02x".format(it) }.take(16)

    private fun JSONArray.optDoubleOrNull(i: Int): Double? = if (isNull(i)) null else getDouble(i)
}
