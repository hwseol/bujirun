package com.mhmh2.bujirun.ui

import android.content.Context
import androidx.compose.foundation.background
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
}

/** "📍 출발: 내 위치" - tap to pick another start on the map. */
@Composable
fun StartChip() {
    var picking by remember { mutableStateOf(false) }
    AssistChip(
        onClick = { picking = true },
        label = { Text(if (CourseStart.picked == null) "출발: 내 위치" else "출발: 지도에서 고른 곳") },
        leadingIcon = { Icon(Icons.Filled.Place, null, Modifier.size(18.dp), tint = MaterialTheme.colorScheme.primary) },
    )
    if (picking) StartPickerDialog(onDismiss = { picking = false })
}

/** A map with a pin fixed at its centre: move the map under the pin, then "여기서 출발". */
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
                Text(
                    "지도를 움직여 핀을 출발점에 맞추세요",
                    style = MaterialTheme.typography.titleMedium,
                    modifier = Modifier.padding(16.dp)
                )
                val at = opening
                if (at == null) {
                    Box(Modifier.weight(1f).fillMaxWidth())
                    return@Column
                }
                val mapView = rememberMapView(
                    NaverMapOptions().camera(CameraPosition(LatLng(at.lat, at.lon), 16.0))
                        .zoomControlEnabled(true).scaleBarEnabled(true).compassEnabled(false)
                )
                Box(Modifier.weight(1f).fillMaxWidth()) {
                    AndroidView(factory = { mapView }, modifier = Modifier.fillMaxSize())
                    // The pin's tip marks the centre of the map.
                    Column(Modifier.align(Alignment.Center), horizontalAlignment = Alignment.CenterHorizontally) {
                        Icon(Icons.Filled.Place, "출발점", Modifier.size(44.dp), tint = Color(0xFFE8541E))
                        Box(Modifier.size(6.dp).clip(CircleShape).background(Color(0xFFE8541E)))
                        Box(Modifier.height(44.dp))
                    }
                }
                Row(Modifier.fillMaxWidth().padding(16.dp), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    OutlinedButton(onClick = { CourseStart.picked = null; onDismiss() }, modifier = Modifier.weight(1f).height(52.dp)) {
                        Text("내 위치에서 출발")
                    }
                    Button(onClick = {
                        mapView.getMapAsync { map ->
                            val c = map.cameraPosition.target
                            CourseStart.picked = GeoPoint(c.latitude, c.longitude)
                            onDismiss()
                        }
                    }, modifier = Modifier.weight(1f).height(52.dp)) { Text("여기서 출발") }
                }
            }
        }
    }
}
