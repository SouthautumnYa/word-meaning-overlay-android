package com.codex.wordoverlay

import android.content.Context
import android.os.PowerManager
import android.util.Log

class KeepAliveManager(context: Context) {
    private val appContext = context.applicationContext
    private val packageName = appContext.packageName
    private var wakeLock: PowerManager.WakeLock? = null

    fun renewWakeLock() {
        runCatching {
            val powerManager = appContext.getSystemService(PowerManager::class.java)
            val lock = wakeLock ?: powerManager
                .newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "$packageName:clipboard-monitor")
                .apply {
                    setReferenceCounted(false)
                    wakeLock = this
                }

            lock.acquire(WAKE_LOCK_TIMEOUT_MILLIS)
            Diagnostics.record(appContext, "Keep-alive wake lock renewed.")
        }.onFailure { error ->
            Diagnostics.record(appContext, "Wake lock failed: ${error.message}")
            Log.w(TAG, "Wake lock failed", error)
        }
    }

    fun releaseWakeLock() {
        runCatching {
            wakeLock
                ?.takeIf { it.isHeld }
                ?.release()
        }.onFailure { error ->
            Log.w(TAG, "Wake lock release failed", error)
        }
        wakeLock = null
    }

    fun startRootKeepAlive(): RootShell.Result {
        return RootShell.runScript(startRootKeepAliveScript(), timeoutMillis = ROOT_COMMAND_TIMEOUT_MILLIS)
    }

    fun stopRootWatchdog(): RootShell.Result {
        return RootShell.runScript(stopRootWatchdogScript(), timeoutMillis = ROOT_COMMAND_TIMEOUT_MILLIS)
    }

    private fun startRootKeepAliveScript(): String {
        val serviceName = "$packageName/.ClipboardMonitorService"
        val receiverName = "$packageName/.BootReceiver"
        return """
            APP="$packageName"
            SERVICE="$serviceName"
            RECEIVER="$receiverName"
            START_ACTION="$ROOT_KEEP_ALIVE_ACTION"
            WATCHDOG_VERSION="$WATCHDOG_SCRIPT_VERSION"
            WATCHDOG_SCRIPT="/data/local/tmp/word_overlay_watchdog.sh"
            WATCHDOG_PID="/data/local/tmp/word_overlay_watchdog.pid"
            WATCHDOG_LOG="/data/local/tmp/word_overlay_watchdog.log"
            WATCHDOG_VERSION_FILE="/data/local/tmp/word_overlay_watchdog.version"

            pm grant "${'$'}APP" android.permission.READ_CLIPBOARD_IN_BACKGROUND >/dev/null 2>&1 || true
            cmd appops set "${'$'}APP" READ_CLIPBOARD allow >/dev/null 2>&1 || true
            cmd appops set --uid "${'$'}APP" READ_CLIPBOARD allow >/dev/null 2>&1 || true
            cmd appops set "${'$'}APP" SYSTEM_ALERT_WINDOW allow >/dev/null 2>&1 || true
            cmd appops set "${'$'}APP" START_FOREGROUND allow >/dev/null 2>&1 || true
            cmd appops set "${'$'}APP" RUN_IN_BACKGROUND allow >/dev/null 2>&1 || true
            cmd appops set "${'$'}APP" RUN_ANY_IN_BACKGROUND allow >/dev/null 2>&1 || true
            cmd appops set "${'$'}APP" 10008 allow >/dev/null 2>&1 || true
            cmd appops set "${'$'}APP" 10033 allow >/dev/null 2>&1 || true
            cmd appops set "${'$'}APP" 10053 allow >/dev/null 2>&1 || true
            cmd deviceidle whitelist +"${'$'}APP" >/dev/null 2>&1 || dumpsys deviceidle whitelist +"${'$'}APP" >/dev/null 2>&1 || true
            am set-inactive "${'$'}APP" false >/dev/null 2>&1 || true

            cat > "${'$'}WATCHDOG_SCRIPT" <<'WORD_OVERLAY_WATCHDOG'
            #!/system/bin/sh
            APP="$packageName"
            SERVICE="$serviceName"
            RECEIVER="$receiverName"
            START_ACTION="$ROOT_KEEP_ALIVE_ACTION"
            PREFS="/data/data/$packageName/shared_prefs/word_overlay_prefs.xml"
            WATCHDOG_VERSION="$WATCHDOG_SCRIPT_VERSION"
            INTERVAL_SECONDS=15

            translation_enabled() {
                [ ! -f "${'$'}PREFS" ] && return 0
                ! grep -q 'name="translation_enabled" value="false"' "${'$'}PREFS"
            }

            start_monitor() {
                am broadcast --receiver-foreground --include-stopped-packages -n "${'$'}RECEIVER" -a "${'$'}START_ACTION" >/dev/null 2>&1 ||
                    am broadcast --receiver-foreground -f 0x20 -n "${'$'}RECEIVER" -a "${'$'}START_ACTION" >/dev/null 2>&1 ||
                    am start-foreground-service -n "${'$'}SERVICE" >/dev/null 2>&1 ||
                    am startservice -n "${'$'}SERVICE" >/dev/null 2>&1
            }

            while true; do
                if ! translation_enabled; then
                    exit 0
                fi
                if ! pidof "${'$'}APP" >/dev/null 2>&1; then
                    start_monitor
                elif ! dumpsys activity services "${'$'}APP" 2>/dev/null | grep -q "ClipboardMonitorService"; then
                    start_monitor
                fi
                sleep "${'$'}INTERVAL_SECONDS"
            done
            WORD_OVERLAY_WATCHDOG

            chmod 700 "${'$'}WATCHDOG_SCRIPT"

            if [ -f "${'$'}WATCHDOG_PID" ]; then
                OLD_PID="${'$'}(cat "${'$'}WATCHDOG_PID" 2>/dev/null)"
                if [ -n "${'$'}OLD_PID" ] && kill -0 "${'$'}OLD_PID" >/dev/null 2>&1; then
                    OLD_CMDLINE="${'$'}(tr '\000' ' ' < "/proc/${'$'}OLD_PID/cmdline" 2>/dev/null)"
                    CURRENT_VERSION="${'$'}(cat "${'$'}WATCHDOG_VERSION_FILE" 2>/dev/null)"
                    if echo "${'$'}OLD_CMDLINE" | grep -q "word_overlay_watchdog.sh" &&
                        [ "${'$'}CURRENT_VERSION" = "${'$'}WATCHDOG_VERSION" ]; then
                        echo "root keep-alive already active pid=${'$'}OLD_PID"
                        exit 0
                    fi
                    echo "${'$'}OLD_CMDLINE" | grep -q "word_overlay_watchdog.sh" && kill "${'$'}OLD_PID" >/dev/null 2>&1 || true
                fi
            fi

            if command -v nohup >/dev/null 2>&1; then
                nohup sh "${'$'}WATCHDOG_SCRIPT" > "${'$'}WATCHDOG_LOG" 2>&1 &
            else
                sh "${'$'}WATCHDOG_SCRIPT" > "${'$'}WATCHDOG_LOG" 2>&1 &
            fi
            echo ${'$'}! > "${'$'}WATCHDOG_PID"
            echo "${'$'}WATCHDOG_VERSION" > "${'$'}WATCHDOG_VERSION_FILE"
            am broadcast --receiver-foreground --include-stopped-packages -n "${'$'}RECEIVER" -a "${'$'}START_ACTION" >/dev/null 2>&1 ||
                am broadcast --receiver-foreground -f 0x20 -n "${'$'}RECEIVER" -a "${'$'}START_ACTION" >/dev/null 2>&1 ||
                am start-foreground-service -n "${'$'}SERVICE" >/dev/null 2>&1 ||
                am startservice -n "${'$'}SERVICE" >/dev/null 2>&1 ||
                true
            echo "root keep-alive active pid=${'$'}(cat "${'$'}WATCHDOG_PID" 2>/dev/null)"
        """.trimIndent()
    }

    private fun stopRootWatchdogScript(): String {
        return """
            WATCHDOG_SCRIPT="/data/local/tmp/word_overlay_watchdog.sh"
            WATCHDOG_PID="/data/local/tmp/word_overlay_watchdog.pid"
            WATCHDOG_LOG="/data/local/tmp/word_overlay_watchdog.log"
            WATCHDOG_VERSION_FILE="/data/local/tmp/word_overlay_watchdog.version"
            if [ -f "${'$'}WATCHDOG_PID" ]; then
                OLD_PID="${'$'}(cat "${'$'}WATCHDOG_PID" 2>/dev/null)"
                if [ -n "${'$'}OLD_PID" ] && kill -0 "${'$'}OLD_PID" >/dev/null 2>&1; then
                    kill "${'$'}OLD_PID" >/dev/null 2>&1 || true
                fi
            fi
            pkill -f "${'$'}WATCHDOG_SCRIPT" >/dev/null 2>&1 || true
            rm -f "${'$'}WATCHDOG_PID"
            rm -f "${'$'}WATCHDOG_VERSION_FILE"
            rm -f "${'$'}WATCHDOG_SCRIPT"
            rm -f "${'$'}WATCHDOG_LOG"
            echo "root keep-alive stopped"
        """.trimIndent()
    }

    private companion object {
        private const val TAG = "KeepAliveManager"
        private const val ROOT_KEEP_ALIVE_ACTION = "com.codex.wordoverlay.action.ROOT_KEEP_ALIVE"
        private const val WATCHDOG_SCRIPT_VERSION = "20260607_1"
        private const val WAKE_LOCK_TIMEOUT_MILLIS = 10 * 60 * 1_000L
        private const val ROOT_COMMAND_TIMEOUT_MILLIS = 10_000L
    }
}
