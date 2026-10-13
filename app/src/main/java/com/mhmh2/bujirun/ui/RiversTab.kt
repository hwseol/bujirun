package com.mhmh2.bujirun.ui

import com.mhmh2.bujirun.tr

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
import com.mhmh2.bujirun.course.CourseTheme
import com.mhmh2.bujirun.course.GeoPoint
import com.mhmh2.bujirun.course.PlaceMention
import com.mhmh2.bujirun.course.PlaceRole
import com.mhmh2.bujirun.course.RouteApi
import com.mhmh2.bujirun.net.ApiException
import com.mhmh2.bujirun.tracking.formatKm
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

private val RIVER_DISTANCES = listOf(3000, 5000, 7000, 10000)

/**
 * 천·강변: the streams and rivers around the start, nearest first; pick one and a distance and
 * the course runs along it on the path by the water and comes back down the far bank where
 * that's mapped (backend waterside.py). Same shape as 공원 코스: no button, it follows the choice.
 */
@Composable
internal fun RiversTab(onOpen: (Long) -> Unit, onRun: (Long) -> Unit) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var distance by rememberSaveable { mutableStateOf(5000) }
    var here by remember { mutableStateOf<GeoPoint?>(null) }
    var rivers by remember { mutableStateOf<List<RouteApi.NearbyRiver>?>(null) }
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
        if (!granted) error = tr("주변 천을 찾으려면 위치 권한이 필요해요.", "Location permission is needed to find streams around you.")
    }
    LaunchedEffect(Unit) { if (!located) permission.launch(Manifest.permission.ACCESS_FINE_LOCATION) }

    LaunchedEffect(located, CourseStart.picked) {
        if (!located && CourseStart.picked == null) return@LaunchedEffect
        try {
            val at = CourseStart.resolve(context) ?: throw ApiException(tr("현재 위치를 알 수 없어요. 위치가 켜져 있는지 확인해 주세요.", "Can't tell where you are. Please check that location is on."))
            here = at
            val (found, credit) = RouteApi.rivers(at)
            rivers = found
            sources = credit
            if (chosen == null || found.none { it.id == chosen }) chosen = found.firstOrNull()?.id
            error = if (found.isEmpty()) tr("3km 안에 천이나 강이 없어요. 공원 코스나 순환 코스를 이용해 보세요.", "No stream or river within 3 km. Try a park course or a loop.") else null
        } catch (e: ApiException) {
            error = e.message
        }
    }

    // Keyed on the river list too, for the same reason as ParksTab.
    LaunchedEffect(chosen, distance, here, rivers) {
        val at = here ?: return@LaunchedEffect
        val river = rivers?.firstOrNull { it.id == chosen } ?: return@LaunchedEffect
        delay(200)
        loading = true
        error = null
        try {
            val intent = CourseIntent(
                distanceM = distance, themes = setOf(CourseTheme.RIVER),
                places = listOf(PlaceMention(river.name, PlaceRole.AROUND, river.id)),
            )
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

    LazyColumn(Modifier.fillMaxSize()) {
        item {
            Text(
                tr("가까운 천·강을 골라 거리만 정하면, 물 바로 옆 길로 갔다가 건너편 둑길로 돌아오는 코스를 짜 드려요. ", "Pick a stream or river nearby and a distance: the course goes out on the path by the water and back on the far bank. ") +
                    tr("건너편 길이 지도에 없으면 같은 물가 길로 돌아와요.", "Where the far bank has no mapped path, it comes back the same way."),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(horizontal = 24.dp)
            )
            Spacer(Modifier.height(6.dp))
            Column(Modifier.padding(horizontal = 24.dp)) { StartChip() }
        }
        item {
            LazyRow(contentPadding = PaddingValues(horizontal = 24.dp), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                items(RIVER_DISTANCES) { d ->
                    FilterChip(selected = distance == d, onClick = { distance = d }, label = { Text("${d / 1000}km") })
                }
            }
        }
        item {
            LazyRow(contentPadding = PaddingValues(horizontal = 16.dp, vertical = 6.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                items(rivers.orEmpty(), key = { it.id }) { r -> RiverCard(r, selected = r.id == chosen) { chosen = r.id } }
            }
        }
        item {
            Column(Modifier.padding(horizontal = 24.dp)) {
                if (loading || (rivers == null && error == null)) {
                    Row(Modifier.height(30.dp), verticalAlignment = Alignment.CenterVertically) {
                        CircularProgressIndicator(Modifier.size(16.dp), strokeWidth = 2.dp)
                        Spacer(Modifier.width(8.dp))
                        Text(if (rivers == null) tr("주변 천을 찾는 중…", "Finding streams nearby…") else tr("물가 길로 코스를 짜는 중…", "Planning along the water…"),
                            style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                }
                plan?.notes?.forEach {
                    Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
                error?.let {
                    Text(it, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodyMedium)
                    if (!located) TextButton(onClick = { permission.launch(Manifest.permission.ACCESS_FINE_LOCATION) }) { Text(tr("위치 권한 허용", "Allow location")) }
                }
            }
        }
        items(plan?.courses.orEmpty()) { c ->
            LoopCard(c.points, "${c.title} · ${formatKm(c.distanceM.toDouble())}km", onRun = { save(c, onRun) }, onSave = { save(c, onOpen) }, shade = c.shade, legs = c.legs, hills = c.hills)
        }
        val credits = (sources + plan?.sources.orEmpty()).distinct()
        if (credits.isNotEmpty()) {
            item {
                Text(
                    tr("장소 정보: ", "Place data: ") + credits.joinToString(", "),
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
private fun RiverCard(r: RouteApi.NearbyRiver, selected: Boolean, onClick: () -> Unit) {
    val colors = MaterialTheme.colorScheme
    Surface(
        onClick = onClick,
        shape = RoundedCornerShape(16.dp),
        color = if (selected) colors.primaryContainer else colors.surfaceContainerLow,
        border = if (selected) BorderStroke(2.dp, colors.primary) else null,
        modifier = Modifier.widthIn(min = 140.dp, max = 220.dp),
    ) {
        Column(Modifier.padding(horizontal = 12.dp, vertical = 10.dp)) {
            Text(r.name, style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.Bold, maxLines = 1, overflow = TextOverflow.Ellipsis)
            Text(
                if (r.awayM < 50) tr("바로 여기", "Right here") else tr("${formatKm(r.awayM.toDouble())}km 거리", "${formatKm(r.awayM.toDouble())}km away"),
                style = MaterialTheme.typography.bodySmall, color = colors.onSurfaceVariant
            )
            Text(tr("물가 길 따라", "Along the water"), style = MaterialTheme.typography.labelSmall, color = colors.primary)
        }
    }
}
