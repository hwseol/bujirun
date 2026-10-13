package com.mhmh2.bujirun.ui

import com.mhmh2.bujirun.net.srv

import com.mhmh2.bujirun.tr

import android.Manifest
import android.annotation.SuppressLint
import android.content.Context
import android.content.pm.PackageManager
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
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
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.material3.Tab
import androidx.compose.material3.TextButton
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import kotlinx.coroutines.delay
import androidx.compose.material3.TabRow
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.text.style.TextOverflow
import com.mhmh2.bujirun.course.Trail
import com.mhmh2.bujirun.course.TrailApi
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.AutoAwesome
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.draw.clip
import androidx.compose.foundation.background
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.font.FontWeight
import kotlin.math.roundToInt
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import com.google.android.gms.location.LocationServices
import com.google.android.gms.location.Priority
import com.mhmh2.bujirun.course.CourseGeometry
import com.mhmh2.bujirun.course.CourseSource
import com.mhmh2.bujirun.course.CourseStore
import com.mhmh2.bujirun.course.GeoPoint
import com.mhmh2.bujirun.course.RouteApi
import com.mhmh2.bujirun.data.Paces
import com.mhmh2.bujirun.data.WorkoutStore
import com.mhmh2.bujirun.net.ApiException
import com.mhmh2.bujirun.tracking.formatDuration
import com.mhmh2.bujirun.tracking.formatKm
import kotlinx.coroutines.launch
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlin.coroutines.resume

private val DISTANCES = listOf(3000, 5000, 7000, 10000)

/**
 * Courses around where the user is now: laps of the parks right here (along their own paths),
 * loops generated from here (pick a distance), and official walking trails that come within
 * reach (Durunubi). Any can be run right away or kept. (Courses described in words are
 * AiCourseScreen.)
 */
@Composable
fun SuggestScreen(onClose: () -> Unit, onOpen: (Long) -> Unit, onRun: (Long) -> Unit, initialTab: Int = 0) {
    var tab by rememberSaveable { mutableStateOf(initialTab) }
    Column(Modifier.fillMaxSize().safeDrawingPadding()) {
        Row(Modifier.fillMaxWidth().padding(horizontal = 8.dp, vertical = 4.dp), verticalAlignment = Alignment.CenterVertically) {
            IconButton(onClick = onClose) { Icon(Icons.AutoMirrored.Filled.ArrowBack, tr("뒤로", "Back")) }
            Icon(Icons.Filled.AutoAwesome, null, tint = MaterialTheme.colorScheme.primary)
            Spacer(Modifier.width(8.dp))
            Text(tr("AI 주변 코스", "AI courses nearby"), style = MaterialTheme.typography.titleLarge)
        }
        TabRow(selectedTabIndex = tab) {
            Tab(selected = tab == 0, onClick = { tab = 0 }, text = { Text(tr("공원·트랙", "Parks")) })
            Tab(selected = tab == 1, onClick = { tab = 1 }, text = { Text(tr("천·강변", "Rivers")) })
            Tab(selected = tab == 2, onClick = { tab = 2 }, text = { Text(tr("순환", "Loops")) })
            Tab(selected = tab == 3, onClick = { tab = 3 }, text = { Text(tr("걷기길", "Trails")) })
        }
        Spacer(Modifier.height(12.dp))
        when (tab) {
            0 -> ParksTab(onOpen, onRun)
            1 -> RiversTab(onOpen, onRun)
            2 -> LoopsTab(onOpen, onRun)
            else -> TrailsTab(onOpen, onRun)
        }
    }
}

/** Official trails farther than this aren't "around here"; the tab says so instead. */
private const val TRAIL_REACH_M = 15_000

/** Asks for location permission if needed, then runs [action] with the current position. */
@Composable
internal fun rememberWithLocation(onError: (String) -> Unit, action: suspend (GeoPoint) -> Unit): () -> Unit {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    fun go() {
        scope.launch {
            val here = currentLocation(context)
            if (here == null) onError(tr("현재 위치를 알 수 없어요. 위치가 켜져 있는지 확인해 주세요.", "Can't tell where you are. Please check that location is on.")) else action(here)
        }
    }
    val permission = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        if (granted) go() else onError(tr("주변 코스를 찾으려면 위치 권한이 필요해요.", "Location permission is needed to find courses around you."))
    }
    return {
        if (ContextCompat.checkSelfPermission(context, Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED) go()
        else permission.launch(Manifest.permission.ACCESS_FINE_LOCATION)
    }
}

@Composable
private fun TrailsTab(onOpen: (Long) -> Unit, onRun: (Long) -> Unit) {
    val scope = rememberCoroutineScope()
    var loading by remember { mutableStateOf(false) }
    var busyId by remember { mutableStateOf<String?>(null) }
    var error by remember { mutableStateOf<String?>(null) }
    var trails by remember { mutableStateOf<List<Trail>?>(null) }

    val load = rememberWithLocation(onError = { error = it; loading = false }) { here ->
        try {
            trails = TrailApi.nearby(here)
        } catch (e: ApiException) {
            error = e.message
        } finally {
            loading = false
        }
    }
    LaunchedEffect(Unit) { loading = true; error = null; load() }

    fun keep(t: Trail, then: (Long) -> Unit) {
        busyId = t.id
        scope.launch {
            try {
                then(CourseStore.save(srv(t.name), CourseSource.RECOMMENDED, TrailApi.points(t.id)))
            } catch (e: ApiException) {
                error = e.message
            } finally {
                busyId = null
            }
        }
    }

    val inReach = trails.orEmpty().filter { it.awayM <= TRAIL_REACH_M }
    Column(Modifier.padding(horizontal = 24.dp)) {
        Text(
            tr("코리아둘레길 등 공식 걷기길 중 ${TRAIL_REACH_M / 1000}km 안에 지나는 길이에요.", "Official walking trails, like the Korea Dulle-gil, that pass within ${TRAIL_REACH_M / 1000}km."),
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
        if (loading) CircularProgressIndicator(Modifier.padding(top = 16.dp))
        error?.let { Text(it, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodyMedium, modifier = Modifier.padding(top = 8.dp)) }
        val nearest = trails?.firstOrNull()
        if (trails != null && inReach.isEmpty()) {
            Text(
                tr("근처를 지나는 공식 걷기길이 없어요.", "No official trail passes nearby.") +
                    (nearest?.let { tr(" 가장 가까운 길은 ${it.name} (${formatKm(it.awayM)}km)예요.", " The nearest is ${srv(it.name)} (${formatKm(it.awayM)}km).") } ?: "") +
                    tr("\n공원 코스나 순환 코스를 이용해 보세요.", "\nTry a park course or a loop."),
                style = MaterialTheme.typography.bodyMedium,
                modifier = Modifier.padding(top = 16.dp)
            )
        }
    }
    LazyColumn {
        items(inReach, key = { it.id }) { t ->
            Surface(
                shape = RoundedCornerShape(20.dp),
                tonalElevation = 1.dp,
                modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp)
            ) {
                Column(Modifier.padding(12.dp)) {
                    CourseLineView(t.preview)
                    Spacer(Modifier.height(10.dp))
                    Text(srv(t.name), style = MaterialTheme.typography.titleMedium)
                    Text(
                        listOfNotNull(
                            t.region.takeIf { it.isNotBlank() },
                            "${formatKm(t.distanceM)}km",
                            if (t.loop) tr("순환", "Loop") else tr("편도", "One way"),
                            t.levelLabel,
                            t.hours?.let { tr("약 ${com.mhmh2.bujirun.coach.CueText.duration((it * 3_600_000).toLong())}", "about ${com.mhmh2.bujirun.coach.CueText.duration((it * 3_600_000).toLong())}") },
                        ).joinToString(" · "),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    Text(
                        tr("여기서 ${formatKm(t.awayM)}km", "${formatKm(t.awayM)}km from here"),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.primary
                    )
                    if (t.summary.isNotBlank()) {
                        Text(
                            t.summary,
                            style = MaterialTheme.typography.bodySmall,
                            maxLines = 3,
                            overflow = TextOverflow.Ellipsis,
                            modifier = Modifier.padding(top = 6.dp)
                        )
                    }
                    Spacer(Modifier.height(10.dp))
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        Button(onClick = { keep(t, onRun) }, enabled = busyId == null, modifier = Modifier.weight(1f)) { Text(tr("바로 달리기", "Run now")) }
                        OutlinedButton(onClick = { keep(t, onOpen) }, enabled = busyId == null, modifier = Modifier.weight(1f)) { Text(tr("내 코스에 저장", "Save to my courses")) }
                    }
                }
            }
        }
        if (inReach.isNotEmpty()) {
            item {
                Text(
                    tr("걷기길 정보: ${TrailApi.SOURCE}", "Trail data: ${TrailApi.SOURCE}"),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(horizontal = 24.dp, vertical = 16.dp)
                )
            }
        }
    }
}

@Composable
private fun LoopsTab(onOpen: (Long) -> Unit, onRun: (Long) -> Unit) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var distance by rememberSaveable { mutableStateOf(5000) }
    var loading by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }
    var loops by remember { mutableStateOf<List<RouteApi.Loop>>(emptyList()) }
    var located by remember {
        mutableStateOf(ContextCompat.checkSelfPermission(context, Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED)
    }
    val permission = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        located = granted
        if (!granted) error = tr("주변 코스를 찾으려면 위치 권한이 필요해요.", "Location permission is needed to find courses around you.")
    }
    LaunchedEffect(Unit) { if (!located) permission.launch(Manifest.permission.ACCESS_FINE_LOCATION) }

    // No button: courses for the chosen distance load on opening and whenever it changes. A new
    // choice cancels the one in flight; the server caches answers, so going back is free.
    LaunchedEffect(distance, located, CourseStart.picked) {
        if (!located && CourseStart.picked == null) return@LaunchedEffect
        delay(250)
        loading = true
        error = null
        try {
            val here = CourseStart.resolve(context) ?: throw ApiException(tr("현재 위치를 알 수 없어요. 위치가 켜져 있는지 확인해 주세요.", "Can't tell where you are. Please check that location is on."))
            loops = RouteApi.loops(here, distance)
        } catch (e: ApiException) {
            error = e.message
            loops = emptyList()
        } finally {
            loading = false
        }
    }

    fun save(points: List<GeoPoint>, then: (Long) -> Unit) {
        scope.launch {
            val km = formatKm(CourseGeometry.distanceM(points))
            then(CourseStore.save(tr("추천 순환 ${km}km", "Suggested loop ${km}km"), CourseSource.RECOMMENDED, points))
        }
    }

    // One list for the whole tab, so everything scrolls together.
    LazyColumn(Modifier.fillMaxSize()) {
        item {
            Column(Modifier.padding(horizontal = 24.dp)) {
                Text(
                    tr("출발점에서 시작해 다시 돌아오는 코스를 찾아드려요.", "Loops that start and finish at your start point."),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                Spacer(Modifier.height(6.dp))
                StartChip()
            }
        }
        item {
            LazyRow(contentPadding = PaddingValues(horizontal = 24.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                items(DISTANCES) { d ->
                    FilterChip(selected = distance == d, onClick = { distance = d }, label = { Text("${d / 1000}km") })
                }
            }
        }
        item {
            Column(Modifier.padding(horizontal = 24.dp)) {
                if (loading) {
                    Row(Modifier.height(32.dp), verticalAlignment = Alignment.CenterVertically) {
                        CircularProgressIndicator(Modifier.size(16.dp), strokeWidth = 2.dp)
                        Spacer(Modifier.width(8.dp))
                        Text(tr("${distance / 1000}km 코스를 찾는 중…", "Finding ${distance / 1000}km courses…"), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                }
                error?.let {
                    Text(it, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodyMedium)
                    if (!located) TextButton(onClick = { permission.launch(Manifest.permission.ACCESS_FINE_LOCATION) }) { Text(tr("위치 권한 허용", "Allow location")) }
                }
            }
        }
        itemsIndexed(loops) { i, loop ->
            val points = loop.points
            LoopCard(points, tr("추천 ${i + 1} · ${formatKm(CourseGeometry.distanceM(points))}km 순환", "Suggestion ${i + 1} · ${formatKm(CourseGeometry.distanceM(points))}km loop"), onRun = { save(points, onRun) }, onSave = { save(points, onOpen) }, shade = loop.shade, hills = loop.hills)
        }
        if (loops.any { it.shade != null || it.hills != null }) {
            item {
                Text(
                    listOfNotNull(
                        tr("그늘: ESA WorldCover 2021 (나무 그늘 기준)", "Shade: ESA WorldCover 2021 (tree cover)").takeIf { loops.any { it.shade != null } },
                        tr("높이: Copernicus DEM (ESA)", "Height: Copernicus DEM (ESA)").takeIf { loops.any { it.hills != null } },
                    ).joinToString(" · "),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(horizontal = 24.dp, vertical = 8.dp)
                )
            }
        }
        item { Spacer(Modifier.height(16.dp)) }
    }
}

/** "🌳 그늘 62%": the share of a course (or park) under trees. Greener the shadier. */
@Composable
internal fun ShadeBadge(percent: Int) {
    val strong = percent >= 50
    Text(
        tr("🌳 그늘 $percent%", "🌳 Shade $percent%"),
        style = MaterialTheme.typography.labelMedium,
        color = if (strong) Color(0xFF1B5E20) else MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = Modifier.padding(start = 8.dp).clip(RoundedCornerShape(50))
            .background(if (strong) Color(0xFFDCEFD9) else MaterialTheme.colorScheme.surfaceVariant)
            .padding(horizontal = 8.dp, vertical = 3.dp)
    )
}

/** "⛰ 오르막 85m · 최대 12% · 오르막이 많은 언덕 코스예요": amber when hilly, so it's noticed. */
@Composable
internal fun HillsLine(h: RouteApi.Hills) {
    val hilly = h.terrain.contains("언덕")
    Text(
        if (h.terrain == "평지") tr("⛰ 평지 코스 (오르막 ${h.ascentM}m)", "⛰ Flat course (climb ${h.ascentM}m)")
        else tr("⛰ 오르막 ${h.ascentM}m · 최대 경사 ${h.maxGrade.roundToInt()}% · ${h.terrain}", "⛰ Climb ${h.ascentM}m · steepest ${h.maxGrade.roundToInt()}% · ${if (hilly) "a hilly course" else "some gentle climbs"}"),
        style = MaterialTheme.typography.bodySmall,
        fontWeight = if (hilly) FontWeight.Bold else null,
        color = if (hilly) Color(0xFFB45309) else MaterialTheme.colorScheme.onSurfaceVariant,
    )
}

/** The user's own running and walking paces (from their workouts), for time estimates. */
@Composable
internal fun rememberPaces(): Paces =
    remember { WorkoutStore.observePaces() }.collectAsState(initial = Paces(null, null)).value

/** "달리기 약 32:10 · 걷기 약 1:01:00", at the user's own paces when known ("내 페이스"). */
internal fun estimateText(meters: Double, paces: Paces): String {
    val run = tr("달리기 약 ${formatDuration(paces.runMs(meters))}", "Run about ${formatDuration(paces.runMs(meters))}") + if (paces.runOrNull != null) tr(" (내 페이스)", " (your pace)") else ""
    val walk = tr("걷기 약 ${formatDuration(paces.walkMs(meters))}", "Walk about ${formatDuration(paces.walkMs(meters))}") + if (paces.walkOrNull != null) tr(" (내 페이스)", " (your pace)") else ""
    return "$run · $walk"
}

/**
 * One suggested course: its shape, a title, walking/running time, how much of it is under trees
 * ([shade] %, when the server knows), and run / keep buttons.
 */
@Composable
internal fun LoopCard(
    points: List<GeoPoint>,
    title: String,
    onRun: () -> Unit,
    onSave: () -> Unit,
    shade: Int? = null,
    legs: List<RouteApi.Leg> = emptyList(),
    hills: RouteApi.Hills? = null,
) {
    val meters = CourseGeometry.distanceM(points)
    val paces = rememberPaces()
    var details by remember { mutableStateOf(false) }
    if (details) {
        CoursePreviewDialog(
            CourseInfo(title, points, shade, legs, hills = hills),
            onDismiss = { details = false },
            onRun = { details = false; onRun() },
            onSave = { details = false; onSave() },
        )
    }
    // Tapping the card (its map is static) opens the course up close.
    Surface(
        onClick = { details = true },
        shape = RoundedCornerShape(20.dp),
        tonalElevation = 1.dp,
        modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp)
    ) {
        Column(Modifier.padding(12.dp)) {
            CourseLineView(points)
            Spacer(Modifier.height(10.dp))
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(title, style = MaterialTheme.typography.titleMedium, modifier = Modifier.weight(1f, fill = false))
                shade?.let { ShadeBadge(it) }
            }
            Text(
                estimateText(meters, paces),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            hills?.let { HillsLine(it) }
            if (legs.isNotEmpty()) {
                Text(
                    legs.joinToString(" → ") { srv(it.label).substringBefore(" (") },
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 2,
                )
            }
            TextButton(onClick = { details = true }, contentPadding = PaddingValues(0.dp)) { Text(tr("지도 크게 보기 · 자세히 ›", "Larger map · details ›")) }
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Button(onClick = onRun, modifier = Modifier.weight(1f)) { Text(tr("바로 달리기", "Run now")) }
                OutlinedButton(onClick = onSave, modifier = Modifier.weight(1f)) { Text(tr("내 코스에 저장", "Save to my courses")) }
            }
        }
    }
}

/** A fresh fix (falls back to the last known one), or null without permission or a signal. */
@SuppressLint("MissingPermission")
internal suspend fun currentLocation(context: Context): GeoPoint? {
    if (ContextCompat.checkSelfPermission(context, Manifest.permission.ACCESS_FINE_LOCATION) != PackageManager.PERMISSION_GRANTED) return null
    val client = LocationServices.getFusedLocationProviderClient(context)
    val fresh = suspendCancellableCoroutine<android.location.Location?> { cont ->
        client.getCurrentLocation(Priority.PRIORITY_HIGH_ACCURACY, null)
            .addOnSuccessListener { cont.resume(it) }
            .addOnFailureListener { cont.resume(null) }
    }
    val loc = fresh ?: suspendCancellableCoroutine<android.location.Location?> { cont ->
        client.lastLocation.addOnSuccessListener { cont.resume(it) }.addOnFailureListener { cont.resume(null) }
    }
    return loc?.let { GeoPoint(it.latitude, it.longitude) }
}
