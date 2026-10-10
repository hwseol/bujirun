package com.mhmh2.bujirun.ui

import android.content.Context
import android.net.Uri
import android.provider.OpenableColumns
import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.clickable
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.filled.AutoAwesome
import androidx.compose.material.icons.filled.Draw
import androidx.compose.material3.Surface
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
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
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.FileOpen
import androidx.compose.material.icons.filled.NearMe
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.mhmh2.bujirun.course.CourseGpx
import com.mhmh2.bujirun.course.CourseSource
import com.mhmh2.bujirun.course.CourseStore
import com.mhmh2.bujirun.course.ParsedGpx
import com.mhmh2.bujirun.course.sourceEnum
import com.mhmh2.bujirun.data.CourseEntity
import com.mhmh2.bujirun.tracking.formatKm
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** A GPX handed to the app from outside ("open with" / share), waiting for the courses tab. */
object PendingImport {
    val uri = MutableStateFlow<Uri?>(null)
}

@Composable
fun CoursesScreen(onOpen: (Long) -> Unit, onDraw: () -> Unit, onSuggest: () -> Unit, onAi: () -> Unit) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val courses by remember { CourseStore.observeAll() }.collectAsState(initial = null)
    var parsed by remember { mutableStateOf<Pair<ParsedGpx, String>?>(null) }

    fun import(uri: Uri) {
        scope.launch {
            val result = withContext(Dispatchers.IO) {
                runCatching {
                    context.contentResolver.openInputStream(uri)!!.use { CourseGpx.parse(it) } to fileName(context, uri)
                }
            }
            result.onSuccess { parsed = it }
                .onFailure { Toast.makeText(context, "코스로 읽을 수 있는 GPX 파일이 아니에요", Toast.LENGTH_LONG).show() }
        }
    }

    val picker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri -> uri?.let(::import) }
    val pending by PendingImport.uri.collectAsState()
    LaunchedEffect(pending) {
        pending?.let { PendingImport.uri.value = null; import(it) }
    }

    Column(Modifier.fillMaxSize().padding(horizontal = 20.dp)) {
        Text("코스", style = MaterialTheme.typography.headlineSmall, modifier = Modifier.padding(top = 24.dp, bottom = 12.dp))
        AiHeroCard(onAi)
        Spacer(Modifier.height(12.dp))
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            MakeCard(Icons.Filled.Draw, "DIY 코스 만들기", "지도에 점을 찍어\n나만의 코스", onDraw, Modifier.weight(1f))
            MakeCard(Icons.Filled.NearMe, "AI 주변 코스", "공원·트랙·순환 코스를\nAI가 바로 짜 드려요", onSuggest, Modifier.weight(1f), ai = true)
        }
        // GPX has no reliable MIME type across apps and file managers; accept anything and let
        // the parser decide.
        TextButton(onClick = { picker.launch(arrayOf("*/*")) }, modifier = Modifier.align(Alignment.End)) {
            Icon(Icons.Filled.FileOpen, null, Modifier.size(18.dp)); Spacer(Modifier.width(6.dp)); Text("GPX 불러오기")
        }
        Text("내 코스", style = MaterialTheme.typography.titleMedium, modifier = Modifier.padding(bottom = 4.dp))
        val list = courses ?: return@Column
        if (list.isEmpty()) {
            Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                Text(
                    "아직 저장한 코스가 없어요.\nAI 추천이나 DIY로 만들거나, Strava 등에서 받은 GPX를 불러오세요.\n" +
                        "운동 기록 요약 화면에서 '코스로 저장'도 할 수 있어요.",
                    textAlign = TextAlign.Center,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
            return@Column
        }
        LazyColumn {
            items(list, key = { it.id }) { c -> CourseRow(c, onClick = { onOpen(c.id) }) }
            item { Spacer(Modifier.height(24.dp)) }
        }
    }

    parsed?.let { (gpx, fileName) ->
        NameDialog(
            title = "코스 이름",
            initial = gpx.name ?: fileName.removeSuffix(".gpx").removeSuffix(".GPX"),
            onDismiss = { parsed = null },
            onConfirm = { name ->
                parsed = null
                scope.launch { onOpen(CourseStore.save(name, CourseSource.GPX, gpx.points)) }
            }
        )
    }
}

/** The way in to AI course suggestions: the feature to notice first. */
@Composable
private fun AiHeroCard(onClick: () -> Unit) {
    val brush = Brush.linearGradient(listOf(Color(0xFFFF6A2B), Color(0xFFFF3D6E)))
    Box(
        Modifier.fillMaxWidth().clip(RoundedCornerShape(24.dp)).background(brush).clickable(onClick = onClick)
            .padding(horizontal = 20.dp, vertical = 18.dp)
    ) {
        Column {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(Icons.Filled.AutoAwesome, null, tint = Color.White)
                Spacer(Modifier.width(8.dp))
                Text("AI 코스 추천", color = Color.White, style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold)
            }
            Spacer(Modifier.height(6.dp))
            Text("말하듯 적으면 내 주변 공원·강변으로 코스를 짜 드려요", color = Color.White.copy(alpha = 0.92f),
                style = MaterialTheme.typography.bodyMedium)
            Spacer(Modifier.height(12.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                listOf("한강 따라 5km", "공원 뺑뺑이 3km", "남산 왕복").forEach {
                    Text(it, color = Color.White, style = MaterialTheme.typography.labelMedium,
                        modifier = Modifier.clip(RoundedCornerShape(50)).background(Color.White.copy(alpha = 0.2f))
                            .padding(horizontal = 10.dp, vertical = 5.dp))
                }
            }
        }
    }
}

@Composable
private fun MakeCard(icon: ImageVector, title: String, subtitle: String, onClick: () -> Unit, modifier: Modifier, ai: Boolean = false) {
    Surface(onClick = onClick, shape = RoundedCornerShape(20.dp), tonalElevation = 2.dp, modifier = modifier) {
        Column(Modifier.padding(16.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(icon, null, tint = MaterialTheme.colorScheme.primary)
                if (ai) {
                    // Planned by the same AI as the hero card above: say so.
                    Spacer(Modifier.weight(1f))
                    Row(
                        Modifier.clip(RoundedCornerShape(50))
                            .background(Brush.linearGradient(listOf(Color(0xFFFF6A2B), Color(0xFFFF3D6E))))
                            .padding(horizontal = 8.dp, vertical = 3.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Icon(Icons.Filled.AutoAwesome, null, tint = Color.White, modifier = Modifier.size(12.dp))
                        Spacer(Modifier.width(3.dp))
                        Text("AI", color = Color.White, style = MaterialTheme.typography.labelSmall, fontWeight = FontWeight.Bold)
                    }
                }
            }
            Spacer(Modifier.height(10.dp))
            Text(title, style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.Bold)
            Spacer(Modifier.height(2.dp))
            Text(subtitle, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}

@Composable
private fun CourseRow(c: CourseEntity, onClick: () -> Unit) {
    Column(Modifier.fillMaxWidth().clickable(onClick = onClick).padding(vertical = 12.dp)) {
        Text(c.name, style = MaterialTheme.typography.titleMedium)
        Text(
            listOf("${formatKm(c.distanceM)}km", if (c.isLoop) "순환" else "편도", c.sourceEnum.label).joinToString(" · "),
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
    }
    HorizontalDivider()
}

@Composable
fun NameDialog(title: String, initial: String, onDismiss: () -> Unit, onConfirm: (String) -> Unit) {
    var name by remember { mutableStateOf(initial.take(30)) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(title) },
        text = {
            OutlinedTextField(value = name, onValueChange = { name = it.take(30) }, singleLine = true, placeholder = { Text("한강 아침 5km") })
        },
        confirmButton = { TextButton(onClick = { onConfirm(name) }, enabled = name.isNotBlank()) { Text("저장") } },
        dismissButton = { TextButton(onClick = onDismiss) { Text("취소") } }
    )
}

private fun fileName(context: Context, uri: Uri): String =
    runCatching {
        context.contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use { c ->
            if (c.moveToFirst()) c.getString(0) else null
        }
    }.getOrNull() ?: "불러온 코스"
