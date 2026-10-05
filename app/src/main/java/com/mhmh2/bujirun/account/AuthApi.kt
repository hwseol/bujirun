package com.mhmh2.bujirun.account

import com.mhmh2.bujirun.net.Api
import com.mhmh2.bujirun.net.ApiException
import org.json.JSONObject

/** Kept as the name the account UI already catches. */
typealias AuthException = ApiException

data class AuthResult(val token: String, val user: User)

/** The server's account endpoints. */
object AuthApi {
    suspend fun signUp(email: String, nickname: String, password: String): AuthResult =
        authCall("/auth/signup", JSONObject().put("email", email).put("nickname", nickname).put("password", password))

    suspend fun logIn(email: String, password: String): AuthResult =
        authCall("/auth/login", JSONObject().put("email", email).put("password", password))

    /** Best effort: the local sign-out happens regardless. */
    suspend fun logOut(token: String) {
        runCatching { Api.post("/auth/logout", null, token) }
    }

    suspend fun deleteAccount(email: String, password: String) {
        Api.post("/auth/delete", JSONObject().put("email", email).put("password", password))
    }

    private suspend fun authCall(path: String, body: JSONObject): AuthResult {
        val json = Api.post(path, body)
        return AuthResult(
            token = json.getString("token"),
            user = User(json.getString("email"), json.getString("nickname"), json.optBoolean("is_admin", false)),
        )
    }
}
