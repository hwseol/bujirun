package com.mhmh2.bujirun.ui

import android.Manifest
import android.annotation.SuppressLint
import android.content.pm.PackageManager
import android.graphics.Color as AndroidColor
import android.widget.Toast
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.Undo
import androidx.compose.material3.Button
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.content.ContextCompat
import com.google.android.gms.location.LocationServices
import com.mhmh2.bujirun.course.CourseGeometry
import com.mhmh2.bujirun.course.CourseSource
import com.mhmh2.bujirun.course.CourseStore
import com.mhmh2.bujirun.course.GeoPoint
import com.mhmh2.bujirun.course.RouteApi
import com.mhmh2.bujirun.net.ApiException
import com.mhmh2.bujirun.tracking.formatKm
import com.naver.maps.geometry.LatLng
import com.naver.maps.map.CameraUpdate
import com.naver.maps.map.NaverMapOptions
import com.naver.maps.map.overlay.Marker
import com.naver.maps.map.overlay.PathOverlay
import com.naver.maps.map.util.MarkerIcons
import kotlinx.coroutines.launch

/**
 * Draw a course by tapping the map. Each new point is joined to the previous one by a walking
 * route from the server (TMAP), or a straight line when "follow paths" is off (or for a park
 * lawn/track the router doesn't know). Undo removes the last point; "back to start" closes a loop.
 */
@SuppressLint("MissingPermission") // lastLocation is only read after checking the permission
@Composable
fun CourseDrawScreen(onClose: () -> Unit, onSaved: (Long) -> Unit) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val density = LocalDensity.current.density
    var waypoints by remember { mutableStateOf(listOf<GeoPoint>()) }
    var segments by remember { mutableStateOf(listOf<List<GeoPoint>>()) } // segments[i]: point i -> i+1
    var followPaths by remember { mutableStateOf(true) }
    var busy by remember { mutableStateOf(false) }
    var naming by remember { mutableStateOf(false) }

    val line = remember(waypoints, segments) {
        if (segments.isEmpty()) waypoints else segments.flatMapIndexed { i, s -> if (i == 0) s else s.drop(1) }
    }
    val distance = CourseGeometry.distanceM(line)

    fun addPoint(p: GeoPoint) {
        if (busy) return
        val last = waypoints.lastOrNull()
        if (last == null) {
            waypoints = listOf(p)
            return
        }
        if (!followPaths) {
            waypoints = waypoints + p
            segments = segments + listOf(listOf(last, p))
            return
        }
        busy = true
        scope.launch {
            try {
                val route = RouteApi.walk(last, p)
                waypoints = waypoints + p
                segments = segments + listOf(route)
            } catch (e: ApiException) {
                Toast.makeText(context, e.message, Toast.LENGTH_LONG).show()
            } finally {
                busy = false
            }
        }
    }

    fun undo() {
        if (busy || waypoints.isEmpty()) return
        waypoints = waypoints.dropLast(1)
        segments = segments.take((waypoints.size - 1).coerceAtLeast(0))
    }

    val onTap by rememberUpdatedState<(GeoPoint) -> Unit>(::addPoint)
    val mapView = rememberMapView(NaverMapOptions().zoomControlEnabled(true).scaleBarEnabled(true).compassEnabled(false))
    val path = remember { PathOverlay() }
    val markers = remember { mutableListOf<Marker>() }
    val lineColor = MaterialTheme.colorScheme.primary.toArgb()

    // One-time setup: tap handler, and the camera on the user's last known position (or Banpo).
    LaunchedEffect(mapView) {
        mapView.getMapAsync { map ->
            map.setOnMapClickListener { _, latLng -> onTap(GeoPoint(latLng.latitude, latLng.longitude)) }
            map.moveCamera(CameraUpdate.scrollAndZoomTo(LatLng(37.5115, 126.9960), 15.0))
            if (ContextCompat.checkSelfPermission(context, Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED) {
                LocationServices.getFusedLocationProviderClient(context).lastLocation.addOnSuccessListener { loc ->
                    if (loc == null) return@addOnSuccessListener
                    val here = LatLng(loc.latitude, loc.longitude)
                    map.locationOverlay.apply { isVisible = true; position = here }
                    if (waypoints.isEmpty()) map.moveCamera(CameraUpdate.scrollTo(here))
                }
            }
        }
    }
    // Redraw the line and point markers whenever they change.
    LaunchedEffect(line, waypoints) {
        mapView.getMapAsync { map ->
            if (line.size >= 2) {
                path.coords = line.map { LatLng(it.lat, it.lon) }
                path.color = lineColor
                path.outlineColor = AndroidColor.WHITE
                path.width = (5 * density).toInt()
                path.outlineWidth = (1.25 * density).toInt()
                path.map = map
            } else {
                path.map = null
            }
            markers.forEach { it.map = null }
            markers.clear()
            waypoints.forEachIndexed { i, p ->
                markers += Marker().apply {
                    position = LatLng(p.lat, p.lon)
                    icon = MarkerIcons.BLACK
                    iconTintColor = if (i == 0) START_GREEN else lineColor
                    width = (18 * density).toInt()
                    height = (25 * density).toInt()
                    if (i == 0) captionText = "출발"
                    this.map = map
                }
            }
        }
    }

    Box(Modifier.fillMaxSize()) {
        AndroidView(factory = { mapView }, modifier = Modifier.fillMaxSize())

        Surface(
            modifier = Modifier.safeDrawingPadding().padding(12.dp),
            shape = RoundedCornerShape(24.dp),
            tonalElevation = 2.dp,
            shadowElevation = 2.dp,
        ) {
            Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(end = 16.dp)) {
                IconButton(onClick = onClose) { Icon(Icons.AutoMirrored.Filled.ArrowBack, "뒤로") }
                Text("DIY 코스 만들기", style = MaterialTheme.typography.titleMedium)
            }
        }

        Surface(
            modifier = Modifier.align(Alignment.BottomCenter).fillMaxWidth().safeDrawingPadding().padding(12.dp),
            shape = RoundedCornerShape(20.dp),
            tonalElevation = 2.dp,
            shadowElevation = 4.dp,
        ) {
            Column(Modifier.padding(16.dp)) {
                if (busy) LinearProgressIndicator(Modifier.fillMaxWidth().padding(bottom = 8.dp))
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text("${formatKm(distance)}km", fontSize = 28.sp)
                    Spacer(Modifier.width(12.dp))
                    Text(
                        when (waypoints.size) {
                            0 -> "지도를 눌러 출발점을 찍으세요"
                            1 -> "다음 지점을 찍으면 길을 따라 이어져요"
                            else -> "지점 ${waypoints.size}개"
                        },
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.weight(1f)
                    )
                }
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text("길 따라 잇기", style = MaterialTheme.typography.bodyMedium, modifier = Modifier.weight(1f))
                    Text(
                        if (followPaths) "걷는 길로 연결" else "직선으로 연결",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    Spacer(Modifier.width(8.dp))
                    Switch(checked = followPaths, onCheckedChange = { followPaths = it })
                }
                Spacer(Modifier.height(8.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedButton(onClick = ::undo, enabled = waypoints.isNotEmpty() && !busy) {
                        Icon(Icons.AutoMirrored.Filled.Undo, null)
                    }
                    OutlinedButton(
                        onClick = { waypoints.firstOrNull()?.let(::addPoint) },
                        enabled = waypoints.size >= 2 && !busy && !CourseGeometry.isLoop(line),
                        modifier = Modifier.weight(1f)
                    ) { Text("출발점으로") }
                    Button(onClick = { naming = true }, enabled = line.size >= 2 && !busy, modifier = Modifier.weight(1f)) { Text("저장") }
                }
            }
        }
    }

    if (naming) {
        NameDialog("코스 이름", "내 코스 ${formatKm(distance)}km", onDismiss = { naming = false }) { name ->
            naming = false
            scope.launch { onSaved(CourseStore.save(name, CourseSource.DRAWN, line)) }
        }
    }
}
