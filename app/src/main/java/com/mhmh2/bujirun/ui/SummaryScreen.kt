package com.mhmh2.bujirun.ui

import android.content.Intent
import android.net.Uri
import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.compose.foundation.background
import androidx.compose.material3.OutlinedButton
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.platform.LocalContext
import androidx.health.connect.client.PermissionController
import com.mhmh2.bujirun.course.CourseSource
import com.mhmh2.bujirun.course.CourseStore
import com.mhmh2.bujirun.course.GeoPoint
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.runtime.LaunchedEffect
import com.mhmh2.bujirun.Prefs
import com.mhmh2.bujirun.data.AutoExport
import com.mhmh2.bujirun.data.PhotoPlaces
import com.mhmh2.bujirun.data.WorkoutPhoto
import com.mhmh2.bujirun.data.WorkoutPhotos
import androidx.activity.result.PickVisualMediaRequest
import androidx.compose.runtime.produceState
import com.mhmh2.bujirun.data.CalendarExport
import com.mhmh2.bujirun.data.WorkoutReport
import com.mhmh2.bujirun.data.GpxShare
import com.mhmh2.bujirun.data.HealthExport
import kotlinx.coroutines.launch
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.mhmh2.bujirun.data.WorkoutEntity
import com.mhmh2.bujirun.data.WorkoutStore
import com.mhmh2.bujirun.data.toTrackPoint
import com.mhmh2.bujirun.tracking.Split
import com.mhmh2.bujirun.tracking.Splits
import com.mhmh2.bujirun.tracking.formatDuration
import com.mhmh2.bujirun.tracking.formatKm
import com.mhmh2.bujirun.tracking.formatPace
import kotlin.math.roundToInt

/**
 * A saved workout: route, totals, km splits. Shown right after finishing ([justFinished]: a
 * primary "done" button) and from the history (a back arrow).
 */
@Composable
fun SummaryScreen(workoutId: Long, justFinished: Boolean, onClose: () -> Unit) {
    val workout by remember(workoutId) { WorkoutStore.observe(workoutId) }.collectAsState(initial = null)
    val pointRows by remember(workoutId) { WorkoutStore.observePoints(workoutId) }.collectAsState(initial = emptyList())
    val points = remember(pointRows) { pointRows.map { it.toTrackPoint() } }
    var confirmDelete by remember { mutableStateOf(false) }
    var savingCourse by remember { mutableStateOf(false) }

    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    fun toast(text: String) = Toast.makeText(context, text, Toast.LENGTH_LONG).show()
    fun writeToHealth() {
        val w = workout ?: return
        scope.launch {
            runCatching { HealthExport.write(context, w, pointRows) }
                .onSuccess { Prefs.markExported("health", w.id); toast("Health Connect에 저장했어요") }
                .onFailure { toast("저장하지 못했어요: ${it.message}") }
        }
    }
    val healthPermissionLauncher = rememberLauncherForActivityResult(
        PermissionController.createRequestPermissionResultContract()
    ) { granted ->
        if (granted.containsAll(HealthExport.required)) writeToHealth()
        else toast("권한을 허용해야 삼성헬스에 저장할 수 있어요")
    }
    fun saveToHealth() {
        when (HealthExport.availability(context)) {
            HealthExport.Availability.UNAVAILABLE -> toast("이 폰에서는 Health Connect를 쓸 수 없어요")
            HealthExport.Availability.NEEDS_UPDATE -> {
                toast("Health Connect를 먼저 설치하거나 업데이트해 주세요")
                context.startActivity(
                    Intent(Intent.ACTION_VIEW, Uri.parse("market://details?id=com.google.android.apps.healthdata"))
                        .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                )
            }
            HealthExport.Availability.AVAILABLE -> scope.launch {
                if (HealthExport.missingPermissions(context).isEmpty()) writeToHealth()
                else healthPermissionLauncher.launch(HealthExport.permissions)
            }
        }
    }

    // The course's name and the km splits, for the report (calendar notes, sharing).
    val courseName by remember(workout?.courseId) {
        workout?.courseId?.let { CourseStore.observe(it) } ?: kotlinx.coroutines.flow.flowOf(null)
    }.collectAsState(initial = null)
    val reportSplits = remember(points, workout?.distanceM, workout?.durationMs) {
        workout?.let { Splits.compute(points, it.distanceM, it.durationMs) }.orEmpty()
    }
    // The route in Google Maps: in the calendar event and the shared text, and a button below.
    val mapsUrl = remember(pointRows) { WorkoutReport.mapsUrl(pointRows.map { it.lat to it.lon }) }
    fun report(w: WorkoutEntity) = WorkoutReport.text(w, courseName?.name, reportSplits, mapsUrl)

    // Photos taken during the workout: on the phone by time, plus ones picked (cloud) for it.
    var photoPermissionAsked by remember { mutableStateOf(0) }
    val photoPermission = rememberLauncherForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { photoPermissionAsked++ }
    val canReadPhotos = remember(photoPermissionAsked) { WorkoutPhotos.canRead(context) }
    val added by remember(workoutId) { WorkoutPhotos.observeAdded(workoutId) }.collectAsState(initial = emptyList())
    val onPhone by produceState(emptyList<WorkoutPhoto>(), workout?.startedAt, workout?.endedAt, canReadPhotos) {
        val w = workout ?: return@produceState
        value = WorkoutPhotos.onPhone(context, w.startedAt - PhotoPlaces.MARGIN_MS, w.endedAt + PhotoPlaces.MARGIN_MS)
    }
    val photos = remember(onPhone, added) {
        val times = onPhone.mapNotNull { it.takenAt }.toSet()
        (onPhone + added.filter { it.takenAt == null || it.takenAt !in times }
            .map { WorkoutPhoto(Uri.parse(it.uri), it.takenAt, it.id) })
            .sortedBy { it.takenAt ?: Long.MAX_VALUE }
    }
    val photoPlaces = remember(photos, points) { photos.map { p -> p.takenAt?.let { PhotoPlaces.at(points, it) } } }
    // Small copies for the map, where each photo sits on the spot it was taken.
    val photoThumbs by produceState(emptyMap<Uri, android.graphics.Bitmap>(), photos) {
        value = photos.mapNotNull { p -> WorkoutPhotos.load(context, p.uri, 160)?.let { p.uri to it } }.toMap()
    }
    var viewingPhoto by remember { mutableStateOf<Int?>(null) }
    val pickPhotos = rememberLauncherForActivityResult(ActivityResultContracts.PickMultipleVisualMedia(20)) { uris ->
        if (uris.isNotEmpty()) scope.launch { WorkoutPhotos.add(context, workoutId, uris) }
    }

    fun writeToCalendar(quiet: Boolean = false) {
        val w = workout ?: return
        val ok = runCatching { CalendarExport.write(context, w, WorkoutReport.title(w), report(w)) }.getOrDefault(false)
        if (ok) {
            Prefs.markExported("calendar", w.id)
            toast("캘린더에 기록했어요")
        } else if (!quiet) toast("기록할 캘린더가 없어요. 폰에 구글 계정 캘린더가 있는지 확인해 주세요")
    }
    val calendarPermission = rememberLauncherForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { granted ->
        if (granted.values.all { it }) writeToCalendar() else toast("캘린더 권한을 허용해야 기록할 수 있어요")
    }
    fun saveToCalendar() {
        if (CalendarExport.hasPermission(context)) writeToCalendar() else calendarPermission.launch(CalendarExport.permissions)
    }
    // Turned on from the card on this screen: from now on, and this workout right away.
    val autoCalendarPermission = rememberLauncherForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { granted ->
        if (granted.values.all { it }) { Prefs.setAutoCalendar(true); writeToCalendar() }
        else toast("캘린더 권한을 허용해야 기록할 수 있어요")
    }
    val autoHealthPermission = rememberLauncherForActivityResult(PermissionController.createRequestPermissionResultContract()) { granted ->
        if (granted.containsAll(HealthExport.required)) { Prefs.setAutoHealth(true); writeToHealth() }
        else toast("권한을 허용해야 삼성헬스에 저장할 수 있어요")
    }
    fun setAutoCalendar(on: Boolean) {
        val w = workout ?: return
        when {
            !on -> Prefs.setAutoCalendar(false)
            CalendarExport.hasPermission(context) -> {
                Prefs.setAutoCalendar(true)
                if (!Prefs.exported("calendar", w.id)) writeToCalendar()
            }
            else -> autoCalendarPermission.launch(CalendarExport.permissions)
        }
    }
    fun setAutoHealth(on: Boolean) {
        val w = workout ?: return
        if (!on) { Prefs.setAutoHealth(false); return }
        if (HealthExport.availability(context) != HealthExport.Availability.AVAILABLE) {
            toast("이 폰에서는 Health Connect를 먼저 설치하거나 업데이트해야 해요")
            return
        }
        scope.launch {
            if (HealthExport.missingPermissions(context).isEmpty()) {
                Prefs.setAutoHealth(true)
                if (!Prefs.exported("health", w.id)) writeToHealth()
            } else autoHealthPermission.launch(HealthExport.permissions)
        }
    }
    fun shareReport() {
        val w = workout ?: return
        val send = Intent(Intent.ACTION_SEND).setType("text/plain")
            .putExtra(Intent.EXTRA_SUBJECT, WorkoutReport.title(w))
            .putExtra(Intent.EXTRA_TEXT, report(w))
        context.startActivity(Intent.createChooser(send, "운동 결과 보내기"))
    }

    // Just finished: what the settings say to do without a tap (AutoExport, which the service
    // also runs as it saves - whichever comes first does it, once). Here only to say so.
    LaunchedEffect(workout?.id, workout?.status, pointRows.size) {
        val w = workout ?: return@LaunchedEffect
        if (!justFinished || w.simulated || w.status != WorkoutEntity.DONE || pointRows.isEmpty()) return@LaunchedEffect
        AutoExport.runPending(context)
        val places = listOfNotNull(
            "캘린더".takeIf { Prefs.autoCalendar.value && Prefs.exported("calendar", w.id) },
            "삼성헬스(Health Connect)".takeIf { Prefs.autoHealth.value && Prefs.exported("health", w.id) },
        )
        if (places.isNotEmpty()) toast(places.joinToString("와 ") + "에 기록했어요")
    }

    val w = workout
    Column(Modifier.fillMaxSize().safeDrawingPadding()) {
        Row(Modifier.fillMaxWidth().padding(horizontal = 8.dp, vertical = 4.dp), verticalAlignment = Alignment.CenterVertically) {
            if (!justFinished) IconButton(onClick = onClose) { Icon(Icons.AutoMirrored.Filled.ArrowBack, "뒤로") }
            Spacer(Modifier.weight(1f))
            TextButton(onClick = { confirmDelete = true }) { Text("삭제") }
        }
        if (w == null) return@Column
        Column(
            Modifier.weight(1f).verticalScroll(rememberScrollState()).padding(horizontal = 24.dp),
        ) {
            Text(workoutTitle(w), style = MaterialTheme.typography.headlineSmall)
            Text(workoutDate(w), style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
            w.courseId?.let { cid ->
                val course by remember(cid) { CourseStore.observe(cid) }.collectAsState(initial = null)
                course?.let { Text("코스: ${it.name}", style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.primary) }
            }
            Spacer(Modifier.height(16.dp))
            RouteView(points, photos = photos.indices.mapNotNull { i ->
                photoPlaces.getOrNull(i)?.let { it to photoThumbs[photos[i].uri] }
            })
            Spacer(Modifier.height(16.dp))
            PhotoStrip(
                photos, photoPlaces, canReadPhotos,
                onAllow = { photoPermission.launch(WorkoutPhotos.permissions) },
                onAdd = { pickPhotos.launch(PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly)) },
                onOpen = { viewingPhoto = it },
            )
            Spacer(Modifier.height(20.dp))

            val avgPace = if (w.distanceM >= 20) w.durationMs / 1000.0 / (w.distanceM / 1000.0) else null
            StatRow(
                "거리" to "${formatKm(w.distanceM)}km",
                "시간" to formatDuration(w.durationMs),
            )
            Spacer(Modifier.height(12.dp))
            StatRow(
                "평균 페이스" to "${formatPace(avgPace)}/km",
                "칼로리" to "약 ${w.calories.roundToInt()}kcal",
            )
            Spacer(Modifier.height(12.dp))
            val moving = w.runMs + w.walkMs
            val runPct = if (moving > 0) (w.runMs * 100.0 / moving).roundToInt() else null
            StatRow(
                "오르막" to "${w.ascentM.roundToInt()}m",
                "달리기 · 걷기" to (runPct?.let { "$it% · ${100 - it}%" } ?: "-"),
            )

            // Most people never open the settings: offer the automatic records where the result is.
            // Shown while either is off (kept for this visit once shown), until "다시 보지 않기".
            val autoCalendarOn by Prefs.autoCalendar.collectAsState()
            val autoHealthOn by Prefs.autoHealth.collectAsState()
            var offerAuto by remember {
                mutableStateOf(justFinished && !w.simulated && !Prefs.autoExportOfferHidden && !(autoCalendarOn && autoHealthOn))
            }
            if (offerAuto) {
                Spacer(Modifier.height(20.dp))
                Surface(shape = RoundedCornerShape(16.dp), color = MaterialTheme.colorScheme.surfaceContainerLow) {
                    Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                        Text("운동이 끝날 때마다 자동으로 기록할까요?", style = MaterialTheme.typography.titleMedium)
                        ToggleRow("구글 캘린더", "운동 시간·거리·페이스가 일정으로 남아요", autoCalendarOn, ::setAutoCalendar)
                        ToggleRow("삼성헬스", "Health Connect를 거쳐 운동과 경로가 들어가요", autoHealthOn, ::setAutoHealth)
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Text(
                                "설정에서도 바꿀 수 있어요", style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.weight(1f)
                            )
                            TextButton(onClick = { Prefs.autoExportOfferHidden = true; offerAuto = false }) { Text("다시 보지 않기") }
                        }
                    }
                }
            }

            val splits = remember(points, w.distanceM, w.durationMs) { Splits.compute(points, w.distanceM, w.durationMs) }
            if (splits.isNotEmpty()) {
                Spacer(Modifier.height(28.dp))
                Text("구간", style = MaterialTheme.typography.titleMedium)
                Spacer(Modifier.height(8.dp))
                SplitsTable(splits)
            }

            Spacer(Modifier.height(28.dp))
            Text("내보내기", style = MaterialTheme.typography.titleMedium)
            Spacer(Modifier.height(8.dp))
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                OutlinedButton(
                    onClick = { GpxShare.share(context, workoutTitle(w), w, pointRows) },
                    enabled = pointRows.isNotEmpty(),
                    modifier = Modifier.weight(1f)
                ) { Text("GPX 파일") }
                // Fake test runs stay out of the user's real health records.
                OutlinedButton(onClick = ::saveToHealth, enabled = !w.simulated, modifier = Modifier.weight(1f)) {
                    Text("삼성헬스에 저장")
                }
            }
            Row(Modifier.fillMaxWidth().padding(top = 8.dp), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                OutlinedButton(onClick = ::saveToCalendar, modifier = Modifier.weight(1f)) { Text("캘린더에 기록") }
                OutlinedButton(onClick = ::shareReport, modifier = Modifier.weight(1f)) { Text("결과 공유") }
            }
            mapsUrl?.let { url ->
                OutlinedButton(
                    onClick = { runCatching { context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url))) } },
                    modifier = Modifier.fillMaxWidth().padding(top = 8.dp)
                ) { Text("구글 지도에서 경로 보기") }
            }
            OutlinedButton(
                onClick = { savingCourse = true },
                enabled = points.size >= 2,
                modifier = Modifier.fillMaxWidth().padding(top = 8.dp)
            ) { Text("이 경로를 코스로 저장") }
            Text(
                if (w.simulated) "테스트 기록은 삼성헬스에 저장하지 않아요."
                else "캘린더는 폰의 구글 계정 캘린더에 기록돼 구글 캘린더로 동기화돼요. 일정과 공유한 글에는 구글 지도 " +
                    "경로 링크가 들어가요 (지도는 그 지점들을 잇는 걷기 길을 보여줘요). 결과 공유는 카카오톡·메모 등 " +
                    "어떤 앱으로든 보낼 수 있어요. GPX는 Strava, Garmin 등에 올릴 수 있고, 삼성헬스는 Health Connect를 " +
                    "거쳐 저장돼요 (삼성헬스 설정 > Health Connect에서 동기화가 켜져 있어야 해요). " +
                    "설정에서 운동이 끝나면 자동으로 기록되게 할 수 있어요.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(top = 8.dp)
            )
            Spacer(Modifier.height(24.dp))
        }
        if (justFinished) {
            Button(onClick = onClose, modifier = Modifier.fillMaxWidth().padding(24.dp).height(56.dp)) { Text("확인") }
        }
    }

    viewingPhoto?.let { start ->
        PhotoViewer(photos, photoPlaces, start, onDismiss = { viewingPhoto = null }) { p ->
            viewingPhoto = null
            p.addedId?.let { id -> scope.launch { WorkoutPhotos.remove(id) } }
        }
    }

    if (savingCourse) {
        NameDialog("코스 이름", w?.let { workoutTitle(it).removeSuffix(" (테스트)") }.orEmpty(), onDismiss = { savingCourse = false }) { name ->
            savingCourse = false
            scope.launch {
                CourseStore.save(name, CourseSource.WORKOUT, points.map { GeoPoint(it.sample.lat, it.sample.lon, it.sample.altitudeM) })
                toast("코스에 저장했어요. 코스 탭에서 볼 수 있어요.")
            }
        }
    }

    if (confirmDelete) {
        AlertDialog(
            onDismissRequest = { confirmDelete = false },
            title = { Text("이 기록을 삭제할까요?") },
            text = { Text("삭제한 기록은 되돌릴 수 없어요.") },
            confirmButton = {
                TextButton(onClick = {
                    confirmDelete = false
                    WorkoutStore.delete(workoutId)
                    onClose()
                }) { Text("삭제") }
            },
            dismissButton = { TextButton(onClick = { confirmDelete = false }) { Text("취소") } }
        )
    }
}

@Composable
private fun StatRow(left: Pair<String, String>, right: Pair<String, String>) {
    Row(Modifier.fillMaxWidth()) {
        listOf(left, right).forEach { (label, value) ->
            Column(Modifier.weight(1f)) {
                Text(label, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                Text(value, fontSize = 24.sp)
            }
        }
    }
}

/** km, pace and a bar per split - longer bar = faster, the fastest km highlighted. */
@Composable
private fun SplitsTable(splits: List<Split>) {
    val fastest = splits.minOf { it.paceSecPerKm }
    val slowest = splits.maxOf { it.paceSecPerKm }
    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
        splits.forEach { s ->
            Row(verticalAlignment = Alignment.CenterVertically) {
                val label = if (s.distanceM < 999) "${formatKm(s.distanceM)}" else "${s.index}"
                Text(label, modifier = Modifier.width(44.dp), style = MaterialTheme.typography.bodyMedium)
                Text(formatPace(s.paceSecPerKm), modifier = Modifier.width(64.dp), style = MaterialTheme.typography.bodyMedium)
                // Bars from 35% (slowest) to 100% (fastest) width, so even the slowest is visible.
                val f = if (slowest > fastest) 0.35f + 0.65f * ((slowest - s.paceSecPerKm) / (slowest - fastest)).toFloat() else 1f
                Box(Modifier.weight(1f).height(14.dp)) {
                    Box(
                        Modifier.fillMaxWidth(f).fillMaxHeight()
                            .background(
                                if (s.paceSecPerKm == fastest && splits.size > 1) MaterialTheme.colorScheme.primary
                                else MaterialTheme.colorScheme.primary.copy(alpha = 0.45f),
                                RoundedCornerShape(4.dp)
                            )
                    )
                }
            }
        }
        if (splits.last().distanceM < 999) {
            Text(
                "마지막 구간은 남은 거리 기준 페이스예요",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                textAlign = TextAlign.Start
            )
        }
    }
}
