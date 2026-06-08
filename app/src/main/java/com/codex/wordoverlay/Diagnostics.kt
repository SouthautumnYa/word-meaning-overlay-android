package com.codex.wordoverlay

import android.content.Context
import android.util.Log
import androidx.core.content.edit
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

object Diagnostics {
    fun record(context: Context, message: String) {
        val line = "${timestamp()}  $message"
        Log.i(TAG, line)
        context.applicationContext
            .getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .edit {
                putString(KEY_LATEST, line)
            }
    }

    fun latest(context: Context): String {
        return context.applicationContext
            .getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .getString(KEY_LATEST, "No diagnostic status yet.")
            .orEmpty()
    }

    private fun timestamp(): String {
        return SimpleDateFormat("HH:mm:ss", Locale.getDefault()).format(Date())
    }

    private const val TAG = "WordDiag"
    private const val PREFS_NAME = "word_overlay_diagnostics"
    private const val KEY_LATEST = "latest"
}
