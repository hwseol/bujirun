package com.mhmh2.bujirun.ui

import com.mhmh2.bujirun.tr

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
import androidx.compose.material3.SmallFloatingActionButton
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.MyLocation
import androidx.compose.foundation.layout.size
import com.naver.maps.map.CameraAnimation
import com.naver.maps.map.NaverMap
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
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
import androidx.compose.material3.TextField
import androidx.compose.material3.TextFieldDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Search
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import androidx.compose.ui.zIndex
import kotlinx.coroutines.launch

/**
 * Draw a course by tapping the map. Each new point is joined to the previous one by a walking
 * route from the server (TMAP), or a straight line when "follow paths" is off (or for a park
 * lawn/track the router doesn't know). Undo removes the last point; "back to start" closes a loop.
 */
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
    // The course starts where the user is unless they choose otherwise: the first tap is then
    // already the second point, routed from here.
    var here by remember { mutableStateOf<GeoPoint?>(null) }
    var startIsHere by remember { mutableStateOf(false) }

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
        if (waypoints.isEmpty()) startIsHere = false
        segments = segments.take((waypoints.size - 1).coerceAtLeast(0))
    }

    val onTap by rememberUpdatedState<(GeoPoint) -> Unit>(::addPoint)
    val mapView = rememberMapView(NaverMapOptions().zoomControlEnabled(true).scaleBarEnabled(true).compassEnabled(false))
    val path = remember { PathOverlay() }
    val markers = remember { mutableListOf<Marker>() }
    val lineColor = MaterialTheme.colorScheme.primary.toArgb()

    // One-time setup: the camera on Banpo until the user's position comes in. A point goes where
    // the map is tapped (once - a tap waits a moment so a double-tap zoom doesn't drop two), where
    // it's long-pressed, or where the + in the middle is (tr("여기 추가", "Add here")).
    var naverMap by remember { mutableStateOf<NaverMap?>(null) }
    var pendingTap by remember { mutableStateOf<Job?>(null) }
    LaunchedEffect(mapView) {
        mapView.getMapAsync { map ->
            naverMap = map
            map.setOnMapClickListener { _, latLng ->
                val second = pendingTap?.isActive == true
                pendingTap?.cancel()
                pendingTap = if (second) null else scope.launch {
                    delay(300)
                    onTap(GeoPoint(latLng.latitude, latLng.longitude))
                }
            }
            map.setOnMapLongClickListener { _, latLng -> onTap(GeoPoint(latLng.latitude, latLng.longitude)) }
            map.moveCamera(CameraUpdate.scrollAndZoomTo(LatLng(37.5115, 126.9960), 15.0))
        }
    }
    // Place search (as in the start picker).
    var query by remember { mutableStateOf("") }
    var results by remember { mutableStateOf<List<RouteApi.FoundPlace>>(emptyList()) }
    var searching by remember { mutableStateOf(false) }
    var nothingFound by remember { mutableStateOf(false) }
    var justPicked by remember { mutableStateOf(false) }
    val keyboard = LocalSoftwareKeyboardController.current
    LaunchedEffect(query) {
        nothingFound = false
        if (justPicked) { justPicked = false; return@LaunchedEffect }
        if (query.trim().length < 2) { results = emptyList(); return@LaunchedEffect }
        delay(500)
        val near = naverMap?.cameraPosition?.target?.let { GeoPoint(it.latitude, it.longitude) } ?: here ?: return@LaunchedEffect
        searching = true
        results = try { RouteApi.lookup(query.trim(), near) } catch (e: ApiException) { emptyList() }
        nothingFound = results.isEmpty()
        searching = false
    }
    fun addAtCentre() {
        val t = naverMap?.cameraPosition?.target ?: return
        addPoint(GeoPoint(t.latitude, t.longitude))
    }
    // Then the user's position: shown, the camera there, and the start (if nothing's drawn yet).
    LaunchedEffect(Unit) {
        val p = currentLocation(context) ?: return@LaunchedEffect
        here = p
        mapView.getMapAsync { map ->
            val at = LatLng(p.lat, p.lon)
            map.locationOverlay.apply { isVisible = true; position = at }
            if (waypoints.isEmpty()) map.moveCamera(CameraUpdate.scrollAndZoomTo(at, 16.0))
        }
        if (waypoints.isEmpty()) { waypoints = listOf(p); startIsHere = true }
    }
    // Redraw the line and point markers whenever they change.
    LaunchedEffect(line, waypoints, startIsHere) {
        mapView.getMapAsync { map ->
            if (line.size >= 2) {
                path.coords = line.map { LatLng(it.lat, it.lon) }
                path.color = lineColor
                path.outlineColor = AndroidColor.WHITE
                path.width = (7 * density).toInt()
                path.outlineWidth = (1.25 * density).toInt()
                path.map = map
            } else {
                path.map = null
            }
            markers.forEach { it.map = null }
            markers.clear()
            markers += directionMarkers(map, line, density, lineColor)
            waypoints.forEachIndexed { i, p ->
                markers += Marker().apply {
                    position = LatLng(p.lat, p.lon)
                    icon = MarkerIcons.BLACK
                    iconTintColor = if (i == 0) START_GREEN else lineColor
                    width = (18 * density).toInt()
                    height = (25 * density).toInt()
                    if (i == 0) captionText = if (startIsHere) tr("출발 · 내 위치", "Start · my location") else tr("출발", "Start")
                    this.map = map
                }
            }
        }
    }

    Box(Modifier.fillMaxSize()) {
        AndroidView(factory = { mapView }, modifier = Modifier.fillMaxSize())
        // Where tr("여기 추가", "Add here") puts the next point: the middle of the map.
        Icon(
            Icons.Filled.Add, contentDescription = null,
            tint = MaterialTheme.colorScheme.primary,
            modifier = Modifier.align(Alignment.Center).size(40.dp)
        )
        here?.let { me ->
            SmallFloatingActionButton(
                onClick = { naverMap?.moveCamera(CameraUpdate.scrollTo(LatLng(me.lat, me.lon)).animate(CameraAnimation.Easing)) },
                // Right, under the search bar and clear of the map's own zoom buttons (middle right).
                modifier = Modifier.align(Alignment.TopEnd).safeDrawingPadding().padding(top = 84.dp, end = 12.dp)
            ) { Icon(Icons.Filled.MyLocation, contentDescription = tr("내 위치로", "To my location")) }
        }

        // Back, and a search for a place to go through: picking one moves the map there and adds
        // it as the next point (or the start).
        // Above the bottom panel: with the keyboard up the results would otherwise be under it.
        Column(Modifier.zIndex(1f).safeDrawingPadding().padding(12.dp).fillMaxWidth()) {
            Surface(shape = RoundedCornerShape(24.dp), tonalElevation = 2.dp, shadowElevation = 2.dp) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    IconButton(onClick = onClose) { Icon(Icons.AutoMirrored.Filled.ArrowBack, tr("뒤로", "Back")) }
                    TextField(
                        value = query,
                        onValueChange = { query = it },
                        placeholder = { Text(tr("장소 검색해서 추가 (예: 금정역)", "Search a place to add")) },
                        trailingIcon = {
                            if (searching) CircularProgressIndicator(Modifier.size(18.dp), strokeWidth = 2.dp)
                            else if (query.isNotEmpty()) IconButton(onClick = { query = "" }) { Icon(Icons.Filled.Close, tr("지우기", "Clear")) }
                            else Icon(Icons.Filled.Search, null)
                        },
                        singleLine = true,
                        colors = TextFieldDefaults.colors(
                            focusedContainerColor = Color.Transparent, unfocusedContainerColor = Color.Transparent,
                            focusedIndicatorColor = Color.Transparent, unfocusedIndicatorColor = Color.Transparent,
                        ),
                        modifier = Modifier.weight(1f),
                    )
                }
            }
            if (results.isNotEmpty() || nothingFound) {
                Surface(shape = RoundedCornerShape(16.dp), shadowElevation = 6.dp, modifier = Modifier.padding(top = 6.dp).fillMaxWidth().heightIn(max = 300.dp)) {
                    if (nothingFound) {
                        Text(tr("검색 결과가 없어요", "No results"), modifier = Modifier.padding(16.dp))
                    } else {
                        LazyColumn {
                            items(results) { r ->
                                Column(
                                    Modifier.fillMaxWidth().clickable {
                                        naverMap?.moveCamera(CameraUpdate.scrollAndZoomTo(LatLng(r.point.lat, r.point.lon), 16.0))
                                        results = emptyList()
                                        justPicked = true
                                        query = r.name
                                        keyboard?.hide()
                                        addPoint(r.point)
                                    }.padding(horizontal = 16.dp, vertical = 10.dp)
                                ) {
                                    Text(r.name, style = MaterialTheme.typography.bodyLarge)
                                    Text(
                                        listOf(r.address, "${formatKm(r.awayM.toDouble())}km").filter { it.isNotBlank() }.joinToString(" · "),
                                        style = MaterialTheme.typography.bodySmall,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant
                                    )
                                }
                                HorizontalDivider()
                            }
                        }
                    }
                }
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
                            0 -> tr("지도를 눌러 출발점을 찍거나, 위에서 장소를 검색하세요", "Tap the map to set the start, or search a place above")
                            1 -> if (startIsHere) tr("내 위치에서 출발해요. 지도를 눌러 다음 지점을 찍으세요", "Starting from your location. Tap the map for the next point") else tr("지도를 눌러 다음 지점을 찍으세요", "Tap the map for the next point")
                            else -> tr("지점 ${waypoints.size}개", "${waypoints.size} points")
                        },
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.weight(1f)
                    )
                }
                // Changing the start: only before the course goes anywhere (undo gets back there).
                if (waypoints.size == 1 && startIsHere) {
                    TextButton(onClick = { waypoints = emptyList(); startIsHere = false }) { Text(tr("다른 곳에서 출발하기", "Start somewhere else")) }
                } else if (waypoints.isEmpty() && here != null) {
                    TextButton(onClick = { here?.let { waypoints = listOf(it); startIsHere = true } }) { Text(tr("내 위치에서 출발하기", "Start from my location")) }
                }
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(tr("길 따라 잇기", "Follow paths"), style = MaterialTheme.typography.bodyMedium, modifier = Modifier.weight(1f))
                    Text(
                        if (followPaths) tr("걷는 길로 연결", "Along walkways") else tr("직선으로 연결", "Straight lines"),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    Spacer(Modifier.width(8.dp))
                    Switch(checked = followPaths, onCheckedChange = { followPaths = it })
                }
                Spacer(Modifier.height(8.dp))
                Button(onClick = ::addAtCentre, enabled = !busy && naverMap != null, modifier = Modifier.fillMaxWidth().height(48.dp)) {
                    Icon(Icons.Filled.Add, null)
                    Spacer(Modifier.width(6.dp))
                    Text(if (waypoints.isEmpty()) tr("여기를 출발점으로", "Start here") else tr("여기 추가", "Add here"))
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
                    ) { Text(tr("출발점으로", "Back to start")) }
                    OutlinedButton(onClick = { naming = true }, enabled = line.size >= 2 && !busy, modifier = Modifier.weight(1f)) { Text(tr("저장", "Save")) }
                }
            }
        }
    }

    if (naming) {
        NameDialog(tr("코스 이름", "Course name"), tr("내 코스 ${formatKm(distance)}km", "My course ${formatKm(distance)}km"), onDismiss = { naming = false }) { name ->
            naming = false
            scope.launch { onSaved(CourseStore.save(name, CourseSource.DRAWN, line)) }
        }
    }
}
