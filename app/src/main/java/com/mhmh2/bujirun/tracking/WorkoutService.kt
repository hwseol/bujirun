package com.mhmh2.bujirun.tracking

import android.Manifest
import android.annotation.SuppressLint
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.content.pm.ServiceInfo
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import android.location.Location
import android.os.Build
import android.os.Looper
import android.os.SystemClock
import androidx.core.app.NotificationCompat
import androidx.core.app.ServiceCompat
import androidx.core.content.ContextCompat
import androidx.lifecycle.LifecycleService
import androidx.lifecycle.lifecycleScope
import com.google.android.gms.location.FusedLocationProviderClient
import com.google.android.gms.location.LocationCallback
import com.google.android.gms.location.LocationRequest
import com.google.android.gms.location.LocationResult
import com.google.android.gms.location.LocationServices
import com.google.android.gms.location.Priority
import com.mhmh2.bujirun.MainActivity
import com.mhmh2.bujirun.Prefs
import com.mhmh2.bujirun.R
import com.mhmh2.bujirun.coach.CoachEngine
import com.mhmh2.bujirun.coach.Cue
import com.mhmh2.bujirun.coach.CueText
import com.mhmh2.bujirun.coach.Goal
import com.mhmh2.bujirun.coach.VoiceAnnouncer
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

/**
 * Records a workout. A foreground service (with the ongoing notification Android requires) so
 * GPS keeps flowing and the process isn't killed while the phone is locked in a pocket.
 *
 * Started while the app is on screen, so the plain "while using the app" location permission is
 * enough - no background-location permission (and its Play review) needed.
 *
 * Everything here runs on the main thread (location callbacks are delivered on the main looper,
 * the ticker runs in lifecycleScope), so the tracker needs no locking.
 */
class WorkoutService : LifecycleService() {

    private lateinit var fused: FusedLocationProviderClient
    private var tracker: WorkoutTracker? = null
    private var coach: CoachEngine? = null
    private var voice: VoiceAnnouncer? = null
    private var goal: Goal = Goal.Free
    private var countdown: Int? = null
    private var ticker: Job? = null

    // Walk/run detection and auto-pause (see Motion.kt).
    private enum class PausedBy { USER, AUTO }
    private var pausedBy: PausedBy? = null
    private var speedMeter = SpeedMeter()
    private var cadenceMeter = CadenceMeter()
    private var classifier = ActivityClassifier()
    private var autoPause = AutoPauseDetector()
    private var stepSensorActive = false

    private val locationCallback = object : LocationCallback() {
        override fun onLocationResult(result: LocationResult) {
            val t = tracker ?: return
            result.locations.forEach {
                val sample = it.toSample()
                speedMeter.add(sample)
                t.onLocation(sample)
            }
        }
    }

    private val stepListener = object : SensorEventListener {
        override fun onSensorChanged(event: SensorEvent) = cadenceMeter.onStep(SystemClock.elapsedRealtime())
        override fun onAccuracyChanged(sensor: Sensor?, accuracy: Int) {}
    }

    override fun onCreate() {
        super.onCreate()
        Prefs.init(this)
        fused = LocationServices.getFusedLocationProviderClient(this)
        createChannel()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        super.onStartCommand(intent, flags, startId)
        val now = SystemClock.elapsedRealtime()
        when (intent?.action) {
            ACTION_START -> start(
                Goal.decode(intent.getStringExtra(EXTRA_GOAL_TYPE), intent.getDoubleExtra(EXTRA_GOAL_VALUE, 0.0))
            )
            ACTION_PAUSE -> tracker?.takeIf { it.status == WorkoutStatus.RUNNING }?.let {
                it.pause(now)
                pausedBy = PausedBy.USER
                say(Cue.Paused)
            }
            // Also the way out of an auto-pause, if the runner doesn't want to wait for it.
            ACTION_RESUME -> tracker?.takeIf { it.status == WorkoutStatus.PAUSED }?.let {
                it.resume(now)
                pausedBy = null
                say(Cue.Resumed)
            }
            ACTION_STOP -> stop(now)
        }
        publish()
        // If the system kills us anyway, don't come back with a half-initialised empty workout -
        // restoring an interrupted workout is M4's job (it needs the saved track).
        return START_NOT_STICKY
    }

    private fun start(goal: Goal) {
        if (tracker != null) return
        if (!hasLocationPermission()) {
            stopSelf()
            return
        }
        this.goal = goal
        ServiceCompat.startForeground(
            this,
            NOTIFICATION_ID,
            buildNotification(WorkoutSnapshot(goal = goal, countdown = COUNTDOWN_FROM)),
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) ServiceInfo.FOREGROUND_SERVICE_TYPE_LOCATION else 0
        )
        val t = WorkoutTracker(weightKg = Prefs.weightKg)
        tracker = t
        coach = CoachEngine(goal)
        voice = VoiceAnnouncer(this)
        pausedBy = null
        speedMeter = SpeedMeter()
        cadenceMeter = CadenceMeter()
        classifier = ActivityClassifier()
        autoPause = AutoPauseDetector()
        // GPS starts now so it has the countdown to get a fix before the clock runs.
        requestLocationUpdates()
        stepSensorActive = registerStepSensor()
        ticker = lifecycleScope.launch {
            for (n in COUNTDOWN_FROM downTo 1) {
                countdown = n
                say(Cue.Countdown(n))
                publish()
                delay(1000)
            }
            countdown = null
            t.start(SystemClock.elapsedRealtime())
            say(Cue.Started(goal))
            while (isActive) {
                updateMotion(SystemClock.elapsedRealtime())
                val snapshot = publish() ?: break
                coach?.onTick(snapshot)?.forEach(::say)
                delay(1000)
            }
        }
    }

    /** Once a second: walking or running, and whether to auto-pause/resume. */
    private fun updateMotion(now: Long) {
        val t = tracker ?: return
        // Without the step sensor (no permission, or the phone lacks one) GPS speed decides alone.
        val cadence = if (stepSensorActive) cadenceMeter.cadence(now) else null
        val recentSteps = if (stepSensorActive) cadenceMeter.stepsSince(now - 5_000) else null

        if (t.status == WorkoutStatus.RUNNING) {
            t.activity = classifier.update(now, speedMeter.speed(10_000, now), cadence)
            t.tick(now)
        }
        // A pause the user chose is theirs to end - never auto-resume it.
        if (!Prefs.autoPause.value || pausedBy == PausedBy.USER) return
        val decision = autoPause.update(
            now,
            autoPaused = pausedBy == PausedBy.AUTO,
            slowSpeed = speedMeter.speed(8_000, now),
            quickSpeed = speedMeter.speed(3_000, now),
            recentSteps = recentSteps,
        )
        // Standing at the start line also auto-pauses (the clock shouldn't run before the first
        // step), but announcing it right after "starting" would be noise - stay quiet until the
        // runner has actually gone somewhere.
        val announce = t.distanceM >= 10
        when (decision) {
            AutoPauseDetector.Decision.PAUSE -> if (t.status == WorkoutStatus.RUNNING) {
                t.pause(now)
                pausedBy = PausedBy.AUTO
                if (announce) say(Cue.AutoPaused)
            }
            AutoPauseDetector.Decision.RESUME -> if (pausedBy == PausedBy.AUTO) {
                t.resume(now)
                pausedBy = null
                if (announce) say(Cue.Resumed)
            }
            AutoPauseDetector.Decision.NONE -> {}
        }
    }

    private fun registerStepSensor(): Boolean {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q &&
            ContextCompat.checkSelfPermission(this, Manifest.permission.ACTIVITY_RECOGNITION) != PackageManager.PERMISSION_GRANTED
        ) return false
        val sensors = getSystemService(SensorManager::class.java)
        val detector = sensors.getDefaultSensor(Sensor.TYPE_STEP_DETECTOR) ?: return false
        return sensors.registerListener(stepListener, detector, SensorManager.SENSOR_DELAY_NORMAL)
    }

    private fun unregisterStepSensor() {
        if (stepSensorActive) getSystemService(SensorManager::class.java).unregisterListener(stepListener)
        stepSensorActive = false
    }

    private fun stop(now: Long) {
        val t = tracker ?: run { stopSelf(); return }
        ticker?.cancel()
        if (countdown != null) {
            // Cancelled before it began: nothing to show, straight back to the start screen.
            WorkoutSession.publish(WorkoutSnapshot())
        } else {
            t.finish(now)
            publish()
            say(Cue.Finished(t.distanceM, t.elapsedMs(now), t.averagePaceSecPerKm(now), t.calories))
        }
        voice?.releaseWhenDone()
        tracker = null
        coach = null
        voice = null
        countdown = null
        pausedBy = null
        fused.removeLocationUpdates(locationCallback)
        unregisterStepSensor()
        ServiceCompat.stopForeground(this, ServiceCompat.STOP_FOREGROUND_REMOVE)
        stopSelf()
    }

    private fun say(cue: Cue) {
        if (Prefs.voiceEnabled.value) voice?.speak(CueText.of(cue))
    }

    private fun publish(): WorkoutSnapshot? {
        val t = tracker ?: return null
        val snapshot = t.snapshot(SystemClock.elapsedRealtime())
            .copy(goal = goal, countdown = countdown, autoPaused = pausedBy == PausedBy.AUTO)
        WorkoutSession.publish(snapshot)
        if (snapshot.status != WorkoutStatus.FINISHED) {
            getSystemService(NotificationManager::class.java).notify(NOTIFICATION_ID, buildNotification(snapshot))
        }
        return snapshot
    }

    @SuppressLint("MissingPermission") // checked in start() before we get here
    private fun requestLocationUpdates() {
        val request = LocationRequest.Builder(Priority.PRIORITY_HIGH_ACCURACY, 1000L)
            .setMinUpdateIntervalMillis(1000L)
            .setWaitForAccurateLocation(false)
            .build()
        fused.requestLocationUpdates(request, locationCallback, Looper.getMainLooper())
    }

    private fun hasLocationPermission() =
        ContextCompat.checkSelfPermission(this, Manifest.permission.ACCESS_FINE_LOCATION) ==
            PackageManager.PERMISSION_GRANTED

    private fun createChannel() {
        val channel = NotificationChannel(CHANNEL_ID, "운동 기록", NotificationManager.IMPORTANCE_LOW)
            .apply { description = "운동 중 시간과 거리를 보여줍니다" }
        getSystemService(NotificationManager::class.java).createNotificationChannel(channel)
    }

    private fun buildNotification(s: WorkoutSnapshot): Notification {
        val openApp = PendingIntent.getActivity(
            this, 0,
            Intent(this, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP),
            PendingIntent.FLAG_IMMUTABLE
        )
        val paused = s.status == WorkoutStatus.PAUSED
        val title = when {
            s.countdown != null -> "곧 시작합니다"
            s.autoPaused -> "자동 일시정지"
            paused -> "일시정지"
            !s.hasFix -> "GPS 찾는 중"
            else -> activityLabel(s.activity)
        }
        val builder = NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_stat_run)
            .setContentTitle(title)
            .setContentText("${formatDuration(s.elapsedMs)} · ${formatKm(s.distanceM)}km · ${formatPace(s.currentPaceSecPerKm)}/km")
            .setContentIntent(openApp)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setSilent(true)
            .setCategory(NotificationCompat.CATEGORY_WORKOUT)
            .setForegroundServiceBehavior(NotificationCompat.FOREGROUND_SERVICE_IMMEDIATE)
        when (s.status) {
            WorkoutStatus.RUNNING -> builder.addAction(0, "일시정지", servicePendingIntent(ACTION_PAUSE))
            WorkoutStatus.PAUSED -> builder.addAction(0, "재개", servicePendingIntent(ACTION_RESUME))
            else -> {}
        }
        return builder.build()
    }

    private fun servicePendingIntent(action: String) = PendingIntent.getService(
        this, action.hashCode(),
        Intent(this, WorkoutService::class.java).setAction(action),
        PendingIntent.FLAG_IMMUTABLE
    )

    override fun onDestroy() {
        fused.removeLocationUpdates(locationCallback)
        unregisterStepSensor()
        voice?.releaseWhenDone()
        super.onDestroy()
    }

    companion object {
        private const val CHANNEL_ID = "workout"
        private const val NOTIFICATION_ID = 1
        private const val COUNTDOWN_FROM = 3
        const val ACTION_START = "com.mhmh2.bujirun.START"
        const val ACTION_PAUSE = "com.mhmh2.bujirun.PAUSE"
        const val ACTION_RESUME = "com.mhmh2.bujirun.RESUME"
        const val ACTION_STOP = "com.mhmh2.bujirun.STOP"
        private const val EXTRA_GOAL_TYPE = "goal_type"
        private const val EXTRA_GOAL_VALUE = "goal_value"

        fun start(context: Context, goal: Goal) {
            val (type, value) = goal.encode()
            val intent = Intent(context, WorkoutService::class.java)
                .setAction(ACTION_START)
                .putExtra(EXTRA_GOAL_TYPE, type)
                .putExtra(EXTRA_GOAL_VALUE, value)
            ContextCompat.startForegroundService(context, intent)
        }

        fun send(context: Context, action: String) {
            context.startService(Intent(context, WorkoutService::class.java).setAction(action))
        }
    }
}

private fun Location.toSample() = LocationSample(
    timeMs = elapsedRealtimeNanos / 1_000_000,
    lat = latitude,
    lon = longitude,
    accuracyM = if (hasAccuracy()) accuracy else Float.MAX_VALUE,
    altitudeM = if (hasAltitude()) altitude else null,
    speedMps = if (hasSpeed()) speed.toDouble() else null,
)
