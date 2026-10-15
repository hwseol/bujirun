package com.mhmh2.bujirun.ui

import com.mhmh2.bujirun.tr
import com.mhmh2.bujirun.data.HealthExport
import androidx.compose.runtime.produceState

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
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
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

private enum class GoalKind(private val ko: String, private val en: String) {
    FREE("자유", "Free"), DISTANCE("거리", "Distance"), TIME("시간", "Time"), PACE("페이스", "Pace");

    val label: String get() = tr(ko, en)
}

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
    GoalKind.TIME to GoalDial(5, 180, 5, listOf(20, 30, 60), { tr("${it}분", "$it min") }, { Goal.Time(it * 60_000L) }),
    GoalKind.PACE to GoalDial(180, 900, 10, listOf(330, 420, 600), { formatPace(it.toDouble()) }, { Goal.Pace(it) }),
)

/** This week so far, for the home card. */
data class TodayStats(val steps: Long = 0, val distanceM: Double = 0.0, val activeMs: Long = 0, val calories: Double = 0.0)

data class WeekStats(val count: Int, val distanceM: Double, val durationMs: Long, val calories: Double)

@Composable
fun HomeScreen(
    initialGoal: Goal,
    permissionDenied: Boolean,
    weekSummary: WeekStats,
    today: TodayStats = TodayStats(),
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
        // Scrolls: with a large system font the goal and the start button still fit, just lower.
        modifier = Modifier.fillMaxSize().safeDrawingPadding().verticalScroll(rememberScrollState()).padding(horizontal = 20.dp, vertical = 12.dp),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Column(Modifier.fillMaxWidth().padding(top = 8.dp)) {
            Text(tr("부지런", "Buji Run"), style = MaterialTheme.typography.headlineMedium, fontWeight = FontWeight.Bold)
            nickname?.let {
                Text(tr("${it}님, 오늘도 가볍게", "Hi $it! Ready for an easy one today?"), style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
        Spacer(Modifier.height(16.dp))
        // Today at a glance: what the phone counted on its own, steps first.
        TodayCard(today)
        Spacer(Modifier.height(12.dp))
        // The week's numbers are in the history; here just a word about it.
        Text(
            cheer(weekSummary), style = MaterialTheme.typography.bodyLarge, color = MaterialTheme.colorScheme.primary,
            modifier = Modifier.fillMaxWidth(),
        )
        Spacer(Modifier.height(28.dp))

        Text(tr("목표", "Goal"), style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.onSurfaceVariant)
        Spacer(Modifier.height(8.dp))
        // One line whatever the font size: slides sideways rather than breaking words apart.
        Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            GoalKind.entries.forEach { k ->
                FilterChip(selected = kind == k, onClick = { kind = k }, label = { Text(k.label, maxLines = 1, softWrap = false) })
            }
        }
        Spacer(Modifier.height(16.dp))

        if (dial != null) {
            val value = values.getValue(kind)
            fun set(v: Int) { values = values + (kind to v.coerceIn(dial.min, dial.max)) }
            Row(verticalAlignment = Alignment.CenterVertically) {
                OutlinedIconButton(onClick = { set(value - dial.step) }) { Icon(Icons.Filled.Remove, tr("줄이기", "Less")) }
                Text(
                    dial.label(value),
                    fontSize = 36.sp,
                    textAlign = TextAlign.Center,
                    modifier = Modifier.widthIn(min = 160.dp)
                )
                OutlinedIconButton(onClick = { set(value + dial.step) }) { Icon(Icons.Filled.Add, tr("늘리기", "More")) }
            }
            Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                dial.presets.forEach { p -> SuggestionChip(onClick = { set(p) }, label = { Text(dial.label(p), maxLines = 1, softWrap = false) }) }
            }
        } else {
            Text(
                tr("목표 없이 자유롭게 기록해요", "No goal - just record"),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(vertical = 24.dp)
            )
        }

        Spacer(Modifier.height(32.dp))
        Button(onClick = { onStart(goal) }, shape = CircleShape, modifier = Modifier.size(128.dp)) {
            Text(tr("시작", "Start"), fontSize = 28.sp)
        }
        Spacer(Modifier.height(16.dp))
        Text(
            if (permissionDenied) tr("기록하려면 위치 권한이 필요해요", "Location permission is needed to record") else tr("걷기와 달리기는 자동으로 구분해요", "Walking and running are detected automatically"),
            style = MaterialTheme.typography.bodySmall,
            color = if (permissionDenied) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.fillMaxWidth(),
            textAlign = TextAlign.Center
        )
        Spacer(Modifier.height(24.dp))
    }
}

/** One line on how the week is going, kind whatever it says. */
private fun cheer(w: WeekStats): String {
    val km = formatKm(w.distanceM)
    return when {
        w.count == 0 -> tr("이번 주 첫 걸음, 오늘 시작해 볼까요?", "No workouts yet this week. Start one today?")
        w.count == 1 -> tr("이번 주 한 번, ${km}km. 좋은 출발이에요!", "1 workout this week, ${km}km. Great start!")
        w.count < 4 -> tr("이번 주 ${w.count}번, ${km}km. 잘하고 있어요!", "${w.count} workouts this week, ${km}km. Nice work!")
        else -> tr("이번 주 벌써 ${w.count}번! 꾸준함이 최고예요.", "${w.count} workouts already this week! Keep it up.")
    }
}

/** Steps (Samsung Health's day count when it may be read, else the phone's own), distance, kcal. */
@Composable
private fun TodayCard(t: TodayStats) {
    val context = LocalContext.current
    // The whole day's steps from Health Connect beat a count that started when 자동 기록 was turned on.
    val health by produceState<Long?>(null) {
        value = runCatching {
            if (HealthExport.canReadSteps(context)) {
                val zone = java.time.ZoneId.systemDefault()
                HealthExport.steps(context, java.time.LocalDate.now(zone).atStartOfDay(zone).toInstant(), java.time.Instant.now())
            } else null
        }.getOrNull()
    }
    val steps = maxOf(health ?: 0L, t.steps)
    val n = java.text.NumberFormat.getIntegerInstance(java.util.Locale.US)
    Surface(shape = RoundedCornerShape(16.dp), color = MaterialTheme.colorScheme.surfaceContainerLow, modifier = Modifier.fillMaxWidth()) {
        Row(Modifier.padding(horizontal = 16.dp, vertical = 12.dp), verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1.3f)) {
                Text(tr("오늘 걸음", "Steps today"), style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                Text(n.format(steps), style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.Bold, maxLines = 1)
            }
            Column(Modifier.weight(1f)) {
                Text(tr("거리", "Distance"), style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                Text("${formatKm(t.distanceM)}km", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold, maxLines = 1)
            }
            Column(Modifier.weight(1f)) {
                Text(tr("칼로리", "Calories"), style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                Text("${t.calories.roundToInt()}kcal", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold, maxLines = 1)
            }
        }
    }
}
