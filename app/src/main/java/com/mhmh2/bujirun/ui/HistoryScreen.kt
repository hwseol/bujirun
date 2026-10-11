package com.mhmh2.bujirun.ui

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowLeft
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.health.connect.client.PermissionController
import com.mhmh2.bujirun.data.HealthExport
import com.mhmh2.bujirun.data.WorkoutEntity
import com.mhmh2.bujirun.data.WorkoutStore
import com.mhmh2.bujirun.tracking.formatDuration
import com.mhmh2.bujirun.tracking.formatKm
import com.mhmh2.bujirun.tracking.formatPace
import java.text.NumberFormat
import java.time.Instant
import java.time.LocalDate
import java.time.YearMonth
import java.time.ZoneId
import java.time.format.TextStyle
import java.util.Locale
import kotlin.math.roundToInt

/**
 * 기록 by date: a month calendar with each day's distance on it, the month's totals, and the
 * chosen day - distance, time, calories, steps (the workouts' own, and the whole day's from
 * Samsung Health via Health Connect when allowed) and its workouts.
 */
@Composable
fun HistoryScreen(onOpen: (Long) -> Unit) {
    val workouts by remember { WorkoutStore.observeDone() }.collectAsState(initial = null)
    val list = workouts ?: return
    val zone = ZoneId.systemDefault()
    val context = LocalContext.current
    var monthKey by rememberSaveable { mutableStateOf(YearMonth.now().toString()) }
    var dayKey by rememberSaveable { mutableStateOf(LocalDate.now().toString()) }
    val month = YearMonth.parse(monthKey)
    val day = LocalDate.parse(dayKey)

    val byDay = remember(list) { list.groupBy { Instant.ofEpochMilli(it.startedAt).atZone(zone).toLocalDate() } }
    // Test runs are listed (labelled) but left out of the totals.
    fun real(ws: List<WorkoutEntity>) = ws.filter { !it.simulated }

    // The whole day's steps from Health Connect, if the user lets us read them.
    var canReadSteps by remember { mutableStateOf(false) }
    var daySteps by remember { mutableStateOf<Long?>(null) }
    var askedSteps by remember { mutableStateOf(0) }
    val stepsPermission = rememberLauncherForActivityResult(PermissionController.createRequestPermissionResultContract()) {
        askedSteps++
    }
    LaunchedEffect(dayKey, askedSteps) {
        canReadSteps = HealthExport.canReadSteps(context)
        daySteps = if (canReadSteps) {
            HealthExport.steps(context, day.atStartOfDay(zone).toInstant(), day.plusDays(1).atStartOfDay(zone).toInstant())
        } else null
    }

    LazyColumn(Modifier.fillMaxSize().padding(horizontal = 20.dp)) {
        item {
            Text("기록", style = MaterialTheme.typography.headlineSmall, modifier = Modifier.padding(top = 24.dp, bottom = 4.dp))
            Row(verticalAlignment = Alignment.CenterVertically) {
                IconButton(onClick = { monthKey = month.minusMonths(1).toString() }) {
                    Icon(Icons.AutoMirrored.Filled.KeyboardArrowLeft, "이전 달")
                }
                Text("${month.year}년 ${month.monthValue}월", style = MaterialTheme.typography.titleMedium)
                IconButton(onClick = { monthKey = month.plusMonths(1).toString() }, enabled = month < YearMonth.now()) {
                    Icon(Icons.AutoMirrored.Filled.KeyboardArrowRight, "다음 달")
                }
                Spacer(Modifier.weight(1f))
                val inMonth = real(byDay.filterKeys { YearMonth.from(it) == month }.values.flatten())
                Text(
                    "${inMonth.size}회 · ${formatKm(inMonth.sumOf { it.distanceM })}km · ${inMonth.sumOf { it.calories }.roundToInt()}kcal",
                    style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
            MonthGrid(month, day, byDay.mapValues { (_, ws) -> real(ws).sumOf { it.distanceM } }) { dayKey = it.toString() }
            Spacer(Modifier.height(12.dp))
            DayCard(day, real(byDay[day].orEmpty()), daySteps, canReadSteps) {
                stepsPermission.launch(setOf(HealthExport.readSteps))
            }
        }
        val ofDay = byDay[day].orEmpty()
        if (ofDay.isEmpty()) {
            item {
                Text(
                    if (list.isEmpty()) "첫 기록을 남겨보세요.\n홈에서 시작을 누르면 여기에 쌓여요." else "이 날은 운동 기록이 없어요.",
                    textAlign = TextAlign.Center, color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.fillMaxWidth().padding(vertical = 24.dp)
                )
            }
        }
        items(ofDay, key = { it.id }) { w -> WorkoutRow(w, onClick = { onOpen(w.id) }) }
        item { Spacer(Modifier.height(24.dp)) }
    }
}

@Composable
private fun MonthGrid(month: YearMonth, selected: LocalDate, kmByDay: Map<LocalDate, Double>, onPick: (LocalDate) -> Unit) {
    val colors = MaterialTheme.colorScheme
    val today = LocalDate.now()
    Row(Modifier.fillMaxWidth()) {
        listOf("일", "월", "화", "수", "목", "금", "토").forEachIndexed { i, d ->
            Text(
                d, modifier = Modifier.weight(1f), textAlign = TextAlign.Center,
                style = MaterialTheme.typography.labelSmall,
                color = if (i == 0) colors.error else colors.onSurfaceVariant
            )
        }
    }
    // Weeks start on Sunday, as Korean calendars do.
    val lead = month.atDay(1).dayOfWeek.value % 7
    val cells = List(lead) { null } + (1..month.lengthOfMonth()).map { month.atDay(it) }
    cells.chunked(7).forEach { week ->
        Row(Modifier.fillMaxWidth()) {
            (week + List(7 - week.size) { null }).forEach { date ->
                Box(Modifier.weight(1f).aspectRatio(0.9f).padding(2.dp), contentAlignment = Alignment.Center) {
                    if (date != null) {
                        val km = kmByDay[date]
                        val isSel = date == selected
                        Column(
                            Modifier.fillMaxSize().clip(RoundedCornerShape(10.dp))
                                .then(if (isSel) Modifier.border(BorderStroke(2.dp, colors.primary), RoundedCornerShape(10.dp)) else Modifier)
                                .clickable(enabled = !date.isAfter(today)) { onPick(date) },
                            horizontalAlignment = Alignment.CenterHorizontally,
                            verticalArrangement = Arrangement.Center,
                        ) {
                            Text(
                                "${date.dayOfMonth}",
                                fontWeight = if (date == today) FontWeight.Bold else FontWeight.Normal,
                                color = if (date.isAfter(today)) colors.outline else colors.onSurface,
                                style = MaterialTheme.typography.bodyMedium,
                            )
                            // The same space with or without a badge, so the dates stay in line.
                            Box(Modifier.padding(top = 2.dp).height(20.dp), contentAlignment = Alignment.Center) {
                                if (km != null && km > 0) {
                                    Box(
                                        Modifier.clip(RoundedCornerShape(50)).background(colors.primary)
                                            .padding(horizontal = 5.dp, vertical = 2.dp)
                                    ) {
                                        Text(
                                            formatKm(km).removeSuffix("0").removeSuffix("."), color = colors.onPrimary,
                                            fontSize = 10.sp, lineHeight = 12.sp, maxLines = 1,
                                        )
                                    }
                                }
                            }
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun DayCard(day: LocalDate, ws: List<WorkoutEntity>, daySteps: Long?, canReadSteps: Boolean, onAllowSteps: () -> Unit) {
    val n = NumberFormat.getIntegerInstance(Locale.KOREA)
    Surface(shape = RoundedCornerShape(16.dp), color = MaterialTheme.colorScheme.surfaceContainerLow, modifier = Modifier.fillMaxWidth()) {
        Column(Modifier.padding(16.dp)) {
            Text(
                "${day.monthValue}월 ${day.dayOfMonth}일 (${day.dayOfWeek.getDisplayName(TextStyle.SHORT, Locale.KOREAN)})" +
                    if (ws.isNotEmpty()) " · 운동 ${ws.size}회" else "",
                style = MaterialTheme.typography.titleMedium
            )
            Spacer(Modifier.height(8.dp))
            Row(Modifier.fillMaxWidth()) {
                DayStat("거리", "${formatKm(ws.sumOf { it.distanceM })}km", Modifier.weight(1f))
                DayStat("시간", formatDuration(ws.sumOf { it.durationMs }), Modifier.weight(1f))
                DayStat("칼로리", "${ws.sumOf { it.calories }.roundToInt()}kcal", Modifier.weight(1f))
                DayStat(
                    if (daySteps != null) "하루 걸음" else "운동 걸음",
                    n.format(daySteps ?: ws.sumOf { it.steps }.toLong()),
                    Modifier.weight(1f)
                )
            }
            if (!canReadSteps) {
                TextButton(onClick = onAllowSteps, contentPadding = androidx.compose.foundation.layout.PaddingValues(0.dp)) {
                    Text("삼성헬스의 하루 전체 걸음 수도 보기", style = MaterialTheme.typography.bodySmall)
                }
            }
        }
    }
}

@Composable
private fun DayStat(label: String, value: String, modifier: Modifier) {
    Column(modifier) {
        Text(label, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        Text(value, style = MaterialTheme.typography.titleSmall)
    }
}

@Composable
private fun WorkoutRow(w: WorkoutEntity, onClick: () -> Unit) {
    val pace = if (w.distanceM >= 20) w.durationMs / 1000.0 / (w.distanceM / 1000.0) else null
    Column(Modifier.fillMaxWidth().clickable(onClick = onClick).padding(vertical = 12.dp)) {
        Text(workoutTitle(w), style = MaterialTheme.typography.titleMedium)
        Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            listOfNotNull(
                "${formatKm(w.distanceM)}km", formatDuration(w.durationMs), "${formatPace(pace)}/km",
                "${w.calories.roundToInt()}kcal", w.steps.takeIf { it > 0 }?.let { "${it}걸음" },
            ).forEach {
                Text(it, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
    }
    HorizontalDivider()
}
