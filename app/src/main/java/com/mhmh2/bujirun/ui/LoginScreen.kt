package com.mhmh2.bujirun.ui

import com.mhmh2.bujirun.tr

import androidx.compose.foundation.layout.Arrangement
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
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import com.mhmh2.bujirun.account.Account
import com.mhmh2.bujirun.account.AccountRules
import com.mhmh2.bujirun.account.AuthException
import com.mhmh2.bujirun.account.GoogleSignIn
import kotlinx.coroutines.launch

/** Log in, or sign up (email + nickname + password). Both go to the server. */
@Composable
fun LoginScreen() {
    var signUp by rememberSaveable { mutableStateOf(false) }
    var email by rememberSaveable { mutableStateOf("") }
    var nickname by rememberSaveable { mutableStateOf("") }
    var password by rememberSaveable { mutableStateOf("") }
    var passwordAgain by rememberSaveable { mutableStateOf("") }
    // Field errors appear after the first attempt, not while the user is still typing.
    var attempted by rememberSaveable { mutableStateOf(false) }
    var serverError by rememberSaveable { mutableStateOf<String?>(null) }
    var busy by rememberSaveable { mutableStateOf(false) }
    val scope = rememberCoroutineScope()
    val context = androidx.compose.ui.platform.LocalContext.current

    val emailOk = AccountRules.isValidEmail(email)
    val nicknameOk = AccountRules.isValidNickname(nickname)
    val passwordOk = if (signUp) AccountRules.isValidPassword(password) else password.isNotEmpty()
    val againOk = password == passwordAgain

    fun submit() {
        attempted = true
        serverError = null
        if (!emailOk || !passwordOk || (signUp && (!nicknameOk || !againOk)) || busy) return
        busy = true
        scope.launch {
            try {
                if (signUp) Account.signUp(email, nickname, password) else Account.logIn(email, password)
            } catch (e: AuthException) {
                serverError = e.message
            } finally {
                busy = false
            }
        }
    }

    Column(
        Modifier.fillMaxSize().safeDrawingPadding().imePadding().verticalScroll(rememberScrollState()).padding(24.dp)
    ) {
        Spacer(Modifier.height(40.dp))
        Text(tr("부지런", "Buji Run"), style = MaterialTheme.typography.headlineLarge)
        Text(
            if (signUp) tr("계정을 만들어요", "Create your account") else tr("다시 오셨네요", "Welcome back"),
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
        Spacer(Modifier.height(32.dp))

        OutlinedTextField(
            value = email,
            onValueChange = { email = it.trim(); serverError = null },
            label = { Text(tr("이메일", "Email")) },
            placeholder = { Text("name@example.com") },
            singleLine = true,
            isError = attempted && !emailOk,
            supportingText = { if (attempted && !emailOk) Text(tr("이메일 형식으로 입력해 주세요", "Please enter an email address")) },
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Email, imeAction = ImeAction.Next),
            modifier = Modifier.fillMaxWidth()
        )
        if (signUp) {
            OutlinedTextField(
                value = nickname,
                onValueChange = { nickname = it.take(20); serverError = null },
                label = { Text(tr("닉네임", "Nickname")) },
                placeholder = { Text(tr("달리는 사람", "Runner")) },
                singleLine = true,
                isError = attempted && !nicknameOk,
                supportingText = { if (attempted && !nicknameOk) Text(tr("2~12자로 입력해 주세요", "2 to 12 characters, please")) },
                keyboardOptions = KeyboardOptions(imeAction = ImeAction.Next),
                modifier = Modifier.fillMaxWidth()
            )
        }
        OutlinedTextField(
            value = password,
            onValueChange = { password = it; serverError = null },
            label = { Text(tr("비밀번호", "Password")) },
            singleLine = true,
            visualTransformation = PasswordVisualTransformation(),
            isError = attempted && !passwordOk,
            supportingText = {
                when {
                    attempted && !passwordOk -> Text(if (signUp) tr("8자 이상으로 입력해 주세요", "At least 8 characters, please") else tr("비밀번호를 입력해 주세요", "Please enter your password"))
                    signUp -> Text(tr("8자 이상", "At least 8 characters"))
                }
            },
            keyboardOptions = KeyboardOptions(
                keyboardType = KeyboardType.Password,
                imeAction = if (signUp) ImeAction.Next else ImeAction.Done
            ),
            keyboardActions = KeyboardActions(onDone = { submit() }),
            modifier = Modifier.fillMaxWidth()
        )
        if (signUp) {
            OutlinedTextField(
                value = passwordAgain,
                onValueChange = { passwordAgain = it; serverError = null },
                label = { Text(tr("비밀번호 확인", "Confirm password")) },
                singleLine = true,
                visualTransformation = PasswordVisualTransformation(),
                isError = attempted && !againOk,
                supportingText = { if (attempted && !againOk) Text(tr("비밀번호가 서로 달라요", "The passwords don't match")) },
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password, imeAction = ImeAction.Done),
                keyboardActions = KeyboardActions(onDone = { submit() }),
                modifier = Modifier.fillMaxWidth()
            )
        }

        serverError?.let {
            Text(it, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodyMedium, modifier = Modifier.padding(top = 4.dp))
        }
        Spacer(Modifier.height(16.dp))
        Button(onClick = ::submit, enabled = !busy, modifier = Modifier.fillMaxWidth().height(56.dp)) {
            Box(contentAlignment = Alignment.Center) {
                if (busy) CircularProgressIndicator(Modifier.size(22.dp), strokeWidth = 2.dp)
                else Text(if (signUp) tr("가입하기", "Sign up") else tr("로그인", "Log in"))
            }
        }
        if (GoogleSignIn.available) {
            Spacer(Modifier.height(10.dp))
            androidx.compose.material3.OutlinedButton(
                onClick = {
                    busy = true
                    serverError = null
                    scope.launch {
                        try {
                            GoogleSignIn.signIn(context)
                        } catch (e: AuthException) {
                            serverError = e.message
                        } finally {
                            busy = false
                        }
                    }
                },
                enabled = !busy,
                modifier = Modifier.fillMaxWidth().height(56.dp),
            ) {
                Text("G", fontWeight = androidx.compose.ui.text.font.FontWeight.ExtraBold, color = androidx.compose.ui.graphics.Color(0xFF4285F4))
                Spacer(Modifier.size(10.dp))
                Text(tr("구글로 계속하기", "Continue with Google"))
            }
        }
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.Center, verticalAlignment = Alignment.CenterVertically) {
            Text(
                if (signUp) tr("이미 계정이 있나요?", "Already have an account?") else tr("처음이신가요?", "New here?"),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            TextButton(onClick = {
                signUp = !signUp
                attempted = false
                serverError = null
            }) { Text(if (signUp) tr("로그인", "Log in") else tr("가입하기", "Sign up")) }
        }
        if (!signUp) {
            Text(
                tr("비밀번호를 잊으셨다면 운영자에게 문의해 주세요.", "Forgot your password? Contact the admin."),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.fillMaxWidth(),
            )
        }
    }
}
