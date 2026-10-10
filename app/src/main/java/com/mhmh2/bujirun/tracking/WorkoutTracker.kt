package com.mhmh2.bujirun.tracking

import com.mhmh2.bujirun.coach.Goal

enum class WorkoutStatus { IDLE, RUNNING, PAUSED, FINISHED }

/** Everything the screen and the notification show, frozen at one moment. */
data class WorkoutSnapshot(
    // Row in WorkoutStore, once the clock has started.
    val workoutId: Long? = null,
    // > 0 during an admin fake-GPS test run (the speed-up factor).
    val simScale: Int = 0,
    // Following a course: which one, where we are on it and what's next.
    val courseId: Long? = null,
    val course: com.mhmh2.bujirun.course.CourseProgress? = null,
    // The line being guided along: the course as joined from where the runner started (or the
    // way back to it after "경로 다시 찾기"), which isn't the saved course's own line.
    val courseRoute: List<com.mhmh2.bujirun.course.GeoPoint>? = null,
    // Looking for a new route back to the course right now.
    val rerouting: Boolean = false,
    // Last precise position (for the live map).
    val position: com.mhmh2.bujirun.course.GeoPoint? = null,
    val goal: Goal = Goal.Free,
    // 3, 2, 1 before the clock starts (GPS is already warming up meanwhile); null otherwise.
    val countdown: Int? = null,
    val status: WorkoutStatus = WorkoutStatus.IDLE,
    val autoPaused: Boolean = false,
    val activity: Activity = Activity.UNKNOWN,
    val elapsedMs: Long = 0,
    val distanceM: Double = 0.0,
    val currentPaceSecPerKm: Double? = null,
    val averagePaceSecPerKm: Double? = null,
    val calories: Double = 0.0,
    val ascentM: Double = 0.0,
    val runMs: Long = 0,
    val walkMs: Long = 0,
    val hasFix: Boolean = false,
    val pointCount: Int = 0,
)

/**
 * Turns a stream of GPS fixes plus start/pause/resume/finish into time, distance and calories.
 * Pure logic: the caller supplies every timestamp (elapsed-realtime ms), so tests can drive it
 * with a fake clock.
 */
class WorkoutTracker(
    private val weightKg: Double = 65.0,
    private val filter: TrackFilter = TrackFilter(),
    // Standing still, GPS still wanders a few metres; adding every wobble would grow the
    // distance while not moving. Distance is only counted once we're this far from the last
    // counted point (the anchor isn't moved by smaller steps, so slow walking still adds up).
    private val minStepM: Double = 3.0,
    private val paceWindowMs: Long = 30_000,
    private val minPaceDistanceM: Double = 20.0,
    private val speedWindowMs: Long = 12_000,
) {
    private companion object {
        const val STALE_SPEED_MS = 5_000L   // no speed reading for this long: don't trust the last ones
        const val MIN_MOVING_MPS = 0.5      // slower than this is standing; distance tells it better
    }

    var status = WorkoutStatus.IDLE
        private set
    var distanceM = 0.0
        private set
    var calories = 0.0
        private set
    var hasFix = false
        private set

    /** Set by the caller from [ActivityClassifier]; labels new points and active time. */
    var activity = Activity.UNKNOWN

    private val elevation = ElevationTracker()
    private var activeMsBeforeSegment = 0L
    private var segmentStartMs = 0L
    private var segment = 0
    private var lastAccepted: LocationSample? = null
    private var anchor: LocationSample? = null
    private val points = mutableListOf<TrackPoint>()
    // (time, cumulative distance) of recent counted points, for the rolling current pace.
    private val recent = ArrayDeque<Pair<Long, Double>>()
    // (time, m/s) of the GPS chip's own speed readings (Doppler), the steadier source of pace.
    private val speeds = ArrayDeque<Pair<Long, Double>>()
    private val activityMs = mutableMapOf<Activity, Long>()
    private var lastTickMs: Long? = null

    val track: List<TrackPoint> get() = points

    fun start(nowMs: Long) {
        check(status == WorkoutStatus.IDLE) { "already started" }
        status = WorkoutStatus.RUNNING
        segmentStartMs = nowMs
        lastTickMs = nowMs
    }

    fun pause(nowMs: Long) {
        if (status != WorkoutStatus.RUNNING) return
        tick(nowMs)
        activeMsBeforeSegment += nowMs - segmentStartMs
        status = WorkoutStatus.PAUSED
        // Whatever distance is covered while paused (walking back to the start, etc.) must not
        // count: the first fix after resuming becomes a fresh anchor.
        anchor = null
        lastAccepted = null
        recent.clear()
        speeds.clear()
        elevation.breakSegment()
    }

    fun resume(nowMs: Long) {
        if (status != WorkoutStatus.PAUSED) return
        status = WorkoutStatus.RUNNING
        segmentStartMs = nowMs
        lastTickMs = nowMs
        segment++
    }

    fun finish(nowMs: Long) {
        pause(nowMs)
        status = WorkoutStatus.FINISHED
    }

    /** Credits the active time since the last tick to the current activity (run/walk split). */
    fun tick(nowMs: Long) {
        val last = lastTickMs
        if (status == WorkoutStatus.RUNNING && last != null && nowMs > last) {
            activityMs[activity] = (activityMs[activity] ?: 0L) + (nowMs - last)
        }
        lastTickMs = nowMs
    }

    fun elapsedMs(nowMs: Long): Long =
        activeMsBeforeSegment + if (status == WorkoutStatus.RUNNING) nowMs - segmentStartMs else 0

    fun onLocation(sample: LocationSample) {
        if (filter.isPrecise(sample)) hasFix = true
        if (status != WorkoutStatus.RUNNING) return
        if (!filter.accept(lastAccepted, sample)) return
        lastAccepted = sample
        sample.speedMps?.let { speeds.addLast(sample.timeMs to it) }

        val from = anchor
        if (from == null) {
            countPoint(sample)
            return
        }
        val step = distanceMeters(from, sample)
        if (step < minStepM) return
        distanceM += step
        elevation.add(sample.altitudeM, step)
        calories += Calories.kcal(activity, step, (sample.timeMs - from.timeMs) / 1000.0, elevation.grade, weightKg)
        countPoint(sample)
    }

    private fun countPoint(sample: LocationSample) {
        anchor = sample
        // A fix can be timestamped a moment before the segment (re)started; never let active time
        // run backwards along the track.
        val activeMs = maxOf(elapsedMs(sample.timeMs), points.lastOrNull()?.activeMs ?: 0L)
        points += TrackPoint(sample, segment, activity, distanceM, activeMs)
        recent.addLast(sample.timeMs to distanceM)
    }

    /**
     * What the runner is doing now. Primarily the GPS chip's own speed (Doppler) averaged over
     * [speedWindowMs]: it measures motion directly, while distance between fixes zigzags with
     * position noise (too fast in the open, jumpy under trees and between buildings). Falls back
     * to distance over [paceWindowMs] when there are no fresh speed readings, or when they read
     * far below the distance actually covered (some devices, the emulator among them, report a
     * confident 0 while moving).
     */
    fun currentPaceSecPerKm(nowMs: Long): Double? {
        if (status != WorkoutStatus.RUNNING) return null
        val byDistance = distancePaceSecPerKm(nowMs)
        while (speeds.isNotEmpty() && speeds.first().first < nowMs - speedWindowMs) speeds.removeFirst()
        val fresh = speeds.isNotEmpty() && speeds.last().first >= nowMs - STALE_SPEED_MS
        if (!fresh || speeds.size < 3) return byDistance
        val mps = speeds.sumOf { it.second } / speeds.size
        val distanceMps = byDistance?.let { 1000.0 / it }
        if (distanceMps != null && mps < 0.5 * distanceMps) return byDistance
        if (mps < MIN_MOVING_MPS) return byDistance
        return 1000.0 / mps
    }

    /**
     * Pace over roughly the last [paceWindowMs], measured up to [nowMs] rather than to the last
     * fix - so if the runner stops, the pace visibly slows instead of freezing at its last value.
     */
    private fun distancePaceSecPerKm(nowMs: Long): Double? {
        while (recent.size > 1 && recent[1].first <= nowMs - paceWindowMs) recent.removeFirst()
        val (startMs, startDist) = recent.firstOrNull() ?: return null
        val meters = distanceM - startDist
        val seconds = (nowMs - startMs) / 1000.0
        if (meters < minPaceDistanceM || seconds <= 0) return null
        return seconds / (meters / 1000.0)
    }

    fun averagePaceSecPerKm(nowMs: Long): Double? {
        if (distanceM < minPaceDistanceM) return null
        return elapsedMs(nowMs) / 1000.0 / (distanceM / 1000.0)
    }

    fun snapshot(nowMs: Long) = WorkoutSnapshot(
        status = status,
        activity = activity,
        elapsedMs = elapsedMs(nowMs),
        distanceM = distanceM,
        currentPaceSecPerKm = currentPaceSecPerKm(nowMs),
        averagePaceSecPerKm = averagePaceSecPerKm(nowMs),
        calories = calories,
        ascentM = elevation.ascentM,
        runMs = activityMs[Activity.RUN] ?: 0L,
        walkMs = activityMs[Activity.WALK] ?: 0L,
        hasFix = hasFix,
        pointCount = points.size,
    )
}
