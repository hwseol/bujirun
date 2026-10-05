package com.mhmh2.bujirun.account

import com.mhmh2.bujirun.BuildConfig
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL

/** A failure to show the user: the server's own Korean message, or a network one. */
class AuthException(message: String) : Exception(message)

data class AuthResult(val token: String, val user: User)

/**
 * The Bujirun server's account endpoints (backend/server.py). Plain HttpURLConnection + org.json:
 * five small calls don't justify an HTTP/JSON library.
 */
object AuthApi {
    private val base = BuildConfig.API_BASE.trimEnd('/')

    suspend fun signUp(email: String, nickname: String, password: String): AuthResult =
        authCall("/auth/signup", JSONObject().put("email", email).put("nickname", nickname).put("password", password))

    suspend fun logIn(email: String, password: String): AuthResult =
        authCall("/auth/login", JSONObject().put("email", email).put("password", password))

    /** Best effort: the local sign-out happens regardless. */
    suspend fun logOut(token: String) {
        runCatching { request("POST", "/auth/logout", null, token) }
    }

    suspend fun deleteAccount(email: String, password: String) {
        request("POST", "/auth/delete", JSONObject().put("email", email).put("password", password), null)
    }

    private suspend fun authCall(path: String, body: JSONObject): AuthResult {
        val json = request("POST", path, body, null)
        return AuthResult(
            token = json.getString("token"),
            user = User(json.getString("email"), json.getString("nickname"), json.optBoolean("is_admin", false)),
        )
    }

    private suspend fun request(method: String, path: String, body: JSONObject?, token: String?): JSONObject =
        withContext(Dispatchers.IO) {
            val conn = try {
                (URL(base + path).openConnection() as HttpURLConnection).apply {
                    requestMethod = method
                    connectTimeout = 10_000
                    readTimeout = 15_000
                    setRequestProperty("Accept", "application/json")
                    token?.let { setRequestProperty("Authorization", it) }
                    if (body != null) {
                        doOutput = true
                        setRequestProperty("Content-Type", "application/json; charset=utf-8")
                        outputStream.use { it.write(body.toString().toByteArray(Charsets.UTF_8)) }
                    }
                }
            } catch (e: IOException) {
                throw AuthException("서버에 연결하지 못했어요. 인터넷 연결을 확인해 주세요.")
            }
            try {
                val code = conn.responseCode
                val text = (if (code in 200..299) conn.inputStream else conn.errorStream)
                    ?.bufferedReader(Charsets.UTF_8)?.use { it.readText() }.orEmpty()
                if (code in 200..299) return@withContext JSONObject(text.ifEmpty { "{}" })
                val detail = runCatching { JSONObject(text).optString("detail") }.getOrNull()
                throw AuthException(
                    when {
                        code == 429 -> "시도가 너무 많아요. 잠시 후 다시 해 주세요."
                        !detail.isNullOrBlank() -> detail
                        else -> "요청을 처리하지 못했어요. ($code)"
                    }
                )
            } catch (e: IOException) {
                throw AuthException("서버에 연결하지 못했어요. 인터넷 연결을 확인해 주세요.")
            } finally {
                conn.disconnect()
            }
        }
}
