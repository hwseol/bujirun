package com.mhmh2.bujirun.tracking

import android.Manifest
import android.annotation.SuppressLint
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import android.os.Build
import android.os.SystemClock
import androidx.core.content.ContextCompat
import androidx.work.CoroutineWorker
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import com.google.android.gms.location.ActivityRecognition
import com.google.android.gms.location.ActivityTransition
import com.google.android.gms.location.ActivityTransitionRequest
import com.google.android.gms.location.ActivityTransitionResult
import com.google.android.gms.location.DetectedActivity
import com.mhmh2.bujirun.Prefs
import com.mhmh2.bujirun.data.AutoActivityEntity
import com.mhmh2.bujirun.data.WorkoutStore
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withTimeoutOrNull
import java.time.ZoneId
import java.util.concurrent.TimeUnit
import kotlin.coroutines.resume

/**
 * Recording without the app open, when the user turns it on (설정 → 자동 기록): the system's
 * activity recognition says when walking or running starts and stops (no GPS, nothing polled),
 * and the step counter is read at those moments and every few hours for the day's steps.
 * The decisions are in Passive.kt (StepLedger, BoutTracker).
 */
object PassiveRecorder {
    private val lock = Mutex()
    private const val WORK = "passive"

    fun hasPermission(context: Context) = Build.VERSION.SDK_INT < Build.VERSION_CODES.Q ||
        ContextCompat.checkSelfPermission(context, Manifest.permission.ACTIVITY_RECOGNITION) == PackageManager.PERMISSION_GRANTED

    fun isOn(context: Context) = Prefs.autoRecord.value && hasPermission(context)

    /** Starts or stops listening to match the setting. Safe to call again and again. */
    @SuppressLint("MissingPermission")
    fun apply(context: Context) {
        val app = context.applicationContext
        val client = ActivityRecognition.getClient(app)
        val work = WorkManager.getInstance(app)
        if (!isOn(app)) {
            runCatching { client.removeActivityTransitionUpdates(transitionIntent(app)) }
            work.cancelUniqueWork(WORK)
            return
        }
        val transitions = listOf(DetectedActivity.WALKING, DetectedActivity.RUNNING).flatMap { type ->
            listOf(ActivityTransition.ACTIVITY_TRANSITION_ENTER, ActivityTransition.ACTIVITY_TRANSITION_EXIT).map {
                ActivityTransition.Builder().setActivityType(type).setActivityTransition(it).build()
            }
        }
        runCatching { client.requestActivityTransitionUpdates(ActivityTransitionRequest(transitions), transitionIntent(app)) }
        // The day's steps, and closing a walk whose end never came: every 3 hours is plenty.
        work.enqueueUniquePeriodicWork(
            WORK, ExistingPeriodicWorkPolicy.KEEP, PeriodicWorkRequestBuilder<PassiveWorker>(3, TimeUnit.HOURS).build()
        )
    }

    private fun transitionIntent(context: Context): PendingIntent = PendingIntent.getBroadcast(
        context, 0, Intent(context, TransitionReceiver::class.java),
        // Mutable: the system adds the transitions to it.
        PendingIntent.FLAG_UPDATE_CURRENT or (if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) PendingIntent.FLAG_MUTABLE else 0)
    )

    /** The step counter's current total since the phone started, or null without one. */
    private suspend fun readCounter(context: Context): Long? {
        if (!hasPermission(context)) return null
        val sm = context.getSystemService(SensorManager::class.java) ?: return null
        val sensor = sm.getDefaultSensor(Sensor.TYPE_STEP_COUNTER) ?: return null
        return withTimeoutOrNull(5_000) {
            suspendCancellableCoroutine { cont ->
                val listener = object : SensorEventListener {
                    override fun onSensorChanged(e: SensorEvent) {
                        sm.unregisterListener(this)
                        if (cont.isActive) cont.resume(e.values[0].toLong())
                    }
                    override fun onAccuracyChanged(s: Sensor?, a: Int) {}
                }
                sm.registerListener(listener, sensor, SensorManager.SENSOR_DELAY_NORMAL)
                cont.invokeOnCancellation { sm.unregisterListener(listener) }
            }
        }
    }

    /** Adds the steps since the last reading to the days they were taken on; returns the reading. */
    private suspend fun recordSteps(context: Context, now: Long): Long? {
        val counter = readCounter(context) ?: return null
        val last = Prefs.stepReading
        if (last != null) {
            val steps = StepLedger.delta(last.first, counter)
            val dao = WorkoutStore.database.passive()
            StepLedger.split(last.second, now, steps, ZoneId.systemDefault()).forEach { (day, n) -> dao.addSteps(day.toString(), n) }
        }
        Prefs.stepReading = counter to now
        return counter
    }

    /** Reads the steps now (the app opened, the periodic check) and closes a finished bout. */
    suspend fun check(context: Context) = lock.withLock {
        // Walks an earlier version recorded as the whole 4 hours after a start whose end never
        // came (not walking at all): gone.
        WorkoutStore.database.passive().deleteActivitiesLongerThan(BoutTracker.MAX_MS - 60_000)
        if (!isOn(context)) return@withLock
        val now = System.currentTimeMillis()
        val counter = recordSteps(context, now)
        val tracker = BoutTracker(BoutTracker.State.decode(Prefs.boutState))
        tracker.check(now, counter)?.let { save(context, it) }
        Prefs.boutState = tracker.state.encode()
    }

    internal suspend fun onTransitions(context: Context, events: List<Triple<FootKind, Boolean, Long>>) = lock.withLock {
        if (!isOn(context) || events.isEmpty()) return@withLock
        val counter = recordSteps(context, System.currentTimeMillis())
        val tracker = BoutTracker(BoutTracker.State.decode(Prefs.boutState))
        for ((kind, entered, t) in events.sortedBy { it.third }) {
            if (entered) tracker.enter(kind, t, counter)?.let { save(context, it) } else tracker.exit(kind, t, counter)
        }
        Prefs.boutState = tracker.state.encode()
    }

    private suspend fun save(context: Context, b: Bout) {
        val dao = WorkoutStore.database.passive()
        // Already recorded with the app (a workout at the same time): that one is the record.
        if (dao.workoutsOverlapping(b.startMs, b.endMs) > 0) return
        val activity = if (b.kind == FootKind.RUN) Activity.RUN else Activity.WALK
        dao.insertActivity(
            AutoActivityEntity(
                startedAt = b.startMs, endedAt = b.endMs, kind = b.kind.name, movingMs = b.movingMs, steps = b.steps,
                distanceM = b.distanceM, calories = Calories.kcal(activity, b.distanceM, b.movingMs / 1000.0, 0.0, Prefs.weightKg),
            )
        )
    }

    /** Transition times are on the boot clock; this is the same moment on the wall clock. */
    internal fun wallTime(elapsedRealtimeNanos: Long): Long =
        System.currentTimeMillis() - (SystemClock.elapsedRealtimeNanos() - elapsedRealtimeNanos) / 1_000_000
}

class TransitionReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val result = if (ActivityTransitionResult.hasResult(intent)) ActivityTransitionResult.extractResult(intent) else null
        val events = result?.transitionEvents.orEmpty().mapNotNull { e ->
            val kind = when (e.activityType) {
                DetectedActivity.WALKING -> FootKind.WALK
                DetectedActivity.RUNNING -> FootKind.RUN
                else -> return@mapNotNull null
            }
            Triple(kind, e.transitionType == ActivityTransition.ACTIVITY_TRANSITION_ENTER, PassiveRecorder.wallTime(e.elapsedRealTimeNanos))
        }
        if (events.isEmpty()) return
        val app = context.applicationContext
        val pending = goAsync()
        scope.launch {
            try {
                Prefs.init(app)
                WorkoutStore.init(app)
                PassiveRecorder.onTransitions(app, events)
            } finally {
                pending.finish()
            }
        }
    }

    private companion object {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    }
}

/** After a restart or an update the system forgets the transition request: ask again. */
class PassiveBootReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != Intent.ACTION_BOOT_COMPLETED && intent.action != Intent.ACTION_MY_PACKAGE_REPLACED) return
        Prefs.init(context)
        PassiveRecorder.apply(context)
    }
}

class PassiveWorker(context: Context, params: WorkerParameters) : CoroutineWorker(context, params) {
    override suspend fun doWork(): Result {
        Prefs.init(applicationContext)
        WorkoutStore.init(applicationContext)
        runCatching { PassiveRecorder.check(applicationContext) }
        return Result.success()
    }
}
