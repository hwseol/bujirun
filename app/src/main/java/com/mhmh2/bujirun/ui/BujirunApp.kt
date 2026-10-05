package com.mhmh2.bujirun.ui

import android.Manifest
import android.content.pm.PackageManager
import android.os.Build
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.TextButton
import androidx.compose.ui.unit.dp
import com.mhmh2.bujirun.account.Account
import androidx.compose.foundation.layout.consumeWindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.List
import androidx.compose.material.icons.filled.Home
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.Icon
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.core.content.ContextCompat
import com.mhmh2.bujirun.Prefs
import com.mhmh2.bujirun.coach.Goal
import com.mhmh2.bujirun.data.WorkoutEntity
import com.mhmh2.bujirun.data.WorkoutStore
import com.mhmh2.bujirun.tracking.WorkoutService
import com.mhmh2.bujirun.tracking.WorkoutSession
import com.mhmh2.bujirun.tracking.WorkoutStatus
import com.mhmh2.bujirun.tracking.formatKm
import java.time.DayOfWeek
import java.time.LocalDate
import java.time.ZoneId
import java.time.temporal.TemporalAdjusters

private enum class Tab(val label: String, val icon: ImageVector) {
    HOME("홈", Icons.Filled.Home),
    HISTORY("기록", Icons.AutoMirrored.Filled.List),
    SETTINGS("설정", Icons.Filled.Settings),
}

/** Picks the screen from the workout state - the service is the source of truth, not the UI. */
@Composable
fun BujirunApp() {
    val context = LocalContext.current
    val state by WorkoutSession.state.collectAsState()
    val user by Account.user.collectAsState()
    var permissionDenied by remember { mutableStateOf(false) }
    var pendingGoal by remember { mutableStateOf<Goal>(Goal.Free) }
    var pendingSimScale by remember { mutableStateOf(0) }
    var adminChoiceFor by remember { mutableStateOf<Goal?>(null) }
    var tab by rememberSaveable { mutableStateOf(Tab.HOME) }
    var openWorkout by rememberSaveable { mutableStateOf<Long?>(null) }

    val permissionLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { result ->
        // Notifications and activity recognition are optional (recording still works without
        // them); location isn't.
        if (result[Manifest.permission.ACCESS_FINE_LOCATION] == true) {
            permissionDenied = false
            WorkoutService.start(context, pendingGoal, pendingSimScale)
        } else {
            permissionDenied = true
        }
    }

    // Login is required. A workout already running (e.g. after signing out mid-run, or the
    // activity being recreated) still takes precedence below, so it's never orphaned.
    val signedIn = user
    val workoutActive = state.countdown != null || state.status != WorkoutStatus.IDLE
    if (signedIn == null && !workoutActive) {
        LoginScreen()
        return
    }

    fun start(goal: Goal, simScale: Int = 0) {
        Prefs.lastGoal = goal
        pendingGoal = goal
        pendingSimScale = simScale
        val needed = buildList {
            add(Manifest.permission.ACCESS_FINE_LOCATION)
            add(Manifest.permission.ACCESS_COARSE_LOCATION)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) add(Manifest.permission.POST_NOTIFICATIONS)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) add(Manifest.permission.ACTIVITY_RECOGNITION)
        }
        val missing = needed.filter {
            ContextCompat.checkSelfPermission(context, it) != PackageManager.PERMISSION_GRANTED
        }
        if (missing.isEmpty()) WorkoutService.start(context, goal, simScale)
        else permissionLauncher.launch(missing.toTypedArray())
    }

    adminChoiceFor?.let { goal ->
        AdminStartDialog(
            onReal = { adminChoiceFor = null; start(goal) },
            onTest = { scale -> adminChoiceFor = null; start(goal, scale) },
            onDismiss = { adminChoiceFor = null },
        )
    }

    val countdown = state.countdown
    val finishedId = state.workoutId
    when {
        countdown != null -> CountdownScreen(countdown)
        state.status == WorkoutStatus.RUNNING || state.status == WorkoutStatus.PAUSED -> LiveWorkoutScreen(
            state,
            onPause = { WorkoutService.send(context, WorkoutService.ACTION_PAUSE) },
            onResume = { WorkoutService.send(context, WorkoutService.ACTION_RESUME) },
            onStop = { WorkoutService.send(context, WorkoutService.ACTION_STOP) }
        )
        state.status == WorkoutStatus.FINISHED && finishedId != null -> {
            BackHandler { WorkoutSession.reset() }
            SummaryScreen(finishedId, justFinished = true, onClose = {
                WorkoutSession.reset()
                tab = Tab.HOME
            })
        }
        state.status == WorkoutStatus.FINISHED -> LaunchedEffect(Unit) { WorkoutSession.reset() }
        openWorkout != null -> {
            BackHandler { openWorkout = null }
            SummaryScreen(openWorkout!!, justFinished = false, onClose = { openWorkout = null })
        }
        else -> Scaffold(
            bottomBar = {
                NavigationBar {
                    Tab.entries.forEach { t ->
                        NavigationBarItem(
                            selected = tab == t,
                            onClick = { tab = t },
                            icon = { Icon(t.icon, contentDescription = null) },
                            label = { Text(t.label) }
                        )
                    }
                }
            }
        ) { inner ->
            Box(Modifier.fillMaxSize().padding(inner).consumeWindowInsets(inner)) {
                when (tab) {
                    Tab.HOME -> {
                        val workouts by remember { WorkoutStore.observeDone() }.collectAsState(initial = emptyList())
                        HomeScreen(
                            remember { Prefs.lastGoal }, permissionDenied, weekSummary(workouts),
                            nickname = signedIn?.nickname,
                            onStart = { goal -> if (signedIn?.isAdmin == true) adminChoiceFor = goal else start(goal) },
                        )
                    }
                    Tab.HISTORY -> HistoryScreen(onOpen = { openWorkout = it })
                    Tab.SETTINGS -> SettingsScreen()
                }
            }
        }
    }
}

/** Admin only: a real workout, or a fake-GPS test run at 1x/5x/10x. */
@Composable
private fun AdminStartDialog(onReal: () -> Unit, onTest: (Int) -> Unit, onDismiss: () -> Unit) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("어떻게 시작할까요?") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Button(onClick = onReal, modifier = Modifier.fillMaxWidth()) { Text("실제 운동") }
                Text(
                    "테스트 (가짜 GPS · 기록에 '테스트'로 저장, 삼성헬스 제외)",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(top = 8.dp)
                )
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    listOf(1, 5, 10).forEach { scale ->
                        OutlinedButton(
                            onClick = { onTest(scale) },
                            contentPadding = PaddingValues(horizontal = 4.dp),
                            modifier = Modifier.weight(1f)
                        ) { Text("${scale}배속", maxLines = 1) }
                    }
                }
            }
        },
        confirmButton = {},
        dismissButton = { TextButton(onClick = onDismiss) { Text("취소") } }
    )
}

/** "이번 주 3회 · 12.40km", or null before the first workout of the week. */
private fun weekSummary(workouts: List<WorkoutEntity>): String? {
    val zone = ZoneId.systemDefault()
    val monday = LocalDate.now(zone).with(TemporalAdjusters.previousOrSame(DayOfWeek.MONDAY))
        .atStartOfDay(zone).toInstant().toEpochMilli()
    val week = workouts.filter { it.startedAt >= monday }
    if (week.isEmpty()) return null
    return "이번 주 ${week.size}회 · ${formatKm(week.sumOf { it.distanceM })}km"
}
