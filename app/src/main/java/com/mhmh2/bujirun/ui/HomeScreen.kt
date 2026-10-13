package com.mhmh2.bujirun.ui

import android.Manifest
import android.annotation.SuppressLint
import android.content.pm.PackageManager
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Surface
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.content.ContextCompat
import com.google.android.gms.location.LocationServices
import com.mhmh2.bujirun.tracking.formatDuration
import com.mhmh2.bujirun.tracking.formatKm
import com.naver.maps.geometry.LatLng
import com.naver.maps.map.CameraUpdate
import com.naver.maps.map.NaverMapOptions
import kotlin.math.roundToInt
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Remove
import androidx.compose.material3.Button
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedIconButton
import androidx.compose.material3.SuggestionChip
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.mhmh2.bujirun.coach.Goal
import com.mhmh2.bujirun.tracking.formatPace
import java.util.Locale

private enum class GoalKind(val label: String) { FREE("자유"), DISTANCE("거리"), TIME("시간"), PACE("페이스") }

/** One adjustable goal value: its range, step, presets, and how to show it. */
private class GoalDial(
    val min: Int, val max: Int, val step: Int, val presets: List<Int>,
    val label: (Int) -> String, val toGoal: (Int) -> Goal,
)

// Distance in 100 m units so 0.5 km steps stay integers; pace in seconds per km. Pace goes up to
// 15'00" because walkers are first-class users, not just runners.
private val dials = mapOf(
    GoalKind.DISTANCE to GoalDial(5, 422, 5, listOf(30, 50, 100),
        { String.format(Locale.US, "%skm", (it / 10.0).toString().removeSuffix(".0")) }, { Goal.Distance(it * 100.0) }),
    GoalKind.TIME to GoalDial(5, 180, 5, listOf(20, 30, 60), { "${it}분" }, { Goal.Time(it * 60_000L) }),
    GoalKind.PACE to GoalDial(180, 900, 10, listOf(330, 420, 600), { formatPace(it.toDouble()) }, { Goal.Pace(it) }),
)

/** This week so far, for the home card. */
data class WeekStats(val count: Int, val distanceM: Double, val durationMs: Long, val calories: Double)

@Composable
fun HomeScreen(
    initialGoal: Goal,
    permissionDenied: Boolean,
    weekSummary: WeekStats,
    nickname: String?,
    onStart: (Goal) -> Unit,
) {
    var kind by remember {
        mutableStateOf(
            when (initialGoal) {
                Goal.Free -> GoalKind.FREE
                is Goal.Distance -> GoalKind.DISTANCE
                is Goal.Time -> GoalKind.TIME
                is Goal.Pace -> GoalKind.PACE
            }
        )
    }
    // Each kind keeps its own value, so flipping between chips doesn't lose what was set.
    var values by remember {
        mutableStateOf(
            mapOf(
                GoalKind.DISTANCE to ((initialGoal as? Goal.Distance)?.meters?.div(100)?.toInt() ?: 50),
                GoalKind.TIME to ((initialGoal as? Goal.Time)?.ms?.div(60_000)?.toInt() ?: 30),
                GoalKind.PACE to ((initialGoal as? Goal.Pace)?.secPerKm ?: 390),
            )
        )
    }
    val dial = dials[kind]
    val goal = dial?.toGoal(values.getValue(kind)) ?: Goal.Free

    Column(
        modifier = Modifier.fillMaxSize().safeDrawingPadding().padding(horizontal = 20.dp, vertical = 12.dp),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Row(Modifier.fillMaxWidth().padding(top = 8.dp), verticalAlignment = Alignment.Bottom) {
            Text("부지런", style = MaterialTheme.typography.headlineMedium, fontWeight = FontWeight.Bold)
            Spacer(Modifier.width(8.dp))
            nickname?.let {
                Text("${it}님, 오늘도 가볍게", style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(bottom = 4.dp))
            }
        }
        Spacer(Modifier.height(12.dp))
        // Where you are now: the start of whatever you run next.
        HereMap(Modifier.fillMaxWidth().height(190.dp).clip(RoundedCornerShape(20.dp)))
        Spacer(Modifier.height(12.dp))
        WeekCard(weekSummary)
        Spacer(Modifier.height(20.dp))

        Text("목표", style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.onSurfaceVariant)
        Spacer(Modifier.height(8.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            GoalKind.entries.forEach { k ->
                FilterChip(selected = kind == k, onClick = { kind = k }, label = { Text(k.label) })
            }
        }
        Spacer(Modifier.height(16.dp))

        if (dial != null) {
            val value = values.getValue(kind)
            fun set(v: Int) { values = values + (kind to v.coerceIn(dial.min, dial.max)) }
            Row(verticalAlignment = Alignment.CenterVertically) {
                OutlinedIconButton(onClick = { set(value - dial.step) }) { Icon(Icons.Filled.Remove, "줄이기") }
                Text(
                    dial.label(value),
                    fontSize = 36.sp,
                    textAlign = TextAlign.Center,
                    modifier = Modifier.widthIn(min = 160.dp)
                )
                OutlinedIconButton(onClick = { set(value + dial.step) }) { Icon(Icons.Filled.Add, "늘리기") }
            }
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                dial.presets.forEach { p -> SuggestionChip(onClick = { set(p) }, label = { Text(dial.label(p)) }) }
            }
        } else {
            Text(
                "목표 없이 자유롭게 기록해요",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(vertical = 24.dp)
            )
        }

        Spacer(Modifier.weight(1f))
        Button(onClick = { onStart(goal) }, shape = CircleShape, modifier = Modifier.size(128.dp)) {
            Text("시작", fontSize = 28.sp)
        }
        Spacer(Modifier.height(16.dp))
        Text(
            if (permissionDenied) "기록하려면 위치 권한이 필요해요" else "걷기와 달리기는 자동으로 구분해요",
            style = MaterialTheme.typography.bodySmall,
            color = if (permissionDenied) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.fillMaxWidth(),
            textAlign = TextAlign.Center
        )
        Spacer(Modifier.weight(0.5f))
    }
}

/** "이번 주" in three numbers: runs, distance, time. */
@Composable
private fun WeekCard(w: WeekStats) {
    Surface(shape = RoundedCornerShape(16.dp), color = MaterialTheme.colorScheme.surfaceContainerLow, modifier = Modifier.fillMaxWidth()) {
        Column(Modifier.padding(horizontal = 16.dp, vertical = 12.dp)) {
            Text("이번 주", style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.onSurfaceVariant)
            Spacer(Modifier.height(4.dp))
            if (w.count == 0) {
                Text("아직 기록이 없어요. 짧게라도 시작해 볼까요?", style = MaterialTheme.typography.bodyMedium)
            } else {
                Row(Modifier.fillMaxWidth()) {
                    WeekStat("${w.count}", "회", Modifier.weight(1f))
                    WeekStat(formatKm(w.distanceM), "km", Modifier.weight(1f))
                    WeekStat(formatDuration(w.durationMs), "시간", Modifier.weight(1f))
                    WeekStat("${w.calories.roundToInt()}", "kcal", Modifier.weight(1f))
                }
            }
        }
    }
}

@Composable
private fun WeekStat(value: String, unit: String, modifier: Modifier) {
    Row(modifier, verticalAlignment = Alignment.Bottom) {
        Text(value, style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold)
        Spacer(Modifier.width(2.dp))
        Text(unit, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(bottom = 3.dp))
    }
}

/** A map at the phone's last known position (with location permission), else a note. */
@Composable
private fun HereMap(modifier: Modifier) {
    val context = LocalContext.current
    val granted = ContextCompat.checkSelfPermission(context, Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED
    if (!granted) {
        Box(modifier.background(MaterialTheme.colorScheme.surfaceVariant), contentAlignment = Alignment.Center) {
            Text("시작을 누르면 위치 권한을 물어요.\n허용하면 여기에 지금 있는 곳이 보여요.", textAlign = TextAlign.Center,
                style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        return
    }
    val mapView = rememberMapView(
        NaverMapOptions().zoomControlEnabled(false).scaleBarEnabled(false).compassEnabled(false).locationButtonEnabled(false)
    )
    LaunchedEffect(mapView) {
        @SuppressLint("MissingPermission")
        val task = LocationServices.getFusedLocationProviderClient(context).lastLocation
        task.addOnSuccessListener { loc ->
            loc ?: return@addOnSuccessListener
            mapView.getMapAsync { map ->
                val here = LatLng(loc.latitude, loc.longitude)
                map.locationOverlay.apply { isVisible = true; position = here }
                map.moveCamera(CameraUpdate.scrollAndZoomTo(here, 15.5))
            }
        }
    }
    Box(modifier) { AndroidView(factory = { mapView }, modifier = Modifier.fillMaxSize()) }
}
