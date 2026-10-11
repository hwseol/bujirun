package com.mhmh2.bujirun.net

import android.Manifest
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat
import androidx.work.Constraints
import androidx.work.CoroutineWorker
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.NetworkType
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import com.mhmh2.bujirun.MainActivity
import com.mhmh2.bujirun.Prefs
import com.mhmh2.bujirun.R
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.util.concurrent.TimeUnit

/**
 * Notices from the server (backend/notices.py), each shown once: as a dialog when they arrive
 * with the app open, as a phone notification when found in the background. One small request
 * when the app opens and every few hours; nothing is pushed, so the server stays idle.
 */
object Notices {
    data class Notice(val id: Int, val title: String, val body: String)

    private val _waiting = MutableStateFlow<List<Notice>>(emptyList())
    /** Arrived while the app was open, for the app to show; [dismiss] the first once read. */
    val waiting: StateFlow<List<Notice>> = _waiting.asStateFlow()

    private val lock = Mutex()
    private const val CHANNEL_ID = "notices"
    private const val WORK = "notices"

    /** Notices not shown yet, marked shown as they're returned (so never shown twice). */
    private suspend fun fetchNew(): List<Notice> = lock.withLock {
        val after = Prefs.lastNoticeId
        val list = runCatching { Api.get("/notices?after=$after").optJSONArray("notices") }.getOrNull()
            ?: return emptyList()
        val new = (0 until list.length()).map { i ->
            list.getJSONObject(i).let { Notice(it.getInt("id"), it.getString("title"), it.getString("body")) }
        }.filter { it.id > after }
        new.maxOfOrNull { it.id }?.let { Prefs.lastNoticeId = it }
        new
    }

    /** The app opened: new notices go on screen. */
    suspend fun checkNow() {
        val new = fetchNew()
        if (new.isNotEmpty()) _waiting.value = _waiting.value + new
    }

    fun dismiss() {
        _waiting.value = _waiting.value.drop(1)
    }

    /** Looks for notices every 6 hours while the app isn't open (kept across restarts). */
    fun schedule(context: Context) {
        val request = PeriodicWorkRequestBuilder<NoticeWorker>(6, TimeUnit.HOURS)
            .setConstraints(Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build())
            .build()
        WorkManager.getInstance(context).enqueueUniquePeriodicWork(WORK, ExistingPeriodicWorkPolicy.KEEP, request)
    }

    internal suspend fun checkInBackground(context: Context) {
        val new = fetchNew()
        if (new.isEmpty()) return
        if (ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED &&
            android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.TIRAMISU
        ) {
            // No notifications allowed: hold them for the next time the app is opened instead.
            Prefs.lastNoticeId = new.first().id - 1
            return
        }
        context.getSystemService(NotificationManager::class.java).createNotificationChannel(
            NotificationChannel(CHANNEL_ID, "공지", NotificationManager.IMPORTANCE_DEFAULT)
                .apply { description = "부지런에서 보내는 소식" }
        )
        val open = PendingIntent.getActivity(
            context, 0, Intent(context, MainActivity::class.java), PendingIntent.FLAG_IMMUTABLE
        )
        new.forEach { n ->
            val notification = NotificationCompat.Builder(context, CHANNEL_ID)
                .setSmallIcon(R.drawable.ic_stat_run)
                .setContentTitle(n.title)
                .setContentText(n.body)
                .setStyle(NotificationCompat.BigTextStyle().bigText(n.body))
                .setContentIntent(open)
                .setAutoCancel(true)
                .build()
            runCatching { NotificationManagerCompat.from(context).notify(NOTIFICATION_BASE + n.id, notification) }
        }
    }

    // Away from the workout notification's id (1).
    private const val NOTIFICATION_BASE = 1000
}

class NoticeWorker(context: Context, params: WorkerParameters) : CoroutineWorker(context, params) {
    override suspend fun doWork(): Result {
        Prefs.init(applicationContext)
        Notices.checkInBackground(applicationContext)
        return Result.success()
    }
}
