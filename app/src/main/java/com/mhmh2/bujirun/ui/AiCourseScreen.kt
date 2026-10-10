package com.mhmh2.bujirun.ui

import android.Manifest
import android.content.pm.PackageManager
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.AutoAwesome
import androidx.compose.material.icons.filled.Close
import androidx.compose.material3.AssistChip
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.InputChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.SuggestionChip
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import com.mhmh2.bujirun.course.CourseIntent
import com.mhmh2.bujirun.course.CourseReader
import com.mhmh2.bujirun.course.CourseShape
import com.mhmh2.bujirun.course.CourseSource
import com.mhmh2.bujirun.course.CourseStore
import com.mhmh2.bujirun.course.NluModel
import com.mhmh2.bujirun.course.PlaceMention
import com.mhmh2.bujirun.course.PlaceRole
import com.mhmh2.bujirun.course.RouteApi
import com.mhmh2.bujirun.data.WorkoutStore
import com.mhmh2.bujirun.net.ApiException
import com.mhmh2.bujirun.tracking.Activity
import com.mhmh2.bujirun.tracking.formatKm
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

private val EXAMPLES = listOf("한강 따라 5km", "공원 뺑뺑이 3km", "석촌호수 세 바퀴", "남산타워 왕복", "공원 돌아서 30분")
private val DISTANCE_CHOICES = listOf(2000, 3000, 5000, 7000, 10000)

/**
 * AI 코스 추천: the user describes a course; the phone reads it into chips right away (on-device
 * model + rules, docs/NL_COURSES.md) and the server's planner builds it. There is no button:
 * the course follows the request - about a second after typing stops, or at once when a chip
 * is changed. Requests that read the same don't go out again (and the server caches too).
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun AiCourseScreen(onClose: () -> Unit, onOpen: (Long) -> Unit, onRun: (Long) -> Unit) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    // The on-device model; if it can't load for any reason the rules alone still work.
    val reader = remember {
        val model = runCatching { context.assets.open("nlu.bin").use { NluModel.load(it) } }.getOrNull()
        CourseReader(model?.let { it::read })
    }
    var text by rememberSaveable { mutableStateOf("") }
    var intent by remember { mutableStateOf(reader.read(text)) }
    var chipEdited by remember { mutableStateOf(false) }
    var pace by remember { mutableStateOf<Int?>(null) }
    var loading by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }
    var plan by remember { mutableStateOf<RouteApi.Plan?>(null) }
    var located by remember {
        mutableStateOf(ContextCompat.checkSelfPermission(context, Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED)
    }
    val permission = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        located = granted
        if (!granted) error = "내 주변 코스를 짜려면 위치 권한이 필요해요."
    }
    LaunchedEffect(Unit) {
        pace = WorkoutStore.typicalRunPace()
        if (!located) permission.launch(Manifest.permission.ACCESS_FINE_LOCATION)
    }

    // The course follows the request. A new reading cancels the one in flight.
    LaunchedEffect(intent, pace, located) {
        if (text.isBlank()) { plan = null; error = null; return@LaunchedEffect }
        if (!located) return@LaunchedEffect
        delay(if (chipEdited) 300 else 1000)
        loading = true
        error = null
        try {
            val here = currentLocation(context) ?: throw ApiException("현재 위치를 알 수 없어요. 위치가 켜져 있는지 확인해 주세요.")
            // "세 바퀴" alone: whole laps of the place, however long they are - no default distance.
            val lapsOnly = intent.laps != null && intent.distanceM == null && intent.durationMin == null
            plan = RouteApi.plan(here, intent, if (lapsOnly) null else intent.targetDistanceM(pace))
        } catch (e: ApiException) {
            error = e.message
            plan = null
        } finally {
            loading = false
        }
    }

    fun edit(newText: String) {
        text = newText
        chipEdited = false
        intent = reader.read(newText)
    }

    fun save(course: RouteApi.PlannedCourse, then: (Long) -> Unit) {
        scope.launch { then(CourseStore.save(course.title, CourseSource.RECOMMENDED, course.points)) }
    }

    Column(Modifier.fillMaxSize().safeDrawingPadding()) {
        Row(Modifier.fillMaxWidth().padding(horizontal = 8.dp, vertical = 4.dp), verticalAlignment = Alignment.CenterVertically) {
            IconButton(onClick = onClose) { Icon(Icons.AutoMirrored.Filled.ArrowBack, "뒤로") }
            Icon(Icons.Filled.AutoAwesome, null, tint = MaterialTheme.colorScheme.primary)
            Spacer(Modifier.width(6.dp))
            Text("AI 코스 추천", style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold)
        }
        Column(Modifier.padding(horizontal = 20.dp)) {
            OutlinedTextField(
                value = text,
                onValueChange = ::edit,
                placeholder = { Text("예: 보라매공원 뺑뺑이 5km") },
                maxLines = 3,
                shape = RoundedCornerShape(16.dp),
                trailingIcon = if (text.isNotEmpty()) {
                    { IconButton(onClick = { edit("") }) { Icon(Icons.Filled.Close, "지우기") } }
                } else null,
                modifier = Modifier.fillMaxWidth(),
            )
            Spacer(Modifier.height(10.dp))
            if (text.isBlank()) {
                Text("이렇게 말해 보세요", style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.onSurfaceVariant)
                Spacer(Modifier.height(4.dp))
                FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    EXAMPLES.forEach { SuggestionChip(onClick = { edit(it) }, label = { Text(it) }) }
                }
            } else {
                IntentChips(intent, pace, autoShade = plan?.preferShade ?: false) { intent = it; chipEdited = true }
                Row(Modifier.height(32.dp), verticalAlignment = Alignment.CenterVertically) {
                    if (loading) {
                        CircularProgressIndicator(Modifier.size(16.dp), strokeWidth = 2.dp)
                        Spacer(Modifier.width(8.dp))
                        Text("코스를 짜는 중…", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                }
            }
            plan?.notes?.forEach {
                Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(top = 4.dp))
            }
            error?.let {
                Text(it, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodyMedium, modifier = Modifier.padding(top = 4.dp))
                if (!located) TextButton(onClick = { permission.launch(Manifest.permission.ACCESS_FINE_LOCATION) }) { Text("위치 권한 허용") }
            }
        }
        if (text.isBlank()) {
            Box(Modifier.fillMaxSize().padding(24.dp), contentAlignment = Alignment.Center) {
                Text(
                    "거리나 시간, 가고 싶은 곳을 말하듯 적으면\n내 주변 공원·강변·호수로 코스를 짜 드려요.\n알아들은 내용은 칩으로 보여드리고, 탭해서 고칠 수 있어요.",
                    textAlign = TextAlign.Center,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }
        LazyColumn(Modifier.weight(1f)) {
            items(plan?.courses.orEmpty()) { c ->
                LoopCard(c.points, "${c.title} · ${formatKm(c.distanceM.toDouble())}km", onRun = { save(c, onRun) }, onSave = { save(c, onOpen) }, shade = c.shade)
            }
            plan?.sources?.takeIf { it.isNotEmpty() }?.let { sources ->
                item {
                    Text(
                        "장소 정보: " + sources.joinToString(", "),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(horizontal = 24.dp, vertical = 8.dp)
                    )
                }
            }
            item { Spacer(Modifier.height(16.dp)) }
        }
    }
}

/** What the request was understood as; every chip can be changed or removed. */
@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun IntentChips(intent: CourseIntent, pace: Int?, autoShade: Boolean, onChange: (CourseIntent) -> Unit) {
    var pickDistance by remember { mutableStateOf(false) }
    FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        // On when asked for in words, or when the server chose shade for a high sun; tapping
        // makes it explicit either way.
        val shadeOn = intent.shade ?: autoShade
        FilterChip(selected = shadeOn, onClick = { onChange(intent.copy(shade = !shadeOn)) }, label = { Text("🌳 그늘 우선") })
        Box {
            AssistChip(onClick = { pickDistance = true }, label = { Text(distanceLabel(intent, pace)) })
            DropdownMenu(expanded = pickDistance, onDismissRequest = { pickDistance = false }) {
                DISTANCE_CHOICES.forEach { d ->
                    DropdownMenuItem(text = { Text("${d / 1000}km") }, onClick = {
                        onChange(intent.copy(distanceM = d, durationMin = null))
                        pickDistance = false
                    })
                }
            }
        }
        AssistChip(onClick = { onChange(intent.copy(shape = nextShape(intent))) }, label = { Text(intent.shape.label) })
        intent.laps?.let { RemovableChip("${it}바퀴") { onChange(intent.copy(laps = null)) } }
        if (intent.repeat && intent.laps == null) RemovableChip("뺑뺑이") { onChange(intent.copy(repeat = false)) }
        intent.activity?.let { a ->
            RemovableChip(if (a == Activity.WALK) "걷기" else "달리기") { onChange(intent.copy(activity = null)) }
        }
        intent.themes.forEach { t -> RemovableChip(t.label) { onChange(intent.copy(themes = intent.themes - t)) } }
        intent.places.forEach { p -> RemovableChip(placeLabel(p, intent.shape)) { onChange(intent.copy(places = intent.places - p)) } }
        if (intent.flat) RemovableChip("평지 위주") { onChange(intent.copy(flat = false)) }
    }
}

@Composable
private fun RemovableChip(label: String, onRemove: () -> Unit) {
    InputChip(
        selected = true,
        onClick = onRemove,
        label = { Text(label) },
        trailingIcon = { Icon(Icons.Filled.Close, "빼기", Modifier.size(16.dp)) },
    )
}

private fun distanceLabel(intent: CourseIntent, pace: Int?): String {
    val target = intent.targetDistanceM(pace)
    return when {
        intent.distanceM != null -> "${km(intent.distanceM)}km"
        intent.durationMin != null && target != null -> "${intent.durationMin}분 · 약 ${km(target)}km"
        target == null -> "목적지까지"
        intent.laps != null -> "거리 자동"
        else -> "${km(target)}km"
    }
}

private fun placeLabel(p: PlaceMention, shape: CourseShape) = when (p.role) {
    PlaceRole.START -> "출발 ${p.name}"
    PlaceRole.DEST -> if (shape == CourseShape.OUT_AND_BACK) "반환점 ${p.name}" else "도착 ${p.name}"
    PlaceRole.AROUND -> p.name
}

/** Tapping the shape chip cycles through the shapes; one-way only makes sense with a destination. */
private fun nextShape(intent: CourseIntent): CourseShape {
    val options = CourseShape.entries.filter { it != CourseShape.ONE_WAY || intent.destination != null }
    return options[(options.indexOf(intent.shape) + 1) % options.size]
}

/** "5", "4.5", "2.25": whole and half kilometres without trailing zeros. */
private fun km(m: Int) = if (m % 1000 == 0) "${m / 1000}" else formatKm(m.toDouble()).trimEnd('0').trimEnd('.')
