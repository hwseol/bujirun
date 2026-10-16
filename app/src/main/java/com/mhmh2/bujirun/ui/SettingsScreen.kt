package com.mhmh2.bujirun.ui

import com.mhmh2.bujirun.tr
import androidx.compose.foundation.horizontalScroll
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.draw.clip
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.background

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.FilterChip
import androidx.compose.material3.TextButton
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.text.input.PasswordVisualTransformation
import com.mhmh2.bujirun.account.Account
import com.mhmh2.bujirun.account.AuthException
import kotlinx.coroutines.launch
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import android.widget.Toast
import android.Manifest
import android.os.Build
import com.mhmh2.bujirun.tracking.PassiveRecorder
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.ui.platform.LocalContext
import androidx.health.connect.client.PermissionController
import com.mhmh2.bujirun.data.CalendarExport
import com.mhmh2.bujirun.data.HealthExport
import com.mhmh2.bujirun.Prefs
import com.mhmh2.bujirun.coach.CueInterval

private val intervals get() = listOf(
    "500m" to CueInterval.EveryDistance(500.0),
    "1km" to CueInterval.EveryDistance(1000.0),
    tr("5분", "5 min") to CueInterval.EveryTime(5 * 60_000L),
    tr("10분", "10 min") to CueInterval.EveryTime(10 * 60_000L),
)

@Composable
fun SettingsScreen() {
    val voiceOn by Prefs.voiceEnabled.collectAsState()
    val autoPause by Prefs.autoPause.collectAsState()
    var interval by remember { mutableStateOf(Prefs.cueInterval) }
    var weightText by remember { mutableStateOf(if (Prefs.weightSet) Prefs.weightKg.toInt().toString() else "") }
    val weight = weightText.toIntOrNull()
    val weightValid = weight != null && weight in 25..250

    val user by Account.user.collectAsState()
    var confirmSignOut by remember { mutableStateOf(false) }
    var deleting by remember { mutableStateOf(false) }
    val scope = rememberCoroutineScope()
    val context = LocalContext.current
    val autoCalendar by Prefs.autoCalendar.collectAsState()
    val autoHealth by Prefs.autoHealth.collectAsState()
    val calendarPermission = rememberLauncherForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { granted ->
        if (granted.values.all { it }) Prefs.setAutoCalendar(true)
        else Toast.makeText(context, tr("캘린더 권한을 허용해야 기록할 수 있어요", "Allow calendar access to add workouts there"), Toast.LENGTH_LONG).show()
    }
    val healthPermission = rememberLauncherForActivityResult(PermissionController.createRequestPermissionResultContract()) { granted ->
        if (granted.containsAll(HealthExport.required)) Prefs.setAutoHealth(true)
        else Toast.makeText(context, tr("권한을 허용해야 삼성헬스에 저장할 수 있어요", "Allow access to save to Samsung Health"), Toast.LENGTH_LONG).show()
    }

    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(horizontal = 20.dp)) {
        Text(tr("설정", "Settings"), style = MaterialTheme.typography.headlineSmall, modifier = Modifier.padding(top = 24.dp, bottom = 16.dp))

        // Who's signed in, in the brand card.
        user?.let { u ->
            HeroCard {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Box(Modifier.size(52.dp).clip(CircleShape).background(Color.White.copy(alpha = 0.25f)), contentAlignment = Alignment.Center) {
                        Text(u.nickname.take(1).uppercase(), color = Color.White, style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.ExtraBold)
                    }
                    Spacer(Modifier.width(14.dp))
                    Column(Modifier.weight(1f)) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Text(u.nickname, style = MaterialTheme.typography.titleLarge, color = Color.White, maxLines = 1)
                            if (u.isAdmin) {
                                Spacer(Modifier.width(8.dp))
                                Text(
                                    tr("관리자", "Admin"), style = MaterialTheme.typography.labelSmall, color = Color.White,
                                    modifier = Modifier.clip(RoundedCornerShape(50)).background(Color.White.copy(alpha = 0.25f)).padding(horizontal = 8.dp, vertical = 2.dp),
                                )
                            }
                        }
                        Text(u.email, style = MaterialTheme.typography.bodySmall, color = Color.White.copy(alpha = 0.85f), maxLines = 1)
                    }
                    TextButton(onClick = { confirmSignOut = true }) { Text(tr("로그아웃", "Log out"), color = Color.White) }
                }
            }
            Spacer(Modifier.height(20.dp))
        }

        GroupTitle(tr("운동", "Workout"))
        Card {
            Section(tr("체중", "Weight"), tr("칼로리 계산에 써요. 폰에만 저장돼요.", "Used for calories. Kept on this phone only."))
            Row(verticalAlignment = Alignment.CenterVertically) {
                OutlinedTextField(
                    value = weightText,
                    onValueChange = { text ->
                        weightText = text.filter(Char::isDigit).take(3)
                        weightText.toIntOrNull()?.takeIf { it in 25..250 }?.let { Prefs.weightKg = it.toDouble() }
                    },
                    placeholder = { Text("65") },
                    singleLine = true,
                    isError = weightText.isNotEmpty() && !weightValid,
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                    modifier = Modifier.width(120.dp)
                )
                Spacer(Modifier.width(8.dp))
                Text("kg")
            }
            if (weightText.isNotEmpty() && !weightValid) {
                Text(tr("25~250 사이로 입력해 주세요", "Please enter 25 to 250"), color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall)
            } else if (weightText.isEmpty()) {
                Text(tr("비워두면 65kg으로 계산해요", "If left empty, 65 kg is used"), color = MaterialTheme.colorScheme.onSurfaceVariant, style = MaterialTheme.typography.bodySmall)
            }
            Divider()
            Section(tr("음성 안내 주기", "Voice update interval"), tr("이 간격마다 거리, 시간, 페이스를 알려줘요.", "Hear distance, time and pace at this interval."))
            Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                intervals.forEach { (label, value) ->
                    FilterChip(selected = interval == value, onClick = { interval = value; Prefs.cueInterval = value }, label = { Text(label) })
                }
            }
            Divider()
            ToggleRow(tr("음성 안내", "Voice guidance"), tr("운동 중 음성으로 알려줘요. 운동 화면에서도 바꿀 수 있어요.", "Spoken updates during workouts. You can also toggle this on the workout screen."), voiceOn, Prefs::setVoiceEnabled)
            Divider()
            ToggleRow(tr("자동 일시정지", "Auto pause"), tr("멈춰 서면 기록을 잠시 멈추고, 움직이면 다시 시작해요.", "Pauses when you stop and resumes when you move."), autoPause, Prefs::setAutoPause)
        }

        Spacer(Modifier.height(20.dp))
        GroupTitle(tr("기록", "Records"))
        Card {
            val autoRecord by Prefs.autoRecord.collectAsState()
            val setAutoRecord = rememberAutoRecordSwitch()
            ToggleRow(
                tr("자동 기록 · 만보기", "Auto recording · pedometer"),
                tr("앱을 켜지 않아도 하루 걸음 수를 세고, 15분 넘게 걷거나 5분 넘게 달리면 기록에 남겨요. GPS는 쓰지 않아요.", "Counts daily steps without opening the app, and records walks over 15 minutes and runs over 5. No GPS."),
                autoRecord,
                setAutoRecord,
            )
            Divider()
            // After each workout, without a tap. Turning one on asks for its permission there and then.
            Section(tr("운동이 끝나면 자동으로", "When a workout ends"), tr("운동을 마치면 바로 기록해요 (앱을 닫아서 끝낸 운동도). 테스트 운동은 기록하지 않아요.", "Saved right away, even if you end a workout by closing the app. Test runs are skipped."))
            ToggleRow(
                tr("구글 캘린더에 기록", "Add to Google Calendar"),
                tr("폰의 구글 계정 캘린더에 운동 시간, 거리, 페이스, 구간 기록이 일정으로 남아요.", "Time, distance, pace and splits are added to your Google Calendar as an event."),
                autoCalendar,
            ) { on ->
                if (!on) Prefs.setAutoCalendar(false)
                else if (CalendarExport.hasPermission(context)) Prefs.setAutoCalendar(true)
                else calendarPermission.launch(CalendarExport.permissions)
            }
            Spacer(Modifier.height(12.dp))
            ToggleRow(
                tr("삼성헬스에 저장", "Save to Samsung Health"),
                tr("Health Connect를 거쳐 삼성헬스, Google Fit 등에 운동과 경로가 들어가요.", "The workout and route go to Samsung Health, Google Fit and others through Health Connect."),
                autoHealth,
            ) { on ->
                if (!on) Prefs.setAutoHealth(false)
                else when (HealthExport.availability(context)) {
                    HealthExport.Availability.AVAILABLE -> scope.launch {
                        if (HealthExport.missingPermissions(context).isEmpty()) Prefs.setAutoHealth(true)
                        else healthPermission.launch(HealthExport.permissions)
                    }
                    else -> Toast.makeText(context, tr("이 폰에서는 Health Connect를 먼저 설치하거나 업데이트해야 해요", "Install or update Health Connect on this phone first"), Toast.LENGTH_LONG).show()
                }
            }
        }

        Spacer(Modifier.height(20.dp))
        GroupTitle(tr("앱", "App"))
        Card {
            Section("언어 · Language", tr("앱 화면과 음성 안내의 언어예요.", "The language of the app and the voice."))
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                listOf("ko" to "한국어", "en" to "English").forEach { (code, name) ->
                    FilterChip(
                        selected = Prefs.language == code,
                        onClick = {
                            if (Prefs.language != code) {
                                Prefs.language = code
                                (context as? android.app.Activity)?.recreate()
                            }
                        },
                        label = { Text(name) },
                    )
                }
            }
        }
        if (user != null) {
            TextButton(onClick = { deleting = true }, modifier = Modifier.padding(top = 12.dp)) {
                Text(tr("계정 삭제", "Delete account"), color = MaterialTheme.colorScheme.error)
            }
        }
        Spacer(Modifier.height(32.dp))
    }

    if (confirmSignOut) {
        AlertDialog(
            onDismissRequest = { confirmSignOut = false },
            title = { Text(tr("로그아웃할까요?", "Log out?")) },
            text = { Text(tr("운동 기록은 이 폰에 그대로 남아요.", "Your workouts stay on this phone.")) },
            confirmButton = {
                TextButton(onClick = { confirmSignOut = false; scope.launch { Account.logOut() } }) { Text(tr("로그아웃", "Log out")) }
            },
            dismissButton = { TextButton(onClick = { confirmSignOut = false }) { Text(tr("취소", "Cancel")) } }
        )
    }
    if (deleting) DeleteAccountDialog(onDismiss = { deleting = false })
}

/** Account deletion (required by Play for apps with sign-up). Re-asks for the password. */
@Composable
private fun DeleteAccountDialog(onDismiss: () -> Unit) {
    var password by remember { mutableStateOf("") }
    var error by remember { mutableStateOf<String?>(null) }
    var busy by remember { mutableStateOf(false) }
    val scope = rememberCoroutineScope()
    AlertDialog(
        onDismissRequest = { if (!busy) onDismiss() },
        title = { Text(tr("계정을 삭제할까요?", "Delete your account?")) },
        text = {
            Column {
                Text(tr("서버에서 계정이 지워지고 되돌릴 수 없어요. 이 폰의 운동 기록은 남아요.", "Your account will be permanently deleted from the server. Workouts on this phone will remain."))
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
            }
        },
        confirmButton = {
            TextButton(
                enabled = password.isNotEmpty() && !busy,
                onClick = {
                    busy = true
                    scope.launch {
                        try {
                            Account.deleteAccount(password)
                        } catch (e: AuthException) {
                            error = e.message
                        } finally {
                            busy = false
                        }
                    }
                }
            ) { Text(tr("삭제", "Delete"), color = MaterialTheme.colorScheme.error) }
        },
        dismissButton = { TextButton(onClick = onDismiss, enabled = !busy) { Text(tr("취소", "Cancel")) } }
    )
}

@Composable
private fun Section(title: String, description: String) {
    Text(title, style = MaterialTheme.typography.titleMedium)
    Text(description, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
    Spacer(Modifier.height(12.dp))
}

@Composable
internal fun ToggleRow(title: String, description: String, checked: Boolean, onChange: (Boolean) -> Unit) {
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        Column(Modifier.weight(1f)) {
            Text(title, style = MaterialTheme.typography.titleMedium)
            Text(description, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        Spacer(Modifier.width(16.dp))
        Switch(checked = checked, onCheckedChange = onChange)
    }
}

@Composable
private fun Divider() {
    HorizontalDivider(Modifier.padding(vertical = 16.dp), color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.5f))
}

@Composable
private fun GroupTitle(text: String) {
    Text(text, style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.primary, modifier = Modifier.padding(start = 4.dp, bottom = 8.dp))
}

/** Turns 자동 기록 on (asking for activity recognition first, if needed) or off. */
@Composable
fun rememberAutoRecordSwitch(): (Boolean) -> Unit {
    val context = LocalContext.current
    val permission = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        if (granted) {
            Prefs.setAutoRecord(true)
            PassiveRecorder.apply(context)
            Toast.makeText(context, tr("자동 기록을 켰어요. 설정에서 언제든 끌 수 있어요", "Auto recording is on. You can turn it off in Settings any time"), Toast.LENGTH_LONG).show()
        } else {
            Toast.makeText(context, tr("신체 활동 권한을 허용해야 걸음과 걷기를 기록할 수 있어요", "The Physical activity permission is needed to record steps and walks"), Toast.LENGTH_LONG).show()
        }
    }
    return { on ->
        if (on && !PassiveRecorder.hasPermission(context) && Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            permission.launch(Manifest.permission.ACTIVITY_RECOGNITION)
        } else {
            Prefs.setAutoRecord(on)
            PassiveRecorder.apply(context)
            if (on) Toast.makeText(context, tr("자동 기록을 켰어요. 설정에서 언제든 끌 수 있어요", "Auto recording is on. You can turn it off in Settings any time"), Toast.LENGTH_LONG).show()
        }
    }
}
