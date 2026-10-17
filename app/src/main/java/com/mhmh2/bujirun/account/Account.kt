package com.mhmh2.bujirun.account

import android.content.Context
import android.content.SharedPreferences
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/** [isAdmin] comes from the server (admin email + correct password), never decided locally. */
data class User(
    val email: String,
    val nickname: String,
    val isAdmin: Boolean = false,
    // False for an account made with Google sign-in (it has no password to re-enter).
    val hasPassword: Boolean = true,
    val createdAt: String? = null, // ISO time, from the server
)

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
            User(email, nickname, sp.getBoolean("is_admin", false), sp.getBoolean("has_password", true), sp.getString("created_at", null))
        } else {
            sp.edit().clear().apply()
            null
        }
    }

    suspend fun signUp(email: String, nickname: String, password: String) = store(
        AuthApi.signUp(AccountRules.normalizeEmail(email), AccountRules.normalizeNickname(nickname), password)
    )

    suspend fun logIn(email: String, password: String) = store(AuthApi.logIn(AccountRules.normalizeEmail(email), password))

    /** Google sign-in (or sign-up): [idToken] from Credential Manager, checked by the server. */
    suspend fun googleLogIn(idToken: String) = store(AuthApi.google(idToken))

    suspend fun changeNickname(nickname: String) {
        val t = token ?: return
        val u = AuthApi.changeNickname(t, AccountRules.normalizeNickname(nickname))
        save(u)
    }

    /** A new password with the code emailed by the server; signs in. */
    suspend fun resetPassword(email: String, code: String, password: String) =
        store(AuthApi.confirmReset(AccountRules.normalizeEmail(email), code.trim(), password))

    /** [current] is null for a Google-made account adding its first password. */
    suspend fun changePassword(current: String?, password: String) {
        val t = token ?: return
        save(AuthApi.changePassword(t, current, password))
    }

    suspend fun logOut() {
        token?.let { AuthApi.logOut(it) }
        clear()
    }

    /**
     * Checks the stored session is still valid on the server (the account may have been deleted,
     * or its password reset). Signs out only on a definite "not signed in" - offline or a server
     * hiccup keeps the session, since the app works fine without the server.
     */
    suspend fun verify() {
        val t = token ?: return
        try {
            // Also picks up what the server knows that older builds didn't keep (join date...).
            save(AuthApi.userOf(com.mhmh2.bujirun.net.Api.get("/auth/me", t)))
        } catch (e: com.mhmh2.bujirun.net.ApiException) {
            if (e.status == 401) clear()
        }
    }

    /** [password] is needed for an account that has one; a Google account has none. */
    suspend fun deleteAccount(password: String?) {
        val t = token ?: return
        AuthApi.deleteMe(t, password)
        clear()
    }

    private fun store(result: AuthResult) {
        sp.edit().putString("token", result.token).apply()
        save(result.user)
    }

    private fun save(u: User) {
        sp.edit()
            .putString("email", u.email)
            .putString("nickname", u.nickname)
            .putBoolean("is_admin", u.isAdmin)
            .putBoolean("has_password", u.hasPassword)
            .putString("created_at", u.createdAt)
            .apply()
        _user.value = u
    }

    private fun clear() {
        sp.edit().clear().apply()
        _user.value = null
    }
}
