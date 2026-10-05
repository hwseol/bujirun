package com.mhmh2.bujirun.tracking

import com.mhmh2.bujirun.coach.Goal

enum class WorkoutStatus { IDLE, RUNNING, PAUSED, FINISHED }

/** Everything the screen and the notification show, frozen at one moment. */
data class WorkoutSnapshot(
    val goal: Goal = Goal.Free,
    // 3, 2, 1 before the clock starts (GPS is already warming up meanwhile); null otherwise.
    val countdown: Int? = null,
    val status: WorkoutStatus = WorkoutStatus.IDLE,
    val elapsedMs: Long = 0,
    val distanceM: Double = 0.0,
    val currentPaceSecPerKm: Double? = null,
    val averagePaceSecPerKm: Double? = null,
    val hasFix: Boolean = false,
    val pointCount: Int = 0,
)

/**
 * Turns a stream of GPS fixes plus start/pause/resume/finish into time and distance.
 * Pure logic: the caller supplies every timestamp (elapsed-realtime ms), so tests can drive it
 * with a fake clock.
 */
class WorkoutTracker(
    private val filter: TrackFilter = TrackFilter(),
    // Standing still, GPS still wanders a few metres; adding every wobble would grow the
    // distance while not moving. Distance is only counted once we're this far from the last
    // counted point (the anchor isn't moved by smaller steps, so slow walking still adds up).
    private val minStepM: Double = 3.0,
    private val paceWindowMs: Long = 30_000,
    private val minPaceDistanceM: Double = 20.0,
) {
    var status = WorkoutStatus.IDLE
        private set
    var distanceM = 0.0
        private set
    var hasFix = false
        private set

    private var activeMsBeforeSegment = 0L
    private var segmentStartMs = 0L
    private var segment = 0
    private var lastAccepted: LocationSample? = null
    private var anchor: LocationSample? = null
    private val points = mutableListOf<TrackPoint>()
    // (time, cumulative distance) of recent counted points, for the rolling current pace.
    private val recent = ArrayDeque<Pair<Long, Double>>()

    val track: List<TrackPoint> get() = points

    fun start(nowMs: Long) {
        check(status == WorkoutStatus.IDLE) { "already started" }
        status = WorkoutStatus.RUNNING
        segmentStartMs = nowMs
    }

    fun pause(nowMs: Long) {
        if (status != WorkoutStatus.RUNNING) return
        activeMsBeforeSegment += nowMs - segmentStartMs
        status = WorkoutStatus.PAUSED
        // Whatever distance is covered while paused (walking back to the start, etc.) must not
        // count: the first fix after resuming becomes a fresh anchor.
        anchor = null
        lastAccepted = null
        recent.clear()
    }

    fun resume(nowMs: Long) {
        if (status != WorkoutStatus.PAUSED) return
        status = WorkoutStatus.RUNNING
        segmentStartMs = nowMs
        segment++
    }

    fun finish(nowMs: Long) {
        pause(nowMs)
        status = WorkoutStatus.FINISHED
    }

    fun elapsedMs(nowMs: Long): Long =
        activeMsBeforeSegment + if (status == WorkoutStatus.RUNNING) nowMs - segmentStartMs else 0

    fun onLocation(sample: LocationSample) {
        if (filter.isPrecise(sample)) hasFix = true
        if (status != WorkoutStatus.RUNNING) return
        if (!filter.accept(lastAccepted, sample)) return
        lastAccepted = sample

        val from = anchor
        if (from == null) {
            countPoint(sample)
            return
        }
        val step = distanceMeters(from, sample)
        if (step < minStepM) return
        distanceM += step
        countPoint(sample)
    }

    private fun countPoint(sample: LocationSample) {
        anchor = sample
        points += TrackPoint(sample, segment)
        recent.addLast(sample.timeMs to distanceM)
    }

    /**
     * Pace over roughly the last [paceWindowMs], measured up to [nowMs] rather than to the last
     * fix - so if the runner stops, the pace visibly slows instead of freezing at its last value.
     */
    fun currentPaceSecPerKm(nowMs: Long): Double? {
        if (status != WorkoutStatus.RUNNING) return null
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
        elapsedMs = elapsedMs(nowMs),
        distanceM = distanceM,
        currentPaceSecPerKm = currentPaceSecPerKm(nowMs),
        averagePaceSecPerKm = averagePaceSecPerKm(nowMs),
        hasFix = hasFix,
        pointCount = points.size,
    )
}
