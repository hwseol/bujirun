package com.mhmh2.bujirun.ui

import android.graphics.Color as AndroidColor
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
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
) {
    val density = LocalDensity.current.density
    val color = MaterialTheme.colorScheme.primary.toArgb()
    val mapView = rememberMapView(
        NaverMapOptions().zoomControlEnabled(false).scaleBarEnabled(false).compassEnabled(false).locationButtonEnabled(false)
    )
    val path = remember { PathOverlay() }
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
            path.width = (6 * density).toInt()
            path.outlineWidth = (1.5 * density).toInt()
            path.map = map
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
        TurnBanner(progress, Modifier.align(Alignment.TopCenter).padding(12.dp))
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
            }) { Icon(Icons.Filled.ZoomOutMap, contentDescription = "코스 전체 보기") }
            if (!following) {
                SmallFloatingActionButton(onClick = { following = true }) {
                    Icon(Icons.Filled.MyLocation, contentDescription = "내 위치로")
                }
            }
            Row {
                ExtendedFloatingActionButton(
                    onClick = { if (!rerouting) onReroute() },
                    icon = {
                        if (rerouting) CircularProgressIndicator(Modifier.size(20.dp), strokeWidth = 2.dp)
                        else Icon(Icons.Filled.Refresh, contentDescription = null)
                    },
                    text = { Text(if (rerouting) "경로 찾는 중" else "경로 다시 찾기") },
                    containerColor = if (!progress.onCourse && progress.started) MaterialTheme.colorScheme.errorContainer
                    else MaterialTheme.colorScheme.secondaryContainer,
                )
            }
        }
    }
}

@Composable
private fun TurnBanner(p: CourseProgress, modifier: Modifier = Modifier) {
    val (text, warn) = when {
        !p.started -> "출발점까지 ${p.offCourseM.roundToInt()}m" to false
        !p.onCourse -> "코스를 벗어났어요 · ${p.offCourseM.roundToInt()}m" to true
        p.finished -> "코스 완주" to false
        else -> {
            val turn = p.nextTurn
            val toTurn = p.toNextTurnM
            if (turn != null && toTurn != null && toTurn <= 300) {
                "${turn.direction.arrow}  ${((toTurn / 10).roundToInt() * 10).coerceAtLeast(0)}m 앞 ${turn.direction.spoken}" to false
            } else {
                "↑  직진 · 남은 ${formatKm(p.remainingM)}km" to false
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
