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
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalLifecycleOwner
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import com.naver.maps.geometry.LatLng
import com.naver.maps.geometry.LatLngBounds
import com.naver.maps.map.CameraUpdate
import com.naver.maps.map.MapView
import com.naver.maps.map.NaverMap
import com.naver.maps.map.NaverMapOptions
import com.naver.maps.map.overlay.Align
import com.naver.maps.map.overlay.Marker
import com.naver.maps.map.overlay.Overlay
import com.naver.maps.map.util.MarkerIcons

/** A NAVER MapView that follows the screen's lifecycle (the SDK needs every callback forwarded). */
@Composable
fun rememberMapView(options: NaverMapOptions): MapView {
    val context = LocalContext.current
    val lifecycle = LocalLifecycleOwner.current.lifecycle
    val mapView = remember { MapView(context, options) }
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
    return mapView
}

/**
 * A static (lite-mode) map card: no panning, so it sits inside a scrolling screen without
 * fighting the scroll. [draw] adds the overlays; the camera is fitted to [fit].
 */
@Composable
fun LiteMap(
    fit: List<LatLng>,
    modifier: Modifier = Modifier,
    height: Dp = 240.dp,
    emptyText: String = "표시할 경로가 없어요",
    draw: (NaverMap, density: Float) -> List<Overlay>,
) {
    val box = modifier.fillMaxWidth().height(height).clip(RoundedCornerShape(16.dp))
        .background(MaterialTheme.colorScheme.surfaceVariant)
    if (fit.size < 2) {
        Box(box, contentAlignment = Alignment.Center) {
            Text(emptyText, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        return
    }
    val density = LocalDensity.current.density
    val mapView = rememberMapView(
        NaverMapOptions().liteModeEnabled(true).zoomControlEnabled(false).scaleBarEnabled(false)
            .compassEnabled(false).locationButtonEnabled(false)
    )
    val overlays = remember { mutableListOf<Overlay>() }
    Box(box) {
        AndroidView(factory = { mapView }, modifier = Modifier.fillMaxSize(), update = {
            mapView.getMapAsync { map ->
                overlays.forEach { it.map = null }
                overlays.clear()
                overlays += draw(map, density)
                map.moveCamera(CameraUpdate.fitBounds(LatLngBounds.from(fit), (32 * density).toInt()))
            }
        })
    }
}

/** A start/end pin, caption above it (below, the route line usually runs through the text). */
fun endpointMarker(map: NaverMap, at: LatLng, caption: String, tint: Int, density: Float) = Marker().apply {
    position = at
    icon = MarkerIcons.BLACK
    iconTintColor = tint
    captionText = caption
    setCaptionAligns(Align.Top)
    width = (25 * density).toInt()
    height = (35 * density).toInt()
    this.map = map
}

val START_GREEN = AndroidColor.rgb(0x2E, 0x9E, 0x5B)
val END_DARK = AndroidColor.rgb(0x33, 0x33, 0x33)
