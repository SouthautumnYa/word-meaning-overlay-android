package com.codex.wordoverlay

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.util.Log
import androidx.core.app.NotificationCompat
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class ClipboardMonitorService : Service() {
    private val serviceScope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
    private val handler = Handler(Looper.getMainLooper())
    private lateinit var clipboardManager: ClipboardManager
    private lateinit var coordinator: WordLookupCoordinator
    private lateinit var keepAliveManager: KeepAliveManager
    private lateinit var appSettings: AppSettings
    private var rootAccessJob: Job? = null
    private var rootClipboardReadJob: Job? = null
    private var lastRootAccessAttemptAt = 0L
    private var lastClipboardTimestamp = Long.MIN_VALUE
    private var lastDeliveredQuery: String? = null
    private var lastClipboardErrorLogAt = 0L
    private var lastClipboardProbeLogAt = 0L
    private var lastRootReadErrorLogAt = 0L
    private var monitorStarted = false

    private val clipboardListener = ClipboardManager.OnPrimaryClipChangedListener {
        handleClipboardChange(source = "listener")
    }

    private val pollClipboardRunnable = object : Runnable {
        override fun run() {
            handleClipboardChange(source = "poll")
            handler.postDelayed(this, CLIPBOARD_POLL_INTERVAL_MILLIS)
        }
    }

    private val keepAliveRunnable = object : Runnable {
        override fun run() {
            keepAliveManager.renewWakeLock()
            ensureRootKeepAlive(force = false)
            handler.postDelayed(this, KEEP_ALIVE_RENEW_INTERVAL_MILLIS)
        }
    }

    override fun onCreate() {
        super.onCreate()
        appSettings = AppSettings(this)
        coordinator = WordLookupCoordinator(
            context = this,
            scope = serviceScope,
            overlayWindowType = OverlayWindowType.Application
        )
        keepAliveManager = KeepAliveManager(this)
        clipboardManager = getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
        createNotificationChannel()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_STOP_MONITOR) {
            appSettings.setTranslationEnabled(false)
            stopRootKeepAliveAndSelf()
            return START_NOT_STICKY
        }

        if (!appSettings.isTranslationEnabled()) {
            stopRootKeepAliveAndSelf()
            return START_NOT_STICKY
        }

        startMonitoringIfNeeded()
        return START_STICKY
    }

    override fun onDestroy() {
        handler.removeCallbacks(pollClipboardRunnable)
        handler.removeCallbacks(keepAliveRunnable)
        if (monitorStarted && ::clipboardManager.isInitialized) {
            clipboardManager.removePrimaryClipChangedListener(clipboardListener)
        }
        if (::coordinator.isInitialized) {
            coordinator.dismiss()
        }
        if (::keepAliveManager.isInitialized) {
            keepAliveManager.releaseWakeLock()
        }
        serviceScope.cancel()
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null

    private fun buildNotification(): Notification {
        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(android.R.drawable.ic_menu_search)
            .setContentTitle(getString(R.string.notification_title))
            .setContentText(getString(R.string.notification_text))
            .setOngoing(true)
            .build()
    }

    private fun createNotificationChannel() {
        val manager = getSystemService(NotificationManager::class.java)
        val channel = NotificationChannel(
            CHANNEL_ID,
            getString(R.string.notification_channel_name),
            NotificationManager.IMPORTANCE_LOW
        )
        manager.createNotificationChannel(channel)
    }

    private fun startMonitoringIfNeeded() {
        if (monitorStarted) {
            keepAliveManager.renewWakeLock()
            ensureRootKeepAlive(force = false)
            return
        }

        startForeground(NOTIFICATION_ID, buildNotification())
        keepAliveManager.renewWakeLock()
        ensureRootKeepAlive(force = true)
        clipboardManager.addPrimaryClipChangedListener(clipboardListener)
        handler.post(pollClipboardRunnable)
        handler.postDelayed(keepAliveRunnable, KEEP_ALIVE_RENEW_INTERVAL_MILLIS)
        monitorStarted = true
        Diagnostics.record(this, "Clipboard monitor service started.")
        Log.d(TAG, "Clipboard monitor service started")
    }

    private fun handleClipboardChange(source: String) {
        if (!appSettings.isTranslationEnabled()) {
            stopRootKeepAliveAndSelf()
            return
        }

        val snapshot = readClipboardSnapshotSafely(source) ?: return
        val query = WordExtractor.normalize(snapshot.text)
        if (snapshot.timestamp > 0L && snapshot.timestamp == lastClipboardTimestamp) {
            if (query == null) {
                logClipboardProbe(source, snapshot)
            }
            return
        }
        if (snapshot.timestamp > 0L) {
            lastClipboardTimestamp = snapshot.timestamp
        }

        if (query == null) {
            logClipboardProbe(source, snapshot)
            return
        }

        deliverQuery(query = query, source = source)
    }

    private fun pollRootClipboard() {
        if (rootClipboardReadJob?.isActive == true) return

        rootClipboardReadJob = serviceScope.launch(Dispatchers.IO) {
            val result = RootClipboardReader.read()
            withContext(Dispatchers.Main) {
                result.error?.let(::logRootClipboardReadError)
                val query = WordExtractor.normalize(result.text)
                if (query == null) {
                    logRootClipboardEmpty(result.rawOutput)
                    return@withContext
                }
                deliverQuery(query = query, source = "root")
            }
        }
    }

    private fun deliverQuery(query: String, source: String) {
        if (query == lastDeliveredQuery) return

        lastDeliveredQuery = query
        Diagnostics.record(this, "Clipboard $source read text: $query")
        Log.d(TAG, "Clipboard $source text=$query")
        coordinator.handleText(query)
    }

    private fun readClipboardSnapshotSafely(source: String): ClipboardSnapshot? {
        return runCatching {
            val result = ClipboardTextReader.readTextOnly(this, clipboardManager.primaryClip)
            ClipboardSnapshot(
                text = result.text,
                timestamp = result.timestamp,
                skippedReason = result.skippedReason
            )
        }.onFailure { error ->
            logClipboardReadBlocked(source, error)
            ensureRootKeepAlive(force = false)
        }.getOrNull()
    }

    private fun ensureRootKeepAlive(force: Boolean) {
        val now = System.currentTimeMillis()
        if (!force && now - lastRootAccessAttemptAt < ROOT_ACCESS_RETRY_MILLIS) return
        if (rootAccessJob?.isActive == true) return

        lastRootAccessAttemptAt = now
        rootAccessJob = serviceScope.launch(Dispatchers.IO) {
            val result = keepAliveManager.startRootKeepAlive()
            if (result.isSuccess) {
                Diagnostics.record(
                    this@ClipboardMonitorService,
                    "Root keep-alive active. ${result.stdout.take(RAW_OUTPUT_PREVIEW_LENGTH)}"
                )
                Log.d(TAG, "Root keep-alive active: ${result.stdout}")
            } else {
                Diagnostics.record(
                    this@ClipboardMonitorService,
                    "Root keep-alive failed: exit=${result.exitCode} ${result.stderr}"
                )
                Log.w(
                    TAG,
                    "Root keep-alive failed exit=${result.exitCode} stdout=${result.stdout} stderr=${result.stderr}"
                )
            }
        }
    }

    private fun stopRootKeepAliveAndSelf() {
        rootAccessJob?.cancel()
        serviceScope.launch(Dispatchers.IO) {
            val result = keepAliveManager.stopRootWatchdog()
            if (result.isSuccess) {
                Diagnostics.record(this@ClipboardMonitorService, "Root keep-alive stopped.")
                Log.d(TAG, "Root keep-alive stopped")
            } else {
                Diagnostics.record(
                    this@ClipboardMonitorService,
                    "Root keep-alive stop failed: exit=${result.exitCode} ${result.stderr}"
                )
                Log.w(
                    TAG,
                    "Root keep-alive stop failed exit=${result.exitCode} stdout=${result.stdout} stderr=${result.stderr}"
                )
            }
            stopSelf()
        }
    }

    private fun logClipboardReadBlocked(source: String, error: Throwable) {
        val now = System.currentTimeMillis()
        if (now - lastClipboardErrorLogAt < CLIPBOARD_ERROR_LOG_INTERVAL_MILLIS) return

        lastClipboardErrorLogAt = now
        Diagnostics.record(this, "Normal clipboard $source read blocked: ${error.message}")
        Log.w(TAG, "Clipboard $source read was blocked by the system", error)
    }

    private fun logClipboardProbe(source: String, snapshot: ClipboardSnapshot) {
        val now = System.currentTimeMillis()
        if (now - lastClipboardProbeLogAt < CLIPBOARD_PROBE_LOG_INTERVAL_MILLIS) return

        lastClipboardProbeLogAt = now
        val rawText = snapshot.text?.toString()?.trim().orEmpty()
        val status = snapshot.skippedReason
            ?: if (rawText.isBlank()) {
                "empty"
            } else {
                "not English text: ${rawText.take(RAW_OUTPUT_PREVIEW_LENGTH)}"
            }
        Diagnostics.record(this, "Clipboard $source read $status.")
    }

    private fun logRootClipboardReadError(error: String) {
        val now = System.currentTimeMillis()
        if (now - lastRootReadErrorLogAt < CLIPBOARD_ERROR_LOG_INTERVAL_MILLIS) return

        lastRootReadErrorLogAt = now
        Diagnostics.record(this, "Root clipboard read failed: $error")
        Log.w(TAG, "Root clipboard read failed: $error")
    }

    private fun logRootClipboardEmpty(rawOutput: String) {
        val now = System.currentTimeMillis()
        if (now - lastRootReadErrorLogAt < CLIPBOARD_EMPTY_LOG_INTERVAL_MILLIS) return

        lastRootReadErrorLogAt = now
        Diagnostics.record(this, "Root clipboard is empty. raw=${rawOutput.take(RAW_OUTPUT_PREVIEW_LENGTH)}")
    }

    private data class ClipboardSnapshot(
        val text: CharSequence?,
        val timestamp: Long,
        val skippedReason: String?
    )

    companion object {
        private const val TAG = "ClipboardMonitor"
        private const val CHANNEL_ID = "clipboard_monitor"
        private const val NOTIFICATION_ID = 1001
        private const val CLIPBOARD_POLL_INTERVAL_MILLIS = 800L
        private const val KEEP_ALIVE_RENEW_INTERVAL_MILLIS = 4 * 60 * 1_000L
        private const val ROOT_ACCESS_RETRY_MILLIS = 30_000L
        private const val CLIPBOARD_ERROR_LOG_INTERVAL_MILLIS = 5_000L
        private const val CLIPBOARD_PROBE_LOG_INTERVAL_MILLIS = 5_000L
        private const val CLIPBOARD_EMPTY_LOG_INTERVAL_MILLIS = 8_000L
        private const val RAW_OUTPUT_PREVIEW_LENGTH = 80
        private const val ACTION_STOP_MONITOR = "com.codex.wordoverlay.action.STOP_MONITOR"

        fun createStopIntent(context: Context): Intent {
            return Intent(context, ClipboardMonitorService::class.java)
                .setAction(ACTION_STOP_MONITOR)
        }
    }
}
