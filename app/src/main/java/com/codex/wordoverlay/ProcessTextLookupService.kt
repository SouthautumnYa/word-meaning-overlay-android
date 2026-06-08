package com.codex.wordoverlay

import android.app.Service
import android.content.Context
import android.content.Intent
import android.os.IBinder
import android.util.Log
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch

class ProcessTextLookupService : Service() {
    private val serviceScope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
    private val dictionaryClient = DictionaryApiClient()
    private lateinit var overlayController: OverlayController

    override fun onCreate() {
        super.onCreate()
        overlayController = OverlayController(applicationContext, OverlayWindowType.Application)
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val query = intent?.getStringExtra(EXTRA_QUERY)
        if (query == null || !AppSettings(applicationContext).isTranslationEnabled()) {
            stopSelf(startId)
            return START_NOT_STICKY
        }

        serviceScope.launch {
            Diagnostics.record(applicationContext, "Manual/process lookup requested: $query")
            Log.d(TAG, "Process Text lookup requested for query=$query")
            val meaning = dictionaryClient.lookup(query)
            overlayController.show(
                word = query,
                meaning = meaning ?: getString(R.string.word_not_found),
                dismissAfterSeconds = AppSettings(applicationContext).getDismissSeconds()
            )
            stopSelf(startId)
        }

        return START_NOT_STICKY
    }

    override fun onDestroy() {
        serviceScope.cancel()
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null

    companion object {
        private const val TAG = "ProcessTextLookup"
        private const val EXTRA_QUERY = "com.codex.wordoverlay.extra.QUERY"

        fun createIntent(context: Context, query: String): Intent {
            return Intent(context, ProcessTextLookupService::class.java)
                .putExtra(EXTRA_QUERY, query)
        }
    }
}
