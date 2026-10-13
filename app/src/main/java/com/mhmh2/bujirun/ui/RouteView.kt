package com.mhmh2.bujirun.ui

import android.graphics.Color as AndroidColor
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.compositeOver
import androidx.compose.ui.graphics.toArgb
import com.mhmh2.bujirun.course.GeoPoint
import com.mhmh2.bujirun.data.PhotoPlace
import com.naver.maps.map.overlay.Marker
import com.naver.maps.map.util.MarkerIcons
import com.mhmh2.bujirun.tracking.Activity
import com.mhmh2.bujirun.tracking.TrackPoint
import com.naver.maps.geometry.LatLng
import com.naver.maps.map.NaverMap
import com.naver.maps.map.overlay.MultipartPathOverlay
import com.naver.maps.map.overlay.Overlay
import com.naver.maps.map.overlay.PathOverlay

/**
 * A recorded workout on the map: running stretches in the accent colour, walking lighter; one
 * line part per pause segment, so a pause spent somewhere else doesn't draw a straight jump.
 */
@Composable
fun RouteView(points: List<TrackPoint>, modifier: Modifier = Modifier, photos: List<Pair<PhotoPlace, android.graphics.Bitmap?>> = emptyList()) {
    val runColor = MaterialTheme.colorScheme.primary.toArgb()
    val walkColor = MaterialTheme.colorScheme.primary.copy(alpha = 0.45f).compositeOver(Color.White).toArgb()
    val fit = remember(points) { points.map { LatLng(it.sample.lat, it.sample.lon) } }
    LiteMap(fit, modifier, emptyText = "경로를 그릴 위치 기록이 없어요") { map, density ->
        drawWorkout(map, points, runColor, walkColor, density) + photos.map { (place, bmp) ->
            // Each photo on the spot it was taken: the picture itself, round, as the pin.
            Marker().apply {
                position = LatLng(place.lat, place.lon)
                icon = photoPin(bmp, density)
                width = (36 * density).toInt()
                height = (36 * density).toInt()
                anchor = android.graphics.PointF(0.5f, 0.5f)
                isHideCollidedSymbols = false
                this.map = map
            }
        }
    }
}

/** A course line on the map, with start (and end, unless it's a loop) pins. */
@Composable
fun CourseLineView(points: List<GeoPoint>, modifier: Modifier = Modifier) {
    val color = MaterialTheme.colorScheme.primary.toArgb()
    val fit = remember(points) { points.map { LatLng(it.lat, it.lon) } }
    LiteMap(fit, modifier) { map, density ->
        val result = mutableListOf<Overlay>()
        result += PathOverlay(fit).apply {
            this.color = color
            outlineColor = AndroidColor.WHITE
            width = (7 * density).toInt()
            outlineWidth = (1.25 * density).toInt()
            this.map = map
        }
        result += directionMarkers(map, points, density, color)
        result += endpointMarker(map, fit.first(), "출발", START_GREEN, density)
        val loop = com.mhmh2.bujirun.course.CourseGeometry.isLoop(points)
        if (!loop) result += endpointMarker(map, fit.last(), "도착", END_DARK, density)
        result
    }
}

private fun drawWorkout(map: NaverMap, points: List<TrackPoint>, runColor: Int, walkColor: Int, density: Float): List<Overlay> {
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
    val widthPx = (5 * density).toInt()
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
    result += endpointMarker(map, LatLng(first.lat, first.lon), "출발", START_GREEN, density)
    result += endpointMarker(map, LatLng(last.lat, last.lon), "도착", END_DARK, density)
    return result
}

/** A photo as a round map pin with a white ring (a plain camera-blue dot while it loads). */
private fun photoPin(bmp: android.graphics.Bitmap?, density: Float): com.naver.maps.map.overlay.OverlayImage {
    val size = (36 * density).toInt()
    val out = android.graphics.Bitmap.createBitmap(size, size, android.graphics.Bitmap.Config.ARGB_8888)
    val c = android.graphics.Canvas(out)
    val r = size / 2f
    val paint = android.graphics.Paint(android.graphics.Paint.ANTI_ALIAS_FLAG)
    paint.color = AndroidColor.WHITE
    c.drawCircle(r, r, r, paint)
    val inner = r - 2.5f * density
    if (bmp != null) {
        val side = minOf(bmp.width, bmp.height)
        val src = android.graphics.Rect((bmp.width - side) / 2, (bmp.height - side) / 2, (bmp.width + side) / 2, (bmp.height + side) / 2)
        val dst = android.graphics.RectF(r - inner, r - inner, r + inner, r + inner)
        val layer = c.saveLayer(dst, null)
        c.drawCircle(r, r, inner, paint)
        paint.xfermode = android.graphics.PorterDuffXfermode(android.graphics.PorterDuff.Mode.SRC_IN)
        c.drawBitmap(bmp, src, dst, paint)
        paint.xfermode = null
        c.restoreToCount(layer)
    } else {
        paint.color = PHOTO_BLUE
        c.drawCircle(r, r, inner, paint)
    }
    return com.naver.maps.map.overlay.OverlayImage.fromBitmap(out)
}
