package com.mhmh2.bujirun.data

import android.content.Context
import androidx.health.connect.client.HealthConnectClient
import androidx.health.connect.client.permission.HealthPermission
import androidx.health.connect.client.records.DistanceRecord
import androidx.health.connect.client.records.ExerciseRoute
import androidx.health.connect.client.records.ExerciseSessionRecord
import androidx.health.connect.client.records.TotalCaloriesBurnedRecord
import androidx.health.connect.client.records.metadata.Device
import androidx.health.connect.client.records.metadata.Metadata
import androidx.health.connect.client.units.Energy
import androidx.health.connect.client.units.Length
import java.time.Instant
import java.time.ZoneId

/**
 * Writes workouts to Health Connect, Android's shared health store. Samsung Health (and Google
 * Fit, etc.) read from it, which is how a workout here shows up there.
 *
 * Each record carries a client record id derived from our workout id, so saving the same
 * workout twice updates it instead of creating a duplicate.
 */
object HealthExport {
    private val ROUTE = HealthPermission.PERMISSION_WRITE_EXERCISE_ROUTE
    /** Needed to save anything at all. */
    val required = setOf(
        HealthPermission.getWritePermission(ExerciseSessionRecord::class),
        HealthPermission.getWritePermission(DistanceRecord::class),
        HealthPermission.getWritePermission(TotalCaloriesBurnedRecord::class),
    )
    /** What we ask for: the route on top, which the user may decline. */
    val permissions = required + ROUTE

    enum class Availability { AVAILABLE, NEEDS_UPDATE, UNAVAILABLE }

    fun availability(context: Context): Availability = when (HealthConnectClient.getSdkStatus(context)) {
        HealthConnectClient.SDK_AVAILABLE -> Availability.AVAILABLE
        HealthConnectClient.SDK_UNAVAILABLE_PROVIDER_UPDATE_REQUIRED -> Availability.NEEDS_UPDATE
        else -> Availability.UNAVAILABLE
    }

    suspend fun missingPermissions(context: Context): Set<String> =
        permissions - HealthConnectClient.getOrCreate(context).permissionController.getGrantedPermissions()

    /** The route is optional: without its permission the session is saved without the map line. */
    suspend fun write(context: Context, workout: WorkoutEntity, points: List<TrackPointEntity>) {
        val client = HealthConnectClient.getOrCreate(context)
        val granted = client.permissionController.getGrantedPermissions()

        val start = Instant.ofEpochMilli(workout.startedAt)
        // The session must contain every route point, and end after it starts.
        val lastPoint = points.maxOfOrNull { it.epochMs } ?: 0L
        val end = Instant.ofEpochMilli(maxOf(workout.endedAt, lastPoint + 1, workout.startedAt + 1000))
        val zone = ZoneId.systemDefault().rules
        val startOffset = zone.getOffset(start)
        val endOffset = zone.getOffset(end)
        val device = Device(type = Device.TYPE_PHONE)
        fun meta(kind: String) = Metadata.activelyRecorded(
            clientRecordId = "bujirun-${workout.id}-$kind",
            clientRecordVersion = workout.endedAt,
            device = device,
        )

        val route = if (ROUTE in granted) {
            points.filter { it.epochMs in workout.startedAt..end.toEpochMilli() }
                .distinctBy { it.epochMs }
                .map {
                    ExerciseRoute.Location(
                        time = Instant.ofEpochMilli(it.epochMs),
                        latitude = it.lat,
                        longitude = it.lon,
                        horizontalAccuracy = Length.meters(it.accuracyM.toDouble()),
                        altitude = it.altitudeM?.let(Length::meters),
                    )
                }
                .takeIf { it.isNotEmpty() }
                ?.let(::ExerciseRoute)
        } else null

        val session = ExerciseSessionRecord(
            startTime = start,
            startZoneOffset = startOffset,
            endTime = end,
            endZoneOffset = endOffset,
            exerciseType = if (workout.isRun) ExerciseSessionRecord.EXERCISE_TYPE_RUNNING
            else ExerciseSessionRecord.EXERCISE_TYPE_WALKING,
            title = if (workout.isRun) "러닝" else "걷기",
            metadata = meta("session"),
            exerciseRoute = route,
        )
        val distance = DistanceRecord(
            startTime = start, startZoneOffset = startOffset, endTime = end, endZoneOffset = endOffset,
            distance = Length.meters(workout.distanceM),
            metadata = meta("distance"),
        )
        val calories = TotalCaloriesBurnedRecord(
            startTime = start, startZoneOffset = startOffset, endTime = end, endZoneOffset = endOffset,
            energy = Energy.kilocalories(workout.calories),
            metadata = meta("calories"),
        )
        client.insertRecords(listOf(session, distance, calories))
    }
}
