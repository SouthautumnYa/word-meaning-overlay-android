package com.codex.wordoverlay

import android.content.Context
import android.util.Log
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch

class WordLookupCoordinator(
    context: Context,
    private val scope: CoroutineScope,
    overlayWindowType: OverlayWindowType
) {
    private val appContext = context.applicationContext
    private val settings = AppSettings(appContext)
    private val dictionaryClient = DictionaryApiClient()
    private val overlayController = OverlayController(context, overlayWindowType)
    private var lastQuery: String? = null
    private var lastShownAtMillis = 0L
    private var lookupJob: Job? = null

    fun handleText(text: CharSequence?) {
        val query = WordExtractor.normalize(text) ?: return
        Diagnostics.record(appContext, "Lookup requested: $query")
        Log.d(TAG, "Lookup requested for query=$query")
        val now = System.currentTimeMillis()
        if (query == lastQuery && now - lastShownAtMillis < DUPLICATE_WINDOW_MILLIS) {
            Diagnostics.record(appContext, "Duplicate skipped: $query")
            Log.d(TAG, "Skipping duplicate query=$query")
            return
        }

        lastQuery = query
        lastShownAtMillis = now
        lookupJob?.cancel()
        lookupJob = scope.launch {
            val meaning = dictionaryClient.lookup(query)
            Diagnostics.record(appContext, "Lookup finished: $query found=${meaning != null}")
            Log.d(TAG, "Lookup finished for query=$query, found=${meaning != null}")
            overlayController.show(
                word = query,
                meaning = meaning ?: appContext.getString(R.string.word_not_found),
                dismissAfterSeconds = settings.getDismissSeconds()
            )
        }
    }

    fun dismiss() {
        lookupJob?.cancel()
        overlayController.dismiss()
    }

    companion object {
        private const val TAG = "WordLookup"
        private const val DUPLICATE_WINDOW_MILLIS = 2_000L
    }
}
