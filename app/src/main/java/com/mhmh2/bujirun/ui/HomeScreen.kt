package com.mhmh2.bujirun.ui

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
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Remove
import androidx.compose.material3.Button
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedIconButton
import androidx.compose.material3.SuggestionChip
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.mhmh2.bujirun.coach.Goal
import com.mhmh2.bujirun.tracking.formatPace
import java.util.Locale

private enum class GoalKind(val label: String) { FREE("자유"), DISTANCE("거리"), TIME("시간"), PACE("페이스") }

/** One adjustable goal value: its range, step, presets, and how to show it. */
private class GoalDial(
    val min: Int, val max: Int, val step: Int, val presets: List<Int>,
    val label: (Int) -> String, val toGoal: (Int) -> Goal,
)

// Distance in 100 m units so 0.5 km steps stay integers; pace in seconds per km. Pace goes up to
// 15'00" because walkers are first-class users, not just runners.
private val dials = mapOf(
    GoalKind.DISTANCE to GoalDial(5, 422, 5, listOf(30, 50, 100),
        { String.format(Locale.US, "%skm", (it / 10.0).toString().removeSuffix(".0")) }, { Goal.Distance(it * 100.0) }),
    GoalKind.TIME to GoalDial(5, 180, 5, listOf(20, 30, 60), { "${it}분" }, { Goal.Time(it * 60_000L) }),
    GoalKind.PACE to GoalDial(180, 900, 10, listOf(330, 420, 600), { formatPace(it.toDouble()) }, { Goal.Pace(it) }),
)

@Composable
fun HomeScreen(initialGoal: Goal, permissionDenied: Boolean, onStart: (Goal) -> Unit) {
    var kind by remember {
        mutableStateOf(
            when (initialGoal) {
                Goal.Free -> GoalKind.FREE
                is Goal.Distance -> GoalKind.DISTANCE
                is Goal.Time -> GoalKind.TIME
                is Goal.Pace -> GoalKind.PACE
            }
        )
    }
    // Each kind keeps its own value, so flipping between chips doesn't lose what was set.
    var values by remember {
        mutableStateOf(
            mapOf(
                GoalKind.DISTANCE to ((initialGoal as? Goal.Distance)?.meters?.div(100)?.toInt() ?: 50),
                GoalKind.TIME to ((initialGoal as? Goal.Time)?.ms?.div(60_000)?.toInt() ?: 30),
                GoalKind.PACE to ((initialGoal as? Goal.Pace)?.secPerKm ?: 390),
            )
        )
    }
    val dial = dials[kind]
    val goal = dial?.toGoal(values.getValue(kind)) ?: Goal.Free

    Column(
        modifier = Modifier.fillMaxSize().safeDrawingPadding().padding(24.dp),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Text("부지런", style = MaterialTheme.typography.headlineLarge, modifier = Modifier.padding(top = 24.dp))
        Spacer(Modifier.height(32.dp))

        Text("목표", style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.onSurfaceVariant)
        Spacer(Modifier.height(8.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            GoalKind.entries.forEach { k ->
                FilterChip(selected = kind == k, onClick = { kind = k }, label = { Text(k.label) })
            }
        }
        Spacer(Modifier.height(16.dp))

        if (dial != null) {
            val value = values.getValue(kind)
            fun set(v: Int) { values = values + (kind to v.coerceIn(dial.min, dial.max)) }
            Row(verticalAlignment = Alignment.CenterVertically) {
                OutlinedIconButton(onClick = { set(value - dial.step) }) { Icon(Icons.Filled.Remove, "줄이기") }
                Text(
                    dial.label(value),
                    fontSize = 36.sp,
                    textAlign = TextAlign.Center,
                    modifier = Modifier.widthIn(min = 160.dp)
                )
                OutlinedIconButton(onClick = { set(value + dial.step) }) { Icon(Icons.Filled.Add, "늘리기") }
            }
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                dial.presets.forEach { p -> SuggestionChip(onClick = { set(p) }, label = { Text(dial.label(p)) }) }
            }
        } else {
            Text(
                "목표 없이 자유롭게 기록해요",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(vertical = 24.dp)
            )
        }

        Spacer(Modifier.weight(1f))
        Button(onClick = { onStart(goal) }, shape = CircleShape, modifier = Modifier.size(160.dp)) {
            Text("시작", fontSize = 28.sp)
        }
        Spacer(Modifier.height(16.dp))
        Text(
            if (permissionDenied) "기록하려면 위치 권한이 필요해요" else "걷기와 달리기는 자동으로 구분해요",
            style = MaterialTheme.typography.bodySmall,
            color = if (permissionDenied) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.fillMaxWidth(),
            textAlign = TextAlign.Center
        )
        Spacer(Modifier.weight(0.5f))
    }
}
