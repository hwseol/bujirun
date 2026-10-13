package com.mhmh2.bujirun.ui

import com.mhmh2.bujirun.net.srv

import com.mhmh2.bujirun.tr

import android.graphics.Color as AndroidColor
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.asPaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.systemBars
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.Button
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import com.mhmh2.bujirun.course.CourseRoute
import com.naver.maps.map.CameraAnimation
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.view.doOnLayout
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import com.mhmh2.bujirun.course.CourseGeometry
import com.mhmh2.bujirun.course.GeoPoint
import com.mhmh2.bujirun.course.RouteApi
import com.mhmh2.bujirun.tracking.formatDuration
import com.mhmh2.bujirun.tracking.formatKm
import com.naver.maps.geometry.LatLng
import com.naver.maps.geometry.LatLngBounds
import com.naver.maps.map.CameraUpdate
import com.naver.maps.map.NaverMapOptions
import com.naver.maps.map.overlay.PathOverlay

/** What a suggested course is made of, for the detail view. */
data class CourseInfo(
    val title: String,
    val points: List<GeoPoint>,
    val shade: Int? = null,
    val legs: List<RouteApi.Leg> = emptyList(),
    val via: List<String> = emptyList(),
    val hills: RouteApi.Hills? = null,
)

/**
 * A suggested course up close: a map that pans and zooms freely (the cards' maps are static so
 * they don't fight the list's scrolling), what the course is made of, time at the user's own
 * paces and shade, and run / keep.
 */
@Composable
fun CoursePreviewDialog(info: CourseInfo, onDismiss: () -> Unit, onRun: () -> Unit, onSave: () -> Unit) {
    val bars = WindowInsets.systemBars.asPaddingValues()
    val paces = rememberPaces()
    val meters = CourseGeometry.distanceM(info.points)
    Dialog(onDismissRequest = onDismiss, properties = DialogProperties(usePlatformDefaultWidth = false, decorFitsSystemWindows = false)) {
        Surface(Modifier.fillMaxSize()) {
            Column(Modifier.fillMaxSize().padding(bars)) {
                Row(Modifier.fillMaxWidth().padding(horizontal = 4.dp, vertical = 4.dp), verticalAlignment = Alignment.CenterVertically) {
                    IconButton(onClick = onDismiss) { Icon(Icons.AutoMirrored.Filled.ArrowBack, tr("닫기", "Close")) }
                    Text(srv(info.title), style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold, maxLines = 1)
                }
                // Tapping a part of the course shows just that part on the map (tap again: all of it).
                var selected by remember(info) { mutableStateOf<Int?>(null) }
                val parts = remember(info) { legLines(info.points, info.legs) }
                Box(Modifier.weight(1f).fillMaxWidth()) { CourseMap(info.points, selected?.let { parts.getOrNull(it) }) }
                Column(Modifier.fillMaxWidth().heightInScreen().verticalScroll(rememberScrollState()).padding(horizontal = 20.dp, vertical = 12.dp)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text("${formatKm(meters)}km", style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.Bold)
                        info.shade?.let { ShadeBadge(it) }
                    }
                    Text(estimateText(meters, paces), style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    info.hills?.let { HillsLine(it) }
                    if (info.legs.isNotEmpty()) {
                        Spacer(Modifier.height(12.dp))
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Text(tr("코스 구성", "Course parts"), style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.Bold)
                            Spacer(Modifier.width(8.dp))
                            Text(tr("누르면 지도에서 보여줘요", "Tap to see it on the map"), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                        info.legs.forEachIndexed { i, leg ->
                            val on = selected == i
                            Row(
                                Modifier.fillMaxWidth()
                                    .clip(RoundedCornerShape(12.dp))
                                    .background(if (on) MaterialTheme.colorScheme.primaryContainer else Color.Transparent)
                                    .clickable { selected = if (on) null else i }
                                    .padding(vertical = 6.dp, horizontal = 4.dp),
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Text("${i + 1}", color = MaterialTheme.colorScheme.primary, fontWeight = FontWeight.Bold, modifier = Modifier.padding(end = 10.dp))
                                Column(Modifier.weight(1f)) {
                                    Text(srv(leg.label), style = MaterialTheme.typography.bodyLarge)
                                    Text(
                                        "${formatKm(leg.distanceM.toDouble())}km" + (leg.shade?.let { tr(" · 나무 그늘 $it%", " · tree shade $it%") } ?: "") +
                                            (leg.ascentM?.let { tr(" · 오르막 ${it}m", " · climb ${it}m") } ?: ""),
                                        style = MaterialTheme.typography.bodySmall,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant
                                    )
                                }
                            }
                            if (i < info.legs.lastIndex) HorizontalDivider()
                        }
                    }
                    if (info.shade != null) {
                        Text(
                            tr("그늘 %는 코스 길이 중 나무 그늘 아래를 지나는 비율이에요 (위성 자료 기준, 건물 그늘은 빠져 있어요).", "Shade % is the share of the course under trees (from satellite data; building shade isn't counted)."),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.padding(top = 10.dp)
                        )
                    }
                }
                Row(Modifier.fillMaxWidth().padding(16.dp), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    OutlinedButton(onClick = onSave, modifier = Modifier.weight(1f).height(52.dp)) { Text(tr("내 코스에 저장", "Save to my courses")) }
                    Button(onClick = onRun, modifier = Modifier.weight(1f).height(52.dp)) { Text(tr("바로 달리기", "Run now")) }
                }
            }
        }
    }
}

/** The detail part never takes more than ~40% of the screen: the map stays the main thing. */
@Composable
private fun Modifier.heightInScreen(): Modifier =
    heightIn(max = (LocalConfiguration.current.screenHeightDp * 0.4f).dp)

/**
 * Each leg's stretch of [points]: legs come in order along the course, so leg i runs from the
 * sum of the ones before it (scaled, as the server measured legs on its own copy of the line).
 */
private fun legLines(points: List<GeoPoint>, legs: List<RouteApi.Leg>): List<List<GeoPoint>> {
    if (points.size < 2 || legs.isEmpty()) return emptyList()
    val route = CourseRoute(points)
    val total = legs.sumOf { it.distanceM }.toDouble()
    if (total <= 0) return emptyList()
    val k = route.lengthM / total
    var from = 0.0
    return legs.map { leg ->
        val to = from + leg.distanceM * k
        val inner = points.filterIndexed { i, _ -> route.cum[i] > from && route.cum[i] < to }
        (listOf(route.pointAt(from)) + inner + route.pointAt(to)).also { from = to }
    }
}

/**
 * An interactive map of the course: pan, zoom, start pin, the line. [highlight]: a part of it to
 * show on its own - drawn over the (faded) course and zoomed to.
 */
@Composable
internal fun CourseMap(points: List<GeoPoint>, highlight: List<GeoPoint>? = null) {
    val part = remember { PathOverlay() }
    val whole = remember { PathOverlay() }
    val density = LocalDensity.current.density
    val color = MaterialTheme.colorScheme.primary.toArgb()
    val fit = remember(points) { points.map { LatLng(it.lat, it.lon) } }
    val mapView = rememberMapView(
        NaverMapOptions().camera(com.naver.maps.map.CameraPosition(fit.first(), 15.0))
            .zoomControlEnabled(true).scaleBarEnabled(true).compassEnabled(false)
    )
    val partColor = MaterialTheme.colorScheme.tertiary.toArgb()
    LaunchedEffect(mapView, points) {
        mapView.getMapAsync { map ->
            whole.apply {
                coords = fit
                this.color = color
                outlineColor = AndroidColor.WHITE
                width = (7 * density).toInt()
                outlineWidth = (1.5 * density).toInt()
                this.map = map
            }
            directionMarkers(map, points, density, color)
            endpointMarker(map, fit.first(), tr("출발", "Start"), START_GREEN, density)
            if (!CourseGeometry.isLoop(points)) endpointMarker(map, fit.last(), tr("도착", "Finish"), END_DARK, density)
            // Fitted once the view has its size (inside a scrolling page it may not have yet).
            mapView.doOnLayout { map.moveCamera(CameraUpdate.fitBounds(LatLngBounds.from(fit), (48 * density).toInt())) }
        }
    }
    LaunchedEffect(mapView, highlight) {
        mapView.getMapAsync { map ->
            val h = highlight?.takeIf { it.size >= 2 }?.map { LatLng(it.lat, it.lon) }
            // Faded by mixing toward white (the overlay ignores alpha in its colour).
            whole.color = if (h == null) color else androidx.core.graphics.ColorUtils.blendARGB(color, AndroidColor.WHITE, 0.65f)
            if (h == null) {
                part.map = null
                map.moveCamera(CameraUpdate.fitBounds(LatLngBounds.from(fit), (48 * density).toInt()).animate(CameraAnimation.Easing))
            } else {
                part.coords = h
                part.color = partColor
                part.outlineColor = AndroidColor.WHITE
                part.width = (8 * density).toInt()
                part.outlineWidth = (2 * density).toInt()
                part.map = map
                // A short part (the 30 m to a park gate) is shown at street level, not 5 m across.
                val b = LatLngBounds.from(h)
                val update = if (b.southWest.distanceTo(b.northEast) < 150) CameraUpdate.scrollAndZoomTo(b.center, 17.0)
                else CameraUpdate.fitBounds(b, (64 * density).toInt())
                map.moveCamera(update.animate(CameraAnimation.Easing))
            }
        }
    }
    AndroidView(factory = { mapView }, modifier = Modifier.fillMaxSize())
}
