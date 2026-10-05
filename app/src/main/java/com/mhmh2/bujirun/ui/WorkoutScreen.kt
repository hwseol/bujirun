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
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
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
import com.mhmh2.bujirun.tracking.WorkoutService
import com.mhmh2.bujirun.tracking.WorkoutSession
import com.mhmh2.bujirun.tracking.WorkoutSnapshot
import com.mhmh2.bujirun.tracking.WorkoutStatus
import com.mhmh2.bujirun.tracking.formatDuration
import com.mhmh2.bujirun.tracking.formatKm
import com.mhmh2.bujirun.tracking.formatPace

/**
 * M1 screen: just enough to start, watch and stop a recording. The real home / workout /
 * summary screens (docs/PHASE1.md section 1) replace this in M2 and M4.
 */
@Composable
fun WorkoutScreen() {
    val context = LocalContext.current
    val state by WorkoutSession.state.collectAsState()
    var permissionDenied by remember { mutableStateOf(false) }

    val permissionLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { result ->
        // Notifications are optional (the service still records without them); location isn't.
        if (result[Manifest.permission.ACCESS_FINE_LOCATION] == true) {
            permissionDenied = false
            WorkoutService.send(context, WorkoutService.ACTION_START)
        } else {
            permissionDenied = true
        }
    }

    fun startWorkout() {
        val needed = buildList {
            add(Manifest.permission.ACCESS_FINE_LOCATION)
            add(Manifest.permission.ACCESS_COARSE_LOCATION)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) add(Manifest.permission.POST_NOTIFICATIONS)
        }
        val missing = needed.filter {
            ContextCompat.checkSelfPermission(context, it) != PackageManager.PERMISSION_GRANTED
        }
        if (missing.isEmpty()) WorkoutService.send(context, WorkoutService.ACTION_START)
        else permissionLauncher.launch(missing.toTypedArray())
    }

    Column(
        modifier = Modifier.fillMaxSize().safeDrawingPadding().padding(24.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center
    ) {
        when (state.status) {
            WorkoutStatus.IDLE -> {
                Text("부지런", style = MaterialTheme.typography.headlineLarge)
                Spacer(Modifier.height(48.dp))
                Button(onClick = ::startWorkout, shape = CircleShape, modifier = Modifier.size(160.dp)) {
                    Text("시작", fontSize = 28.sp)
                }
                if (permissionDenied) {
                    Spacer(Modifier.height(24.dp))
                    Text(
                        "기록하려면 위치 권한이 필요해요",
                        color = MaterialTheme.colorScheme.error,
                        style = MaterialTheme.typography.bodyMedium
                    )
                }
            }
            WorkoutStatus.RUNNING, WorkoutStatus.PAUSED -> LiveStats(
                state,
                onPause = { WorkoutService.send(context, WorkoutService.ACTION_PAUSE) },
                onResume = { WorkoutService.send(context, WorkoutService.ACTION_RESUME) },
                onStop = { WorkoutService.send(context, WorkoutService.ACTION_STOP) }
            )
            WorkoutStatus.FINISHED -> {
                Text("운동 끝", style = MaterialTheme.typography.headlineLarge)
                Spacer(Modifier.height(32.dp))
                StatsGrid(state, showCurrentPace = false)
                Spacer(Modifier.height(48.dp))
                Button(onClick = WorkoutSession::reset) { Text("확인") }
            }
        }
    }
}

@Composable
private fun LiveStats(state: WorkoutSnapshot, onPause: () -> Unit, onResume: () -> Unit, onStop: () -> Unit) {
    val label = when {
        state.status == WorkoutStatus.PAUSED -> "일시정지"
        !state.hasFix -> "GPS 찾는 중…"
        else -> "기록 중"
    }
    Text(label, style = MaterialTheme.typography.titleMedium, color = MaterialTheme.colorScheme.primary)
    Spacer(Modifier.height(24.dp))
    StatsGrid(state, showCurrentPace = true)
    Spacer(Modifier.height(48.dp))
    Row(horizontalArrangement = Arrangement.spacedBy(16.dp), modifier = Modifier.fillMaxWidth()) {
        if (state.status == WorkoutStatus.PAUSED) {
            Button(onClick = onResume, modifier = Modifier.weight(1f)) { Text("재개") }
        } else {
            OutlinedButton(onClick = onPause, modifier = Modifier.weight(1f)) { Text("일시정지") }
        }
        OutlinedButton(onClick = onStop, modifier = Modifier.weight(1f)) { Text("종료") }
    }
}

@Composable
private fun StatsGrid(state: WorkoutSnapshot, showCurrentPace: Boolean) {
    Column(horizontalAlignment = Alignment.CenterHorizontally) {
        Stat("시간", formatDuration(state.elapsedMs), big = true)
        Spacer(Modifier.height(24.dp))
        Row(horizontalArrangement = Arrangement.SpaceEvenly, modifier = Modifier.fillMaxWidth()) {
            Stat("거리 (km)", formatKm(state.distanceM))
            if (showCurrentPace) Stat("현재 페이스", formatPace(state.currentPaceSecPerKm))
            Stat("평균 페이스", formatPace(state.averagePaceSecPerKm))
        }
    }
}

@Composable
private fun Stat(label: String, value: String, big: Boolean = false) {
    Column(horizontalAlignment = Alignment.CenterHorizontally) {
        Text(label, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        Text(value, fontSize = if (big) 56.sp else 28.sp, style = MaterialTheme.typography.headlineSmall)
    }
}
