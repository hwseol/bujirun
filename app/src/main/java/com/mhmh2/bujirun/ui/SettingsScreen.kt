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
import androidx.compose.material3.FilterChip
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

    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(horizontal = 24.dp)) {
        Text("설정", style = MaterialTheme.typography.headlineSmall, modifier = Modifier.padding(top = 24.dp, bottom = 16.dp))

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
        Spacer(Modifier.height(32.dp))
    }
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
