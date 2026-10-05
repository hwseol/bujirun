package com.mhmh2.bujirun.ui

import android.graphics.Color as AndroidColor
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.compositeOver
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalLifecycleOwner
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import com.mhmh2.bujirun.tracking.Activity
import com.mhmh2.bujirun.tracking.TrackPoint
import com.naver.maps.geometry.LatLng
import com.naver.maps.geometry.LatLngBounds
import com.naver.maps.map.CameraUpdate
import com.naver.maps.map.MapView
import com.naver.maps.map.NaverMap
import com.naver.maps.map.NaverMapOptions
import com.naver.maps.map.overlay.Align
import com.naver.maps.map.overlay.Marker
import com.naver.maps.map.overlay.MultipartPathOverlay
import com.naver.maps.map.overlay.Overlay
import com.naver.maps.map.util.MarkerIcons

/**
 * The route on a NAVER map. Lite mode: a light, static map (no panning) - right for a card
 * inside a scrolling summary, where a draggable map would fight the scroll.
 * Running stretches in the accent colour, walking lighter; one line part per pause segment, so
 * a pause spent somewhere else doesn't draw a straight jump.
 */
@Composable
fun RouteView(points: List<TrackPoint>, modifier: Modifier = Modifier) {
    val shape = RoundedCornerShape(16.dp)
    val box = modifier.fillMaxWidth().height(240.dp).clip(shape).background(MaterialTheme.colorScheme.surfaceVariant)
    if (points.size < 2) {
        Box(box, contentAlignment = Alignment.Center) {
            Text("경로를 그릴 위치 기록이 없어요", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        return
    }

    val context = LocalContext.current
    val density = LocalDensity.current
    val lifecycle = LocalLifecycleOwner.current.lifecycle
    val runColor = MaterialTheme.colorScheme.primary.toArgb()
    val walkColor = MaterialTheme.colorScheme.primary.copy(alpha = 0.45f)
        .compositeOver(androidx.compose.ui.graphics.Color.White).toArgb()

    val mapView = remember {
        MapView(
            context,
            NaverMapOptions()
                .liteModeEnabled(true)
                .zoomControlEnabled(false)
                .scaleBarEnabled(false)
                .compassEnabled(false)
                .locationButtonEnabled(false)
        )
    }
    // MapView needs the host's lifecycle forwarded to it.
    DisposableEffect(lifecycle, mapView) {
        val observer = LifecycleEventObserver { _, event ->
            when (event) {
                Lifecycle.Event.ON_CREATE -> mapView.onCreate(null)
                Lifecycle.Event.ON_START -> mapView.onStart()
                Lifecycle.Event.ON_RESUME -> mapView.onResume()
                Lifecycle.Event.ON_PAUSE -> mapView.onPause()
                Lifecycle.Event.ON_STOP -> mapView.onStop()
                Lifecycle.Event.ON_DESTROY -> mapView.onDestroy()
                else -> {}
            }
        }
        lifecycle.addObserver(observer)
        onDispose {
            lifecycle.removeObserver(observer)
            // Leaving the screen while the activity lives on: wind the map down ourselves.
            val state = lifecycle.currentState
            if (state.isAtLeast(Lifecycle.State.RESUMED)) mapView.onPause()
            if (state.isAtLeast(Lifecycle.State.STARTED)) mapView.onStop()
            mapView.onDestroy()
        }
    }

    val overlays = remember { mutableListOf<Overlay>() }
    Box(box) {
        AndroidView(factory = { mapView }, modifier = Modifier.fillMaxSize(), update = {
            mapView.getMapAsync { map ->
                overlays.forEach { it.map = null }
                overlays.clear()
                overlays += drawRoute(map, points, runColor, walkColor, with(density) { 5.dp.roundToPx() })
                val bounds = LatLngBounds.from(points.map { LatLng(it.sample.lat, it.sample.lon) })
                map.moveCamera(CameraUpdate.fitBounds(bounds, with(density) { 32.dp.roundToPx() }))
            }
        })
    }
}

private fun drawRoute(map: NaverMap, points: List<TrackPoint>, runColor: Int, walkColor: Int, widthPx: Int): List<Overlay> {
    // Split into parts at every pause and every walk/run change; consecutive parts of the same
    // segment share their boundary point so the line stays continuous.
    val parts = mutableListOf<MutableList<LatLng>>()
    val colors = mutableListOf<MultipartPathOverlay.ColorPart>()
    var prev: TrackPoint? = null
    for (p in points) {
        val latLng = LatLng(p.sample.lat, p.sample.lon)
        val walking = p.activity == Activity.WALK
        val newSegment = prev == null || prev.segment != p.segment
        val newColor = prev != null && (prev.activity == Activity.WALK) != walking
        if (newSegment || newColor) {
            val part = mutableListOf<LatLng>()
            if (!newSegment && prev != null) part += LatLng(prev.sample.lat, prev.sample.lon)
            parts += part
            val c = if (walking) walkColor else runColor
            colors += MultipartPathOverlay.ColorPart(c, AndroidColor.WHITE, c, AndroidColor.WHITE)
        }
        parts.last() += latLng
        prev = p
    }
    // Every part needs at least two points.
    val keep = parts.indices.filter { parts[it].size >= 2 }
    val result = mutableListOf<Overlay>()
    if (keep.isNotEmpty()) {
        result += MultipartPathOverlay().apply {
            coordParts = keep.map { parts[it] }
            colorParts = keep.map { colors[it] }
            width = widthPx
            outlineWidth = widthPx / 4
            this.map = map
        }
    }
    val first = points.first().sample
    val last = points.last().sample
    result += endpointMarker(map, LatLng(first.lat, first.lon), "출발", AndroidColor.rgb(0x2E, 0x9E, 0x5B), widthPx)
    result += endpointMarker(map, LatLng(last.lat, last.lon), "도착", AndroidColor.rgb(0x33, 0x33, 0x33), widthPx)
    return result
}

private fun endpointMarker(map: NaverMap, at: LatLng, caption: String, tint: Int, widthPx: Int) = Marker().apply {
    position = at
    icon = MarkerIcons.BLACK
    iconTintColor = tint
    captionText = caption
    // Above the pin: below it, the route line itself usually runs through the text.
    setCaptionAligns(Align.Top)
    width = widthPx * 5
    height = widthPx * 7
    this.map = map
}
