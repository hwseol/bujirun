package com.mhmh2.bujirun.data

import androidx.room.ColumnInfo
import androidx.room.Dao
import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase
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
    // An admin fake-GPS test run: labelled as such, and never exported to Health Connect.
    @ColumnInfo(defaultValue = "0") val simulated: Boolean = false,
    // The course this workout followed, if any (the course may since have been deleted).
    val courseId: Long? = null,
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

/** A route to run: imported from GPX, saved from a workout, or drawn on the map. */
@Entity(tableName = "courses")
data class CourseEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val name: String,
    val source: String,       // CourseSource name
    val distanceM: Double,
    val ascentM: Double,
    val isLoop: Boolean,
    val createdAt: Long,      // epoch ms
)

@Entity(tableName = "course_points", indices = [Index("courseId")])
data class CoursePointEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val courseId: Long,
    val seq: Int,
    val lat: Double,
    val lon: Double,
    val altitudeM: Double?,
)

@Dao
interface CourseDao {
    @Insert suspend fun insert(course: CourseEntity): Long
    @Insert suspend fun insertPoints(points: List<CoursePointEntity>)
    @Query("UPDATE courses SET name = :name WHERE id = :id") suspend fun rename(id: Long, name: String)
    @Query("SELECT * FROM courses ORDER BY createdAt DESC") fun observeAll(): Flow<List<CourseEntity>>
    @Query("SELECT * FROM courses WHERE id = :id") fun observe(id: Long): Flow<CourseEntity?>
    @Query("SELECT * FROM courses WHERE id = :id") suspend fun get(id: Long): CourseEntity?
    @Query("SELECT * FROM course_points WHERE courseId = :id ORDER BY seq") fun observePoints(id: Long): Flow<List<CoursePointEntity>>
    @Query("SELECT * FROM course_points WHERE courseId = :id ORDER BY seq") suspend fun points(id: Long): List<CoursePointEntity>
    @Query("DELETE FROM course_points WHERE courseId = :id") suspend fun deletePoints(id: Long)
    @Query("DELETE FROM courses WHERE id = :id") suspend fun deleteCourse(id: Long)

    @Transaction
    suspend fun insertWithPoints(course: CourseEntity, points: List<CoursePointEntity>): Long {
        val id = insert(course)
        insertPoints(points.map { it.copy(courseId = id) })
        return id
    }

    @Transaction
    suspend fun delete(id: Long) {
        deletePoints(id)
        deleteCourse(id)
    }
}

@Database(
    entities = [WorkoutEntity::class, TrackPointEntity::class, CourseEntity::class, CoursePointEntity::class],
    version = 4,
    exportSchema = false,
)
abstract class AppDatabase : RoomDatabase() {
    abstract fun workouts(): WorkoutDao
    abstract fun courses(): CourseDao

    companion object {
        // Testers' phones already hold databases with real workouts: migrate, never wipe.
        val MIGRATION_1_2 = object : Migration(1, 2) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE workouts ADD COLUMN simulated INTEGER NOT NULL DEFAULT 0")
            }
        }
        val MIGRATION_2_3 = object : Migration(2, 3) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL(
                    "CREATE TABLE IF NOT EXISTS `courses` (`id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, " +
                        "`name` TEXT NOT NULL, `source` TEXT NOT NULL, `distanceM` REAL NOT NULL, `ascentM` REAL NOT NULL, " +
                        "`isLoop` INTEGER NOT NULL, `createdAt` INTEGER NOT NULL)"
                )
                db.execSQL(
                    "CREATE TABLE IF NOT EXISTS `course_points` (`id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, " +
                        "`courseId` INTEGER NOT NULL, `seq` INTEGER NOT NULL, `lat` REAL NOT NULL, `lon` REAL NOT NULL, " +
                        "`altitudeM` REAL)"
                )
                db.execSQL("CREATE INDEX IF NOT EXISTS `index_course_points_courseId` ON `course_points` (`courseId`)")
            }
        }
        val MIGRATION_3_4 = object : Migration(3, 4) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE workouts ADD COLUMN courseId INTEGER")
            }
        }
    }
}
