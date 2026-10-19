package com.mhmh2.bujirun.ui

import com.mhmh2.bujirun.tr

import androidx.compose.foundation.background
import android.graphics.Color as AndroidColor
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.MyLocation
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.ZoomOutMap
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExtendedFloatingActionButton
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.SmallFloatingActionButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import com.mhmh2.bujirun.course.CourseProgress
import com.mhmh2.bujirun.course.GeoPoint
import com.mhmh2.bujirun.tracking.formatKm
import com.naver.maps.geometry.LatLng
import com.naver.maps.geometry.LatLngBounds
import com.naver.maps.map.CameraUpdate
import com.naver.maps.map.NaverMap
import com.naver.maps.map.NaverMapOptions
import com.naver.maps.map.overlay.PathOverlay
import kotlin.math.roundToInt

/**
 * The map during a course run: the line being guided along ([route] - the course as joined from
 * where the runner started, or the way back to it after a reroute), the runner's position and a
 * banner with the next instruction. The camera follows the runner until they move the map
 * themselves; then it stays put (to look around) until "내 위치" brings it back.
 */
@Composable
fun CourseLiveMap(
    route: List<GeoPoint>,
    progress: CourseProgress,
    position: GeoPoint?,
    rerouting: Boolean,
    onReroute: () -> Unit,
    modifier: Modifier = Modifier,
    trail: List<GeoPoint> = emptyList(),
) {
    val density = LocalDensity.current.density
    val color = MaterialTheme.colorScheme.primary.toArgb()
    val mapView = rememberMapView(
        NaverMapOptions().zoomControlEnabled(false).scaleBarEnabled(false).compassEnabled(false).locationButtonEnabled(false)
    )
    val path = remember { PathOverlay() }
    // The way actually run, over the course: dark and thin, with where it began.
    val ran = remember { PathOverlay() }
    val ranStart = remember { mutableStateOf<com.naver.maps.map.overlay.Marker?>(null) }
    LaunchedEffect(trail.size) {
        if (trail.isEmpty()) return@LaunchedEffect
        mapView.getMapAsync { map ->
            if (ranStart.value == null) {
                ranStart.value = endpointMarker(map, LatLng(trail.first().lat, trail.first().lon), tr("출발", "Start"), START_GREEN, density)
            }
            if (trail.size >= 2) {
                ran.coords = trail.map { LatLng(it.lat, it.lon) }
                ran.color = 0xFF37474F.toInt()
                ran.outlineWidth = 0
                ran.width = (3 * density).toInt()
                ran.zIndex = 200001
                ran.map = map
            }
        }
    }
    val arrows = remember { mutableListOf<com.naver.maps.map.overlay.Marker>() }
    var following by remember { mutableStateOf(true) }

    // A drag or pinch on the map stops following.
    DisposableEffect(mapView) {
        var map: NaverMap? = null
        val listener = NaverMap.OnCameraChangeListener { reason, _ ->
            if (reason == CameraUpdate.REASON_GESTURE) following = false
        }
        mapView.getMapAsync { it.addOnCameraChangeListener(listener); map = it }
        onDispose { map?.removeOnCameraChangeListener(listener) }
    }

    LaunchedEffect(route) {
        if (route.size < 2) return@LaunchedEffect
        mapView.getMapAsync { map ->
            path.coords = route.map { LatLng(it.lat, it.lon) }
            path.color = color
            path.passedColor = Color(color).copy(alpha = 0.35f).toArgb()
            path.outlineColor = AndroidColor.WHITE
            path.width = (8 * density).toInt()
            path.outlineWidth = (1.5 * density).toInt()
            path.map = map
            arrows.forEach { it.map = null }
            arrows.clear()
            arrows += directionMarkers(map, route, density, color)
            arrows += keyPointMarkers(map, route, density)
            if (position == null) map.moveCamera(CameraUpdate.scrollAndZoomTo(path.coords.first(), 16.0))
        }
    }
    // Follow the runner, and grey out the part already done.
    LaunchedEffect(position, progress.progressM, following) {
        val p = position ?: return@LaunchedEffect
        mapView.getMapAsync { map ->
            val here = LatLng(p.lat, p.lon)
            map.locationOverlay.apply { isVisible = true; this.position = here }
            if (following) map.moveCamera(CameraUpdate.scrollAndZoomTo(here, maxOf(map.cameraPosition.zoom, 16.0)))
            if (progress.lengthM > 0) path.progress = (progress.progressM / progress.lengthM).coerceIn(0.0, 1.0)
        }
    }

    Box(modifier) {
        AndroidView(factory = { mapView }, modifier = Modifier.fillMaxSize())
        // The banner, and right under it the reroute button: up top, away from the pause and
        // finish buttons below the map, where a thumb hits it by accident.
        Column(Modifier.align(Alignment.TopCenter).fillMaxWidth().padding(12.dp), horizontalAlignment = Alignment.End) {
            TurnBanner(progress, Modifier.align(Alignment.CenterHorizontally))
            Spacer(Modifier.height(8.dp))
            ExtendedFloatingActionButton(
                onClick = { if (!rerouting) onReroute() },
                icon = {
                    if (rerouting) CircularProgressIndicator(Modifier.size(20.dp), strokeWidth = 2.dp)
                    else Icon(Icons.Filled.Refresh, contentDescription = null)
                },
                text = { Text(if (rerouting) tr("경로 찾는 중", "Finding route") else tr("경로 다시 찾기", "Find route again")) },
                containerColor = if (!progress.onCourse && progress.started) MaterialTheme.colorScheme.errorContainer
                else MaterialTheme.colorScheme.secondaryContainer,
            )
        }
        Column(
            Modifier.align(Alignment.BottomEnd).padding(12.dp),
            horizontalAlignment = Alignment.End,
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            SmallFloatingActionButton(onClick = {
                following = false
                if (route.size >= 2) mapView.getMapAsync { map ->
                    val pts = route.map { LatLng(it.lat, it.lon) } + listOfNotNull(position?.let { LatLng(it.lat, it.lon) })
                    map.moveCamera(CameraUpdate.fitBounds(LatLngBounds.from(pts), (48 * density).toInt()))
                }
            }) { Icon(Icons.Filled.ZoomOutMap, contentDescription = tr("코스 전체 보기", "Show the whole course")) }
            if (!following) {
                SmallFloatingActionButton(onClick = { following = true }) {
                    Icon(Icons.Filled.MyLocation, contentDescription = tr("내 위치로", "To my location"))
                }
            }
        }
    }
}

/**
 * A run without a course: where the runner is and the way run so far. Follows them until the
 * map is dragged or pinched; the button brings it back.
 */
@Composable
fun FreeLiveMap(trail: List<GeoPoint>, position: GeoPoint?, modifier: Modifier = Modifier) {
    val density = LocalDensity.current.density
    val color = MaterialTheme.colorScheme.primary.toArgb()
    val mapView = rememberMapView(
        NaverMapOptions().zoomControlEnabled(false).scaleBarEnabled(false).compassEnabled(false).locationButtonEnabled(false)
    )
    val path = remember { PathOverlay() }
    val startPin = remember { mutableStateOf<com.naver.maps.map.overlay.Marker?>(null) }
    var following by remember { mutableStateOf(true) }
    DisposableEffect(mapView) {
        var map: NaverMap? = null
        val listener = NaverMap.OnCameraChangeListener { reason, _ ->
            if (reason == CameraUpdate.REASON_GESTURE) following = false
        }
        mapView.getMapAsync { it.addOnCameraChangeListener(listener); map = it }
        onDispose { map?.removeOnCameraChangeListener(listener) }
    }
    LaunchedEffect(trail.size, position, following) {
        mapView.getMapAsync { map ->
            // Where the run began, pinned once.
            if (startPin.value == null && trail.isNotEmpty()) {
                startPin.value = endpointMarker(map, LatLng(trail.first().lat, trail.first().lon), tr("출발", "Start"), START_GREEN, density)
            }
            if (trail.size >= 2) {
                path.coords = trail.map { LatLng(it.lat, it.lon) }
                path.color = color
                path.outlineColor = AndroidColor.WHITE
                path.width = (7 * density).toInt()
                path.outlineWidth = (1.5 * density).toInt()
                path.map = map
            }
            val p = position ?: return@getMapAsync
            val here = LatLng(p.lat, p.lon)
            map.locationOverlay.apply { isVisible = true; this.position = here }
            if (following) map.moveCamera(CameraUpdate.scrollAndZoomTo(here, maxOf(map.cameraPosition.zoom, 16.0)))
        }
    }
    Box(modifier) {
        AndroidView(factory = { mapView }, modifier = Modifier.fillMaxSize())
        if (position == null) {
            Text(
                tr("현재 위치를 찾는 중...", "Finding your location..."), style = MaterialTheme.typography.bodyMedium,
                modifier = Modifier.align(Alignment.Center).background(MaterialTheme.colorScheme.surface.copy(alpha = 0.85f), RoundedCornerShape(50))
                    .padding(horizontal = 14.dp, vertical = 8.dp),
            )
        }
        Column(Modifier.align(Alignment.BottomEnd).padding(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            if (trail.size >= 2) {
                SmallFloatingActionButton(onClick = {
                    following = false
                    mapView.getMapAsync { map ->
                        val pts = trail.map { LatLng(it.lat, it.lon) } + listOfNotNull(position?.let { LatLng(it.lat, it.lon) })
                        map.moveCamera(CameraUpdate.fitBounds(LatLngBounds.from(pts), (48 * density).toInt()))
                    }
                }) { Icon(Icons.Filled.ZoomOutMap, contentDescription = tr("달려온 길 전체 보기", "Show the whole route")) }
            }
            if (!following) {
                SmallFloatingActionButton(onClick = { following = true }) {
                    Icon(Icons.Filled.MyLocation, contentDescription = tr("내 위치로", "To my location"))
                }
            }
        }
    }
}

@Composable
private fun TurnBanner(p: CourseProgress, modifier: Modifier = Modifier) {
    val (text, warn) = when {
        !p.started -> tr("출발점까지 ${p.offCourseM.roundToInt()}m", "${p.offCourseM.roundToInt()}m to the start") to false
        !p.onCourse -> tr("코스를 벗어났어요 · ${p.offCourseM.roundToInt()}m", "Off course · ${p.offCourseM.roundToInt()}m") to true
        p.finished -> tr("코스 완주", "Course complete") to false
        else -> {
            val turn = p.nextTurn
            val toTurn = p.toNextTurnM
            if (turn != null && toTurn != null && toTurn <= 300) {
                tr("${turn.direction.arrow}  ${((toTurn / 10).roundToInt() * 10).coerceAtLeast(0)}m 앞 ${turn.direction.spoken}", "${turn.direction.arrow}  ${turn.direction.spoken.replaceFirstChar { it.uppercase() }} in ${((toTurn / 10).roundToInt() * 10).coerceAtLeast(0)}m") to false
            } else {
                tr("↑  직진 · 남은 ${formatKm(p.remainingM)}km", "↑  Go straight · ${formatKm(p.remainingM)}km left") to false
            }
        }
    }
    Surface(
        modifier = modifier.fillMaxWidth(),
        shape = RoundedCornerShape(16.dp),
        color = if (warn) MaterialTheme.colorScheme.errorContainer else MaterialTheme.colorScheme.surface,
        shadowElevation = 4.dp,
    ) {
        Text(
            text,
            fontSize = 22.sp,
            color = if (warn) MaterialTheme.colorScheme.onErrorContainer else MaterialTheme.colorScheme.onSurface,
            modifier = Modifier.padding(horizontal = 16.dp, vertical = 12.dp)
        )
    }
}
