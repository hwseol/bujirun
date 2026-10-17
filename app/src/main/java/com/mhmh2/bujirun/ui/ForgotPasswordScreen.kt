package com.mhmh2.bujirun.ui

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import com.mhmh2.bujirun.account.Account
import com.mhmh2.bujirun.account.AccountRules
import com.mhmh2.bujirun.account.AuthApi
import com.mhmh2.bujirun.account.AuthException
import com.mhmh2.bujirun.account.GoogleSignIn
import com.mhmh2.bujirun.tr
import kotlinx.coroutines.launch

/**
 * 비밀번호 찾기: a 6-digit code emailed by the server, then a new password (which also signs
 * in). A Gmail address can skip all that with Google sign-in, which opens the same account.
 */
@Composable
fun ForgotPasswordScreen(initialEmail: String, onClose: () -> Unit) {
    var email by rememberSaveable { mutableStateOf(initialEmail) }
    var codeSent by rememberSaveable { mutableStateOf(false) }
    var code by rememberSaveable { mutableStateOf("") }
    var password by rememberSaveable { mutableStateOf("") }
    var again by rememberSaveable { mutableStateOf("") }
    var error by rememberSaveable { mutableStateOf<String?>(null) }
    var busy by rememberSaveable { mutableStateOf(false) }
    // Null until the server says whether it can send email.
    var byEmail by rememberSaveable { mutableStateOf<Boolean?>(null) }
    val scope = rememberCoroutineScope()
    val context = LocalContext.current
    LaunchedEffect(Unit) { byEmail = runCatching { AuthApi.resetByEmail() }.getOrDefault(true) }

    fun run(block: suspend () -> Unit) {
        busy = true
        error = null
        scope.launch {
            try { block() } catch (e: AuthException) { error = e.message } finally { busy = false }
        }
    }

    Column(Modifier.fillMaxSize().safeDrawingPadding().imePadding().verticalScroll(rememberScrollState()).padding(horizontal = 24.dp, vertical = 12.dp)) {
        IconButton(onClick = onClose, modifier = Modifier.padding(start = 0.dp)) { Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = tr("뒤로", "Back")) }
        Spacer(Modifier.height(8.dp))
        Text(tr("비밀번호 찾기", "Reset your password"), style = MaterialTheme.typography.headlineMedium, fontWeight = FontWeight.ExtraBold)
        Text(
            if (!codeSent) tr("가입한 이메일로 인증 코드를 보내 드려요.", "We'll email a code to the address you signed up with.")
            else tr("$email 로 보낸 6자리 코드와 새 비밀번호를 입력해 주세요. 메일이 안 보이면 스팸함도 확인해 주세요.", "Enter the 6-digit code sent to $email and a new password. Check your spam folder if it isn't there."),
            style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(top = 4.dp),
        )
        Spacer(Modifier.height(24.dp))

        if (!codeSent) {
            OutlinedTextField(
                value = email, onValueChange = { email = it.trim(); error = null },
                label = { Text(tr("이메일", "Email")) }, singleLine = true,
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Email),
                modifier = Modifier.fillMaxWidth(),
            )
            error?.let { Text(it, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodyMedium, modifier = Modifier.padding(top = 4.dp)) }
            Spacer(Modifier.height(16.dp))
            if (byEmail != false) {
                Button(
                    onClick = { run { AuthApi.requestReset(AccountRules.normalizeEmail(email)); codeSent = true } },
                    enabled = AccountRules.isValidEmail(email) && !busy,
                    modifier = Modifier.fillMaxWidth().height(56.dp),
                ) { BusyText(busy, tr("인증 코드 받기", "Send code")) }
            } else {
                Text(
                    tr("지금은 이메일로 재설정할 수 없어요. 아래 구글 로그인을 쓰거나 운영자에게 문의해 주세요.", "Email reset isn't available right now. Use Google below or contact the admin."),
                    style = MaterialTheme.typography.bodyMedium,
                )
            }
        } else {
            OutlinedTextField(
                value = code, onValueChange = { code = it.filter(Char::isDigit).take(6); error = null },
                label = { Text(tr("인증 코드 6자리", "6-digit code")) }, singleLine = true,
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.NumberPassword),
                modifier = Modifier.fillMaxWidth(),
            )
            OutlinedTextField(
                value = password, onValueChange = { password = it; error = null },
                label = { Text(tr("새 비밀번호 (8자 이상)", "New password (8+ characters)")) }, singleLine = true,
                visualTransformation = PasswordVisualTransformation(),
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password),
                modifier = Modifier.fillMaxWidth().padding(top = 8.dp),
            )
            OutlinedTextField(
                value = again, onValueChange = { again = it; error = null },
                label = { Text(tr("새 비밀번호 확인", "Confirm new password")) }, singleLine = true,
                visualTransformation = PasswordVisualTransformation(),
                isError = again.isNotEmpty() && again != password,
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password),
                modifier = Modifier.fillMaxWidth().padding(top = 8.dp),
            )
            error?.let { Text(it, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodyMedium, modifier = Modifier.padding(top = 4.dp)) }
            Spacer(Modifier.height(16.dp))
            Button(
                onClick = { run { Account.resetPassword(email, code, password) } },
                enabled = code.length == 6 && AccountRules.isValidPassword(password) && password == again && !busy,
                modifier = Modifier.fillMaxWidth().height(56.dp),
            ) { BusyText(busy, tr("비밀번호 바꾸고 로그인", "Change password and log in")) }
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                TextButton(onClick = { run { AuthApi.requestReset(AccountRules.normalizeEmail(email)) } }, enabled = !busy) { Text(tr("코드 다시 받기", "Resend code")) }
                TextButton(onClick = { codeSent = false; code = ""; error = null }, enabled = !busy) { Text(tr("이메일 바꾸기", "Change email")) }
            }
        }

        if (GoogleSignIn.available) {
            Spacer(Modifier.height(28.dp))
            Text(tr("구글(Gmail) 계정으로 가입하셨나요?", "Signed up with a Gmail address?"), style = MaterialTheme.typography.titleSmall)
            Text(
                tr("같은 이메일이면 '구글로 계속하기'로 바로 들어갈 수 있어요. 그다음 내 계정에서 새 비밀번호를 정하면 돼요.", "Continue with Google opens the same account. Then set a new password in My account."),
                style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Spacer(Modifier.height(10.dp))
            OutlinedButton(
                onClick = { run { GoogleSignIn.signIn(context) } }, enabled = !busy,
                modifier = Modifier.fillMaxWidth().height(52.dp),
            ) {
                Text("G", fontWeight = FontWeight.ExtraBold, color = Color(0xFF4285F4))
                Spacer(Modifier.size(10.dp))
                Text(tr("구글로 계속하기", "Continue with Google"))
            }
        }
    }
}

@Composable
private fun BusyText(busy: Boolean, text: String) {
    Box(contentAlignment = Alignment.Center) {
        if (busy) CircularProgressIndicator(Modifier.size(22.dp), strokeWidth = 2.dp) else Text(text)
    }
}
