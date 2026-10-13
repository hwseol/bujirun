package com.mhmh2.bujirun.ui

import com.mhmh2.bujirun.tr

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color as AndroidColor
import android.graphics.Paint
import android.graphics.Path
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
import com.mhmh2.bujirun.course.CourseRoute
import com.mhmh2.bujirun.course.isOutAndBack
import com.mhmh2.bujirun.course.GeoPoint
import com.naver.maps.geometry.LatLng
import com.naver.maps.geometry.LatLngBounds
import com.naver.maps.map.CameraUpdate
import com.naver.maps.map.MapView
import com.naver.maps.map.NaverMap
import com.naver.maps.map.NaverMapOptions
import com.naver.maps.map.overlay.Align
import com.naver.maps.map.overlay.Marker
import com.naver.maps.map.overlay.Overlay
import com.naver.maps.map.overlay.OverlayImage
import com.naver.maps.map.overlay.PathOverlay
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
    emptyText: String = tr("표시할 경로가 없어요", "No route to show"),
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

/**
 * Round arrow badges along [points] pointing the way the course goes, spread evenly (a
 * PathOverlay's own pattern is shrunk to the line's width, too small to read on a phone).
 * Flat, so they turn with the map.
 */
fun directionMarkers(map: NaverMap, points: List<GeoPoint>, density: Float, color: Int): List<Marker> {
    if (points.size < 2) return emptyList()
    val route = CourseRoute(points)
    if (route.lengthM < 100) return emptyList()
    // A few are enough to tell which way round it goes (more just clutter the map): three on
    // most courses, at a sixth, a half and five sixths of the way. Out and back on the same
    // path, the way there and the way back would sit on top of each other: there each arrow
    // goes beside the line, on its right, so "there" and "back" show side by side.
    val outAndBack = route.isOutAndBack()
    val at: List<Double> = if (outAndBack) {
        val pairs = if (route.lengthM > 6000) listOf(1.0 / 8, 3.0 / 8) else listOf(1.0 / 4)
        pairs.flatMap { f -> listOf(route.lengthM * f, route.lengthM * (1 - f)) }
    } else {
        val step = (route.lengthM / 3).coerceIn(300.0, 3000.0)
        generateSequence(step / 2) { it + step }.takeWhile { it < route.lengthM - 30 }.toList()
    }
    val icon = arrowBadge(density, color)
    val out = mutableListOf<Marker>()
    for (d in at) {
        val p = route.pointAt(d)
        out += Marker().apply {
            position = LatLng(p.lat, p.lon)
            this.icon = icon
            width = (20 * density).toInt()
            height = (20 * density).toInt()
            // The anchor turns with the badge: x < 0 puts it to the right of the way it points.
            anchor = android.graphics.PointF(if (outAndBack) -0.15f else 0.5f, 0.5f)
            angle = ((route.headingAt(d) % 360 + 360) % 360).toFloat() // clockwise from north, as NAVER's is
            isFlat = true
            isHideCollidedSymbols = false
            this.map = map
        }
    }
    return out
}

private var badge: Triple<Float, Int, OverlayImage>? = null

private fun arrowBadge(density: Float, color: Int): OverlayImage {
    badge?.takeIf { it.first == density && it.second == color }?.let { return it.third }
    val s = (20 * density).toInt()
    val bmp = Bitmap.createBitmap(s, s, Bitmap.Config.ARGB_8888)
    val c = Canvas(bmp)
    val r = s / 2f
    c.drawCircle(r, r, r, Paint(Paint.ANTI_ALIAS_FLAG).apply { this.color = AndroidColor.WHITE })
    c.drawCircle(r, r, r - 1.5f * density, Paint(Paint.ANTI_ALIAS_FLAG).apply { this.color = color })
    // An "↑": a head and a shaft, so which end is the front can't be misread at this size.
    val white = Paint(Paint.ANTI_ALIAS_FLAG).apply { this.color = AndroidColor.WHITE }
    val head = Path().apply {
        moveTo(r, s * 0.18f)
        lineTo(s * 0.74f, s * 0.48f)
        lineTo(s * 0.26f, s * 0.48f)
        close()
    }
    c.drawPath(head, white)
    c.drawRect(r - s * 0.08f, s * 0.44f, r + s * 0.08f, s * 0.80f, white)
    return OverlayImage.fromBitmap(bmp).also { badge = Triple(density, color, it) }
}

val START_GREEN = AndroidColor.rgb(0x2E, 0x9E, 0x5B)
val END_DARK = AndroidColor.rgb(0x33, 0x33, 0x33)
