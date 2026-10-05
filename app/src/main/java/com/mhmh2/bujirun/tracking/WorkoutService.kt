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
import com.mhmh2.bujirun.R
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
    private var ticker: Job? = null

    private val locationCallback = object : LocationCallback() {
        override fun onLocationResult(result: LocationResult) {
            val t = tracker ?: return
            result.locations.forEach { t.onLocation(it.toSample()) }
        }
    }

    override fun onCreate() {
        super.onCreate()
        fused = LocationServices.getFusedLocationProviderClient(this)
        createChannel()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        super.onStartCommand(intent, flags, startId)
        val now = SystemClock.elapsedRealtime()
        when (intent?.action) {
            ACTION_START -> start(now)
            ACTION_PAUSE -> tracker?.pause(now)
            ACTION_RESUME -> tracker?.resume(now)
            ACTION_STOP -> stop(now)
        }
        publish()
        // If the system kills us anyway, don't come back with a half-initialised empty workout -
        // restoring an interrupted workout is M4's job (it needs the saved track).
        return START_NOT_STICKY
    }

    private fun start(now: Long) {
        if (tracker != null) return
        if (!hasLocationPermission()) {
            stopSelf()
            return
        }
        ServiceCompat.startForeground(
            this,
            NOTIFICATION_ID,
            buildNotification(WorkoutSnapshot(status = WorkoutStatus.RUNNING)),
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) ServiceInfo.FOREGROUND_SERVICE_TYPE_LOCATION else 0
        )
        tracker = WorkoutTracker().also { it.start(now) }
        requestLocationUpdates()
        ticker = lifecycleScope.launch {
            while (isActive) {
                publish()
                delay(1000)
            }
        }
    }

    private fun stop(now: Long) {
        val t = tracker ?: run { stopSelf(); return }
        t.finish(now)
        publish()
        tracker = null
        ticker?.cancel()
        fused.removeLocationUpdates(locationCallback)
        ServiceCompat.stopForeground(this, ServiceCompat.STOP_FOREGROUND_REMOVE)
        stopSelf()
    }

    private fun publish() {
        val t = tracker ?: return
        val snapshot = t.snapshot(SystemClock.elapsedRealtime())
        WorkoutSession.publish(snapshot)
        if (snapshot.status == WorkoutStatus.RUNNING || snapshot.status == WorkoutStatus.PAUSED) {
            getSystemService(NotificationManager::class.java).notify(NOTIFICATION_ID, buildNotification(snapshot))
        }
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
        val toggle = if (paused) {
            NotificationCompat.Action(0, "재개", servicePendingIntent(ACTION_RESUME))
        } else {
            NotificationCompat.Action(0, "일시정지", servicePendingIntent(ACTION_PAUSE))
        }
        val title = if (paused) "일시정지" else if (!s.hasFix) "GPS 찾는 중" else "운동 중"
        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_stat_run)
            .setContentTitle(title)
            .setContentText("${formatDuration(s.elapsedMs)} · ${formatKm(s.distanceM)}km · ${formatPace(s.currentPaceSecPerKm)}/km")
            .setContentIntent(openApp)
            .addAction(toggle)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setSilent(true)
            .setCategory(NotificationCompat.CATEGORY_WORKOUT)
            .setForegroundServiceBehavior(NotificationCompat.FOREGROUND_SERVICE_IMMEDIATE)
            .build()
    }

    private fun servicePendingIntent(action: String) = PendingIntent.getService(
        this, action.hashCode(),
        Intent(this, WorkoutService::class.java).setAction(action),
        PendingIntent.FLAG_IMMUTABLE
    )

    override fun onDestroy() {
        fused.removeLocationUpdates(locationCallback)
        super.onDestroy()
    }

    companion object {
        private const val CHANNEL_ID = "workout"
        private const val NOTIFICATION_ID = 1
        const val ACTION_START = "com.mhmh2.bujirun.START"
        const val ACTION_PAUSE = "com.mhmh2.bujirun.PAUSE"
        const val ACTION_RESUME = "com.mhmh2.bujirun.RESUME"
        const val ACTION_STOP = "com.mhmh2.bujirun.STOP"

        fun send(context: Context, action: String) {
            val intent = Intent(context, WorkoutService::class.java).setAction(action)
            if (action == ACTION_START) ContextCompat.startForegroundService(context, intent)
            else context.startService(intent)
        }
    }
}

private fun Location.toSample() = LocationSample(
    timeMs = elapsedRealtimeNanos / 1_000_000,
    lat = latitude,
    lon = longitude,
    accuracyM = if (hasAccuracy()) accuracy else Float.MAX_VALUE,
    altitudeM = if (hasAltitude()) altitude else null,
)
