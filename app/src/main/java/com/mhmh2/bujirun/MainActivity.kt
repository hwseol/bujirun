package com.mhmh2.bujirun

import android.content.Intent
import android.net.Uri
import android.os.Bundle
import androidx.core.content.IntentCompat
import androidx.lifecycle.lifecycleScope
import kotlinx.coroutines.launch
import com.mhmh2.bujirun.ui.PendingImport
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.Surface
import androidx.compose.ui.Modifier
import com.mhmh2.bujirun.account.Account
import com.mhmh2.bujirun.data.AutoExport
import com.mhmh2.bujirun.data.WorkoutStore
import com.mhmh2.bujirun.net.Notices
import com.mhmh2.bujirun.tracking.PassiveRecorder
import com.mhmh2.bujirun.tracking.WorkoutSession
import com.mhmh2.bujirun.tracking.WorkoutStatus
import com.mhmh2.bujirun.ui.BujirunApp
import com.mhmh2.bujirun.ui.theme.BujirunTheme

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        Prefs.init(this)
        Account.init(this)
        WorkoutStore.init(this)
        // Nothing is recording in this process (a running workout would have moved the session
        // off IDLE), so any workout still marked in-progress was cut short by the process dying.
        val s = WorkoutSession.state.value
        if (s.status == WorkoutStatus.IDLE && s.countdown == null) {
            WorkoutStore.recoverInterrupted { AutoExport.runPendingLater(this) }
        }
        handleGpx(intent)
        // A session the server no longer knows (account deleted, password reset) signs out.
        lifecycleScope.launch { Account.verify() }
        lifecycleScope.launch { Notices.checkNow() }
        Notices.schedule(this)
        // 자동 기록: keep the system listening, and count the steps since the last look.
        PassiveRecorder.apply(this)
        lifecycleScope.launch { runCatching { PassiveRecorder.check(this@MainActivity) } }
        enableEdgeToEdge()
        setContent {
            BujirunTheme {
                Surface(modifier = Modifier.fillMaxSize()) {
                    BujirunApp()
                }
            }
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        handleGpx(intent)
    }

    /** A GPX opened or shared from another app: hand it to the courses tab to import. */
    private fun handleGpx(intent: Intent?) {
        val uri: Uri? = when (intent?.action) {
            Intent.ACTION_VIEW -> intent.data
            Intent.ACTION_SEND -> IntentCompat.getParcelableExtra(intent, Intent.EXTRA_STREAM, Uri::class.java)
            else -> null
        }
        if (uri != null) PendingImport.uri.value = uri
    }
}
