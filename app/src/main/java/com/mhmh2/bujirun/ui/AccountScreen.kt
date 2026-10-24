package com.mhmh2.bujirun.ui

import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.PhotoCamera
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.mhmh2.bujirun.Lang
import com.mhmh2.bujirun.Prefs
import com.mhmh2.bujirun.account.Account
import com.mhmh2.bujirun.account.AccountRules
import com.mhmh2.bujirun.account.AuthException
import com.mhmh2.bujirun.account.ProfilePhoto
import com.mhmh2.bujirun.data.AutoActivityEntity
import com.mhmh2.bujirun.data.WorkoutEntity
import com.mhmh2.bujirun.data.WorkoutStore
import com.mhmh2.bujirun.tr
import com.mhmh2.bujirun.tracking.Records
import com.mhmh2.bujirun.tracking.formatDuration
import com.mhmh2.bujirun.tracking.formatPace
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.time.Instant
import java.time.LocalDate
import java.time.OffsetDateTime
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale

/** The round profile picture, or the nickname's first letter when there's no photo. */
@Composable
fun Avatar(nickname: String, size: Dp, onHero: Boolean = true, modifier: Modifier = Modifier) {
    val photo by ProfilePhoto.bitmap.collectAsState()
    val context = LocalContext.current
    LaunchedEffect(Unit) { ProfilePhoto.load(context) }
    Box(
        modifier.size(size).clip(CircleShape)
            .background(if (onHero) Color.White.copy(alpha = 0.25f) else MaterialTheme.colorScheme.primaryContainer),
        contentAlignment = Alignment.Center,
    ) {
        val p = photo
        if (p != null) {
            Image(p.asImageBitmap(), contentDescription = null, contentScale = ContentScale.Crop, modifier = Modifier.fillMaxSize())
        } else {
            Text(
                nickname.take(1).uppercase(), fontWeight = FontWeight.ExtraBold, fontSize = (size.value * 0.42f).sp,
                color = if (onHero) Color.White else MaterialTheme.colorScheme.onPrimaryContainer,
            )
        }
    }
}

/** A personal best: what, the figure, and the workout it came from. */
private data class Best(val label: String, val value: String, val workout: WorkoutEntity)

/**
 * The account page (Strava's "You"): photo and nickname, this month against a goal, all-time and
 * this year's totals, weeks in a row, personal bests, and log out / delete account.
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun AccountScreen(onClose: () -> Unit, onOpenWorkout: (Long) -> Unit) {
    val user by Account.user.collectAsState()
    val u = user ?: return
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val workouts by remember { WorkoutStore.observeDone() }.collectAsState(initial = emptyList())
    val autos by remember { WorkoutStore.observeAuto() }.collectAsState(initial = emptyList())
    val real = workouts.filter { !it.simulated }
    val monthGoal by Prefs.monthGoalKm.collectAsState()

    var photoMenu by remember { mutableStateOf(false) }
    var editingName by remember { mutableStateOf(false) }
    var editingGoal by remember { mutableStateOf(false) }
    var confirmSignOut by remember { mutableStateOf(false) }
    var deleting by remember { mutableStateOf(false) }
    var changingPassword by remember { mutableStateOf(false) }
    val photo by ProfilePhoto.bitmap.collectAsState()
    val pick = rememberLauncherForActivityResult(ActivityResultContracts.PickVisualMedia()) { uri ->
        if (uri != null) scope.launch {
            if (!ProfilePhoto.set(context, uri)) Toast.makeText(context, tr("사진을 열지 못했어요", "Couldn't open that photo"), Toast.LENGTH_SHORT).show()
        }
    }

    // Personal bests need each run's points: worked out off the main thread when the list changes.
    var bests by remember { mutableStateOf<List<Best>?>(null) }
    LaunchedEffect(real.map { it.id }) {
        bests = withContext(Dispatchers.Default) { personalBests(real) }
    }

    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(horizontal = 20.dp)) {
        Row(Modifier.padding(top = 12.dp, bottom = 8.dp), verticalAlignment = Alignment.CenterVertically) {
            IconButton(onClick = onClose) { Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = tr("뒤로", "Back")) }
            Text(tr("내 계정", "My account"), style = MaterialTheme.typography.headlineSmall)
        }

        HeroCard {
            Column(Modifier.fillMaxWidth(), horizontalAlignment = Alignment.CenterHorizontally) {
                Box {
                    Box(Modifier.clickable { photoMenu = true }) {
                        Avatar(u.nickname, 96.dp, modifier = Modifier.border(3.dp, Color.White.copy(alpha = 0.6f), CircleShape))
                        Box(
                            Modifier.align(Alignment.BottomEnd).size(30.dp).clip(CircleShape).background(Color.White),
                            contentAlignment = Alignment.Center,
                        ) { Icon(Icons.Filled.PhotoCamera, contentDescription = tr("사진 바꾸기", "Change photo"), tint = Brand.Coral, modifier = Modifier.size(18.dp)) }
                    }
                    DropdownMenu(expanded = photoMenu, onDismissRequest = { photoMenu = false }) {
                        DropdownMenuItem(text = { Text(tr("사진 고르기", "Choose a photo")) }, onClick = {
                            photoMenu = false
                            pick.launch(PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly))
                        })
                        if (photo != null) DropdownMenuItem(text = { Text(tr("사진 지우기", "Remove photo")) }, onClick = {
                            photoMenu = false
                            ProfilePhoto.remove(context)
                        })
                    }
                }
                Spacer(Modifier.height(12.dp))
                Row(Modifier.clickable { editingName = true }, verticalAlignment = Alignment.CenterVertically) {
                    Text(u.nickname, style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.ExtraBold, color = Color.White, maxLines = 1)
                    Spacer(Modifier.width(6.dp))
                    Icon(Icons.Filled.Edit, contentDescription = tr("닉네임 바꾸기", "Change nickname"), tint = Color.White.copy(alpha = 0.85f), modifier = Modifier.size(18.dp))
                }
                Text(u.email, style = MaterialTheme.typography.bodySmall, color = Color.White.copy(alpha = 0.85f), maxLines = 1)
                val badges = buildList {
                    if (!u.hasPassword) add(tr("구글 계정", "Google account"))
                    if (u.isAdmin) add(tr("관리자", "Admin"))
                    joinedText(u.createdAt)?.let(::add)
                }
                if (badges.isNotEmpty()) {
                    Spacer(Modifier.height(10.dp))
                    Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                        badges.forEach {
                            Text(
                                it, style = MaterialTheme.typography.labelSmall, color = Color.White,
                                modifier = Modifier.clip(RoundedCornerShape(50)).background(Color.White.copy(alpha = 0.22f)).padding(horizontal = 10.dp, vertical = 3.dp),
                            )
                        }
                    }
                }
                Spacer(Modifier.height(16.dp))
                Row(Modifier.fillMaxWidth()) {
                    val streak = Records.weekStreak(activeDays(real, autos), LocalDate.now())
                    StatTile(tr("운동", "Workouts"), "${real.size}", if (Lang.english) null else "회", Modifier.weight(1f), onHero = true)
                    StatTile(tr("총 거리", "Distance"), km(real.sumOf { it.distanceM } + autos.sumOf { it.distanceM }), "km", Modifier.weight(1f), onHero = true)
                    StatTile(tr("연속", "Streak"), "$streak", tr("주", if (streak == 1) "week" else "weeks"), Modifier.weight(1f), onHero = true)
                }
            }
        }

        Spacer(Modifier.height(20.dp))
        MonthCard(real, autos, monthGoal, onSetGoal = { editingGoal = true })

        Spacer(Modifier.height(20.dp))
        SectionTitle(tr("누적 기록", "Totals"), Modifier.padding(start = 4.dp, bottom = 8.dp))
        Card {
            val zone = ZoneId.systemDefault()
            val yearStart = LocalDate.now(zone).withDayOfYear(1).atStartOfDay(zone).toInstant().toEpochMilli()
            TotalsRow(tr("올해", "This year"), real.filter { it.startedAt >= yearStart }, autos.filter { it.startedAt >= yearStart })
            HorizontalDivider(Modifier.padding(vertical = 14.dp), color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.5f))
            TotalsRow(tr("전체", "All time"), real, autos)
            if (autos.isNotEmpty()) {
                Text(
                    tr("거리와 시간에는 자동 기록된 걷기·달리기도 들어가요.", "Distance and time include auto-recorded walks and runs."),
                    style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(top = 12.dp),
                )
            }
        }

        Spacer(Modifier.height(20.dp))
        SectionTitle(tr("개인 최고 기록", "Personal bests"), Modifier.padding(start = 4.dp, bottom = 8.dp))
        Card(padding = 6.dp) {
            val list = bests
            when {
                list == null -> Text(tr("계산하는 중…", "Working them out…"), color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(14.dp))
                list.isEmpty() -> Text(
                    tr("달리기를 기록하면 가장 빠른 1km, 5km, 10km와 가장 긴 달리기가 여기 남아요.", "Record a run and your fastest 1 km, 5 km and 10 km and your longest run show up here."),
                    style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(14.dp),
                )
                else -> list.forEach { b ->
                    Row(
                        Modifier.fillMaxWidth().clip(RoundedCornerShape(18.dp)).clickable { onOpenWorkout(b.workout.id) }.padding(horizontal = 12.dp, vertical = 12.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Column(Modifier.weight(1f)) {
                            Text(b.label, style = MaterialTheme.typography.titleSmall)
                            Text(dayText(b.workout.startedAt), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                        Text(b.value, style = Brand.number.copy(fontSize = 20.sp), color = MaterialTheme.colorScheme.onSurface)
                        Icon(Icons.AutoMirrored.Filled.KeyboardArrowRight, contentDescription = null, tint = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                }
            }
        }

        Spacer(Modifier.height(24.dp))
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            OutlinedButton(onClick = { changingPassword = true }, modifier = Modifier.weight(1f)) {
                Text(if (u.hasPassword) tr("비밀번호 변경", "Change password") else tr("비밀번호 만들기", "Add a password"), maxLines = 1)
            }
            OutlinedButton(onClick = { confirmSignOut = true }, modifier = Modifier.weight(1f)) { Text(tr("로그아웃", "Log out"), maxLines = 1) }
        }
        TextButton(onClick = { deleting = true }, modifier = Modifier.padding(top = 4.dp)) {
            Text(tr("계정 삭제", "Delete account"), color = MaterialTheme.colorScheme.error)
        }
        Text(
            tr("프로필 사진은 이 폰에만 저장되고 서버로 보내지 않아요.", "Your profile photo stays on this phone and is never uploaded."),
            style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(start = 12.dp),
        )
        Spacer(Modifier.height(32.dp))
    }

    if (editingName) NicknameDialog(u.nickname, onDismiss = { editingName = false })
    if (editingGoal) MonthGoalDialog(monthGoal, onDismiss = { editingGoal = false })
    if (confirmSignOut) {
        AlertDialog(
            onDismissRequest = { confirmSignOut = false },
            title = { Text(tr("로그아웃할까요?", "Log out?")) },
            text = { Text(tr("운동 기록은 이 폰에 그대로 남아요.", "Your workouts stay on this phone.")) },
            confirmButton = {
                TextButton(onClick = { confirmSignOut = false; scope.launch { Account.logOut() } }) { Text(tr("로그아웃", "Log out")) }
            },
            dismissButton = { TextButton(onClick = { confirmSignOut = false }) { Text(tr("취소", "Cancel")) } },
        )
    }
    if (deleting) DeleteAccountDialog(u.hasPassword, onDismiss = { deleting = false })
    if (changingPassword) PasswordDialog(u.hasPassword, onDismiss = { changingPassword = false })
}

@Composable
private fun MonthCard(workouts: List<WorkoutEntity>, autos: List<AutoActivityEntity>, goalKm: Int, onSetGoal: () -> Unit) {
    val zone = ZoneId.systemDefault()
    val today = LocalDate.now(zone)
    val start = today.withDayOfMonth(1).atStartOfDay(zone).toInstant().toEpochMilli()
    val ws = workouts.filter { it.startedAt >= start }
    val au = autos.filter { it.startedAt >= start }
    val meters = ws.sumOf { it.distanceM } + au.sumOf { it.distanceM }
    val month = if (Lang.english) today.format(DateTimeFormatter.ofPattern("MMMM", Locale.US)) else "${today.monthValue}월"
    Card(onClick = onSetGoal) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            if (goalKm > 0) {
                val progress = (meters / 1000 / goalKm).toFloat()
                ProgressRing(progress, Modifier.size(84.dp), stroke = 9.dp, track = MaterialTheme.colorScheme.surfaceContainerHighest, fill = Brand.Coral) {
                    Text("${(progress * 100).toInt().coerceAtMost(999)}%", style = Brand.number.copy(fontSize = 18.sp))
                }
                Spacer(Modifier.width(16.dp))
            }
            Column(Modifier.weight(1f)) {
                Text(tr("$month 목표", "$month goal"), style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.primary)
                Row(verticalAlignment = Alignment.Bottom) {
                    Text(km(meters), style = Brand.number.copy(fontSize = 28.sp))
                    Text(if (goalKm > 0) " / $goalKm km" else " km", style = MaterialTheme.typography.titleSmall, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(bottom = 4.dp))
                }
                val left = goalKm - meters / 1000
                Text(
                    when {
                        goalKm == 0 -> tr("눌러서 이번 달 목표 거리를 정해 보세요", "Tap to set a distance goal for the month")
                        left <= 0 -> tr("목표 달성! 멋져요 🎉", "Goal reached. Nice work! 🎉")
                        else -> {
                            val days = today.lengthOfMonth() - today.dayOfMonth + 1
                            tr("${km(left * 1000)}km 남았어요 · 하루 ${km(left * 1000 / days)}km씩", "${km(left * 1000)} km to go · ${km(left * 1000 / days)} km a day")
                        }
                    },
                    style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
}

@Composable
private fun TotalsRow(label: String, workouts: List<WorkoutEntity>, autos: List<AutoActivityEntity>) {
    Text(label, style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.primary)
    Spacer(Modifier.height(6.dp))
    Row(Modifier.fillMaxWidth()) {
        val runs = workouts.count { it.isRun }
        StatTile(tr("거리", "Distance"), km(workouts.sumOf { it.distanceM } + autos.sumOf { it.distanceM }), "km", Modifier.weight(1f))
        val ms = workouts.sumOf { it.durationMs } + autos.sumOf { it.movingMs }
        if (ms < 3_600_000) StatTile(tr("시간", "Time"), "${ms / 60_000}", tr("분", "min"), Modifier.weight(1f))
        else StatTile(tr("시간", "Time"), hours(ms), tr("시간", "h"), Modifier.weight(1f))
        StatTile(tr("달리기 · 걷기", "Runs · walks"), "$runs · ${workouts.size - runs}", null, Modifier.weight(1f))
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun MonthGoalDialog(current: Int, onDismiss: () -> Unit) {
    var text by remember { mutableStateOf(if (current > 0) "$current" else "") }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(tr("이번 달 목표 거리", "This month's distance goal")) },
        text = {
            Column {
                FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    listOf(30, 50, 100, 150, 200).forEach { km ->
                        FilterChip(selected = text == "$km", onClick = { text = "$km" }, label = { Text("${km}km") })
                    }
                }
                Spacer(Modifier.height(8.dp))
                OutlinedTextField(
                    value = text, onValueChange = { text = it.filter(Char::isDigit).take(4) },
                    label = { Text(tr("직접 입력 (km)", "Or type it (km)")) }, singleLine = true,
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                )
                Text(
                    tr("매달 같은 목표로 이어져요. 걷기와 자동 기록도 들어가요.", "It carries on each month. Walks and auto records count too."),
                    style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        },
        confirmButton = {
            TextButton(onClick = { Prefs.setMonthGoalKm(text.toIntOrNull()?.coerceIn(0, 9999) ?: 0); onDismiss() }) { Text(tr("저장", "Save")) }
        },
        dismissButton = {
            if (current > 0) TextButton(onClick = { Prefs.setMonthGoalKm(0); onDismiss() }) { Text(tr("목표 없애기", "Remove goal")) }
            else TextButton(onClick = onDismiss) { Text(tr("취소", "Cancel")) }
        },
    )
}

@Composable
private fun NicknameDialog(current: String, onDismiss: () -> Unit) {
    var name by remember { mutableStateOf(current) }
    var error by remember { mutableStateOf<String?>(null) }
    var busy by remember { mutableStateOf(false) }
    val scope = rememberCoroutineScope()
    val ok = AccountRules.isValidNickname(name)
    AlertDialog(
        onDismissRequest = { if (!busy) onDismiss() },
        title = { Text(tr("닉네임 바꾸기", "Change nickname")) },
        text = {
            OutlinedTextField(
                value = name, onValueChange = { name = it.take(20); error = null }, singleLine = true,
                label = { Text(tr("닉네임 (2~12자)", "Nickname (2-12 characters)")) },
                isError = error != null || (name.isNotEmpty() && !ok),
                supportingText = { (error ?: if (name.isNotEmpty() && !ok) tr("2~12자로 입력해 주세요", "Use 2 to 12 characters") else null)?.let { Text(it) } },
            )
        },
        confirmButton = {
            TextButton(enabled = ok && !busy && AccountRules.normalizeNickname(name) != current, onClick = {
                busy = true
                scope.launch {
                    try {
                        Account.changeNickname(name)
                        onDismiss()
                    } catch (e: AuthException) {
                        error = e.message
                    } finally {
                        busy = false
                    }
                }
            }) { Text(tr("저장", "Save")) }
        },
        dismissButton = { TextButton(onClick = onDismiss, enabled = !busy) { Text(tr("취소", "Cancel")) } },
    )
}

/** A new password; the current one is asked for if the account has one (a Google-made
 * account adds its first, so it can also log in with email). */
@Composable
private fun PasswordDialog(hasPassword: Boolean, onDismiss: () -> Unit) {
    var current by remember { mutableStateOf("") }
    var password by remember { mutableStateOf("") }
    var again by remember { mutableStateOf("") }
    var error by remember { mutableStateOf<String?>(null) }
    var busy by remember { mutableStateOf(false) }
    val scope = rememberCoroutineScope()
    val context = LocalContext.current
    AlertDialog(
        onDismissRequest = { if (!busy) onDismiss() },
        title = { Text(if (hasPassword) tr("비밀번호 변경", "Change password") else tr("비밀번호 만들기", "Add a password")) },
        text = {
            Column {
                if (!hasPassword) Text(
                    tr("만들어 두면 구글 없이 이메일과 비밀번호로도 로그인할 수 있어요.", "Then you can also log in with your email and password."),
                    style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(bottom = 8.dp),
                )
                if (hasPassword) OutlinedTextField(
                    value = current, onValueChange = { current = it; error = null }, singleLine = true,
                    label = { Text(tr("지금 비밀번호", "Current password")) },
                    visualTransformation = PasswordVisualTransformation(), keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password),
                )
                OutlinedTextField(
                    value = password, onValueChange = { password = it; error = null }, singleLine = true,
                    label = { Text(tr("새 비밀번호 (8자 이상)", "New password (8+ characters)")) },
                    visualTransformation = PasswordVisualTransformation(), keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password),
                )
                OutlinedTextField(
                    value = again, onValueChange = { again = it; error = null }, singleLine = true,
                    label = { Text(tr("새 비밀번호 확인", "Confirm new password")) }, isError = again.isNotEmpty() && again != password,
                    visualTransformation = PasswordVisualTransformation(), keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password),
                )
                error?.let { Text(it, color = MaterialTheme.colorScheme.error, modifier = Modifier.padding(top = 6.dp)) }
            }
        },
        confirmButton = {
            TextButton(
                enabled = !busy && (!hasPassword || current.isNotEmpty()) && AccountRules.isValidPassword(password) && password == again,
                onClick = {
                    busy = true
                    scope.launch {
                        try {
                            Account.changePassword(current.takeIf { hasPassword }, password)
                            Toast.makeText(context, tr("비밀번호를 저장했어요", "Password saved"), Toast.LENGTH_SHORT).show()
                            onDismiss()
                        } catch (e: AuthException) {
                            error = e.message
                        } finally {
                            busy = false
                        }
                    }
                },
            ) { Text(tr("저장", "Save")) }
        },
        dismissButton = { TextButton(onClick = onDismiss, enabled = !busy) { Text(tr("취소", "Cancel")) } },
    )
}

/** Account deletion (required by Play for apps with sign-up). Re-asks for the password, if the
 * account has one; a Google account just confirms. */
@Composable
private fun DeleteAccountDialog(hasPassword: Boolean, onDismiss: () -> Unit) {
    var password by remember { mutableStateOf("") }
    var error by remember { mutableStateOf<String?>(null) }
    var busy by remember { mutableStateOf(false) }
    val scope = rememberCoroutineScope()
    AlertDialog(
        onDismissRequest = { if (!busy) onDismiss() },
        title = { Text(tr("계정을 삭제할까요?", "Delete your account?")) },
        text = {
            Column {
                Text(tr("계정과 계정에 백업된 운동 기록·코스가 서버에서 지워지고 되돌릴 수 없어요. 이 폰에 있는 기록은 남아요.", "Your account and the records backed up with it are deleted from the server for good. Workouts on this phone stay."))
                if (hasPassword) {
                    Spacer(Modifier.height(12.dp))
                    OutlinedTextField(
                        value = password,
                        onValueChange = { password = it; error = null },
                        label = { Text(tr("비밀번호", "Password")) },
                        singleLine = true,
                        visualTransformation = PasswordVisualTransformation(),
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password),
                        isError = error != null,
                        supportingText = { error?.let { Text(it) } },
                    )
                } else error?.let { Text(it, color = MaterialTheme.colorScheme.error, modifier = Modifier.padding(top = 8.dp)) }
            }
        },
        confirmButton = {
            TextButton(
                enabled = (!hasPassword || password.isNotEmpty()) && !busy,
                onClick = {
                    busy = true
                    scope.launch {
                        try {
                            Account.deleteAccount(password.takeIf { hasPassword })
                        } catch (e: AuthException) {
                            error = e.message
                        } finally {
                            busy = false
                        }
                    }
                },
            ) { Text(tr("삭제", "Delete"), color = MaterialTheme.colorScheme.error) }
        },
        dismissButton = { TextButton(onClick = onDismiss, enabled = !busy) { Text(tr("취소", "Cancel")) } },
    )
}

/** Longest run, then the fastest 1 / 5 / 10 km stretches inside any run. */
private suspend fun personalBests(workouts: List<WorkoutEntity>): List<Best> {
    val runs = workouts.filter { it.isRun && it.distanceM >= 500 }
    if (runs.isEmpty()) return emptyList()
    val out = mutableListOf<Best>()
    runs.maxBy { it.distanceM }.let { out += Best(tr("가장 긴 달리기", "Longest run"), "${km(it.distanceM)} km", it) }
    runs.maxBy { it.durationMs }.let { out += Best(tr("가장 오래 달린 시간", "Longest time running"), formatDuration(it.durationMs), it) }
    for (target in listOf(1000.0, 5000.0, 10_000.0)) {
        var best: Pair<Long, WorkoutEntity>? = null
        for (w in runs.filter { it.distanceM >= target }) {
            val pts = WorkoutStore.points(w.id).map { it.distanceM to it.activeMs }
            val ms = Records.fastestMs(pts, target) ?: continue
            if (best == null || ms < best.first) best = ms to w
        }
        best?.let { (ms, w) ->
            val k = (target / 1000).toInt()
            out += Best(tr("가장 빠른 ${k}km", "Fastest $k km"), "${formatDuration(ms)} · ${formatPace(ms / 1000.0 / k)}", w)
        }
    }
    return out
}

private fun activeDays(workouts: List<WorkoutEntity>, autos: List<AutoActivityEntity>): List<LocalDate> {
    val zone = ZoneId.systemDefault()
    return (workouts.map { it.startedAt } + autos.map { it.startedAt }).map { Instant.ofEpochMilli(it).atZone(zone).toLocalDate() }
}

private fun km(meters: Double) = String.format(Locale.US, if (meters >= 100_000) "%.0f" else "%.1f", meters / 1000)

private fun hours(ms: Long) = String.format(Locale.US, "%.1f", ms / 3_600_000.0)

private fun dayText(epochMs: Long): String {
    val d = Instant.ofEpochMilli(epochMs).atZone(ZoneId.systemDefault()).toLocalDate()
    return if (Lang.english) d.format(DateTimeFormatter.ofPattern("MMM d, yyyy", Locale.US)) else "${d.year}년 ${d.monthValue}월 ${d.dayOfMonth}일"
}

private fun joinedText(createdAt: String?): String? {
    val d = runCatching { OffsetDateTime.parse(createdAt).atZoneSameInstant(ZoneId.systemDefault()).toLocalDate() }.getOrNull() ?: return null
    return if (Lang.english) "Since " + d.format(DateTimeFormatter.ofPattern("MMM yyyy", Locale.US)) else "${d.year}년 ${d.monthValue}월부터"
}
