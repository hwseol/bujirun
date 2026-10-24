package com.mhmh2.bujirun.ui

import android.app.Activity
import android.os.SystemClock
import android.widget.Toast
import androidx.activity.compose.BackHandler
import androidx.compose.ui.platform.LocalContext
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import com.mhmh2.bujirun.tr
import androidx.compose.ui.text.font.FontWeight

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.VolumeOff
import androidx.compose.material.icons.automirrored.filled.VolumeUp
import androidx.compose.material3.Button
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.draw.clip
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.mhmh2.bujirun.Prefs
import com.mhmh2.bujirun.coach.Goal
import com.mhmh2.bujirun.tracking.WorkoutSnapshot
import com.mhmh2.bujirun.tracking.WorkoutStatus
import com.mhmh2.bujirun.tracking.activityLabel
import com.mhmh2.bujirun.tracking.formatDuration
import com.mhmh2.bujirun.tracking.formatKm
import com.mhmh2.bujirun.tracking.formatPace
import kotlin.math.abs
import kotlin.math.roundToInt

@Composable
fun CountdownScreen(n: Int) {
    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
        Text(n.toString(), fontSize = 160.sp, color = MaterialTheme.colorScheme.primary)
    }
}

@Composable
fun LiveWorkoutScreen(
    state: WorkoutSnapshot,
    onPause: () -> Unit,
    onResume: () -> Unit,
    onStop: () -> Unit,
    onReroute: () -> Unit = {},
) {
    val voiceOn by Prefs.voiceEnabled.collectAsState()
    val paused = state.status == WorkoutStatus.PAUSED

    // Nothing to go back to mid-run, and a stray back press shouldn't make the app vanish: the
    // first one says what a second does (the app steps aside, the recording goes on).
    val context = LocalContext.current
    var backPressedAt by remember { mutableLongStateOf(0L) }
    BackHandler {
        val now = SystemClock.uptimeMillis()
        if (now - backPressedAt < 2_000) {
            (context as? Activity)?.moveTaskToBack(true)
        } else {
            backPressedAt = now
            Toast.makeText(context, tr("한 번 더 누르면 앱이 닫혀요. 기록은 계속돼요.", "Press back again to leave. Recording goes on."), Toast.LENGTH_SHORT).show()
        }
    }

    Column(
        modifier = Modifier.fillMaxSize().safeDrawingPadding().padding(horizontal = 24.dp, vertical = 16.dp),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            StatusBadge(
                when {
                    state.autoPaused -> tr("자동 일시정지", "Auto paused")
                    paused -> tr("일시정지", "Paused")
                    !state.hasFix -> tr("GPS 찾는 중", "Finding GPS")
                    else -> activityLabel(state.activity)
                },
                emphasized = !paused && state.hasFix
            )
            if (state.simScale > 0) {
                Spacer(Modifier.width(8.dp))
                StatusBadge(tr("테스트 ×${state.simScale}", "Test ×${state.simScale}"), emphasized = false)
            }
            Spacer(Modifier.weight(1f))
            IconButton(onClick = { Prefs.setVoiceEnabled(!voiceOn) }) {
                Icon(
                    if (voiceOn) Icons.AutoMirrored.Filled.VolumeUp else Icons.AutoMirrored.Filled.VolumeOff,
                    contentDescription = if (voiceOn) tr("음성 안내 끄기", "Turn voice off") else tr("음성 안내 켜기", "Turn voice on")
                )
            }
        }

        val course = state.course
        val route = state.courseRoute
        if (course != null && route != null) {
            // Following a course: the map is the main thing, numbers compact underneath.
            Spacer(Modifier.height(8.dp))
            CourseLiveMap(
                route, course, state.position, state.rerouting, onReroute,
                Modifier.weight(1f).fillMaxWidth().clip(RoundedCornerShape(20.dp)), trail = state.trail,
            )
            Spacer(Modifier.height(12.dp))
            Card(padding = 16.dp) {
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                    StatTile(tr("시간", "Time"), formatDuration(state.elapsedMs))
                    StatTile(tr("거리", "Distance"), formatKm(state.distanceM), "km")
                    StatTile(tr("페이스", "Pace"), formatPace(state.currentPaceSecPerKm))
                    if (course.lap != null) StatTile(tr("바퀴", "Lap"), course.laps?.let { "${course.lap}/$it" } ?: "${course.lap}")
                    else StatTile(tr("남은", "Left"), formatKm(course.remainingM), "km")
                }
            }
            Spacer(Modifier.height(12.dp))
        } else {
            // No course: a map of where you are and the way run so far, the numbers below it.
            Spacer(Modifier.height(8.dp))
            FreeLiveMap(state.trail, state.position, Modifier.weight(1f).fillMaxWidth().clip(RoundedCornerShape(20.dp)))
            Spacer(Modifier.height(12.dp))
            Card(padding = 16.dp) {
                Text(formatDuration(state.elapsedMs), style = Brand.number.copy(fontSize = 52.sp), modifier = Modifier.align(Alignment.CenterHorizontally))
                Spacer(Modifier.height(8.dp))
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                    StatTile(tr("거리", "Distance"), formatKm(state.distanceM), "km", big = true)
                    if (state.goal is Goal.Free) StatTile(tr("평균 페이스", "Avg pace"), formatPace(state.averagePaceSecPerKm), big = true)
                    else StatTile(tr("현재 페이스", "Pace"), formatPace(state.currentPaceSecPerKm), big = true)
                    StatTile(tr("칼로리", "Calories"), "${state.calories.roundToInt()}", "kcal", big = true)
                }
                if (state.goal !is Goal.Free) {
                    Spacer(Modifier.height(12.dp))
                    GoalPanel(state)
                }
            }
            Spacer(Modifier.height(12.dp))
        }

        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            if (paused) {
                Button(onClick = onResume, modifier = Modifier.weight(1f).height(56.dp)) { Text(tr("재개", "Resume"), fontWeight = FontWeight.Bold) }
            } else {
                OutlinedButton(onClick = onPause, modifier = Modifier.weight(1f).height(56.dp)) { Text(tr("일시정지", "Pause")) }
            }
            HoldToConfirmButton(tr("길게 눌러 종료", "Hold to finish"), onConfirm = onStop, modifier = Modifier.weight(1f))
        }
    }
}

@Composable
private fun GoalPanel(state: WorkoutSnapshot) {
    when (val goal = state.goal) {
        is Goal.Distance, is Goal.Time -> {
            val progress = goal.progress(state.distanceM, state.elapsedMs) ?: 0f
            val left = when (goal) {
                is Goal.Distance -> tr("남은 거리 ${formatKm((goal.meters - state.distanceM).coerceAtLeast(0.0))}km", "${formatKm((goal.meters - state.distanceM).coerceAtLeast(0.0))}km to go")
                is Goal.Time -> tr("남은 시간 ${formatDuration((goal.ms - state.elapsedMs).coerceAtLeast(0))}", "${formatDuration((goal.ms - state.elapsedMs).coerceAtLeast(0))} to go")
                else -> ""
            }
            Column(Modifier.fillMaxWidth()) {
                Row(Modifier.fillMaxWidth()) {
                    Text(if (progress >= 1f) tr("목표 달성", "Goal reached") else left, style = MaterialTheme.typography.bodyMedium)
                    Spacer(Modifier.weight(1f))
                    Text("${(progress * 100).roundToInt()}%", style = MaterialTheme.typography.bodyMedium)
                }
                Spacer(Modifier.height(8.dp))
                LinearProgressIndicator(
                    progress = { progress },
                    modifier = Modifier.fillMaxWidth().height(8.dp),
                    drawStopIndicator = {}
                )
            }
        }
        is Goal.Pace -> {
            val current = state.currentPaceSecPerKm
            val delta = current?.let { (it - goal.secPerKm).roundToInt() }
            val (text, color) = when {
                delta == null || current >= 3600 -> tr("목표 ${formatPace(goal.secPerKm.toDouble())}", "Target ${formatPace(goal.secPerKm.toDouble())}") to MaterialTheme.colorScheme.onSurfaceVariant
                abs(delta) <= 15 -> tr("목표 페이스 유지 중", "On target pace") to MaterialTheme.colorScheme.primary
                delta > 0 -> tr("목표보다 ${delta}초 느려요", "${delta}s slower than target") to MaterialTheme.colorScheme.error
                else -> tr("목표보다 ${-delta}초 빨라요", "${-delta}s faster than target") to MaterialTheme.colorScheme.tertiary
            }
            Text(text, style = MaterialTheme.typography.titleMedium, color = color)
        }
        Goal.Free -> {}
    }
}

@Composable
private fun StatusBadge(text: String, emphasized: Boolean) {
    val bg = if (emphasized) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.surfaceVariant
    val fg = if (emphasized) MaterialTheme.colorScheme.onPrimary else MaterialTheme.colorScheme.onSurfaceVariant
    Surface(color = bg, shape = RoundedCornerShape(50)) {
        Text(text, color = fg, style = MaterialTheme.typography.labelLarge, modifier = Modifier.padding(horizontal = 12.dp, vertical = 6.dp))
    }
}

@Composable
private fun Label(text: String) {
    Text(text, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
}

@Composable
private fun SmallStat(label: String, value: String) {
    Column(horizontalAlignment = Alignment.CenterHorizontally) {
        Label(label)
        Text(value, fontSize = 22.sp)
    }
}

@Composable
private fun BigStat(label: String, value: String, unit: String) {
    Column(horizontalAlignment = Alignment.CenterHorizontally) {
        Label(label)
        Text(value, fontSize = 32.sp)
        Text(unit, style = MaterialTheme.typography.bodySmall, color = Color.Unspecified)
    }
}
