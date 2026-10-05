package com.mhmh2.bujirun.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.mhmh2.bujirun.data.WorkoutEntity
import com.mhmh2.bujirun.data.WorkoutStore
import com.mhmh2.bujirun.data.toTrackPoint
import com.mhmh2.bujirun.tracking.Split
import com.mhmh2.bujirun.tracking.Splits
import com.mhmh2.bujirun.tracking.formatDuration
import com.mhmh2.bujirun.tracking.formatKm
import com.mhmh2.bujirun.tracking.formatPace
import kotlinx.coroutines.flow.map
import kotlin.math.roundToInt

/**
 * A saved workout: route, totals, km splits. Shown right after finishing ([justFinished]: a
 * primary "done" button) and from the history (a back arrow).
 */
@Composable
fun SummaryScreen(workoutId: Long, justFinished: Boolean, onClose: () -> Unit) {
    val workout by remember(workoutId) { WorkoutStore.observe(workoutId) }.collectAsState(initial = null)
    val points by remember(workoutId) {
        WorkoutStore.observePoints(workoutId).map { list -> list.map { it.toTrackPoint() } }
    }.collectAsState(initial = emptyList())
    var confirmDelete by remember { mutableStateOf(false) }

    val w = workout
    Column(Modifier.fillMaxSize().safeDrawingPadding()) {
        Row(Modifier.fillMaxWidth().padding(horizontal = 8.dp, vertical = 4.dp), verticalAlignment = Alignment.CenterVertically) {
            if (!justFinished) IconButton(onClick = onClose) { Icon(Icons.AutoMirrored.Filled.ArrowBack, "뒤로") }
            Spacer(Modifier.weight(1f))
            TextButton(onClick = { confirmDelete = true }) { Text("삭제") }
        }
        if (w == null) return@Column
        Column(
            Modifier.weight(1f).verticalScroll(rememberScrollState()).padding(horizontal = 24.dp),
        ) {
            Text(workoutTitle(w), style = MaterialTheme.typography.headlineSmall)
            Text(workoutDate(w), style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
            Spacer(Modifier.height(16.dp))
            RouteView(points)
            Spacer(Modifier.height(20.dp))

            val avgPace = if (w.distanceM >= 20) w.durationMs / 1000.0 / (w.distanceM / 1000.0) else null
            StatRow(
                "거리" to "${formatKm(w.distanceM)}km",
                "시간" to formatDuration(w.durationMs),
            )
            Spacer(Modifier.height(12.dp))
            StatRow(
                "평균 페이스" to "${formatPace(avgPace)}/km",
                "칼로리" to "약 ${w.calories.roundToInt()}kcal",
            )
            Spacer(Modifier.height(12.dp))
            val moving = w.runMs + w.walkMs
            val runPct = if (moving > 0) (w.runMs * 100.0 / moving).roundToInt() else null
            StatRow(
                "오르막" to "${w.ascentM.roundToInt()}m",
                "달리기 · 걷기" to (runPct?.let { "$it% · ${100 - it}%" } ?: "-"),
            )

            val splits = remember(points, w.distanceM, w.durationMs) { Splits.compute(points, w.distanceM, w.durationMs) }
            if (splits.isNotEmpty()) {
                Spacer(Modifier.height(28.dp))
                Text("구간", style = MaterialTheme.typography.titleMedium)
                Spacer(Modifier.height(8.dp))
                SplitsTable(splits)
            }
            Spacer(Modifier.height(24.dp))
        }
        if (justFinished) {
            Button(onClick = onClose, modifier = Modifier.fillMaxWidth().padding(24.dp).height(56.dp)) { Text("확인") }
        }
    }

    if (confirmDelete) {
        AlertDialog(
            onDismissRequest = { confirmDelete = false },
            title = { Text("이 기록을 삭제할까요?") },
            text = { Text("삭제한 기록은 되돌릴 수 없어요.") },
            confirmButton = {
                TextButton(onClick = {
                    confirmDelete = false
                    WorkoutStore.delete(workoutId)
                    onClose()
                }) { Text("삭제") }
            },
            dismissButton = { TextButton(onClick = { confirmDelete = false }) { Text("취소") } }
        )
    }
}

@Composable
private fun StatRow(left: Pair<String, String>, right: Pair<String, String>) {
    Row(Modifier.fillMaxWidth()) {
        listOf(left, right).forEach { (label, value) ->
            Column(Modifier.weight(1f)) {
                Text(label, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                Text(value, fontSize = 24.sp)
            }
        }
    }
}

/** km, pace and a bar per split - longer bar = faster, the fastest km highlighted. */
@Composable
private fun SplitsTable(splits: List<Split>) {
    val fastest = splits.minOf { it.paceSecPerKm }
    val slowest = splits.maxOf { it.paceSecPerKm }
    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
        splits.forEach { s ->
            Row(verticalAlignment = Alignment.CenterVertically) {
                val label = if (s.distanceM < 999) "${formatKm(s.distanceM)}" else "${s.index}"
                Text(label, modifier = Modifier.width(44.dp), style = MaterialTheme.typography.bodyMedium)
                Text(formatPace(s.paceSecPerKm), modifier = Modifier.width(64.dp), style = MaterialTheme.typography.bodyMedium)
                // Bars from 35% (slowest) to 100% (fastest) width, so even the slowest is visible.
                val f = if (slowest > fastest) 0.35f + 0.65f * ((slowest - s.paceSecPerKm) / (slowest - fastest)).toFloat() else 1f
                Box(Modifier.weight(1f).height(14.dp)) {
                    Box(
                        Modifier.fillMaxWidth(f).fillMaxHeight()
                            .background(
                                if (s.paceSecPerKm == fastest && splits.size > 1) MaterialTheme.colorScheme.primary
                                else MaterialTheme.colorScheme.primary.copy(alpha = 0.45f),
                                RoundedCornerShape(4.dp)
                            )
                    )
                }
            }
        }
        if (splits.last().distanceM < 999) {
            Text(
                "마지막 구간은 남은 거리 기준 페이스예요",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                textAlign = TextAlign.Start
            )
        }
    }
}
