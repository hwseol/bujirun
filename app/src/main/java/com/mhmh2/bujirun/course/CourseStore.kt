package com.mhmh2.bujirun.course

import com.mhmh2.bujirun.data.CourseEntity
import com.mhmh2.bujirun.data.CoursePointEntity
import com.mhmh2.bujirun.data.WorkoutStore
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch

/** Saved courses, in the same database as workouts (WorkoutStore owns it). */
object CourseStore {
    private val dao get() = WorkoutStore.database.courses()
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    /** Simplifies the line, works out distance/climb/loop, saves. Returns the new course id. */
    suspend fun save(name: String, source: CourseSource, points: List<GeoPoint>): Long {
        require(points.size >= 2) { "a course needs a line" }
        val line = CourseGeometry.simplify(points)
        val course = CourseEntity(
            name = name.trim().ifEmpty { "이름 없는 코스" },
            source = source.name,
            distanceM = CourseGeometry.distanceM(line),
            ascentM = CourseGeometry.ascentM(points),
            isLoop = CourseGeometry.isLoop(line),
            createdAt = System.currentTimeMillis(),
        )
        return dao.insertWithPoints(
            course,
            line.mapIndexed { i, p -> CoursePointEntity(courseId = 0, seq = i, lat = p.lat, lon = p.lon, altitudeM = p.altitudeM) },
        )
    }

    fun rename(id: Long, name: String) { scope.launch { dao.rename(id, name.trim().ifEmpty { "이름 없는 코스" }) } }
    fun delete(id: Long) { scope.launch { dao.delete(id) } }

    fun observeAll(): Flow<List<CourseEntity>> = dao.observeAll()
    fun observe(id: Long): Flow<CourseEntity?> = dao.observe(id)
    fun observePoints(id: Long): Flow<List<GeoPoint>> =
        dao.observePoints(id).map { list -> list.map { GeoPoint(it.lat, it.lon, it.altitudeM) } }
}

val CourseEntity.sourceEnum: CourseSource
    get() = runCatching { CourseSource.valueOf(source) }.getOrDefault(CourseSource.GPX)
