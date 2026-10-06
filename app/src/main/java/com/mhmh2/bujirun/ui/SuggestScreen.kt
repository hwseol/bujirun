package com.mhmh2.bujirun.ui

import android.Manifest
import android.annotation.SuppressLint
import android.content.Context
import android.content.pm.PackageManager
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
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
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import com.google.android.gms.location.LocationServices
import com.google.android.gms.location.Priority
import com.mhmh2.bujirun.course.CourseGeometry
import com.mhmh2.bujirun.course.CourseSource
import com.mhmh2.bujirun.course.CourseStore
import com.mhmh2.bujirun.course.GeoPoint
import com.mhmh2.bujirun.course.RouteApi
import com.mhmh2.bujirun.net.ApiException
import com.mhmh2.bujirun.tracking.formatDuration
import com.mhmh2.bujirun.tracking.formatKm
import kotlinx.coroutines.launch
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlin.coroutines.resume

private val DISTANCES = listOf(3000, 5000, 7000, 10000)

/**
 * Loop courses around where the user is now: pick a distance, get a few real walking loops that
 * start and end right here, run one or keep it.
 */
@Composable
fun SuggestScreen(onClose: () -> Unit, onOpen: (Long) -> Unit, onRun: (Long) -> Unit) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var distance by remember { mutableStateOf(5000) }
    var loading by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }
    var loops by remember { mutableStateOf<List<List<GeoPoint>>>(emptyList()) }

    fun fetch() {
        loading = true
        error = null
        scope.launch {
            try {
                val here = currentLocation(context) ?: throw ApiException("현재 위치를 알 수 없어요. 위치가 켜져 있는지 확인해 주세요.")
                loops = RouteApi.loops(here, distance)
            } catch (e: ApiException) {
                error = e.message
                loops = emptyList()
            } finally {
                loading = false
            }
        }
    }

    val permission = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        if (granted) fetch() else error = "주변 코스를 찾으려면 위치 권한이 필요해요."
    }

    fun request() {
        if (ContextCompat.checkSelfPermission(context, Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED) fetch()
        else permission.launch(Manifest.permission.ACCESS_FINE_LOCATION)
    }

    fun save(points: List<GeoPoint>, then: (Long) -> Unit) {
        scope.launch {
            val km = formatKm(CourseGeometry.distanceM(points))
            then(CourseStore.save("추천 순환 ${km}km", CourseSource.RECOMMENDED, points))
        }
    }

    Column(Modifier.fillMaxSize().safeDrawingPadding()) {
        Row(Modifier.fillMaxWidth().padding(horizontal = 8.dp, vertical = 4.dp), verticalAlignment = Alignment.CenterVertically) {
            IconButton(onClick = onClose) { Icon(Icons.AutoMirrored.Filled.ArrowBack, "뒤로") }
            Text("내 주변 추천 코스", style = MaterialTheme.typography.titleLarge)
        }
        Column(Modifier.padding(horizontal = 24.dp)) {
            Text(
                "지금 있는 곳에서 출발해 다시 돌아오는 코스를 찾아드려요.",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            Spacer(Modifier.height(12.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                DISTANCES.forEach { d ->
                    FilterChip(selected = distance == d, onClick = { distance = d }, label = { Text("${d / 1000}km") })
                }
            }
            Spacer(Modifier.height(8.dp))
            Button(onClick = ::request, enabled = !loading, modifier = Modifier.fillMaxWidth().height(52.dp)) {
                if (loading) CircularProgressIndicator(Modifier.height(22.dp), strokeWidth = 2.dp) else Text("추천 받기")
            }
            error?.let {
                Text(it, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodyMedium, modifier = Modifier.padding(top = 8.dp))
            }
        }
        Spacer(Modifier.height(8.dp))
        if (loops.isEmpty() && !loading && error == null) {
            Box(Modifier.fillMaxSize().padding(24.dp), contentAlignment = Alignment.Center) {
                Text(
                    "거리를 고르고 '추천 받기'를 눌러 보세요.\n실제 걷는 길을 따라 출발점으로 돌아오는 코스예요.",
                    textAlign = TextAlign.Center,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }
        LazyColumn(Modifier.weight(1f)) {
            itemsIndexed(loops) { i, points ->
                val meters = CourseGeometry.distanceM(points)
                Surface(
                    shape = RoundedCornerShape(20.dp),
                    tonalElevation = 1.dp,
                    modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp)
                ) {
                    Column(Modifier.padding(12.dp)) {
                        CourseLineView(points)
                        Spacer(Modifier.height(10.dp))
                        Text("추천 ${i + 1} · ${formatKm(meters)}km 순환", style = MaterialTheme.typography.titleMedium)
                        Text(
                            "달리기 약 ${formatDuration((meters / 1000 * 390_000).toLong())} · 걷기 약 ${formatDuration((meters / 1000 * 720_000).toLong())}",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                        Spacer(Modifier.height(10.dp))
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            Button(onClick = { save(points, onRun) }, modifier = Modifier.weight(1f)) { Text("바로 달리기") }
                            OutlinedButton(onClick = { save(points, onOpen) }, modifier = Modifier.weight(1f)) { Text("내 코스에 저장") }
                        }
                    }
                }
            }
            item { Spacer(Modifier.height(16.dp)) }
        }
    }
}

/** A fresh fix (falls back to the last known one), or null without permission or a signal. */
@SuppressLint("MissingPermission")
private suspend fun currentLocation(context: Context): GeoPoint? {
    if (ContextCompat.checkSelfPermission(context, Manifest.permission.ACCESS_FINE_LOCATION) != PackageManager.PERMISSION_GRANTED) return null
    val client = LocationServices.getFusedLocationProviderClient(context)
    val fresh = suspendCancellableCoroutine<android.location.Location?> { cont ->
        client.getCurrentLocation(Priority.PRIORITY_HIGH_ACCURACY, null)
            .addOnSuccessListener { cont.resume(it) }
            .addOnFailureListener { cont.resume(null) }
    }
    val loc = fresh ?: suspendCancellableCoroutine<android.location.Location?> { cont ->
        client.lastLocation.addOnSuccessListener { cont.resume(it) }.addOnFailureListener { cont.resume(null) }
    }
    return loc?.let { GeoPoint(it.latitude, it.longitude) }
}
