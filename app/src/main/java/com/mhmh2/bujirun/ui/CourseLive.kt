package com.mhmh2.bujirun.ui

import android.graphics.Color as AndroidColor
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import com.mhmh2.bujirun.course.CourseProgress
import com.mhmh2.bujirun.course.CourseStore
import com.mhmh2.bujirun.course.GeoPoint
import com.mhmh2.bujirun.tracking.formatKm
import com.naver.maps.geometry.LatLng
import com.naver.maps.map.CameraUpdate
import com.naver.maps.map.NaverMapOptions
import com.naver.maps.map.overlay.PathOverlay
import kotlin.math.roundToInt

/**
 * The map during a course run: the course line, the runner's position (camera follows), and a
 * banner with the next instruction. Interactive map (not lite): it's the main thing on screen.
 */
@Composable
fun CourseLiveMap(courseId: Long, progress: CourseProgress, position: GeoPoint?, modifier: Modifier = Modifier) {
    val points by remember(courseId) { CourseStore.observePoints(courseId) }.collectAsState(initial = emptyList())
    val density = LocalDensity.current.density
    val color = MaterialTheme.colorScheme.primary.toArgb()
    val mapView = rememberMapView(
        NaverMapOptions().zoomControlEnabled(false).scaleBarEnabled(false).compassEnabled(false).locationButtonEnabled(false)
    )
    val path = remember { PathOverlay() }

    LaunchedEffect(points) {
        if (points.size < 2) return@LaunchedEffect
        mapView.getMapAsync { map ->
            path.coords = points.map { LatLng(it.lat, it.lon) }
            path.color = color
            path.passedColor = Color(color).copy(alpha = 0.35f).toArgb()
            path.outlineColor = AndroidColor.WHITE
            path.width = (6 * density).toInt()
            path.outlineWidth = (1.5 * density).toInt()
            path.map = map
            if (position == null) map.moveCamera(CameraUpdate.scrollAndZoomTo(path.coords.first(), 16.0))
        }
    }
    // Follow the runner, and grey out the part of the course already done.
    LaunchedEffect(position, progress.progressM) {
        val p = position ?: return@LaunchedEffect
        mapView.getMapAsync { map ->
            val here = LatLng(p.lat, p.lon)
            map.locationOverlay.apply { isVisible = true; this.position = here }
            map.moveCamera(CameraUpdate.scrollAndZoomTo(here, maxOf(map.cameraPosition.zoom, 16.0)))
            if (progress.lengthM > 0) path.progress = (progress.progressM / progress.lengthM).coerceIn(0.0, 1.0)
        }
    }

    Box(modifier) {
        AndroidView(factory = { mapView }, modifier = Modifier.fillMaxSize())
        TurnBanner(progress, Modifier.align(Alignment.TopCenter).padding(12.dp))
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
