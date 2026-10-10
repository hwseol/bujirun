package com.mhmh2.bujirun.ui

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

    Column(
        modifier = Modifier.fillMaxSize().safeDrawingPadding().padding(horizontal = 24.dp, vertical = 16.dp),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            StatusBadge(
                when {
                    state.autoPaused -> "자동 일시정지"
                    paused -> "일시정지"
                    !state.hasFix -> "GPS 찾는 중"
                    else -> activityLabel(state.activity)
                },
                emphasized = !paused && state.hasFix
            )
            if (state.simScale > 0) {
                Spacer(Modifier.width(8.dp))
                StatusBadge("테스트 ×${state.simScale}", emphasized = false)
            }
            Spacer(Modifier.weight(1f))
            IconButton(onClick = { Prefs.setVoiceEnabled(!voiceOn) }) {
                Icon(
                    if (voiceOn) Icons.AutoMirrored.Filled.VolumeUp else Icons.AutoMirrored.Filled.VolumeOff,
                    contentDescription = if (voiceOn) "음성 안내 끄기" else "음성 안내 켜기"
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
                Modifier.weight(1f).fillMaxWidth().clip(RoundedCornerShape(20.dp))
            )
            Spacer(Modifier.height(16.dp))
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceEvenly) {
                SmallStat("시간", formatDuration(state.elapsedMs))
                SmallStat("거리", "${formatKm(state.distanceM)}km")
                SmallStat("페이스", formatPace(state.currentPaceSecPerKm))
                if (course.laps != null && course.lap != null) SmallStat("바퀴", "${course.lap}/${course.laps}")
                else SmallStat("코스 남은", "${formatKm(course.remainingM)}km")
            }
            Spacer(Modifier.height(16.dp))
        } else {
            Spacer(Modifier.weight(1f))
            Label("시간")
            Text(formatDuration(state.elapsedMs), fontSize = 72.sp)
            Spacer(Modifier.height(24.dp))
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceEvenly) {
                BigStat("거리", formatKm(state.distanceM), "km")
                if (state.goal is Goal.Free) BigStat("평균 페이스", formatPace(state.averagePaceSecPerKm), "/km")
                else BigStat("현재 페이스", formatPace(state.currentPaceSecPerKm), "/km")
            }
            Spacer(Modifier.height(12.dp))
            Text(
                "약 ${state.calories.roundToInt()}kcal",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            Spacer(Modifier.height(24.dp))
            GoalPanel(state)
            Spacer(Modifier.weight(1f))
        }

        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            if (paused) {
                Button(onClick = onResume, modifier = Modifier.weight(1f).height(56.dp)) { Text("재개") }
            } else {
                OutlinedButton(onClick = onPause, modifier = Modifier.weight(1f).height(56.dp)) { Text("일시정지") }
            }
            HoldToConfirmButton("길게 눌러 종료", onConfirm = onStop, modifier = Modifier.weight(1f))
        }
    }
}

@Composable
private fun GoalPanel(state: WorkoutSnapshot) {
    when (val goal = state.goal) {
        is Goal.Distance, is Goal.Time -> {
            val progress = goal.progress(state.distanceM, state.elapsedMs) ?: 0f
            val left = when (goal) {
                is Goal.Distance -> "남은 거리 ${formatKm((goal.meters - state.distanceM).coerceAtLeast(0.0))}km"
                is Goal.Time -> "남은 시간 ${formatDuration((goal.ms - state.elapsedMs).coerceAtLeast(0))}"
                else -> ""
            }
            Column(Modifier.fillMaxWidth()) {
                Row(Modifier.fillMaxWidth()) {
                    Text(if (progress >= 1f) "목표 달성" else left, style = MaterialTheme.typography.bodyMedium)
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
                delta == null || current >= 3600 -> "목표 ${formatPace(goal.secPerKm.toDouble())}" to MaterialTheme.colorScheme.onSurfaceVariant
                abs(delta) <= 15 -> "목표 페이스 유지 중" to MaterialTheme.colorScheme.primary
                delta > 0 -> "목표보다 ${delta}초 느려요" to MaterialTheme.colorScheme.error
                else -> "목표보다 ${-delta}초 빨라요" to MaterialTheme.colorScheme.tertiary
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
        Text(value, fontSize = 40.sp)
        Text(unit, style = MaterialTheme.typography.bodySmall, color = Color.Unspecified)
    }
}
