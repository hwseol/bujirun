package com.mhmh2.bujirun.net

import com.mhmh2.bujirun.BuildConfig
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL

/** A failure to show the user: the server's own Korean message, or a network one. */
class ApiException(message: String, val status: Int = 0) : Exception(message)

/**
 * The Bujirun server (backend/server.py). Plain HttpURLConnection + org.json: a handful of small
 * JSON calls don't justify an HTTP/JSON library. Errors come back as `{"detail": "..."}` in
 * Korean, ready to show.
 */
object Api {
    private val base = BuildConfig.API_BASE.trimEnd('/')

    suspend fun post(path: String, body: JSONObject?, token: String? = null): JSONObject = request("POST", path, body, token)

    private suspend fun request(method: String, path: String, body: JSONObject?, token: String?): JSONObject =
        withContext(Dispatchers.IO) {
            val offline = "서버에 연결하지 못했어요. 인터넷 연결을 확인해 주세요."
            val conn = try {
                (URL(base + path).openConnection() as HttpURLConnection).apply {
                    requestMethod = method
                    connectTimeout = 10_000
                    readTimeout = 20_000
                    setRequestProperty("Accept", "application/json")
                    token?.let { setRequestProperty("Authorization", it) }
                    if (body != null) {
                        doOutput = true
                        setRequestProperty("Content-Type", "application/json; charset=utf-8")
                        outputStream.use { it.write(body.toString().toByteArray(Charsets.UTF_8)) }
                    }
                }
            } catch (e: IOException) {
                throw ApiException(offline)
            }
            try {
                val code = conn.responseCode
                val text = (if (code in 200..299) conn.inputStream else conn.errorStream)
                    ?.bufferedReader(Charsets.UTF_8)?.use { it.readText() }.orEmpty()
                if (code in 200..299) return@withContext JSONObject(text.ifEmpty { "{}" })
                val detail = runCatching { JSONObject(text).optString("detail") }.getOrNull()
                throw ApiException(
                    when {
                        code == 429 -> "시도가 너무 많아요. 잠시 후 다시 해 주세요."
                        !detail.isNullOrBlank() -> detail
                        else -> "요청을 처리하지 못했어요. ($code)"
                    },
                    code,
                )
            } catch (e: IOException) {
                throw ApiException(offline)
            } finally {
                conn.disconnect()
            }
        }
}
