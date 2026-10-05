package com.mhmh2.bujirun.data

import android.content.Context
import androidx.room.Room
import com.mhmh2.bujirun.coach.Goal
import com.mhmh2.bujirun.tracking.Activity
import com.mhmh2.bujirun.tracking.LocationSample
import com.mhmh2.bujirun.tracking.TrackPoint
import com.mhmh2.bujirun.tracking.WorkoutSnapshot
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/**
 * Saved workouts. Writes run on a process-wide scope rather than the service's, so the final
 * save still completes after the service has stopped itself; a mutex keeps them in order
 * (points of the last flush before the "finished" update).
 */
object WorkoutStore {
    private lateinit var db: AppDatabase
    private val dao get() = db.workouts()
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val writes = Mutex()

    fun init(context: Context) {
        if (::db.isInitialized) return
        db = Room.databaseBuilder(context.applicationContext, AppDatabase::class.java, "bujirun.db").build()
    }

    // Shorter than this, and nowhere: an accidental start. Not worth a row in the history.
    private const val MIN_KEEP_MS = 10_000L
    private const val MIN_KEEP_M = 20.0

    suspend fun create(goal: Goal, startedAt: Long): Long = writes.withLock {
        val (type, value) = goal.encode()
        dao.insert(
            WorkoutEntity(startedAt = startedAt, endedAt = startedAt, status = WorkoutEntity.IN_PROGRESS, goalType = type, goalValue = value)
        )
    }

    /** Saves new track points and the running totals. */
    fun flush(id: Long, newPoints: List<TrackPoint>, s: WorkoutSnapshot, finished: Boolean = false, onDone: (kept: Boolean) -> Unit = {}) {
        scope.launch {
            writes.withLock {
                if (newPoints.isNotEmpty()) dao.insertPoints(newPoints.map { it.toEntity(id) })
                val current = dao.get(id) ?: return@withLock
                if (finished && s.elapsedMs < MIN_KEEP_MS && s.distanceM < MIN_KEEP_M) {
                    dao.delete(id)
                    onDone(false)
                    return@withLock
                }
                dao.update(
                    current.copy(
                        endedAt = System.currentTimeMillis(),
                        status = if (finished) WorkoutEntity.DONE else WorkoutEntity.IN_PROGRESS,
                        durationMs = s.elapsedMs,
                        distanceM = s.distanceM,
                        calories = s.calories,
                        ascentM = s.ascentM,
                        runMs = s.runMs,
                        walkMs = s.walkMs,
                    )
                )
                if (finished) onDone(true)
            }
        }
    }

    /**
     * Workouts left IN_PROGRESS by a process that died mid-run (system kill, crash, battery):
     * keep what was saved up to the last flush. Only call when no workout is being recorded.
     */
    fun recoverInterrupted() {
        scope.launch {
            writes.withLock {
                dao.inProgress().forEach { w ->
                    if (w.durationMs < MIN_KEEP_MS && w.distanceM < MIN_KEEP_M) dao.delete(w.id)
                    else dao.update(w.copy(status = WorkoutEntity.DONE))
                }
            }
        }
    }

    fun delete(id: Long) {
        scope.launch { writes.withLock { dao.delete(id) } }
    }

    fun observeDone(): Flow<List<WorkoutEntity>> = dao.observeDone()
    fun observe(id: Long): Flow<WorkoutEntity?> = dao.observe(id)
    fun observePoints(id: Long): Flow<List<TrackPointEntity>> = dao.observePoints(id)
}

private fun TrackPoint.toEntity(workoutId: Long) = TrackPointEntity(
    workoutId = workoutId,
    epochMs = sample.epochMs,
    lat = sample.lat,
    lon = sample.lon,
    altitudeM = sample.altitudeM,
    accuracyM = sample.accuracyM,
    segment = segment,
    activity = activity.name,
    distanceM = distanceM,
    activeMs = activeMs,
)

fun TrackPointEntity.toTrackPoint() = TrackPoint(
    sample = LocationSample(activeMs, lat, lon, accuracyM, altitudeM, epochMs = epochMs),
    segment = segment,
    activity = runCatching { Activity.valueOf(activity) }.getOrDefault(Activity.UNKNOWN),
    distanceM = distanceM,
    activeMs = activeMs,
)
