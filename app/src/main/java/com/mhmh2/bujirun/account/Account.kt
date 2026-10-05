package com.mhmh2.bujirun.account

import android.content.Context
import android.content.SharedPreferences
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

data class User(val email: String, val nickname: String) {
    val isAdmin: Boolean get() = email == AccountRules.ADMIN_EMAIL
}

/** Sign-up input rules. Pure, so they're unit-tested. */
object AccountRules {
    const val ADMIN_EMAIL = "admin@example.com"

    // Deliberately simple: something@something.tld, no spaces. Full RFC 5322 validation rejects
    // nothing real users type and accepts plenty of nonsense, so it buys nothing here.
    private val EMAIL = Regex("^[A-Za-z0-9._%+-]+@[A-Za-z0-9-]+(\\.[A-Za-z0-9-]+)*\\.[A-Za-z]{2,}$")

    fun normalizeEmail(input: String) = input.trim().lowercase()

    fun isValidEmail(input: String) = EMAIL.matches(normalizeEmail(input))

    fun normalizeNickname(input: String) = input.trim().replace(Regex("\\s+"), " ")

    /** 2-12 characters after trimming. */
    fun isValidNickname(input: String) = normalizeNickname(input).length in 2..12
}

/**
 * The signed-in account. For now it lives only on this phone: there's no email verification,
 * and nothing is synced to a server yet (that comes with record sync). Admin is simply the
 * admin email; admin only unlocks the fake-GPS test runs, which affect nothing but this phone.
 */
object Account {
    private lateinit var sp: SharedPreferences
    private val _user = MutableStateFlow<User?>(null)
    val user: StateFlow<User?> = _user.asStateFlow()

    fun init(context: Context) {
        if (::sp.isInitialized) return
        sp = context.applicationContext.getSharedPreferences("account", Context.MODE_PRIVATE)
        val email = sp.getString("email", null)
        val nickname = sp.getString("nickname", null)
        _user.value = if (email != null && nickname != null) User(email, nickname) else null
    }

    /** Returns false (and changes nothing) if the input doesn't pass [AccountRules]. */
    fun signIn(email: String, nickname: String): Boolean {
        if (!AccountRules.isValidEmail(email) || !AccountRules.isValidNickname(nickname)) return false
        val user = User(AccountRules.normalizeEmail(email), AccountRules.normalizeNickname(nickname))
        sp.edit().putString("email", user.email).putString("nickname", user.nickname).apply()
        _user.value = user
        return true
    }

    fun signOut() {
        sp.edit().clear().apply()
        _user.value = null
    }
}
