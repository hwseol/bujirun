package com.mhmh2.bujirun.data

import com.mhmh2.bujirun.net.srv

import android.content.Context
import com.mhmh2.bujirun.Prefs
import com.mhmh2.bujirun.tracking.Splits
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/**
 * What the settings say to do with every finished workout without a tap: an event in the
 * (Google) calendar, the workout in Health Connect (Samsung Health). Run when a workout is
 * saved, and when the app opens - so a workout ended by swiping the app away, or recovered
 * after a crash, gets there too, not only one whose summary screen was looked at.
 * Each workout once per place; never a test run.
 */
object AutoExport {
    private val lock = Mutex()
    // Outlives the screen or service that asked (the service stops right after saving).
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    fun runPendingLater(context: Context) {
        val app = context.applicationContext
        scope.launch { runCatching { runPending(app) } }
    }

    /** Exports what's waiting; returns where each newly exported workout went (id to places). */
    suspend fun runPending(context: Context): Map<Long, List<String>> = lock.withLock {
        val done = mutableMapOf<Long, MutableList<String>>()
        val calendar = Prefs.autoCalendar.value && CalendarExport.hasPermission(context)
        val health = Prefs.autoHealth.value &&
            HealthExport.availability(context) == HealthExport.Availability.AVAILABLE &&
            runCatching { HealthExport.missingPermissions(context).isEmpty() }.getOrDefault(false)
        if (!calendar && !health) return done
        val since = minOf(
            if (calendar) Prefs.autoSince("calendar") else Long.MAX_VALUE,
            if (health) Prefs.autoSince("health") else Long.MAX_VALUE,
        )
        for (w in WorkoutStore.doneSince(since)) {
            val toCalendar = calendar && w.startedAt >= Prefs.autoSince("calendar") && !Prefs.exported("calendar", w.id)
            val toHealth = health && w.startedAt >= Prefs.autoSince("health") && !Prefs.exported("health", w.id)
            if (!toCalendar && !toHealth) continue
            val points = WorkoutStore.points(w.id)
            if (points.isEmpty()) continue
            if (toCalendar) {
                val course = w.courseId?.let { WorkoutStore.database.courses().get(it)?.let { c -> com.mhmh2.bujirun.ui.courseTitle(c) } }
                val splits = Splits.compute(points.map { it.toTrackPoint() }, w.distanceM, w.durationMs)
                val ok = runCatching {
                    CalendarExport.write(
                        context, w, WorkoutReport.title(w),
                        WorkoutReport.text(w, course, splits, WorkoutReport.mapsUrl(points.map { it.lat to it.lon })),
                    )
                }.getOrDefault(false)
                if (ok) {
                    Prefs.markExported("calendar", w.id)
                    done.getOrPut(w.id) { mutableListOf() } += "calendar"
                }
            }
            if (toHealth) {
                // Health Connect may refuse a write from the background; the next app open retries.
                runCatching { HealthExport.write(context, w, points) }.onSuccess {
                    Prefs.markExported("health", w.id)
                    done.getOrPut(w.id) { mutableListOf() } += "health"
                }
            }
        }
        done
    }
}
