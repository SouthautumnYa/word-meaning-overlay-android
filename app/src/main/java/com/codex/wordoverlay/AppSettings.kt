package com.codex.wordoverlay

import android.content.Context
import androidx.core.content.edit

class AppSettings(context: Context) {
    private val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    fun getDismissSeconds(): Int = prefs.getInt(KEY_DISMISS_SECONDS, 5)

    fun setDismissSeconds(seconds: Int) {
        prefs.edit { putInt(KEY_DISMISS_SECONDS, seconds.coerceIn(1, 60)) }
    }

    fun isTranslationEnabled(): Boolean = prefs.getBoolean(KEY_TRANSLATION_ENABLED, true)

    fun setTranslationEnabled(enabled: Boolean) {
        prefs.edit { putBoolean(KEY_TRANSLATION_ENABLED, enabled) }
    }

    companion object {
        private const val PREFS_NAME = "word_overlay_prefs"
        private const val KEY_DISMISS_SECONDS = "dismiss_seconds"
        private const val KEY_TRANSLATION_ENABLED = "translation_enabled"
    }
}
