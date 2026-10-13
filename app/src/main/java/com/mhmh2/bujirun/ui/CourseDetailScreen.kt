package com.mhmh2.bujirun.ui

import com.mhmh2.bujirun.tr

import android.content.Context
import android.content.Intent
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
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
import com.mhmh2.bujirun.course.CourseRoute
import com.mhmh2.bujirun.course.CourseOutline
import com.mhmh2.bujirun.course.Landmark
import com.mhmh2.bujirun.course.RouteApi
import com.mhmh2.bujirun.course.isOutAndBack
import com.mhmh2.bujirun.course.slice
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
    // The course in a few big strokes (CourseOutline); the tapped one is shown on the map.
    val route = remember(points) { points.takeIf { it.size >= 2 }?.let(::CourseRoute) }
    val outline = remember(route) { route?.let { CourseOutline.of(it) }.orEmpty() }
    var picked by remember(points) { mutableStateOf<Int?>(null) }
    // What's at each of those points ("역삼사거리에서"), from the server when it knows.
    var landmarks by remember(points) { mutableStateOf<List<Landmark?>>(emptyList()) }
    LaunchedEffect(route) {
        val r = route ?: return@LaunchedEffect
        if (outline.isNotEmpty()) landmarks = runCatching { RouteApi.landmarks(outline.map { r.pointAt(it.atM) }) }.getOrDefault(emptyList())
    }

    Column(Modifier.fillMaxSize().safeDrawingPadding()) {
        Row(Modifier.fillMaxWidth().padding(horizontal = 8.dp, vertical = 4.dp), verticalAlignment = Alignment.CenterVertically) {
            IconButton(onClick = onClose) { Icon(Icons.AutoMirrored.Filled.ArrowBack, tr("뒤로", "Back")) }
            Spacer(Modifier.weight(1f))
            TextButton(onClick = { renaming = true }) { Text(tr("이름 변경", "Rename")) }
            TextButton(onClick = { confirmDelete = true }) { Text(tr("삭제", "Delete")) }
        }
        val c = course ?: return@Column
        Column(Modifier.weight(1f).verticalScroll(rememberScrollState()).padding(horizontal = 24.dp)) {
            Text(c.name, style = MaterialTheme.typography.headlineSmall)
            Text(c.sourceEnum.label, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
            Spacer(Modifier.height(16.dp))
            // A real map: pinch or +/- to zoom, drag to look around.
            if (points.size >= 2) {
                Box(Modifier.fillMaxWidth().height(360.dp).clip(RoundedCornerShape(16.dp))) {
                    val highlight = picked?.let { i -> outline.getOrNull(i) }?.takeIf { it.legM > 0 }
                        ?.let { st -> route?.slice(st.atM, st.atM + st.legM) }
                    CourseMap(points, highlight)
                }
            }
            Spacer(Modifier.height(20.dp))
            Row(Modifier.fillMaxWidth()) {
                Stat(tr("거리", "Distance"), "${formatKm(c.distanceM)}km", Modifier.weight(1f))
                Stat(tr("형태", "Shape"), if (route?.isOutAndBack() == true) tr("왕복", "Out and back") else if (c.isLoop) tr("순환", "Loop") else tr("편도", "One way"), Modifier.weight(1f))
            }
            Spacer(Modifier.height(12.dp))
            Row(Modifier.fillMaxWidth()) {
                Stat(tr("오르막", "Climb"), if (c.ascentM > 0) "${c.ascentM.roundToInt()}m" else "-", Modifier.weight(1f))
                // At the user's own paces when their workouts tell them, else an easy 6:30/km run
                // and a 12:00/km walk - a feel for the time, not a target.
                val paces = rememberPaces()
                Stat(
                    tr("예상 시간", "Estimated time"),
                    tr("달리기 ${formatDuration(paces.runMs(c.distanceM))} · 걷기 ${formatDuration(paces.walkMs(c.distanceM))}", "Run ${formatDuration(paces.runMs(c.distanceM))} · Walk ${formatDuration(paces.walkMs(c.distanceM))}"),
                    Modifier.weight(1f), small = true,
                )
            }
            if (outline.size > 1) {
                Spacer(Modifier.height(24.dp))
                Row(verticalAlignment = Alignment.Bottom) {
                    Text(tr("코스 안내", "The course"), style = MaterialTheme.typography.titleMedium)
                    Spacer(Modifier.width(8.dp))
                    Text(tr("큰 흐름만 · 누르면 지도에서 보여줘요", "The big picture · tap to see it on the map"), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
                Spacer(Modifier.height(4.dp))
                outline.forEachIndexed { i, st ->
                    OutlineRow(st, landmarks.getOrNull(i), loop = c.isLoop, selected = picked == i) {
                        picked = if (picked == i || st.legM <= 0) null else i
                    }
                }
            }
            Spacer(Modifier.height(28.dp))
            Button(
                onClick = { onRun(c.distanceM) },
                enabled = points.size >= 2,
                modifier = Modifier.fillMaxWidth().height(56.dp)
            ) { Text(tr("이 코스로 달리기", "Run this course")) }
            Text(
                tr("갈림길 100m 전과 직전에 방향을 알려주고, 코스를 벗어나면 알려줘요.", "You'll hear each turn 100 m before and as you reach it, and when you leave the course."),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(top = 8.dp)
            )
            Spacer(Modifier.height(16.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                OutlinedButton(onClick = { shareCourse(context, c.name, points) }, enabled = points.size >= 2) { Text(tr("GPX로 내보내기", "Export GPX")) }
            }
            Spacer(Modifier.height(24.dp))
        }
    }

    if (renaming) {
        NameDialog(tr("코스 이름", "Course name"), course?.name.orEmpty(), onDismiss = { renaming = false }) { name ->
            renaming = false
            CourseStore.rename(courseId, name)
        }
    }
    if (confirmDelete) {
        AlertDialog(
            onDismissRequest = { confirmDelete = false },
            title = { Text(tr("이 코스를 삭제할까요?", "Delete this course?")) },
            confirmButton = { TextButton(onClick = { confirmDelete = false; CourseStore.delete(courseId); onClose() }) { Text(tr("삭제", "Delete")) } },
            dismissButton = { TextButton(onClick = { confirmDelete = false }) { Text(tr("취소", "Cancel")) } }
        )
    }
}

/**
 * One big stroke: "출발 · 반포한강공원 근처" / "북동쪽으로 0.8km", "역삼사거리에서 오른쪽" / "동쪽으로
 * 1.2km · 여기서 돌아와요", "잠수교에서 되돌아와요", tr("출발점으로 돌아와 끝", "Back to the start, done").
 */
@Composable
private fun OutlineRow(st: CourseOutline.Step, landmark: Landmark?, loop: Boolean, selected: Boolean, onClick: () -> Unit) {
    val colors = MaterialTheme.colorScheme
    val where = landmark?.at ?: tr("${formatKm(st.atM)}km 지점에서", "at ${formatKm(st.atM)}km")
    val (mark, title) = when (st.kind) {
        CourseOutline.Kind.START -> "●" to (tr("출발", "Start") + (landmark?.let { tr(" · ${it.name} 근처", " · near ${it.name}") } ?: ""))
        CourseOutline.Kind.TURN -> st.direction!!.arrow to tr("$where ${st.direction.spoken}", "${st.direction.spoken.replaceFirstChar { it.uppercase() }} $where")
        CourseOutline.Kind.U_TURN -> "↶" to tr("$where 되돌아와요", "Turn back $where")
        CourseOutline.Kind.FINISH -> "■" to (if (loop) tr("출발점으로 돌아와 끝", "Back to the start, done") else tr("도착", "Finish") + (landmark?.let { tr(" · ${it.name} 근처", " · near ${it.name}") } ?: ""))
    }
    Row(
        Modifier.fillMaxWidth().clip(RoundedCornerShape(12.dp))
            .background(if (selected) colors.tertiaryContainer else Color.Transparent)
            .clickable(onClick = onClick).padding(horizontal = 8.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(mark, color = colors.primary, fontWeight = FontWeight.Bold, fontSize = 20.sp, modifier = Modifier.width(36.dp))
        Column(Modifier.weight(1f)) {
            Text(title, style = MaterialTheme.typography.titleSmall)
            if (st.heading != null) {
                Text(
                    tr("${st.heading}으로 ${formatKm(st.legM)}km", "${st.heading} for ${formatKm(st.legM)}km") + if (st.farthest) tr(" · 여기서부터 돌아오는 길", " · heading home from here") else "",
                    style = MaterialTheme.typography.bodySmall, color = colors.onSurfaceVariant,
                )
            }
        }
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
    context.startActivity(Intent.createChooser(send, tr("GPX 내보내기", "Export GPX")))
}
