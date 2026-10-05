package com.mhmh2.bujirun

import android.content.Context
import android.content.SharedPreferences
import com.mhmh2.bujirun.coach.Goal
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/** Small user preferences. The settings screen (M4) edits more of these. */
object Prefs {
    private lateinit var sp: SharedPreferences
    private val _voiceEnabled = MutableStateFlow(true)
    val voiceEnabled: StateFlow<Boolean> = _voiceEnabled.asStateFlow()

    fun init(context: Context) {
        if (::sp.isInitialized) return
        sp = context.applicationContext.getSharedPreferences("prefs", Context.MODE_PRIVATE)
        _voiceEnabled.value = sp.getBoolean("voice_enabled", true)
    }

    fun setVoiceEnabled(enabled: Boolean) {
        _voiceEnabled.value = enabled
        sp.edit().putBoolean("voice_enabled", enabled).apply()
    }

    /** The goal picked last time, so a daily 5 km runner doesn't re-pick it every morning. */
    var lastGoal: Goal
        get() = Goal.decode(sp.getString("goal_type", null), sp.getFloat("goal_value", 0f).toDouble())
        set(goal) {
            val (type, value) = goal.encode()
            sp.edit().putString("goal_type", type).putFloat("goal_value", value.toFloat()).apply()
        }
}
