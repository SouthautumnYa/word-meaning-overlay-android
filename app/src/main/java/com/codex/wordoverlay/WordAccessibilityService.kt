package com.codex.wordoverlay

import android.accessibilityservice.AccessibilityService
import android.content.ClipboardManager
import android.content.Context
import android.os.Handler
import android.os.Looper
import android.util.Log
import android.view.accessibility.AccessibilityEvent
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel

class WordAccessibilityService : AccessibilityService() {
    private val serviceScope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
    private val handler = Handler(Looper.getMainLooper())
    private lateinit var clipboardManager: ClipboardManager
    private lateinit var coordinator: WordLookupCoordinator
    private lateinit var appSettings: AppSettings
    private var lastSelectedQuery: String? = null
    private var lastSelectedAtMillis = 0L

    private val readClipboardRunnable = Runnable { readClipboardNow() }

    private val clipboardListener = ClipboardManager.OnPrimaryClipChangedListener {
        readClipboardSoon()
    }

    override fun onServiceConnected() {
        super.onServiceConnected()
        Log.d(TAG, "Accessibility service connected")
        appSettings = AppSettings(this)
        clipboardManager = getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
        coordinator = WordLookupCoordinator(
            context = this,
            scope = serviceScope,
            overlayWindowType = OverlayWindowType.Accessibility
        )
        clipboardManager.addPrimaryClipChangedListener(clipboardListener)
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        if (event == null || !::coordinator.isInitialized) return
        if (!appSettings.isTranslationEnabled()) return
        Log.d(TAG, "Event type=${event.eventType}, text=${event.text.joinToString(" ")}")

        when (event.eventType) {
            AccessibilityEvent.TYPE_VIEW_TEXT_SELECTION_CHANGED -> {
                selectedTextFrom(event)?.let(::rememberSelectedWord)
            }

            AccessibilityEvent.TYPE_VIEW_CLICKED,
            AccessibilityEvent.TYPE_WINDOW_CONTENT_CHANGED -> {
                if (looksLikeCopyAction(event)) {
                    readClipboardSoon()
                }
            }

            else -> Unit
        }
    }

    override fun onInterrupt() = Unit

    override fun onDestroy() {
        handler.removeCallbacks(readClipboardRunnable)
        if (::clipboardManager.isInitialized) {
            clipboardManager.removePrimaryClipChangedListener(clipboardListener)
        }
        if (::coordinator.isInitialized) {
            coordinator.dismiss()
        }
        serviceScope.cancel()
        super.onDestroy()
    }

    private fun selectedTextFrom(event: AccessibilityEvent): String? {
        val sourceText = event.source?.text?.toString()
        val start = event.fromIndex
        val end = event.toIndex

        if (!sourceText.isNullOrBlank() && start >= 0 && end > start && end <= sourceText.length) {
            return sourceText.substring(start, end)
        }

        return event.text.joinToString(separator = " ")
    }

    private fun looksLikeCopyAction(event: AccessibilityEvent): Boolean {
        val eventText = event.text.joinToString(separator = " ").trim()
        return eventText.equals("copy", ignoreCase = true) ||
            eventText.equals("复制") ||
            eventText.equals("拷贝") ||
            eventText.contains("copy", ignoreCase = true) ||
            eventText.contains("复制") ||
            eventText.contains("拷贝")
    }

    private fun readClipboardSoon() {
        if (!appSettings.isTranslationEnabled()) return
        handler.removeCallbacks(readClipboardRunnable)
        handler.postDelayed(readClipboardRunnable, CLIPBOARD_READ_DELAY_MILLIS)
    }

    private fun readClipboardNow() {
        if (!appSettings.isTranslationEnabled()) return

        runCatching {
            ClipboardTextReader.readTextOnly(this, clipboardManager.primaryClip)
        }.onSuccess { result ->
            Log.d(TAG, "Clipboard text=${result.text}, skipped=${result.skippedReason}")
            val query = WordExtractor.normalize(result.text)
            if (query != null) {
                coordinator.handleText(query)
            } else {
                lookupRecentSelection(result.skippedReason ?: "clipboard text was empty or not English text")
            }
        }.onFailure { error ->
            Log.w(TAG, "Clipboard read was blocked by the system", error)
            lookupRecentSelection("clipboard read blocked")
        }
    }

    private fun rememberSelectedWord(text: CharSequence) {
        if (!appSettings.isTranslationEnabled()) return
        val query = WordExtractor.normalize(text) ?: return
        lastSelectedQuery = query
        lastSelectedAtMillis = System.currentTimeMillis()
        Log.d(TAG, "Remembered selected query=$query")
    }

    private fun lookupRecentSelection(reason: String) {
        if (!appSettings.isTranslationEnabled()) return
        val query = lastSelectedQuery ?: return
        val ageMillis = System.currentTimeMillis() - lastSelectedAtMillis
        if (ageMillis > RECENT_SELECTION_WINDOW_MILLIS) return

        Log.d(TAG, "Using recent selected query=$query because $reason")
        coordinator.handleText(query)
    }

    companion object {
        private const val TAG = "WordAccessibility"
        private const val CLIPBOARD_READ_DELAY_MILLIS = 180L
        private const val RECENT_SELECTION_WINDOW_MILLIS = 10_000L
    }
}
