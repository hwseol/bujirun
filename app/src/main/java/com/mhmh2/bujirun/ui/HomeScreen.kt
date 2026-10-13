package com.mhmh2.bujirun.ui

import com.mhmh2.bujirun.tr

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
        // Scrolls: with a large system font the goal and the start button still fit, just lower.
        modifier = Modifier.fillMaxSize().safeDrawingPadding().verticalScroll(rememberScrollState()).padding(horizontal = 20.dp, vertical = 12.dp),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Column(Modifier.fillMaxWidth().padding(top = 8.dp)) {
            Text(tr("부지런", "Bujirun"), style = MaterialTheme.typography.headlineMedium, fontWeight = FontWeight.Bold)
            nickname?.let {
                Text(tr("${it}님, 오늘도 가볍게", "Hi $it, an easy one today?"), style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
        Spacer(Modifier.height(16.dp))
        // The numbers are in the history; here just a word about the week.
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
                tr("목표 없이 자유롭게 기록해요", "Just record, no goal"),
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
            if (permissionDenied) tr("기록하려면 위치 권한이 필요해요", "Location permission is needed to record") else tr("걷기와 달리기는 자동으로 구분해요", "Walking and running are told apart on their own"),
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
        w.count == 0 -> tr("이번 주 첫 걸음, 오늘 시작해 볼까요?", "Your first one this week - today?")
        w.count == 1 -> tr("이번 주 한 번, ${km}km. 좋은 출발이에요!", "Once this week, ${km}km. A good start!")
        w.count < 4 -> tr("이번 주 ${w.count}번, ${km}km. 잘하고 있어요!", "${w.count} times this week, ${km}km. Going well!")
        else -> tr("이번 주 벌써 ${w.count}번! 꾸준함이 최고예요.", "${w.count} times already this week! Keep it up.")
    }
}

