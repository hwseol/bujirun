package com.mhmh2.bujirun.ui

import com.mhmh2.bujirun.tr

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
import androidx.compose.material3.AlertDialog
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
import com.mhmh2.bujirun.Prefs
import com.mhmh2.bujirun.data.AutoActivityEntity
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
 * Samsung Health via Health Connect when allowed, else the phone's own count) and its workouts,
 * with the walks and runs 자동 기록 found without the app.
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
    // 자동 기록: walks and runs found without the app, and the phone's own count of the day's steps.
    val autos by remember { WorkoutStore.observeAuto() }.collectAsState(initial = emptyList())
    val autoByDay = remember(autos) { autos.groupBy { Instant.ofEpochMilli(it.startedAt).atZone(zone).toLocalDate() } }
    val ownSteps by remember { WorkoutStore.observeDaySteps() }.collectAsState(initial = emptyList())
    val autoRecord by Prefs.autoRecord.collectAsState()
    val setAutoRecord = rememberAutoRecordSwitch()
    var deletingAuto by remember { mutableStateOf<AutoActivityEntity?>(null) }
    var askAutoRecord by remember { mutableStateOf(false) }
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
            Text(tr("기록", "History"), style = MaterialTheme.typography.headlineSmall, modifier = Modifier.padding(top = 24.dp, bottom = 4.dp))
            Row(verticalAlignment = Alignment.CenterVertically) {
                IconButton(onClick = { monthKey = month.minusMonths(1).toString() }) {
                    Icon(Icons.AutoMirrored.Filled.KeyboardArrowLeft, tr("이전 달", "Previous month"))
                }
                Text(tr("${month.year}년 ${month.monthValue}월", "${month.month.getDisplayName(TextStyle.FULL, Locale.US)} ${month.year}"), style = MaterialTheme.typography.titleMedium)
                IconButton(onClick = { monthKey = month.plusMonths(1).toString() }, enabled = month < YearMonth.now()) {
                    Icon(Icons.AutoMirrored.Filled.KeyboardArrowRight, tr("다음 달", "Next month"))
                }
                Spacer(Modifier.weight(1f))
                val inMonth = real(byDay.filterKeys { YearMonth.from(it) == month }.values.flatten())
                val autoInMonth = autoByDay.filterKeys { YearMonth.from(it) == month }.values.flatten()
                Text(
                    tr("${inMonth.size + autoInMonth.size}회 · ${formatKm(inMonth.sumOf { it.distanceM } + autoInMonth.sumOf { it.distanceM })}km · ", "${inMonth.size + autoInMonth.size} runs · ${formatKm(inMonth.sumOf { it.distanceM } + autoInMonth.sumOf { it.distanceM })}km · ") +
                        "${(inMonth.sumOf { it.calories } + autoInMonth.sumOf { it.calories }).roundToInt()}kcal",
                    style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
            val kmByDay = remember(byDay, autoByDay) {
                (byDay.keys + autoByDay.keys).associateWith { d ->
                    real(byDay[d].orEmpty()).sumOf { it.distanceM } + autoByDay[d].orEmpty().sumOf { it.distanceM }
                }
            }
            MonthGrid(month, day, kmByDay) { dayKey = it.toString() }
            Spacer(Modifier.height(12.dp))
            DayCard(
                day, real(byDay[day].orEmpty()), autoByDay[day].orEmpty(),
                daySteps ?: ownSteps.firstOrNull { it.day == dayKey }?.steps, canReadSteps, autoRecord,
                onAllowSteps = { stepsPermission.launch(setOf(HealthExport.readSteps)) },
                onAutoRecord = { askAutoRecord = true },
            )
        }
        val ofDay = byDay[day].orEmpty()
        val autoOfDay = autoByDay[day].orEmpty()
        if (ofDay.isEmpty() && autoOfDay.isEmpty()) {
            item {
                Text(
                    if (list.isEmpty()) tr("첫 기록을 남겨보세요.\n홈에서 시작을 누르면 여기에 쌓여요.", "Record your first workout.\nPress Start on Home and it shows up here.") else tr("이 날은 운동 기록이 없어요.", "No workouts on this day."),
                    textAlign = TextAlign.Center, color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.fillMaxWidth().padding(vertical = 24.dp)
                )
            }
        }
        items(ofDay, key = { it.id }) { w -> WorkoutRow(w, onClick = { onOpen(w.id) }) }
        items(autoOfDay, key = { "auto${it.id}" }) { a -> AutoRow(a, onClick = { deletingAuto = a }) }
        item { Spacer(Modifier.height(24.dp)) }
    }

    // Something that runs in the background from now on: say what it does and ask first.
    if (askAutoRecord) {
        AlertDialog(
            onDismissRequest = { askAutoRecord = false },
            title = { Text(tr("자동 기록을 켤까요?", "Turn on auto recording?")) },
            text = {
                Text(
                    tr("앱을 켜지 않아도 하루 걸음 수를 세고, 15분 넘게 걷거나 5분 넘게 달리면 기록에 남겨요.\n\n", "Counts your daily steps without opening the app, and records walks over 15 minutes and runs over 5.\n\n") +
                        tr("GPS는 쓰지 않아 배터리를 거의 쓰지 않아요. '신체 활동' 권한이 필요하고, 설정에서 언제든 끌 수 있어요.", "No GPS, so it barely uses battery. It needs the Physical activity permission, and you can turn it off any time in Settings.")
                )
            },
            confirmButton = { TextButton(onClick = { askAutoRecord = false; setAutoRecord(true) }) { Text(tr("켜기", "Turn on")) } },
            dismissButton = { TextButton(onClick = { askAutoRecord = false }) { Text(tr("나중에", "Later")) } },
        )
    }

    deletingAuto?.let { a ->
        AlertDialog(
            onDismissRequest = { deletingAuto = null },
            title = { Text(tr("자동 기록", "Auto record")) },
            text = { Text(tr("앱 없이 알아서 남긴 기록이에요. 걸음 수로 거리를 어림했고, 경로는 없어요.\n잘못 남은 기록이면 지울 수 있어요.", "Recorded on its own, without the app. The distance is estimated from steps, and there's no route.\nIf it shouldn't be here, you can delete it.")) },
            confirmButton = { TextButton(onClick = { WorkoutStore.deleteAuto(a.id); deletingAuto = null }) { Text(tr("지우기", "Clear")) } },
            dismissButton = { TextButton(onClick = { deletingAuto = null }) { Text(tr("닫기", "Close")) } },
        )
    }
}

@Composable
private fun MonthGrid(month: YearMonth, selected: LocalDate, kmByDay: Map<LocalDate, Double>, onPick: (LocalDate) -> Unit) {
    val colors = MaterialTheme.colorScheme
    val today = LocalDate.now()
    Row(Modifier.fillMaxWidth()) {
        (if (com.mhmh2.bujirun.Lang.english) listOf("S", "M", "T", "W", "T", "F", "S") else listOf("일", "월", "화", "수", "목", "금", "토")).forEachIndexed { i, d ->
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
                Box(Modifier.weight(1f).height(58.dp).padding(2.dp), contentAlignment = Alignment.Center) {
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
private fun DayCard(
    day: LocalDate, ws: List<WorkoutEntity>, autos: List<AutoActivityEntity>, daySteps: Long?, canReadSteps: Boolean,
    autoRecord: Boolean, onAllowSteps: () -> Unit, onAutoRecord: () -> Unit,
) {
    val n = NumberFormat.getIntegerInstance(Locale.KOREA)
    Surface(shape = RoundedCornerShape(16.dp), color = MaterialTheme.colorScheme.surfaceContainerLow, modifier = Modifier.fillMaxWidth()) {
        Column(Modifier.padding(16.dp)) {
            Text(
                tr("${day.monthValue}월 ${day.dayOfMonth}일 (${day.dayOfWeek.getDisplayName(TextStyle.SHORT, Locale.KOREAN)})",
                    "${day.dayOfWeek.getDisplayName(TextStyle.SHORT, Locale.US)}, ${day.month.getDisplayName(TextStyle.SHORT, Locale.US)} ${day.dayOfMonth}") +
                    listOfNotNull(
                        ws.size.takeIf { it > 0 }?.let { tr("운동 ${it}회", "$it workout" + if (it == 1) "" else "s") },
                        autos.size.takeIf { it > 0 }?.let { tr("자동 ${it}회", "$it auto") },
                    ).joinToString(" · ").let { if (it.isEmpty()) "" else " · $it" },
                style = MaterialTheme.typography.titleMedium
            )
            Spacer(Modifier.height(8.dp))
            Row(Modifier.fillMaxWidth()) {
                DayStat(tr("거리", "Distance"), "${formatKm(ws.sumOf { it.distanceM } + autos.sumOf { it.distanceM })}km", Modifier.weight(1f))
                DayStat(tr("시간", "Time"), formatDuration(ws.sumOf { it.durationMs } + autos.sumOf { it.movingMs }), Modifier.weight(1f))
                DayStat(tr("칼로리", "Calories"), "${(ws.sumOf { it.calories } + autos.sumOf { it.calories }).roundToInt()}kcal", Modifier.weight(1f))
                DayStat(
                    if (daySteps != null) tr("하루 걸음", "Steps today") else tr("걸음", "Steps"),
                    n.format(daySteps ?: (ws.sumOf { it.steps }.toLong() + autos.sumOf { it.steps })),
                    Modifier.weight(1f)
                )
            }
            if (!autoRecord) {
                // Plain clickable text: a TextButton clips a line that wraps.
                Text(
                    tr("자동 기록 켜기 · 앱을 안 켜도 걸음과 걷기·달리기를 기록해요", "Turn on auto recording · steps, walks and runs without opening the app"),
                    style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.clickable(onClick = onAutoRecord).padding(vertical = 10.dp),
                )
            }
            if (!canReadSteps) {
                Text(
                    tr("삼성헬스의 하루 전체 걸음 수도 보기", "Also show the day's steps from Samsung Health"),
                    style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.clickable(onClick = onAllowSteps).padding(vertical = 10.dp),
                )
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
                "${w.calories.roundToInt()}kcal", w.steps.takeIf { it > 0 }?.let { tr("${it}걸음", "$it steps") },
            ).forEach {
                Text(it, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
    }
    HorizontalDivider()
}

/** A walk or run recorded in the background: no route to open, so a tap explains (and can delete). */
@Composable
private fun AutoRow(a: AutoActivityEntity, onClick: () -> Unit) {
    val time = clockTime(a.startedAt)
    Column(Modifier.fillMaxWidth().clickable(onClick = onClick).padding(vertical = 12.dp)) {
        Text("${if (a.isRun) tr("🏃 달리기", "🏃 Run") else tr("🚶 걷기", "🚶 Walk")} · $time", style = MaterialTheme.typography.titleMedium)
        Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            listOf(
                tr("약 ${formatKm(a.distanceM)}km", "about ${formatKm(a.distanceM)}km"), formatDuration(a.movingMs), "${a.calories.roundToInt()}kcal",
                tr("${NumberFormat.getIntegerInstance(Locale.KOREA).format(a.steps)}걸음", "${NumberFormat.getIntegerInstance(Locale.US).format(a.steps)} steps"), tr("자동 기록", "Auto record"),
            ).forEach {
                Text(it, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
    }
    HorizontalDivider()
}
