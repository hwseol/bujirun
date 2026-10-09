package com.mhmh2.bujirun.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material3.AssistChip
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.InputChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.SuggestionChip
import androidx.compose.material3.Text
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
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.mhmh2.bujirun.course.CourseGeometry
import com.mhmh2.bujirun.course.CourseIntent
import com.mhmh2.bujirun.course.CourseShape
import com.mhmh2.bujirun.course.CourseSource
import com.mhmh2.bujirun.course.CourseStore
import com.mhmh2.bujirun.course.GeoPoint
import com.mhmh2.bujirun.course.CourseReader
import com.mhmh2.bujirun.course.NluModel
import com.mhmh2.bujirun.course.PlaceMention
import com.mhmh2.bujirun.course.PlaceRole
import com.mhmh2.bujirun.course.RouteApi
import com.mhmh2.bujirun.data.WorkoutStore
import com.mhmh2.bujirun.net.ApiException
import com.mhmh2.bujirun.tracking.Activity
import com.mhmh2.bujirun.tracking.formatKm
import kotlinx.coroutines.launch

private val EXAMPLES = listOf("한강 따라 5km", "공원 돌아서 30분", "남산까지 갔다가 돌아오기", "평지로 가볍게 3키로")
private val DISTANCE_CHOICES = listOf(2000, 3000, 5000, 7000, 10000)

/**
 * "말로 만들기": the user describes a course, the phone reads it into chips right away (on-device
 * model + rules, no server), the user fixes any chip, and only then is a course requested from
 * the server's planner (along a river, laps of a park, to a place and back...). See
 * docs/NL_COURSES.md.
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
internal fun NaturalTab(onOpen: (Long) -> Unit, onRun: (Long) -> Unit) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    // The on-device model; if it can't load for any reason the rules alone still work.
    val reader = remember {
        val model = runCatching { context.assets.open("nlu.bin").use { NluModel.load(it) } }.getOrNull()
        CourseReader(model?.let { it::read })
    }
    var text by rememberSaveable { mutableStateOf("") }
    var intent by remember { mutableStateOf(reader.read(text)) }
    var pace by remember { mutableStateOf<Int?>(null) }
    var loading by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }
    var plan by remember { mutableStateOf<RouteApi.Plan?>(null) }
    LaunchedEffect(Unit) { pace = WorkoutStore.typicalRunPace() }

    fun edit(newText: String) {
        text = newText
        intent = reader.read(newText)
    }

    val request = rememberWithLocation(onError = { error = it; loading = false }) { here ->
        val asked = intent
        try {
            plan = RouteApi.plan(here, asked, asked.targetDistanceM(pace))
        } catch (e: ApiException) {
            error = e.message
            plan = null
        } finally {
            loading = false
        }
    }

    fun go() {
        if (text.isBlank() || loading) return
        loading = true
        error = null
        request()
    }

    fun save(course: RouteApi.PlannedCourse, then: (Long) -> Unit) {
        scope.launch { then(CourseStore.save(course.title, CourseSource.RECOMMENDED, course.points)) }
    }

    Column(Modifier.fillMaxSize()) {
        Column(Modifier.padding(horizontal = 24.dp)) {
            OutlinedTextField(
                value = text,
                onValueChange = ::edit,
                placeholder = { Text("어떤 코스를 원하세요?") },
                maxLines = 3,
                keyboardOptions = KeyboardOptions(imeAction = ImeAction.Done),
                keyboardActions = KeyboardActions(onDone = { go() }),
                modifier = Modifier.fillMaxWidth(),
            )
            Spacer(Modifier.height(8.dp))
            if (text.isBlank()) {
                FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    EXAMPLES.forEach { SuggestionChip(onClick = { edit(it) }, label = { Text(it) }) }
                }
            } else {
                IntentChips(intent, pace) { intent = it }
                Spacer(Modifier.height(8.dp))
                Button(onClick = ::go, enabled = !loading, modifier = Modifier.fillMaxWidth().height(52.dp)) {
                    if (loading) CircularProgressIndicator(Modifier.size(22.dp), strokeWidth = 2.dp) else Text("코스 만들기")
                }
            }
            plan?.notes?.forEach {
                Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(top = 8.dp))
            }
            error?.let {
                Text(it, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodyMedium, modifier = Modifier.padding(top = 8.dp))
            }
        }
        if (plan == null && !loading && error == null && text.isBlank()) {
            Box(Modifier.fillMaxSize().padding(24.dp), contentAlignment = Alignment.Center) {
                Text(
                    "거리나 시간, 가고 싶은 곳을 말하듯 적어 보세요.\n알아들은 내용은 아래 칩으로 보여드리고, 탭해서 고칠 수 있어요.",
                    textAlign = TextAlign.Center,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }
        LazyColumn(Modifier.weight(1f)) {
            items(plan?.courses.orEmpty()) { c ->
                LoopCard(c.points, "${c.title} · ${formatKm(c.distanceM.toDouble())}km", onRun = { save(c, onRun) }, onSave = { save(c, onOpen) })
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

/** What the user understood the request as; every chip can be changed or removed. */
@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun IntentChips(intent: CourseIntent, pace: Int?, onChange: (CourseIntent) -> Unit) {
    var pickDistance by remember { mutableStateOf(false) }
    FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
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
        trailingIcon = { Icon(Icons.Default.Close, "빼기", Modifier.size(16.dp)) },
    )
}

private fun distanceLabel(intent: CourseIntent, pace: Int?): String {
    val target = intent.targetDistanceM(pace)
    return when {
        intent.distanceM != null -> "${km(intent.distanceM)}km"
        intent.durationMin != null && target != null -> "${intent.durationMin}분 · 약 ${km(target)}km"
        target == null -> "목적지까지"
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
