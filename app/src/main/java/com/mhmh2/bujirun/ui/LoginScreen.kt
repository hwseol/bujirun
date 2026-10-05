package com.mhmh2.bujirun.ui

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import com.mhmh2.bujirun.account.Account
import com.mhmh2.bujirun.account.AccountRules

@Composable
fun LoginScreen() {
    var email by rememberSaveable { mutableStateOf("") }
    var nickname by rememberSaveable { mutableStateOf("") }
    // Errors appear after the first attempt, not while the user is still typing.
    var attempted by rememberSaveable { mutableStateOf(false) }
    val emailOk = AccountRules.isValidEmail(email)
    val nicknameOk = AccountRules.isValidNickname(nickname)

    fun submit() {
        attempted = true
        if (emailOk && nicknameOk) Account.signIn(email, nickname)
    }

    Column(
        Modifier.fillMaxSize().safeDrawingPadding().imePadding().verticalScroll(rememberScrollState()).padding(24.dp)
    ) {
        Spacer(Modifier.height(48.dp))
        Text("부지런", style = MaterialTheme.typography.headlineLarge)
        Text(
            "짬 날 때, 이어폰과 폰만 들고",
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
        Spacer(Modifier.height(48.dp))

        OutlinedTextField(
            value = email,
            onValueChange = { email = it.trim() },
            label = { Text("이메일") },
            placeholder = { Text("name@example.com") },
            singleLine = true,
            isError = attempted && !emailOk,
            supportingText = { if (attempted && !emailOk) Text("이메일 형식으로 입력해 주세요") },
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Email, imeAction = ImeAction.Next),
            modifier = Modifier.fillMaxWidth()
        )
        Spacer(Modifier.height(8.dp))
        OutlinedTextField(
            value = nickname,
            onValueChange = { nickname = it.take(20) },
            label = { Text("닉네임") },
            placeholder = { Text("달리는 사람") },
            singleLine = true,
            isError = attempted && !nicknameOk,
            supportingText = { if (attempted && !nicknameOk) Text("2~12자로 입력해 주세요") },
            keyboardOptions = KeyboardOptions(imeAction = ImeAction.Done),
            keyboardActions = KeyboardActions(onDone = { submit() }),
            modifier = Modifier.fillMaxWidth()
        )
        Spacer(Modifier.height(24.dp))
        Button(onClick = ::submit, modifier = Modifier.fillMaxWidth().height(56.dp)) { Text("시작하기") }
        Spacer(Modifier.height(16.dp))
        Text(
            "계정 정보와 운동 기록은 이 폰에만 저장돼요.",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
    }
}
