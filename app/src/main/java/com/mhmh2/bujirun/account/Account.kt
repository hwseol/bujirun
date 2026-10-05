package com.mhmh2.bujirun.account

import android.content.Context
import android.content.SharedPreferences
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/** [isAdmin] comes from the server (admin email + correct password), never decided locally. */
data class User(val email: String, val nickname: String, val isAdmin: Boolean = false)

/** Input rules, checked in the app before asking the server (which enforces the same ones). */
object AccountRules {
    // Deliberately simple: something@something.tld, no spaces. Must match backend/accounts.py.
    private val EMAIL = Regex("^[A-Za-z0-9._%+-]+@[A-Za-z0-9-]+(\\.[A-Za-z0-9-]+)*\\.[A-Za-z]{2,}$")
    const val MIN_PASSWORD = 8

    fun normalizeEmail(input: String) = input.trim().lowercase()

    fun isValidEmail(input: String) = EMAIL.matches(normalizeEmail(input))

    fun normalizeNickname(input: String) = input.trim().replace(Regex("\\s+"), " ")

    /** 2-12 characters after trimming. */
    fun isValidNickname(input: String) = normalizeNickname(input).length in 2..12

    fun isValidPassword(input: String) = input.length >= MIN_PASSWORD
}

/**
 * The signed-in account: the server-issued session token plus who it belongs to, kept on the
 * phone so the app works offline once signed in. Accounts themselves live on the server
 * (unique email and nickname, password-protected).
 */
object Account {
    private lateinit var sp: SharedPreferences
    private val _user = MutableStateFlow<User?>(null)
    val user: StateFlow<User?> = _user.asStateFlow()
    val token: String? get() = sp.getString("token", null)

    fun init(context: Context) {
        if (::sp.isInitialized) return
        sp = context.applicationContext.getSharedPreferences("account", Context.MODE_PRIVATE)
        val email = sp.getString("email", null)
        val nickname = sp.getString("nickname", null)
        // No token = an account from the earlier local-only build: it never existed on the
        // server, so it has to sign up/in again.
        _user.value = if (email != null && nickname != null && token != null) {
            User(email, nickname, sp.getBoolean("is_admin", false))
        } else {
            sp.edit().clear().apply()
            null
        }
    }

    suspend fun signUp(email: String, nickname: String, password: String) = store(
        AuthApi.signUp(AccountRules.normalizeEmail(email), AccountRules.normalizeNickname(nickname), password)
    )

    suspend fun logIn(email: String, password: String) = store(AuthApi.logIn(AccountRules.normalizeEmail(email), password))

    suspend fun logOut() {
        token?.let { AuthApi.logOut(it) }
        clear()
    }

    suspend fun deleteAccount(password: String) {
        val u = _user.value ?: return
        AuthApi.deleteAccount(u.email, password)
        clear()
    }

    private fun store(result: AuthResult) {
        sp.edit()
            .putString("token", result.token)
            .putString("email", result.user.email)
            .putString("nickname", result.user.nickname)
            .putBoolean("is_admin", result.user.isAdmin)
            .apply()
        _user.value = result.user
    }

    private fun clear() {
        sp.edit().clear().apply()
        _user.value = null
    }
}
