package com.mhmh2.bujirun.ui

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.mhmh2.bujirun.data.WorkoutEntity
import com.mhmh2.bujirun.data.WorkoutStore
import com.mhmh2.bujirun.tracking.formatDuration
import com.mhmh2.bujirun.tracking.formatKm
import com.mhmh2.bujirun.tracking.formatPace
import java.time.Instant
import java.time.ZoneId

@Composable
fun HistoryScreen(onOpen: (Long) -> Unit) {
    val workouts by remember { WorkoutStore.observeDone() }.collectAsState(initial = null)
    val list = workouts ?: return

    Column(Modifier.fillMaxSize().padding(horizontal = 24.dp)) {
        Text("기록", style = MaterialTheme.typography.headlineSmall, modifier = Modifier.padding(top = 24.dp, bottom = 8.dp))
        if (list.isEmpty()) {
            Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                Text(
                    "첫 기록을 남겨보세요.\n홈에서 시작을 누르면 여기에 쌓여요.",
                    textAlign = TextAlign.Center,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
            return@Column
        }
        val zone = ZoneId.systemDefault()
        val byMonth = list.groupBy { Instant.ofEpochMilli(it.startedAt).atZone(zone).let { d -> d.year * 100 + d.monthValue } }
        LazyColumn {
            byMonth.forEach { (key, items) ->
                item(key = "m$key") {
                    val total = items.sumOf { it.distanceM }
                    Row(Modifier.fillMaxWidth().padding(top = 20.dp, bottom = 4.dp)) {
                        Text("${key / 100}년 ${key % 100}월", style = MaterialTheme.typography.titleMedium)
                        Spacer(Modifier.weight(1f))
                        Text(
                            "${items.size}회 · ${formatKm(total)}km",
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }
                items(items, key = { it.id }) { w -> WorkoutRow(w, onClick = { onOpen(w.id) }) }
            }
            item { Spacer(Modifier.height(24.dp)) }
        }
    }
}

@Composable
private fun WorkoutRow(w: WorkoutEntity, onClick: () -> Unit) {
    val pace = if (w.distanceM >= 20) w.durationMs / 1000.0 / (w.distanceM / 1000.0) else null
    Column(Modifier.fillMaxWidth().clickable(onClick = onClick).padding(vertical = 12.dp)) {
        Text(workoutTitle(w), style = MaterialTheme.typography.titleMedium)
        Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            listOf("${formatKm(w.distanceM)}km", formatDuration(w.durationMs), "${formatPace(pace)}/km").forEach {
                Text(it, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
    }
    HorizontalDivider()
}
