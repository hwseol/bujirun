package com.mhmh2.bujirun.ui

import com.mhmh2.bujirun.tr
import androidx.compose.ui.graphics.Color
import androidx.compose.material3.FilledTonalIconButton
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

    // Today's steps, the whole day's from Samsung Health when it may be read.
    val context = LocalContext.current
    val healthSteps by produceState<Long?>(null) {
        value = runCatching {
            if (HealthExport.canReadSteps(context)) {
                val zone = java.time.ZoneId.systemDefault()
                HealthExport.steps(context, java.time.LocalDate.now(zone).atStartOfDay(zone).toInstant(), java.time.Instant.now())
            } else null
        }.getOrNull()
    }
    val steps = maxOf(healthSteps ?: 0L, today.steps)

    Column(
        // Scrolls: with a large system font the goal and the start button still fit, just lower.
        modifier = Modifier.fillMaxSize().safeDrawingPadding().verticalScroll(rememberScrollState()).padding(horizontal = 20.dp, vertical = 12.dp),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Header(nickname)
        Spacer(Modifier.height(16.dp))
        TodayHero(steps, today, cheer(weekSummary))
        Spacer(Modifier.height(16.dp))

        Card {
            Row(verticalAlignment = Alignment.CenterVertically) {
                SectionTitle(tr("오늘의 목표", "Today's goal"), Modifier.weight(1f))
                Text(
                    if (dial == null) tr("자유롭게", "Free") else dial.label(values.getValue(kind)),
                    style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.primary,
                )
            }
            Spacer(Modifier.height(12.dp))
            // One line whatever the font size: slides sideways rather than breaking words apart.
            Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                GoalKind.entries.forEach { k ->
                    FilterChip(selected = kind == k, onClick = { kind = k }, label = { Text(k.label, maxLines = 1, softWrap = false) })
                }
            }
            if (dial != null) {
                val value = values.getValue(kind)
                fun set(v: Int) { values = values + (kind to v.coerceIn(dial.min, dial.max)) }
                Spacer(Modifier.height(8.dp))
                Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                    FilledTonalIconButton(onClick = { set(value - dial.step) }) { Icon(Icons.Filled.Remove, tr("줄이기", "Less")) }
                    Text(
                        dial.label(value), style = Brand.number.copy(fontSize = 40.sp), textAlign = TextAlign.Center,
                        modifier = Modifier.weight(1f), maxLines = 1,
                    )
                    FilledTonalIconButton(onClick = { set(value + dial.step) }) { Icon(Icons.Filled.Add, tr("늘리기", "More")) }
                }
                Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    dial.presets.forEach { p -> SuggestionChip(onClick = { set(p) }, label = { Text(dial.label(p), maxLines = 1, softWrap = false) }) }
                }
            } else {
                Spacer(Modifier.height(10.dp))
                Text(
                    tr("목표 없이 자유롭게 기록해요", "No goal - just record"),
                    style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }

        Spacer(Modifier.height(20.dp))
        StartButton(
            tr("시작", "Start"),
            sub = if (dial == null) tr("자유 기록", "Free run") else tr("목표 ", "Goal ") + dial.label(values.getValue(kind)),
            onClick = { onStart(goal) },
        )
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

/** "10월 16일 목요일" over "좋은 아침이에요, 설프로님". */
@Composable
private fun Header(nickname: String?) {
    val now = java.time.LocalDateTime.now()
    val date = if (com.mhmh2.bujirun.Lang.english) now.format(java.time.format.DateTimeFormatter.ofPattern("EEEE, MMM d", Locale.US))
    else "${now.monthValue}월 ${now.dayOfMonth}일 ${now.dayOfWeek.getDisplayName(java.time.format.TextStyle.FULL, Locale.KOREAN)}"
    val hello = when (now.hour) {
        in 4..10 -> tr("좋은 아침이에요", "Good morning")
        in 11..16 -> tr("좋은 오후예요", "Good afternoon")
        in 17..21 -> tr("좋은 저녁이에요", "Good evening")
        else -> tr("늦은 시간이네요", "Late night")
    }
    Column(Modifier.fillMaxWidth().padding(top = 8.dp)) {
        Text(date, style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.primary)
        Text(
            nickname?.let { tr("$hello, ${it}님", "$hello, $it") } ?: hello,
            style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.ExtraBold,
        )
    }
}

/** Today in the brand gradient: the step ring to 8,000, then distance, time and calories. */
@Composable
private fun TodayHero(steps: Long, t: TodayStats, cheer: String) {
    val n = java.text.NumberFormat.getIntegerInstance(Locale.US)
    HeroCard {
        Row(verticalAlignment = Alignment.CenterVertically) {
            ProgressRing(steps / STEP_GOAL.toFloat(), Modifier.size(124.dp)) {
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    Text(n.format(steps), style = Brand.number.copy(fontSize = 26.sp), color = Color.White, maxLines = 1)
                    Text(tr("/ ${n.format(STEP_GOAL)} 걸음", "/ ${n.format(STEP_GOAL)} steps"), style = MaterialTheme.typography.labelSmall, color = Color.White.copy(alpha = 0.8f))
                }
            }
            Spacer(Modifier.width(18.dp))
            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                StatTile(tr("거리", "Distance"), formatKm(t.distanceM), "km", onHero = true)
                StatTile(tr("활동 시간", "Active"), formatDuration(t.activeMs), onHero = true)
                StatTile(tr("칼로리", "Calories"), "${t.calories.roundToInt()}", "kcal", onHero = true)
            }
        }
        Spacer(Modifier.height(14.dp))
        Text(
            cheer, color = Color.White, style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.SemiBold,
            modifier = Modifier.clip(RoundedCornerShape(50)).background(Color.White.copy(alpha = 0.18f)).padding(horizontal = 12.dp, vertical = 6.dp),
        )
    }
}

private const val STEP_GOAL = 8000L

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
