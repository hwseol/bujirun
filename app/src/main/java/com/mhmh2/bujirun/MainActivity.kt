package com.mhmh2.bujirun

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.Surface
import androidx.compose.ui.Modifier
import com.mhmh2.bujirun.data.WorkoutStore
import com.mhmh2.bujirun.tracking.WorkoutSession
import com.mhmh2.bujirun.tracking.WorkoutStatus
import com.mhmh2.bujirun.ui.BujirunApp
import com.mhmh2.bujirun.ui.theme.BujirunTheme

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        Prefs.init(this)
        WorkoutStore.init(this)
        // Nothing is recording in this process (a running workout would have moved the session
        // off IDLE), so any workout still marked in-progress was cut short by the process dying.
        val s = WorkoutSession.state.value
        if (s.status == WorkoutStatus.IDLE && s.countdown == null) WorkoutStore.recoverInterrupted()
        enableEdgeToEdge()
        setContent {
            BujirunTheme {
                Surface(modifier = Modifier.fillMaxSize()) {
                    BujirunApp()
                }
            }
        }
    }
}
