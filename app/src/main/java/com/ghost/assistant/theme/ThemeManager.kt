package com.ghost.assistant.theme

import android.content.Context
import android.content.SharedPreferences
import android.graphics.Color
import androidx.appcompat.app.AppCompatDelegate
import androidx.core.content.ContextCompat
import com.ghost.assistant.R

object ThemeManager {

    private const val PREFS_NAME = "ghost_stark_prefs"
    private const val KEY_THEME_MODE = "key_theme_mode"
    private const val KEY_USER_CALLSIGN = "key_user_callsign"
    private const val KEY_COMMS_ENABLED = "key_comms_enabled"
    private const val KEY_AI_CUSTOM_ENDPOINT = "key_ai_custom_endpoint"
    private const val KEY_WAKE_WORD_ENABLED = "key_wake_word_enabled"

    private const val KEY_COMPANION_HOST = "key_companion_host"
    private const val KEY_COMPANION_PORT = "key_companion_port"

    const val DEFAULT_USER_CALLSIGN = "Abdur"
    const val DEFAULT_COMPANION_HOST = "192.168.1.100"
    const val DEFAULT_COMPANION_PORT = 8080
    const val WAKE_WORD = "GHOST"

    enum class ThemeMode {
        STEALTH, // Dark Mode (Default Stark Workshop HUD)
        MARK,    // Light Mode (Gunmetal / Titanium Daylight HUD)
        SYSTEM   // System Default
    }

    private fun getPrefs(context: Context): SharedPreferences {
        return context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
    }

    fun getThemeMode(context: Context): ThemeMode {
        val raw = getPrefs(context).getString(KEY_THEME_MODE, ThemeMode.STEALTH.name) ?: ThemeMode.STEALTH.name
        return try {
            ThemeMode.valueOf(raw)
        } catch (e: Exception) {
            ThemeMode.STEALTH
        }
    }

    fun setThemeMode(context: Context, mode: ThemeMode) {
        getPrefs(context).edit().putString(KEY_THEME_MODE, mode.name).apply()
        applyTheme(mode)
    }

    fun applyTheme(mode: ThemeMode) {
        when (mode) {
            ThemeMode.STEALTH -> AppCompatDelegate.setDefaultNightMode(AppCompatDelegate.MODE_NIGHT_YES)
            ThemeMode.MARK -> AppCompatDelegate.setDefaultNightMode(AppCompatDelegate.MODE_NIGHT_NO)
            ThemeMode.SYSTEM -> AppCompatDelegate.setDefaultNightMode(AppCompatDelegate.MODE_NIGHT_FOLLOW_SYSTEM)
        }
    }

    fun getUserName(context: Context): String {
        return getPrefs(context).getString(KEY_USER_CALLSIGN, DEFAULT_USER_CALLSIGN) ?: DEFAULT_USER_CALLSIGN
    }

    fun setUserName(context: Context, name: String) {
        val trimmed = name.trim()
        val finalName = if (trimmed.isNotBlank()) trimmed else DEFAULT_USER_CALLSIGN
        getPrefs(context).edit().putString(KEY_USER_CALLSIGN, finalName).apply()
    }

    fun isCommsEnabled(context: Context): Boolean {
        return getPrefs(context).getBoolean(KEY_COMMS_ENABLED, true)
    }

    fun setCommsEnabled(context: Context, enabled: Boolean) {
        getPrefs(context).edit().putBoolean(KEY_COMMS_ENABLED, enabled).apply()
    }

    fun getAiCustomEndpoint(context: Context): String {
        return getPrefs(context).getString(KEY_AI_CUSTOM_ENDPOINT, "") ?: ""
    }

    fun setAiCustomEndpoint(context: Context, endpoint: String) {
        getPrefs(context).edit().putString(KEY_AI_CUSTOM_ENDPOINT, endpoint.trim()).apply()
    }

    fun getCompanionHost(context: Context): String {
        return getPrefs(context).getString(KEY_COMPANION_HOST, DEFAULT_COMPANION_HOST) ?: DEFAULT_COMPANION_HOST
    }

    fun setCompanionHost(context: Context, host: String) {
        getPrefs(context).edit().putString(KEY_COMPANION_HOST, host.trim()).apply()
    }

    fun getCompanionPort(context: Context): Int {
        return getPrefs(context).getInt(KEY_COMPANION_PORT, DEFAULT_COMPANION_PORT)
    }

    fun setCompanionPort(context: Context, port: Int) {
        getPrefs(context).edit().putInt(KEY_COMPANION_PORT, port).apply()
    }

    fun isWakeWordEnabled(context: Context): Boolean {
        return getPrefs(context).getBoolean(KEY_WAKE_WORD_ENABLED, true)
    }

    fun setWakeWordEnabled(context: Context, enabled: Boolean) {
        getPrefs(context).edit().putBoolean(KEY_WAKE_WORD_ENABLED, enabled).apply()
    }

    fun isStealth(context: Context): Boolean {
        return getThemeMode(context) != ThemeMode.MARK
    }

    fun getBackgroundColor(context: Context): Int {
        return if (isStealth(context)) {
            ContextCompat.getColor(context, R.color.stark_stealth_bg)
        } else {
            ContextCompat.getColor(context, R.color.stark_mark_bg)
        }
    }

    fun getPanelColor(context: Context): Int {
        return if (isStealth(context)) {
            ContextCompat.getColor(context, R.color.stark_stealth_panel)
        } else {
            ContextCompat.getColor(context, R.color.stark_mark_panel)
        }
    }

    fun getBorderColor(context: Context): Int {
        return if (isStealth(context)) {
            ContextCompat.getColor(context, R.color.stark_stealth_panel_border)
        } else {
            ContextCompat.getColor(context, R.color.stark_mark_panel_border)
        }
    }

    fun getPrimaryTextColor(context: Context): Int {
        return if (isStealth(context)) {
            ContextCompat.getColor(context, R.color.stark_stealth_text_primary)
        } else {
            ContextCompat.getColor(context, R.color.stark_mark_text_primary)
        }
    }

    fun getSecondaryTextColor(context: Context): Int {
        return if (isStealth(context)) {
            ContextCompat.getColor(context, R.color.stark_stealth_text_secondary)
        } else {
            ContextCompat.getColor(context, R.color.stark_mark_text_secondary)
        }
    }

    fun getAccentColor(context: Context): Int {
        return ContextCompat.getColor(context, R.color.arc_cyan)
    }
}
