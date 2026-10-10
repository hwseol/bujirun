package com.mhmh2.bujirun.ui

import android.content.Context
import android.content.Intent
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
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
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.FileProvider
import com.mhmh2.bujirun.course.CourseGpx
import com.mhmh2.bujirun.course.CourseStore
import com.mhmh2.bujirun.course.GeoPoint
import com.mhmh2.bujirun.course.sourceEnum
import com.mhmh2.bujirun.tracking.formatDuration
import com.mhmh2.bujirun.tracking.formatKm
import java.io.File
import kotlin.math.roundToInt

@Composable
fun CourseDetailScreen(courseId: Long, onClose: () -> Unit, onRun: (distanceM: Double) -> Unit) {
    val context = LocalContext.current
    val course by remember(courseId) { CourseStore.observe(courseId) }.collectAsState(initial = null)
    val points by remember(courseId) { CourseStore.observePoints(courseId) }.collectAsState(initial = emptyList())
    var renaming by remember { mutableStateOf(false) }
    var confirmDelete by remember { mutableStateOf(false) }

    Column(Modifier.fillMaxSize().safeDrawingPadding()) {
        Row(Modifier.fillMaxWidth().padding(horizontal = 8.dp, vertical = 4.dp), verticalAlignment = Alignment.CenterVertically) {
            IconButton(onClick = onClose) { Icon(Icons.AutoMirrored.Filled.ArrowBack, "뒤로") }
            Spacer(Modifier.weight(1f))
            TextButton(onClick = { renaming = true }) { Text("이름 변경") }
            TextButton(onClick = { confirmDelete = true }) { Text("삭제") }
        }
        val c = course ?: return@Column
        Column(Modifier.weight(1f).verticalScroll(rememberScrollState()).padding(horizontal = 24.dp)) {
            Text(c.name, style = MaterialTheme.typography.headlineSmall)
            Text(c.sourceEnum.label, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
            Spacer(Modifier.height(16.dp))
            CourseLineView(points)
            Spacer(Modifier.height(20.dp))
            Row(Modifier.fillMaxWidth()) {
                Stat("거리", "${formatKm(c.distanceM)}km", Modifier.weight(1f))
                Stat("형태", if (c.isLoop) "순환" else "편도", Modifier.weight(1f))
            }
            Spacer(Modifier.height(12.dp))
            Row(Modifier.fillMaxWidth()) {
                Stat("오르막", if (c.ascentM > 0) "${c.ascentM.roundToInt()}m" else "-", Modifier.weight(1f))
                // At the user's own paces when their workouts tell them, else an easy 6:30/km run
                // and a 12:00/km walk - a feel for the time, not a target.
                val paces = rememberPaces()
                Stat(
                    "예상 시간",
                    "달리기 ${formatDuration(paces.runMs(c.distanceM))} · 걷기 ${formatDuration(paces.walkMs(c.distanceM))}",
                    Modifier.weight(1f), small = true,
                )
            }
            Spacer(Modifier.height(28.dp))
            Button(
                onClick = { onRun(c.distanceM) },
                enabled = points.size >= 2,
                modifier = Modifier.fillMaxWidth().height(56.dp)
            ) { Text("이 코스로 달리기") }
            Text(
                "갈림길 100m 전과 직전에 방향을 알려주고, 코스를 벗어나면 알려줘요.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(top = 8.dp)
            )
            Spacer(Modifier.height(16.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                OutlinedButton(onClick = { shareCourse(context, c.name, points) }, enabled = points.size >= 2) { Text("GPX로 내보내기") }
            }
            Spacer(Modifier.height(24.dp))
        }
    }

    if (renaming) {
        NameDialog("코스 이름", course?.name.orEmpty(), onDismiss = { renaming = false }) { name ->
            renaming = false
            CourseStore.rename(courseId, name)
        }
    }
    if (confirmDelete) {
        AlertDialog(
            onDismissRequest = { confirmDelete = false },
            title = { Text("이 코스를 삭제할까요?") },
            confirmButton = { TextButton(onClick = { confirmDelete = false; CourseStore.delete(courseId); onClose() }) { Text("삭제") } },
            dismissButton = { TextButton(onClick = { confirmDelete = false }) { Text("취소") } }
        )
    }
}

@Composable
private fun Stat(label: String, value: String, modifier: Modifier = Modifier, small: Boolean = false) {
    Column(modifier) {
        Text(label, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        Text(value, fontSize = if (small) 14.sp else 24.sp)
    }
}

private fun shareCourse(context: Context, name: String, points: List<GeoPoint>) {
    val dir = File(context.cacheDir, "exports").apply { mkdirs() }
    dir.listFiles()?.forEach { it.delete() }
    val safe = name.replace(Regex("[^\\p{L}\\p{N}_-]+"), "_").take(40).ifEmpty { "course" }
    val file = File(dir, "$safe.gpx").apply { writeText(CourseGpx.write(name, points)) }
    val uri = FileProvider.getUriForFile(context, "${context.packageName}.files", file)
    val send = Intent(Intent.ACTION_SEND)
        .setType("application/gpx+xml")
        .putExtra(Intent.EXTRA_STREAM, uri)
        .putExtra(Intent.EXTRA_SUBJECT, name)
        .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
    context.startActivity(Intent.createChooser(send, "GPX 내보내기"))
}
