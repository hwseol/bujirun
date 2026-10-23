package com.mhmh2.bujirun.ui

import com.mhmh2.bujirun.net.srv

import com.mhmh2.bujirun.tr

import android.content.Context
import androidx.compose.foundation.background
import kotlinx.coroutines.delay
import com.naver.maps.map.CameraUpdate
import com.mhmh2.bujirun.tracking.formatKm
import com.mhmh2.bujirun.tracking.distanceMeters
import com.mhmh2.bujirun.net.ApiException
import com.mhmh2.bujirun.course.RouteApi
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.IconButton
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.Close
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.asPaddingValues
import androidx.compose.foundation.layout.systemBars
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Place
import androidx.compose.material3.AssistChip
import androidx.compose.material3.Button
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import com.mhmh2.bujirun.course.GeoPoint
import com.naver.maps.geometry.LatLng
import com.naver.maps.map.CameraPosition
import com.naver.maps.map.NaverMapOptions

/**
 * Where suggested courses start: the user's position unless they picked a spot on the map. One
 * choice shared by every suggestion screen (공원 코스, 순환 코스, AI 추천) for the session.
 */
object CourseStart {
    var picked by mutableStateOf<GeoPoint?>(null)

    /** The picked spot, else the current position (null without permission or a fix). */
    suspend fun resolve(context: Context): GeoPoint? = picked ?: currentLocation(context)

    /** Where the user is now, if that's more than 300 m from [from]; null when it isn't (or a spot was picked). */
    suspend fun movedFrom(from: GeoPoint, context: Context): GeoPoint? {
        if (picked != null) return null
        val now = currentLocation(context) ?: return null
        return now.takeIf { distanceMeters(from.lat, from.lon, it.lat, it.lon) > 300 }
    }
}

/** "📍 출발: 내 위치" - tap to pick another start on the map. */
@Composable
fun StartChip() {
    var picking by remember { mutableStateOf(false) }
    AssistChip(
        onClick = { picking = true },
        label = { Text(if (CourseStart.picked == null) tr("출발: 내 위치", "Start: my location") else tr("출발: 지도에서 고른 곳", "Start: picked on the map")) },
        leadingIcon = { Icon(Icons.Filled.Place, null, Modifier.size(18.dp), tint = MaterialTheme.colorScheme.primary) },
    )
    if (picking) StartPickerDialog(onDismiss = { picking = false })
}

/** A map with a pin fixed at its centre: move the map under the pin, then tr("여기서 출발", "Start here"). */
@Composable
private fun StartPickerDialog(onDismiss: () -> Unit) {
    val context = LocalContext.current
    // Where the map opens: the current start. Known before the map is made, so it is created
    // there instead of flashing the SDK's default (Seoul City Hall) first.
    var opening by remember { mutableStateOf(CourseStart.picked) }
    LaunchedEffect(Unit) {
        if (opening == null) opening = currentLocation(context) ?: GeoPoint(37.5115, 126.9960)
    }
    // The dialog's own window doesn't get the system bar insets; take them from the screen behind
    // it, so the buttons stay above the gesture bar and the title below the status bar.
    val bars = WindowInsets.systemBars.asPaddingValues()
    Dialog(onDismissRequest = onDismiss, properties = DialogProperties(usePlatformDefaultWidth = false, decorFitsSystemWindows = false)) {
        Surface(Modifier.fillMaxSize()) {
            Column(Modifier.fillMaxSize().padding(bars)) {
                val at = opening
                if (at == null) {
                    Box(Modifier.weight(1f).fillMaxWidth())
                    return@Column
                }
                val mapView = rememberMapView(
                    NaverMapOptions().camera(CameraPosition(LatLng(at.lat, at.lon), 16.0))
                        .zoomControlEnabled(true).scaleBarEnabled(true).compassEnabled(false)
                )
                // Search by name or address; a result moves the map there (the pin then marks it,
                // and the map can still be nudged before tr("여기서 출발", "Start here")).
                var query by remember { mutableStateOf("") }
                var results by remember { mutableStateOf<List<RouteApi.FoundPlace>>(emptyList()) }
                var searching by remember { mutableStateOf(false) }
                var nothingFound by remember { mutableStateOf(false) }
                // Set when a result fills in its name: that's not a new search.
                var justPicked by remember { mutableStateOf(false) }
                val keyboard = LocalSoftwareKeyboardController.current
                LaunchedEffect(query) {
                    nothingFound = false
                    if (justPicked) { justPicked = false; return@LaunchedEffect }
                    if (query.trim().length < 2) { results = emptyList(); return@LaunchedEffect }
                    delay(500)
                    searching = true
                    results = try { RouteApi.lookup(query.trim(), at) } catch (e: ApiException) { emptyList() }
                    nothingFound = results.isEmpty()
                    searching = false
                }
                OutlinedTextField(
                    value = query,
                    onValueChange = { query = it },
                    placeholder = { Text(tr("장소·주소 검색 (예: 금정역, 산본로 323)", "Search a place or address")) },
                    leadingIcon = { Icon(Icons.Filled.Search, null) },
                    trailingIcon = {
                        if (searching) CircularProgressIndicator(Modifier.size(18.dp), strokeWidth = 2.dp)
                        else if (query.isNotEmpty()) IconButton(onClick = { query = "" }) { Icon(Icons.Filled.Close, tr("지우기", "Clear")) }
                    },
                    singleLine = true,
                    shape = RoundedCornerShape(16.dp),
                    modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp),
                )
                Box(Modifier.weight(1f).fillMaxWidth()) {
                    AndroidView(factory = { mapView }, modifier = Modifier.fillMaxSize())
                    // The pin's tip marks the centre of the map.
                    Column(Modifier.align(Alignment.Center), horizontalAlignment = Alignment.CenterHorizontally) {
                        Icon(Icons.Filled.Place, tr("출발점", "Start point"), Modifier.size(44.dp), tint = Color(0xFFE8541E))
                        Box(Modifier.size(6.dp).clip(CircleShape).background(Color(0xFFE8541E)))
                        Box(Modifier.height(44.dp))
                    }
                    if (results.isNotEmpty()) {
                        Surface(shadowElevation = 6.dp, modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp).heightIn(max = 320.dp)) {
                            LazyColumn {
                                items(results) { r ->
                                    Column(
                                        Modifier.fillMaxWidth().clickable {
                                            mapView.getMapAsync { map ->
                                                map.moveCamera(CameraUpdate.scrollAndZoomTo(LatLng(r.point.lat, r.point.lon), 17.0))
                                            }
                                            results = emptyList()
                                            justPicked = true
                                            query = r.name
                                            keyboard?.hide()
                                        }.padding(horizontal = 16.dp, vertical = 10.dp)
                                    ) {
                                        Text(srv(r.name), style = MaterialTheme.typography.bodyLarge)
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
                    } else if (nothingFound) {
                        Text(
                            tr("검색 결과가 없어요", "No results"),
                            modifier = Modifier.padding(16.dp).background(MaterialTheme.colorScheme.surface).padding(8.dp)
                        )
                    }
                }
                Text(
                    tr("지도를 움직여 핀을 출발점에 맞출 수도 있어요", "Or move the map to place the pin on your start"),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(start = 16.dp, top = 8.dp)
                )
                Row(Modifier.fillMaxWidth().padding(16.dp), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    OutlinedButton(onClick = { CourseStart.picked = null; onDismiss() }, modifier = Modifier.weight(1f).height(52.dp)) {
                        Text(tr("내 위치에서 출발", "Start from my location"))
                    }
                    Button(onClick = {
                        mapView.getMapAsync { map ->
                            val c = map.cameraPosition.target
                            CourseStart.picked = GeoPoint(c.latitude, c.longitude)
                            onDismiss()
                        }
                    }, modifier = Modifier.weight(1f).height(52.dp)) { Text(tr("여기서 출발", "Start here")) }
                }
            }
        }
    }
}
