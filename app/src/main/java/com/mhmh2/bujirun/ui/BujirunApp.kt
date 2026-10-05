package com.mhmh2.bujirun.ui

import android.Manifest
import android.content.pm.PackageManager
import android.os.Build
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.ContextCompat
import com.mhmh2.bujirun.Prefs
import com.mhmh2.bujirun.coach.Goal
import com.mhmh2.bujirun.tracking.WorkoutService
import com.mhmh2.bujirun.tracking.WorkoutSession
import com.mhmh2.bujirun.tracking.WorkoutSnapshot
import com.mhmh2.bujirun.tracking.WorkoutStatus
import com.mhmh2.bujirun.tracking.formatDuration
import com.mhmh2.bujirun.tracking.formatKm
import com.mhmh2.bujirun.tracking.formatPace

/** Picks the screen from the workout state - the service is the source of truth, not the UI. */
@Composable
fun BujirunApp() {
    val context = LocalContext.current
    val state by WorkoutSession.state.collectAsState()
    var permissionDenied by remember { mutableStateOf(false) }
    var pendingGoal by remember { mutableStateOf<Goal>(Goal.Free) }

    val permissionLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { result ->
        // Notifications are optional (the service still records without them); location isn't.
        if (result[Manifest.permission.ACCESS_FINE_LOCATION] == true) {
            permissionDenied = false
            WorkoutService.start(context, pendingGoal)
        } else {
            permissionDenied = true
        }
    }

    fun start(goal: Goal) {
        Prefs.lastGoal = goal
        pendingGoal = goal
        val needed = buildList {
            add(Manifest.permission.ACCESS_FINE_LOCATION)
            add(Manifest.permission.ACCESS_COARSE_LOCATION)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) add(Manifest.permission.POST_NOTIFICATIONS)
        }
        val missing = needed.filter {
            ContextCompat.checkSelfPermission(context, it) != PackageManager.PERMISSION_GRANTED
        }
        if (missing.isEmpty()) WorkoutService.start(context, goal)
        else permissionLauncher.launch(missing.toTypedArray())
    }

    val countdown = state.countdown
    when {
        countdown != null -> CountdownScreen(countdown)
        state.status == WorkoutStatus.RUNNING || state.status == WorkoutStatus.PAUSED -> LiveWorkoutScreen(
            state,
            onPause = { WorkoutService.send(context, WorkoutService.ACTION_PAUSE) },
            onResume = { WorkoutService.send(context, WorkoutService.ACTION_RESUME) },
            onStop = { WorkoutService.send(context, WorkoutService.ACTION_STOP) }
        )
        state.status == WorkoutStatus.FINISHED -> FinishedScreen(state, onDone = WorkoutSession::reset)
        else -> HomeScreen(remember { Prefs.lastGoal }, permissionDenied, onStart = ::start)
    }
}

/** Placeholder until the real summary (route, splits, calories) in M4. */
@Composable
private fun FinishedScreen(state: WorkoutSnapshot, onDone: () -> Unit) {
    Column(
        modifier = Modifier.fillMaxSize().safeDrawingPadding().padding(24.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center
    ) {
        Text("운동 끝", style = MaterialTheme.typography.headlineLarge)
        Spacer(Modifier.height(32.dp))
        Text(formatDuration(state.elapsedMs), fontSize = 56.sp)
        Spacer(Modifier.height(24.dp))
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceEvenly) {
            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                Text("거리", style = MaterialTheme.typography.bodySmall)
                Text("${formatKm(state.distanceM)}km", fontSize = 28.sp)
            }
            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                Text("평균 페이스", style = MaterialTheme.typography.bodySmall)
                Text(formatPace(state.averagePaceSecPerKm), fontSize = 28.sp)
            }
        }
        Spacer(Modifier.height(48.dp))
        Button(onClick = onDone) { Text("확인") }
    }
}
