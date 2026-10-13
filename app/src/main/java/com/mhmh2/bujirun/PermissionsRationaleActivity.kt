package com.mhmh2.bujirun

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.mhmh2.bujirun.ui.theme.BujirunTheme

/**
 * What Health Connect shows when the user asks why this app wants health permissions. Android
 * requires an app to provide this screen before it can request Health Connect permissions.
 */
class PermissionsRationaleActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent {
            BujirunTheme {
                Surface(Modifier.fillMaxSize()) {
                    Column(Modifier.safeDrawingPadding().verticalScroll(rememberScrollState()).padding(24.dp)) {
                        Text(tr("부지런의 건강 데이터 사용", "How Bujirun uses health data"), style = MaterialTheme.typography.headlineSmall)
                        Spacer(Modifier.height(16.dp))
                        Text(
                            tr("부지런은 사용자가 '삼성헬스에 저장'을 누른 운동 기록만 Health Connect에 씁니다. ", "Bujirun writes to Health Connect only the workouts you choose to save there. ") +
                                tr("운동 종류(러닝/걷기), 시작·종료 시각, 거리, 소모 칼로리, 이동 경로가 저장됩니다.\n\n", "It saves the type (run/walk), start and end time, distance, calories burned and the route.\n\n") +
                                tr("Health Connect의 데이터를 읽지 않으며, 어떤 건강 데이터도 서버로 보내지 않습니다. ", "Apart from step counts you allow, it doesn't read Health Connect data, and sends no health data to any server. ") +
                                tr("모든 운동 기록은 이 폰에만 저장됩니다.\n\n", "All workouts are stored only on this phone.\n\n") +
                                tr("권한은 언제든 Health Connect 설정에서 끌 수 있습니다.", "You can turn the permissions off at any time in Health Connect settings."),
                            style = MaterialTheme.typography.bodyLarge
                        )
                    }
                }
            }
        }
    }
}
