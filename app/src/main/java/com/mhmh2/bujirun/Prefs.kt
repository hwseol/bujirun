package com.mhmh2.bujirun

import android.content.Context
import android.content.SharedPreferences
import com.mhmh2.bujirun.coach.CueInterval
import com.mhmh2.bujirun.coach.Goal
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/** Small user preferences. The settings screen (M4) edits more of these. */
object Prefs {
    private lateinit var sp: SharedPreferences
    private val _voiceEnabled = MutableStateFlow(true)
    val voiceEnabled: StateFlow<Boolean> = _voiceEnabled.asStateFlow()

    private val _autoPause = MutableStateFlow(true)
    val autoPause: StateFlow<Boolean> = _autoPause.asStateFlow()

    fun init(context: Context) {
        if (::sp.isInitialized) return
        sp = context.applicationContext.getSharedPreferences("prefs", Context.MODE_PRIVATE)
        _voiceEnabled.value = sp.getBoolean("voice_enabled", true)
        _autoPause.value = sp.getBoolean("auto_pause", true)
        _autoCalendar.value = sp.getBoolean("auto_calendar", false)
        _autoHealth.value = sp.getBoolean("auto_health", false)
    }

    // After a workout, without a tap: an event in the phone's (Google) calendar, and the
    // workout in Health Connect (where Samsung Health picks it up).
    private val _autoCalendar = MutableStateFlow(false)
    val autoCalendar: StateFlow<Boolean> = _autoCalendar.asStateFlow()
    private val _autoHealth = MutableStateFlow(false)
    val autoHealth: StateFlow<Boolean> = _autoHealth.asStateFlow()

    fun setAutoCalendar(enabled: Boolean) {
        _autoCalendar.value = enabled
        sp.edit().putBoolean("auto_calendar", enabled).apply()
    }

    fun setAutoHealth(enabled: Boolean) {
        _autoHealth.value = enabled
        sp.edit().putBoolean("auto_health", enabled).apply()
    }

    /** Workouts already written somewhere ("calendar", "health"), so nothing is written twice. */
    fun exported(where: String, workoutId: Long): Boolean =
        sp.getStringSet("exported_$where", emptySet())!!.contains(workoutId.toString())

    fun markExported(where: String, workoutId: Long) {
        val set = sp.getStringSet("exported_$where", emptySet())!!.toMutableSet()
        set += workoutId.toString()
        sp.edit().putStringSet("exported_$where", set).apply()
    }

    fun setAutoPause(enabled: Boolean) {
        _autoPause.value = enabled
        sp.edit().putBoolean("auto_pause", enabled).apply()
    }

    /** For calories. Until the user sets it in settings, an average adult's 65 kg. */
    var weightKg: Double
        get() = sp.getFloat("weight_kg", 65f).toDouble()
        set(value) = sp.edit().putFloat("weight_kg", value.toFloat()).apply()

    val weightSet: Boolean get() = sp.contains("weight_kg")

    /** How often the routine progress announcement comes. */
    var cueInterval: CueInterval
        get() = when (val v = sp.getString("cue_interval", "d1000")) {
            null -> CueInterval.EveryDistance(1000.0)
            else -> if (v.startsWith("t")) CueInterval.EveryTime(v.drop(1).toLong())
            else CueInterval.EveryDistance(v.drop(1).toDouble())
        }
        set(value) {
            val encoded = when (value) {
                is CueInterval.EveryDistance -> "d${value.meters.toInt()}"
                is CueInterval.EveryTime -> "t${value.ms}"
            }
            sp.edit().putString("cue_interval", encoded).apply()
        }

    fun setVoiceEnabled(enabled: Boolean) {
        _voiceEnabled.value = enabled
        sp.edit().putBoolean("voice_enabled", enabled).apply()
    }

    /** The newest server notice already shown (net/Notices). */
    var lastNoticeId: Int
        get() = sp.getInt("last_notice_id", 0)
        set(id) = sp.edit().putInt("last_notice_id", id).apply()

    /** The goal picked last time, so a daily 5 km runner doesn't re-pick it every morning. */
    var lastGoal: Goal
        get() = Goal.decode(sp.getString("goal_type", null), sp.getFloat("goal_value", 0f).toDouble())
        set(goal) {
            val (type, value) = goal.encode()
            sp.edit().putString("goal_type", type).putFloat("goal_value", value.toFloat()).apply()
        }
}
