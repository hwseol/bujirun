package com.mhmh2.bujirun.ui

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.compositeOver
import androidx.compose.ui.unit.dp
import com.mhmh2.bujirun.tracking.Activity
import com.mhmh2.bujirun.tracking.TrackPoint
import kotlin.math.cos
import kotlin.math.max

/**
 * The route as a line, without a map underneath (the Naver map arrives in M6, once there's an
 * API key). Running stretches in the accent colour, walking stretches lighter; separate lines
 * per pause segment, so a pause spent walking elsewhere doesn't draw a straight jump.
 */
@Composable
fun RouteView(points: List<TrackPoint>, modifier: Modifier = Modifier) {
    val run = MaterialTheme.colorScheme.primary
    // Opaque, not a translucent primary: the many short round-capped segments of a slow walk
    // overlap, and translucent overlaps rendered the walk as a dotted line.
    val walk = MaterialTheme.colorScheme.primary.copy(alpha = 0.4f).compositeOver(MaterialTheme.colorScheme.surfaceVariant)
    val startColor = Color(0xFF2E9E5B)
    val endColor = MaterialTheme.colorScheme.onSurface
    Box(
        modifier
            .fillMaxWidth()
            .height(220.dp)
            .clip(RoundedCornerShape(16.dp))
            .background(MaterialTheme.colorScheme.surfaceVariant),
        contentAlignment = Alignment.Center
    ) {
        if (points.size < 2) {
            Text("경로를 그릴 위치 기록이 없어요", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            return@Box
        }
        Canvas(Modifier.matchParentSize()) {
            // Equirectangular projection: at running scale (a few km) the distortion is invisible.
            val lat0 = points.map { it.sample.lat }.average()
            val kx = cos(Math.toRadians(lat0))
            val xs = points.map { it.sample.lon * kx }
            val ys = points.map { -it.sample.lat }
            val minX = xs.min(); val maxX = xs.max(); val minY = ys.min(); val maxY = ys.max()
            val pad = 24.dp.toPx()
            val spanX = max(maxX - minX, 1e-9); val spanY = max(maxY - minY, 1e-9)
            val scale = minOf((size.width - 2 * pad) / spanX, (size.height - 2 * pad) / spanY)
            val offX = (size.width - spanX * scale) / 2
            val offY = (size.height - spanY * scale) / 2
            fun at(i: Int) = Offset(((xs[i] - minX) * scale + offX).toFloat(), ((ys[i] - minY) * scale + offY).toFloat())

            val stroke = 5.dp.toPx()
            for (i in 1 until points.size) {
                if (points[i].segment != points[i - 1].segment) continue
                val color = if (points[i].activity == Activity.WALK) walk else run
                drawLine(color, at(i - 1), at(i), strokeWidth = stroke, cap = StrokeCap.Round)
            }
            drawCircle(startColor, radius = 7.dp.toPx(), center = at(0))
            drawCircle(endColor, radius = 7.dp.toPx(), center = at(points.lastIndex))
            drawCircle(Color.White, radius = 3.dp.toPx(), center = at(points.lastIndex))
        }
    }
}
