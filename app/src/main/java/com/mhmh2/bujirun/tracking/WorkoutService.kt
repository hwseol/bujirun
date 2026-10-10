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
import com.mhmh2.bujirun.account.Account
import com.mhmh2.bujirun.course.CourseCue
import com.mhmh2.bujirun.course.CourseJoin
import com.mhmh2.bujirun.course.CourseNavigator
import com.mhmh2.bujirun.course.CourseRoute
import com.mhmh2.bujirun.course.CourseStore
import com.mhmh2.bujirun.course.GeoPoint
import com.mhmh2.bujirun.course.RouteApi
import com.mhmh2.bujirun.data.WorkoutStore
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.withTimeoutOrNull
import kotlin.math.roundToInt

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
    private var workoutId: Long? = null
    private var savedPoints = 0

    // Walk/run detection and auto-pause (see Motion.kt).
    private enum class PausedBy { USER, AUTO }
    private var pausedBy: PausedBy? = null
    private var speedMeter = SpeedMeter()
    private var cadenceMeter = CadenceMeter()
    private var classifier = ActivityClassifier()
    private var autoPause = AutoPauseDetector()
    private var stepSensorActive = false

    // Admin test runs: fixes come from FakeRun instead of GPS, and the workout clock can run
    // faster than real time. 0 = a real workout. Everything that reads "now" goes through now(),
    // so the tracker, coach and auto-pause all see one consistent (possibly sped-up) timeline.
    private var simScale = 0
    private var clockOriginReal = 0L
    private var epochOrigin = 0L
    private var simJob: Job? = null
    private var lastNotifyReal = 0L

    private fun now(): Long {
        val real = SystemClock.elapsedRealtime()
        return if (simScale <= 1) real else clockOriginReal + (real - clockOriginReal) * simScale
    }

    // Following a course (null for a free run).
    private var courseId: Long? = null
    private var navigator: CourseNavigator? = null
    private var courseName = ""
    private var position: GeoPoint? = null
    private var rerouting = false

    private fun onSample(sample: LocationSample) {
        val t = tracker ?: return
        speedMeter.add(sample)
        t.onLocation(sample)
        if (sample.accuracyM <= 25f) position = GeoPoint(sample.lat, sample.lon)
    }

    private val locationCallback = object : LocationCallback() {
        override fun onLocationResult(result: LocationResult) {
            result.locations.forEach { onSample(it.toSample()) }
        }
    }

    /** [path]: run along this course instead of wandering from a random Seoul spot. */
    private fun startFakeGps(path: List<GeoPoint>?) {
        val (lat, lon) = FakeRun.SEOUL_STARTS.random()
        val fake = FakeRun(System.nanoTime(), lat, lon, path)
        simJob = lifecycleScope.launch {
            while (isActive) {
                val f = fake.next()
                val t = now()
                onSample(
                    LocationSample(
                        timeMs = t, lat = f.lat, lon = f.lon, accuracyM = f.accuracyM, altitudeM = f.altitudeM,
                        speedMps = f.speedMps, epochMs = epochOrigin + (t - clockOriginReal),
                    )
                )
                delay(1000L / simScale)
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
        WorkoutStore.init(this)
        Account.init(this) // the walking route to the course needs the session token
        fused = LocationServices.getFusedLocationProviderClient(this)
        createChannel()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        super.onStartCommand(intent, flags, startId)
        val now = now()
        when (intent?.action) {
            ACTION_START -> start(
                Goal.decode(intent.getStringExtra(EXTRA_GOAL_TYPE), intent.getDoubleExtra(EXTRA_GOAL_VALUE, 0.0)),
                simScale = intent.getIntExtra(EXTRA_SIM_SCALE, 0),
                courseId = intent.getLongExtra(EXTRA_COURSE_ID, 0L).takeIf { it > 0 },
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
            ACTION_REROUTE -> reroute()
        }
        publish()
        // If the system kills us anyway, don't come back with a half-initialised empty workout -
        // restoring an interrupted workout is M4's job (it needs the saved track).
        return START_NOT_STICKY
    }

    private fun start(goal: Goal, simScale: Int, courseId: Long?) {
        if (tracker != null) return
        if (!hasLocationPermission()) {
            stopSelf()
            return
        }
        this.goal = goal
        this.simScale = simScale
        this.courseId = courseId
        navigator = null
        position = null
        rerouting = false
        clockOriginReal = SystemClock.elapsedRealtime()
        epochOrigin = System.currentTimeMillis()
        ServiceCompat.startForeground(
            this,
            NOTIFICATION_ID,
            buildNotification(WorkoutSnapshot(goal = goal, countdown = COUNTDOWN_FROM)),
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) ServiceInfo.FOREGROUND_SERVICE_TYPE_LOCATION else 0
        )
        val t = WorkoutTracker(weightKg = Prefs.weightKg)
        tracker = t
        coach = CoachEngine(goal, Prefs.cueInterval)
        voice = VoiceAnnouncer(this)
        pausedBy = null
        workoutId = null
        savedPoints = 0
        speedMeter = SpeedMeter()
        cadenceMeter = CadenceMeter()
        classifier = ActivityClassifier()
        autoPause = AutoPauseDetector()
        // GPS starts now so it has the countdown to get a fix before the clock runs. A test run
        // uses neither GPS nor the step sensor (the phone isn't actually moving).
        if (simScale <= 0) {
            requestLocationUpdates()
            stepSensorActive = registerStepSensor()
        }
        val tickMs = 1000L / maxOf(simScale, 1)
        ticker = lifecycleScope.launch {
            // The course (a quick local read) before anything needs it; a test run then runs along it.
            val course = courseId?.let { CourseStore.load(it) }
            if (simScale > 0) startFakeGps(course?.second)
            // The course is run from where the runner is: found during the countdown (GPS fix,
            // then the walking route to the course if they're away from it).
            val joining = course?.let { (name, points) ->
                courseName = name
                launch {
                    val nav = joinCourse(name, points)
                    navigator = nav
                    // Said once the workout has begun (straight away if the join took longer).
                    while (countdown != null) delay(100)
                    say(Cue.Course(nav.begin()))
                }
            }
            for (n in COUNTDOWN_FROM downTo 1) {
                countdown = n
                say(Cue.Countdown(n))
                publish()
                delay(1000)
            }
            workoutId = WorkoutStore.create(
                goal, epochOrigin + (now() - clockOriginReal), simulated = simScale > 0, courseId = courseId.takeIf { course != null },
            )
            countdown = null
            t.start(now())
            say(Cue.Started(goal))
            if (joining != null && navigator == null) say(Cue.Course(CourseCue.Locating))
            var ticks = 0
            while (isActive) {
                updateMotion(now())
                val nav = navigator
                val here = position
                // Guidance keeps following the runner through an auto-pause (waiting at a crossing,
                // then crossing slowly); only a pause the runner chose stops it.
                if (nav != null && here != null && pausedBy != PausedBy.USER && t.status != WorkoutStatus.FINISHED) {
                    val cues = nav.update(here, now())
                    cues.forEach { say(Cue.Course(it)) }
                    autoReroute(nav, cues, now())
                }
                val snapshot = publish() ?: break
                coach?.onTick(snapshot)?.forEach(::say)
                // Save as we go, so a workout killed by the system mid-run isn't lost.
                if (++ticks % SAVE_EVERY_TICKS == 0) save(snapshot, finished = false)
                delay(tickMs)
            }
        }
    }

    /**
     * The navigator for [points] run from where the runner is now: waits (up to 12 s) for a
     * fix, then joins the course there (CourseJoin), with the walking route to it if it's away.
     * No fix: the course as saved, and the navigator talks the runner to its start.
     */
    private suspend fun joinCourse(name: String, points: List<GeoPoint>): CourseNavigator {
        val here = withTimeoutOrNull(12_000) { while (position == null) delay(250); position }
            ?: return CourseNavigator(name, CourseRoute(points))
        val (i, d) = CourseJoin.joinPoint(points, here)
        val approach = if (d > CourseJoin.JOIN_M) walkingRoute(here, points[i]) else null
        val run = CourseJoin.join(points, here, approach)
        val extra = (CourseRoute(run).lengthM - CourseRoute(points).lengthM).coerceAtLeast(0.0)
        return CourseNavigator(name, CourseRoute(run), approachM = extra)
    }

    /** The server's walking route, or null (offline, slow): callers fall back to a straight line. */
    private suspend fun walkingRoute(from: GeoPoint, to: GeoPoint): List<GeoPoint>? =
        runCatching { withTimeout(8_000) { RouteApi.walk(from, to) } }.getOrNull()?.takeIf { it.size >= 2 }

    // The runner took another road: after a while off the course (or going further from it), a
    // new route from where they are instead of repeating "off course". At most once a minute -
    // each is a walking-route request to the server.
    private var offSinceMs: Long? = null
    private var lastAutoRerouteMs = 0L

    private fun autoReroute(nav: CourseNavigator, cues: List<CourseCue>, now: Long) {
        val p = nav.progress()
        if (p.onCourse || !p.started || p.finished) { offSinceMs = null; return }
        val since = offSinceMs ?: now.also { offSinceMs = it }
        if (now - since >= AUTO_REROUTE_AFTER_MS && now - lastAutoRerouteMs >= 60_000) {
            lastAutoRerouteMs = now
            offSinceMs = null
            reroute()
        }
    }

    /** "경로 다시 찾기": a new route from here to the nearest part of the course not yet run. */
    private fun reroute() {
        val nav = navigator ?: return
        if (rerouting) return
        val here = position ?: run { say(Cue.Course(CourseCue.RerouteNoFix)); return }
        rerouting = true
        publish()
        lifecycleScope.launch {
            val route = nav.route
            val done = nav.progress().progressM
            val (at, d) = CourseJoin.rejoinPoint(route, done, here)
            val approach = if (d > CourseJoin.JOIN_M) walkingRoute(here, route.pointAt(at)) else null
            val run = CourseJoin.rejoin(route, done, here, approach)
            val newRoute = CourseRoute(run)
            rerouting = false
            if (navigator !== nav || tracker == null) return@launch // stopped or rerouted meanwhile
            navigator = CourseNavigator(courseName, newRoute, announceHalfway = false)
            val toCourse = (newRoute.lengthM - (route.lengthM - at)).coerceAtLeast(0.0)
            say(Cue.Course(CourseCue.Rerouted(((toCourse / 10).roundToInt() * 10))))
            publish()
        }
    }

    private fun save(snapshot: WorkoutSnapshot, finished: Boolean) {
        val t = tracker ?: return
        val id = workoutId ?: return
        val newPoints = t.track.subList(savedPoints, t.track.size).toList()
        savedPoints = t.track.size
        WorkoutStore.flush(id, newPoints, snapshot, finished) { kept ->
            // Too short to be a real workout (an accidental start): skip the summary too.
            if (!kept) WorkoutSession.publish(WorkoutSnapshot())
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
            publish()?.let { save(it, finished = true) }
            say(Cue.Finished(t.distanceM, t.elapsedMs(now), t.averagePaceSecPerKm(now), t.calories))
        }
        voice?.releaseWhenDone()
        tracker = null
        coach = null
        voice = null
        countdown = null
        pausedBy = null
        simJob?.cancel()
        simJob = null
        simScale = 0
        navigator = null
        courseId = null
        rerouting = false
        offSinceMs = null
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
        val snapshot = t.snapshot(now()).copy(
            workoutId = workoutId, goal = goal, countdown = countdown,
            autoPaused = pausedBy == PausedBy.AUTO, simScale = simScale,
            courseId = courseId.takeIf { navigator != null }, course = navigator?.progress(), position = position,
            courseRoute = navigator?.route?.points, rerouting = rerouting,
        )
        WorkoutSession.publish(snapshot)
        // At most once a real second: a 10x test run ticks ten times a second, and Android
        // drops notification updates from apps that post faster than that.
        val real = SystemClock.elapsedRealtime()
        if (snapshot.status != WorkoutStatus.FINISHED && real - lastNotifyReal >= 1000) {
            lastNotifyReal = real
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
        // Lost on a course: the way back from the lock screen, without opening the app.
        if (s.course != null && s.countdown == null) builder.addAction(0, "경로 다시 찾기", servicePendingIntent(ACTION_REROUTE))
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
        private const val SAVE_EVERY_TICKS = 10
        const val ACTION_START = "com.mhmh2.bujirun.START"
        const val ACTION_PAUSE = "com.mhmh2.bujirun.PAUSE"
        const val ACTION_RESUME = "com.mhmh2.bujirun.RESUME"
        const val ACTION_STOP = "com.mhmh2.bujirun.STOP"
        const val ACTION_REROUTE = "com.mhmh2.bujirun.REROUTE"
        private const val AUTO_REROUTE_AFTER_MS = 30_000L
        private const val EXTRA_GOAL_TYPE = "goal_type"
        private const val EXTRA_GOAL_VALUE = "goal_value"
        private const val EXTRA_SIM_SCALE = "sim_scale"
        private const val EXTRA_COURSE_ID = "course_id"

        /**
         * [simScale] > 0: an admin test run with fake GPS at that many times real speed.
         * [courseId]: follow that course with voice guidance.
         */
        fun start(context: Context, goal: Goal, simScale: Int = 0, courseId: Long? = null) {
            val (type, value) = goal.encode()
            val intent = Intent(context, WorkoutService::class.java)
                .setAction(ACTION_START)
                .putExtra(EXTRA_GOAL_TYPE, type)
                .putExtra(EXTRA_GOAL_VALUE, value)
                .putExtra(EXTRA_SIM_SCALE, simScale)
                .putExtra(EXTRA_COURSE_ID, courseId ?: 0L)
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
    epochMs = time,
)
