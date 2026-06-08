package com.codex.wordoverlay

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.util.Log
import androidx.core.content.ContextCompat

class BootReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent?) {
        val action = intent?.action.orEmpty()
        if (!AppSettings(context).isTranslationEnabled()) {
            Diagnostics.record(context, "Boot receiver ignored because translation is paused: $action")
            return
        }

        Diagnostics.record(context, "Boot receiver triggered: $action")
        runCatching {
            ContextCompat.startForegroundService(
                context,
                Intent(context, ClipboardMonitorService::class.java)
            )
        }.onFailure { error ->
            Diagnostics.record(context, "Boot restart failed: ${error.message}")
            Log.w(TAG, "Boot restart failed", error)
        }
    }

    private companion object {
        private const val TAG = "BootReceiver"
    }
}
