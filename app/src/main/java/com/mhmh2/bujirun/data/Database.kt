package com.mhmh2.bujirun.data

import androidx.room.Dao
import androidx.room.Database
import androidx.room.Entity
import androidx.room.Index
import androidx.room.Insert
import androidx.room.PrimaryKey
import androidx.room.Query
import androidx.room.RoomDatabase
import androidx.room.Transaction
import androidx.room.Update
import kotlinx.coroutines.flow.Flow

/**
 * One workout. Written when the clock starts (status IN_PROGRESS) and updated every few seconds
 * while recording, so a workout survives the app being killed mid-run; DONE once finished.
 */
@Entity(tableName = "workouts")
data class WorkoutEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val startedAt: Long,        // epoch ms
    val endedAt: Long,          // epoch ms of the last update
    val status: String,         // IN_PROGRESS / DONE
    val goalType: String,
    val goalValue: Double,
    val durationMs: Long = 0,   // active time, pauses excluded
    val distanceM: Double = 0.0,
    val calories: Double = 0.0,
    val ascentM: Double = 0.0,
    val runMs: Long = 0,
    val walkMs: Long = 0,
) {
    val isRun: Boolean get() = runMs >= walkMs

    companion object {
        const val IN_PROGRESS = "IN_PROGRESS"
        const val DONE = "DONE"
    }
}

@Entity(tableName = "track_points", indices = [Index("workoutId")])
data class TrackPointEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val workoutId: Long,
    val epochMs: Long,
    val lat: Double,
    val lon: Double,
    val altitudeM: Double?,
    val accuracyM: Float,
    val segment: Int,
    val activity: String,
    val distanceM: Double,
    val activeMs: Long,
)

@Dao
interface WorkoutDao {
    @Insert suspend fun insert(workout: WorkoutEntity): Long
    @Update suspend fun update(workout: WorkoutEntity)
    @Insert suspend fun insertPoints(points: List<TrackPointEntity>)

    @Query("SELECT * FROM workouts WHERE id = :id") suspend fun get(id: Long): WorkoutEntity?
    @Query("SELECT * FROM workouts WHERE id = :id") fun observe(id: Long): Flow<WorkoutEntity?>
    @Query("SELECT * FROM workouts WHERE status = 'DONE' ORDER BY startedAt DESC") fun observeDone(): Flow<List<WorkoutEntity>>
    @Query("SELECT * FROM workouts WHERE status = 'IN_PROGRESS'") suspend fun inProgress(): List<WorkoutEntity>
    @Query("SELECT * FROM track_points WHERE workoutId = :id ORDER BY id") fun observePoints(id: Long): Flow<List<TrackPointEntity>>
    @Query("SELECT COUNT(*) FROM track_points WHERE workoutId = :id") suspend fun pointCount(id: Long): Int

    @Query("DELETE FROM track_points WHERE workoutId = :id") suspend fun deletePoints(id: Long)
    @Query("DELETE FROM workouts WHERE id = :id") suspend fun deleteWorkout(id: Long)

    @Transaction
    suspend fun delete(id: Long) {
        deletePoints(id)
        deleteWorkout(id)
    }
}

@Database(entities = [WorkoutEntity::class, TrackPointEntity::class], version = 1, exportSchema = false)
abstract class AppDatabase : RoomDatabase() {
    abstract fun workouts(): WorkoutDao
}
