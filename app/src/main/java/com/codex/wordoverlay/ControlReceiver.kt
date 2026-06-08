package com.codex.wordoverlay

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch

class ControlReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent?) {
        if (intent?.action != ACTION_PAUSE_TRANSLATION) return

        val pendingResult = goAsync()
        AppSettings(context).setTranslationEnabled(false)
        Diagnostics.record(context, "Translation paused by control receiver.")
        context.startService(ClipboardMonitorService.createStopIntent(context))
        CoroutineScope(Dispatchers.IO).launch {
            runCatching {
                KeepAliveManager(context).stopRootWatchdog()
            }
            pendingResult.finish()
        }
    }

    companion object {
        const val ACTION_PAUSE_TRANSLATION = "com.codex.wordoverlay.action.PAUSE_TRANSLATION"
    }
}
