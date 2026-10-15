package com.mhmh2.bujirun.ui

import com.mhmh2.bujirun.tr

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
import com.mhmh2.bujirun.net.Notices
import androidx.compose.ui.unit.dp
import com.mhmh2.bujirun.account.Account
import androidx.compose.foundation.layout.consumeWindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.List
import androidx.compose.material.icons.filled.Home
import androidx.compose.material.icons.filled.Map
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

private enum class Tab(private val ko: String, private val en: String, val icon: ImageVector) {
    HOME("홈", "Home", Icons.Filled.Home),
    COURSES("코스", "Courses", Icons.Filled.Map),
    HISTORY("기록", "History", Icons.AutoMirrored.Filled.List),
    SETTINGS("설정", "Settings", Icons.Filled.Settings);

    val label: String get() = tr(ko, en)
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
    var pendingCourse by remember { mutableStateOf<Long?>(null) }
    // Admin's "real or test?" question, holding what was about to start.
    var adminChoiceFor by remember { mutableStateOf<Pair<Goal, Long?>?>(null) }
    var tab by rememberSaveable { mutableStateOf(Tab.HOME) }
    var openWorkout by rememberSaveable { mutableStateOf<Long?>(null) }
    var openCourse by rememberSaveable { mutableStateOf<Long?>(null) }
    var drawingCourse by rememberSaveable { mutableStateOf(false) }
    var suggesting by rememberSaveable { mutableStateOf(false) }
    var suggestTab by rememberSaveable { mutableStateOf(0) }
    var aiCourse by rememberSaveable { mutableStateOf(false) }
    // A GPX opened from another app lands on the courses tab, which imports it.
    val pendingImport by PendingImport.uri.collectAsState()
    LaunchedEffect(pendingImport) { if (pendingImport != null) { openCourse = null; drawingCourse = false; tab = Tab.COURSES } }

    val permissionLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { result ->
        // Notifications and activity recognition are optional (recording still works without
        // them); location isn't.
        if (result[Manifest.permission.ACCESS_FINE_LOCATION] == true) {
            permissionDenied = false
            WorkoutService.start(context, pendingGoal, pendingSimScale, pendingCourse)
        } else {
            permissionDenied = true
        }
    }

    // Login is required. A workout already running (e.g. after signing out mid-run, or the
    // activity being recreated) still takes precedence below, so it's never orphaned.
    val signedIn = user
    // Whoever just signed in starts on home, not on whatever tab the previous session left open.
    LaunchedEffect(signedIn?.email) { if (PendingImport.uri.value == null) tab = Tab.HOME }
    val workoutActive = state.countdown != null || state.status != WorkoutStatus.IDLE
    if (signedIn == null && !workoutActive) {
        LoginScreen()
        return
    }

    // A notice from the server, one at a time.
    val notices by Notices.waiting.collectAsState()
    notices.firstOrNull()?.let { n ->
        AlertDialog(
            onDismissRequest = { Notices.dismiss() },
            title = { Text(n.title) },
            text = { Text(n.body) },
            confirmButton = { TextButton(onClick = { Notices.dismiss() }) { Text(tr("확인", "OK")) } },
        )
    }

    fun start(goal: Goal, simScale: Int = 0, courseId: Long? = null) {
        // A course run's distance goal is the course's, not a preference to remember.
        if (courseId == null) Prefs.lastGoal = goal
        pendingGoal = goal
        pendingSimScale = simScale
        pendingCourse = courseId
        val needed = buildList {
            add(Manifest.permission.ACCESS_FINE_LOCATION)
            add(Manifest.permission.ACCESS_COARSE_LOCATION)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) add(Manifest.permission.POST_NOTIFICATIONS)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) add(Manifest.permission.ACTIVITY_RECOGNITION)
        }
        val missing = needed.filter {
            ContextCompat.checkSelfPermission(context, it) != PackageManager.PERMISSION_GRANTED
        }
        if (missing.isEmpty()) WorkoutService.start(context, goal, simScale, courseId)
        else permissionLauncher.launch(missing.toTypedArray())
    }

    fun requestStart(goal: Goal, courseId: Long? = null) {
        if (signedIn?.isAdmin == true) adminChoiceFor = goal to courseId else start(goal, courseId = courseId)
    }

    adminChoiceFor?.let { (goal, courseId) ->
        AdminStartDialog(
            onReal = { adminChoiceFor = null; start(goal, courseId = courseId) },
            onTest = { scale -> adminChoiceFor = null; start(goal, scale, courseId) },
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
            onStop = { WorkoutService.send(context, WorkoutService.ACTION_STOP) },
            onReroute = { WorkoutService.send(context, WorkoutService.ACTION_REROUTE) },
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
        aiCourse -> {
            BackHandler { aiCourse = false }
            AiCourseScreen(
                onClose = { aiCourse = false },
                onOpen = { id -> aiCourse = false; openCourse = id },
                onRun = { id -> aiCourse = false; requestStart(Goal.Free, courseId = id) },
            )
        }
        suggesting -> {
            BackHandler { suggesting = false }
            SuggestScreen(
                initialTab = suggestTab,
                onClose = { suggesting = false },
                onOpen = { id -> suggesting = false; openCourse = id },
                onRun = { id -> suggesting = false; requestStart(Goal.Free, courseId = id) },
            )
        }
        drawingCourse -> {
            BackHandler { drawingCourse = false }
            CourseDrawScreen(
                onClose = { drawingCourse = false },
                onSaved = { id -> drawingCourse = false; openCourse = id },
            )
        }
        openCourse != null -> {
            BackHandler { openCourse = null }
            val id = openCourse!!
            CourseDetailScreen(id, onClose = { openCourse = null }, onRun = { distance ->
                openCourse = null
                // No separate distance goal: the course guidance covers halfway/finish, and a
                // goal would announce "goal reached" right beside "course finished".
                requestStart(Goal.Free, courseId = id)
            })
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
                        val autos by remember { WorkoutStore.observeAuto() }.collectAsState(initial = emptyList())
                        val daySteps by remember { WorkoutStore.observeDaySteps() }.collectAsState(initial = emptyList())
                        HomeScreen(
                            remember { Prefs.lastGoal }, permissionDenied, weekSummary(workouts, autos),
                            today = todaySummary(workouts, autos, daySteps),
                            nickname = signedIn?.nickname,
                            onStart = { goal -> requestStart(goal) },
                        )
                    }
                    Tab.COURSES -> CoursesScreen(
                        onOpen = { openCourse = it },
                        onDraw = { drawingCourse = true },
                        onSuggest = { t -> suggestTab = t; suggesting = true },
                        onAi = { aiCourse = true },
                    )
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
        title = { Text(tr("어떻게 시작할까요?", "How do you want to start?")) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Button(onClick = onReal, modifier = Modifier.fillMaxWidth()) { Text(tr("실제 운동", "Real workout")) }
                Text(
                    tr("테스트 (가짜 GPS · 기록에 '테스트'로 저장, 삼성헬스 제외)", "Test (fake GPS · saved as a test, never sent to Samsung Health)"),
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
                        ) { Text(tr("${scale}배속", "${scale}x"), maxLines = 1) }
                    }
                }
            }
        },
        confirmButton = {},
        dismissButton = { TextButton(onClick = onDismiss) { Text(tr("취소", "Cancel")) } }
    )
}

/** This week from Monday: workouts (test runs left out) and the walks and runs 자동 기록 found. */
private fun weekSummary(workouts: List<WorkoutEntity>, autos: List<com.mhmh2.bujirun.data.AutoActivityEntity>): WeekStats {
    val zone = ZoneId.systemDefault()
    val monday = LocalDate.now(zone).with(TemporalAdjusters.previousOrSame(DayOfWeek.MONDAY))
        .atStartOfDay(zone).toInstant().toEpochMilli()
    val week = workouts.filter { it.startedAt >= monday && !it.simulated }
    val auto = autos.filter { it.startedAt >= monday }
    return WeekStats(
        week.size + auto.size,
        week.sumOf { it.distanceM } + auto.sumOf { it.distanceM },
        week.sumOf { it.durationMs } + auto.sumOf { it.movingMs },
        week.sumOf { it.calories } + auto.sumOf { it.calories },
    )
}

/** Today so far: distance, active time and calories from workouts (no test runs) and 자동 기록,
 * and the phone's own step count (the day's count when 자동 기록 is on, else workout steps). */
private fun todaySummary(
    workouts: List<WorkoutEntity>, autos: List<com.mhmh2.bujirun.data.AutoActivityEntity>,
    daySteps: List<com.mhmh2.bujirun.data.DayStepsEntity>,
): TodayStats {
    val zone = ZoneId.systemDefault()
    val today = LocalDate.now(zone)
    val start = today.atStartOfDay(zone).toInstant().toEpochMilli()
    val ws = workouts.filter { it.startedAt >= start && !it.simulated }
    val au = autos.filter { it.startedAt >= start }
    val counted = daySteps.firstOrNull { it.day == today.toString() }?.steps
    return TodayStats(
        steps = counted ?: (ws.sumOf { it.steps.toLong() } + au.sumOf { it.steps }),
        distanceM = ws.sumOf { it.distanceM } + au.sumOf { it.distanceM },
        activeMs = ws.sumOf { it.durationMs } + au.sumOf { it.movingMs },
        calories = ws.sumOf { it.calories } + au.sumOf { it.calories },
    )
}
