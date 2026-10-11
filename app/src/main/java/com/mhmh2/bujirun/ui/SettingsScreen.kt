package com.mhmh2.bujirun.ui

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

private val intervals = listOf(
    "500m" to CueInterval.EveryDistance(500.0),
    "1km" to CueInterval.EveryDistance(1000.0),
    "5분" to CueInterval.EveryTime(5 * 60_000L),
    "10분" to CueInterval.EveryTime(10 * 60_000L),
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
        else Toast.makeText(context, "캘린더 권한을 허용해야 기록할 수 있어요", Toast.LENGTH_LONG).show()
    }
    val healthPermission = rememberLauncherForActivityResult(PermissionController.createRequestPermissionResultContract()) { granted ->
        if (granted.containsAll(HealthExport.required)) Prefs.setAutoHealth(true)
        else Toast.makeText(context, "권한을 허용해야 삼성헬스에 저장할 수 있어요", Toast.LENGTH_LONG).show()
    }

    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(horizontal = 24.dp)) {
        Text("설정", style = MaterialTheme.typography.headlineSmall, modifier = Modifier.padding(top = 24.dp, bottom = 16.dp))

        user?.let { u ->
            Row(verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text(u.nickname, style = MaterialTheme.typography.titleMedium)
                        if (u.isAdmin) {
                            Spacer(Modifier.width(8.dp))
                            Text(
                                "관리자",
                                style = MaterialTheme.typography.labelMedium,
                                color = MaterialTheme.colorScheme.primary
                            )
                        }
                    }
                    Text(u.email, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
                TextButton(onClick = { confirmSignOut = true }) { Text("로그아웃") }
            }
            Divider()
        }

        Section("체중", "칼로리 계산에 써요. 폰에만 저장돼요.")
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
            Text("25~250 사이로 입력해 주세요", color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall)
        } else if (weightText.isEmpty()) {
            Text("비워두면 65kg으로 계산해요", color = MaterialTheme.colorScheme.onSurfaceVariant, style = MaterialTheme.typography.bodySmall)
        }
        Divider()

        Section("음성 안내 주기", "이 간격마다 거리, 시간, 페이스를 알려줘요.")
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            intervals.forEach { (label, value) ->
                FilterChip(
                    selected = interval == value,
                    onClick = { interval = value; Prefs.cueInterval = value },
                    label = { Text(label) }
                )
            }
        }
        Divider()

        ToggleRow("음성 안내", "운동 중 음성으로 알려줘요. 운동 화면에서도 바꿀 수 있어요.", voiceOn, Prefs::setVoiceEnabled)
        Divider()
        ToggleRow("자동 일시정지", "멈춰 서면 기록을 잠시 멈추고, 움직이면 다시 시작해요.", autoPause, Prefs::setAutoPause)
        Divider()

        val autoRecord by Prefs.autoRecord.collectAsState()
        val setAutoRecord = rememberAutoRecordSwitch()
        ToggleRow(
            "자동 기록 · 만보기",
            "앱을 켜지 않아도 하루 걸음 수를 세고, 15분 넘게 걷거나 5분 넘게 달리면 기록에 남겨요. GPS는 쓰지 않아요.",
            autoRecord,
            setAutoRecord,
        )
        Divider()

        // After each workout, without a tap. Turning one on asks for its permission there and then.
        Section("운동이 끝나면 자동으로", "운동을 마치면 바로 기록해요 (앱을 닫아서 끝낸 운동도). 테스트 운동은 기록하지 않아요.")
        ToggleRow(
            "구글 캘린더에 기록",
            "폰의 구글 계정 캘린더에 운동 시간, 거리, 페이스, 구간 기록이 일정으로 남아요.",
            autoCalendar,
        ) { on ->
            if (!on) Prefs.setAutoCalendar(false)
            else if (CalendarExport.hasPermission(context)) Prefs.setAutoCalendar(true)
            else calendarPermission.launch(CalendarExport.permissions)
        }
        ToggleRow(
            "삼성헬스에 저장",
            "Health Connect를 거쳐 삼성헬스, Google Fit 등에 운동과 경로가 들어가요.",
            autoHealth,
        ) { on ->
            if (!on) Prefs.setAutoHealth(false)
            else when (HealthExport.availability(context)) {
                HealthExport.Availability.AVAILABLE -> scope.launch {
                    if (HealthExport.missingPermissions(context).isEmpty()) Prefs.setAutoHealth(true)
                    else healthPermission.launch(HealthExport.permissions)
                }
                else -> Toast.makeText(context, "이 폰에서는 Health Connect를 먼저 설치하거나 업데이트해야 해요", Toast.LENGTH_LONG).show()
            }
        }
        if (user != null) {
            Divider()
            TextButton(onClick = { deleting = true }, contentPadding = PaddingValues(0.dp)) {
                Text("계정 삭제", color = MaterialTheme.colorScheme.error)
            }
        }
        Spacer(Modifier.height(32.dp))
    }

    if (confirmSignOut) {
        AlertDialog(
            onDismissRequest = { confirmSignOut = false },
            title = { Text("로그아웃할까요?") },
            text = { Text("운동 기록은 이 폰에 그대로 남아요.") },
            confirmButton = {
                TextButton(onClick = { confirmSignOut = false; scope.launch { Account.logOut() } }) { Text("로그아웃") }
            },
            dismissButton = { TextButton(onClick = { confirmSignOut = false }) { Text("취소") } }
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
        title = { Text("계정을 삭제할까요?") },
        text = {
            Column {
                Text("서버에서 계정이 지워지고 되돌릴 수 없어요. 이 폰의 운동 기록은 남아요.")
                Spacer(Modifier.height(12.dp))
                OutlinedTextField(
                    value = password,
                    onValueChange = { password = it; error = null },
                    label = { Text("비밀번호") },
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
            ) { Text("삭제", color = MaterialTheme.colorScheme.error) }
        },
        dismissButton = { TextButton(onClick = onDismiss, enabled = !busy) { Text("취소") } }
    )
}

@Composable
private fun Section(title: String, description: String) {
    Text(title, style = MaterialTheme.typography.titleMedium)
    Text(description, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
    Spacer(Modifier.height(12.dp))
}

@Composable
private fun ToggleRow(title: String, description: String, checked: Boolean, onChange: (Boolean) -> Unit) {
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
    HorizontalDivider(Modifier.padding(vertical = 20.dp))
}

/** Turns 자동 기록 on (asking for activity recognition first, if needed) or off. */
@Composable
fun rememberAutoRecordSwitch(): (Boolean) -> Unit {
    val context = LocalContext.current
    val permission = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        if (granted) {
            Prefs.setAutoRecord(true)
            PassiveRecorder.apply(context)
        } else {
            Toast.makeText(context, "신체 활동 권한을 허용해야 걸음과 걷기를 기록할 수 있어요", Toast.LENGTH_LONG).show()
        }
    }
    return { on ->
        if (on && !PassiveRecorder.hasPermission(context) && Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            permission.launch(Manifest.permission.ACTIVITY_RECOGNITION)
        } else {
            Prefs.setAutoRecord(on)
            PassiveRecorder.apply(context)
        }
    }
}
