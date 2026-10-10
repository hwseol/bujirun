package com.mhmh2.bujirun.ui

import android.Manifest
import android.content.pm.PackageManager
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
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
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import com.mhmh2.bujirun.course.CourseIntent
import com.mhmh2.bujirun.course.CourseSource
import com.mhmh2.bujirun.course.CourseStore
import com.mhmh2.bujirun.course.GeoPoint
import com.mhmh2.bujirun.course.PlaceMention
import com.mhmh2.bujirun.course.PlaceRole
import com.mhmh2.bujirun.course.RouteApi
import com.mhmh2.bujirun.net.ApiException
import com.mhmh2.bujirun.tracking.formatKm
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

private val LAP_DISTANCES = listOf(2000, 3000, 5000, 7000, 10000)

/**
 * 공원 코스: the parks and lakes right around the user, nearest first, and laps of the chosen one
 * along its own paths (backend parkpaths.py). The nearest is picked on opening; the course
 * follows the chosen park and distance with no button.
 */
@Composable
internal fun ParksTab(onOpen: (Long) -> Unit, onRun: (Long) -> Unit) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var distance by rememberSaveable { mutableStateOf(3000) }
    var here by remember { mutableStateOf<GeoPoint?>(null) }
    var parks by remember { mutableStateOf<List<RouteApi.LapPark>?>(null) }
    var sources by remember { mutableStateOf<List<String>>(emptyList()) }
    var chosen by rememberSaveable { mutableStateOf<String?>(null) }
    var plan by remember { mutableStateOf<RouteApi.Plan?>(null) }
    var loading by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }
    var located by remember {
        mutableStateOf(ContextCompat.checkSelfPermission(context, Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED)
    }
    val permission = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        located = granted
        if (!granted) error = "주변 공원을 찾으려면 위치 권한이 필요해요."
    }
    LaunchedEffect(Unit) { if (!located) permission.launch(Manifest.permission.ACCESS_FINE_LOCATION) }

    // The parks around the start (the user's position or a spot they picked); again whenever
    // the start changes.
    LaunchedEffect(located, CourseStart.picked) {
        if (!located && CourseStart.picked == null) return@LaunchedEffect
        try {
            val at = CourseStart.resolve(context) ?: throw ApiException("현재 위치를 알 수 없어요. 위치가 켜져 있는지 확인해 주세요.")
            here = at
            val (found, credit) = RouteApi.lapParks(at)
            parks = found
            sources = credit
            if (chosen == null || found.none { it.id == chosen }) chosen = found.firstOrNull()?.id
            error = if (found.isEmpty()) "2km 안에 돌 만한 공원이 없어요. 순환 코스를 이용해 보세요." else null
        } catch (e: ApiException) {
            error = e.message
        }
    }

    // Laps of the chosen park for the chosen distance, from the start and back, counted in;
    // a new choice cancels the one in flight.
    // Keyed on the park list too: it arrives after the start, and the chosen park survives a
    // rebuilt screen, so without it nothing would trigger the course.
    LaunchedEffect(chosen, distance, here, parks) {
        val at = here ?: return@LaunchedEffect
        val park = parks?.firstOrNull { it.id == chosen } ?: return@LaunchedEffect
        delay(200)
        loading = true
        error = null
        try {
            val intent = CourseIntent(distanceM = distance, repeat = true, places = listOf(PlaceMention(park.name, PlaceRole.AROUND, park.id)))
            plan = RouteApi.plan(at, intent, distance)
        } catch (e: ApiException) {
            error = e.message
            plan = null
        } finally {
            loading = false
        }
    }

    fun save(course: RouteApi.PlannedCourse, then: (Long) -> Unit) {
        scope.launch { then(CourseStore.save(course.title, CourseSource.RECOMMENDED, course.points)) }
    }

    // One list for the whole tab, so everything scrolls together.
    LazyColumn(Modifier.fillMaxSize()) {
        item {
            Column(Modifier.padding(horizontal = 24.dp)) {
                Text(
                    "가까운 공원 안의 산책로를 따라 도는 코스예요. 공원까지 가고 오는 길도 거리에 포함돼요.",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                Spacer(Modifier.height(6.dp))
                StartChip()
            }
        }
        item {
            LazyRow(contentPadding = PaddingValues(horizontal = 24.dp), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                items(LAP_DISTANCES) { d ->
                    FilterChip(selected = distance == d, onClick = { distance = d }, label = { Text("${d / 1000}km") })
                }
            }
        }
        item {
            LazyRow(contentPadding = PaddingValues(horizontal = 16.dp, vertical = 6.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                items(parks.orEmpty(), key = { it.id }) { p -> ParkCard(p, selected = p.id == chosen) { chosen = p.id } }
            }
        }
        item {
            Column(Modifier.padding(horizontal = 24.dp)) {
                if (loading || (parks == null && error == null)) {
                    Row(Modifier.height(30.dp), verticalAlignment = Alignment.CenterVertically) {
                        CircularProgressIndicator(Modifier.size(16.dp), strokeWidth = 2.dp)
                        Spacer(Modifier.width(8.dp))
                        Text(if (parks == null) "주변 공원을 찾는 중…" else "코스를 짜는 중…",
                            style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                }
                plan?.notes?.forEach {
                    Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
                error?.let {
                    Text(it, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodyMedium)
                    if (!located) TextButton(onClick = { permission.launch(Manifest.permission.ACCESS_FINE_LOCATION) }) { Text("위치 권한 허용") }
                }
            }
        }
        items(plan?.courses.orEmpty()) { c ->
            LoopCard(c.points, "${c.title} · ${formatKm(c.distanceM.toDouble())}km", onRun = { save(c, onRun) }, onSave = { save(c, onOpen) }, shade = c.shade, legs = c.legs)
        }
        if (sources.isNotEmpty()) {
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

@Composable
private fun ParkCard(p: RouteApi.LapPark, selected: Boolean, onClick: () -> Unit) {
    val colors = MaterialTheme.colorScheme
    Surface(
        onClick = onClick,
        shape = RoundedCornerShape(16.dp),
        color = if (selected) colors.primaryContainer else colors.surfaceContainerLow,
        border = if (selected) BorderStroke(2.dp, colors.primary) else null,
        modifier = Modifier.widthIn(min = 150.dp, max = 220.dp),
    ) {
        Column(Modifier.padding(horizontal = 12.dp, vertical = 10.dp)) {
            Text(p.name, style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.Bold, maxLines = 1, overflow = TextOverflow.Ellipsis)
            Text(
                (if (p.awayM < 50) "바로 여기" else "${formatKm(p.awayM.toDouble())}km") + " · 둘레 ${formatKm(p.lapM.toDouble())}km",
                style = MaterialTheme.typography.bodySmall, color = colors.onSurfaceVariant, maxLines = 2
            )
            Text(
                (if (p.kind == "lake") "호숫가 산책로" else if (p.paths) "공원 산책로 따라" else "공원 둘레") +
                    (p.shade?.let { " · 숲 $it%" } ?: ""),
                style = MaterialTheme.typography.labelSmall, color = colors.primary, maxLines = 2
            )
        }
    }
}
