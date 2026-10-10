package com.mhmh2.bujirun.ui

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
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
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
                    IconButton(onClick = onDismiss) { Icon(Icons.AutoMirrored.Filled.ArrowBack, "닫기") }
                    Text(info.title, style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold, maxLines = 1)
                }
                Box(Modifier.weight(1f).fillMaxWidth()) { CourseMap(info.points) }
                Column(Modifier.fillMaxWidth().heightInScreen().verticalScroll(rememberScrollState()).padding(horizontal = 20.dp, vertical = 12.dp)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text("${formatKm(meters)}km", style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.Bold)
                        info.shade?.let { ShadeBadge(it) }
                    }
                    Text(estimateText(meters, paces), style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    info.hills?.let { HillsLine(it) }
                    if (info.legs.isNotEmpty()) {
                        Spacer(Modifier.height(12.dp))
                        Text("코스 구성", style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.Bold)
                        info.legs.forEachIndexed { i, leg ->
                            Row(Modifier.fillMaxWidth().padding(vertical = 6.dp), verticalAlignment = Alignment.CenterVertically) {
                                Text("${i + 1}", color = MaterialTheme.colorScheme.primary, fontWeight = FontWeight.Bold, modifier = Modifier.padding(end = 10.dp))
                                Column(Modifier.weight(1f)) {
                                    Text(leg.label, style = MaterialTheme.typography.bodyLarge)
                                    Text(
                                        "${formatKm(leg.distanceM.toDouble())}km" + (leg.shade?.let { " · 나무 그늘 $it%" } ?: "") +
                                            (leg.ascentM?.let { " · 오르막 ${it}m" } ?: ""),
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
                            "그늘 %는 코스 길이 중 나무 그늘 아래를 지나는 비율이에요 (위성 자료 기준, 건물 그늘은 빠져 있어요).",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.padding(top = 10.dp)
                        )
                    }
                }
                Row(Modifier.fillMaxWidth().padding(16.dp), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    OutlinedButton(onClick = onSave, modifier = Modifier.weight(1f).height(52.dp)) { Text("내 코스에 저장") }
                    Button(onClick = onRun, modifier = Modifier.weight(1f).height(52.dp)) { Text("바로 달리기") }
                }
            }
        }
    }
}

/** The detail part never takes more than ~40% of the screen: the map stays the main thing. */
@Composable
private fun Modifier.heightInScreen(): Modifier =
    heightIn(max = (LocalConfiguration.current.screenHeightDp * 0.4f).dp)

/** An interactive map of the course: pan, zoom, start pin, the line. */
@Composable
private fun CourseMap(points: List<GeoPoint>) {
    val density = LocalDensity.current.density
    val color = MaterialTheme.colorScheme.primary.toArgb()
    val fit = remember(points) { points.map { LatLng(it.lat, it.lon) } }
    val mapView = rememberMapView(
        NaverMapOptions().camera(com.naver.maps.map.CameraPosition(fit.first(), 15.0))
            .zoomControlEnabled(true).scaleBarEnabled(true).compassEnabled(false)
    )
    LaunchedEffect(mapView, points) {
        mapView.getMapAsync { map ->
            PathOverlay(fit).apply {
                this.color = color
                outlineColor = AndroidColor.WHITE
                width = (6 * density).toInt()
                outlineWidth = (1.5 * density).toInt()
                this.map = map
            }
            endpointMarker(map, fit.first(), "출발", START_GREEN, density)
            if (!CourseGeometry.isLoop(points)) endpointMarker(map, fit.last(), "도착", END_DARK, density)
            map.moveCamera(CameraUpdate.fitBounds(LatLngBounds.from(fit), (48 * density).toInt()))
        }
    }
    AndroidView(factory = { mapView }, modifier = Modifier.fillMaxSize())
}
