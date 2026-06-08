package com.codex.wordoverlay

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Color
import android.graphics.PixelFormat
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.os.Handler
import android.os.Looper
import android.util.Log
import android.view.Gravity
import android.view.View
import android.view.WindowManager
import android.view.animation.DecelerateInterpolator
import android.widget.LinearLayout
import android.widget.TextView

enum class OverlayWindowType {
    Application,
    Accessibility
}

class OverlayController(
    context: Context,
    private val overlayWindowType: OverlayWindowType = OverlayWindowType.Application
) {
    private val windowContext = context
    private val appContext = context.applicationContext
    private val windowManager = windowContext.getSystemService(Context.WINDOW_SERVICE) as WindowManager
    private val handler = Handler(Looper.getMainLooper())
    private var overlayView: View? = null
    private val dismissRunnable = Runnable { dismiss() }

    @SuppressLint("SetTextI18n")
    fun show(word: String, meaning: String, dismissAfterSeconds: Int) {
        handler.post {
            dismiss()

            val view = buildOverlayView(word, meaning)
            val params = WindowManager.LayoutParams(
                WindowManager.LayoutParams.WRAP_CONTENT,
                WindowManager.LayoutParams.WRAP_CONTENT,
                resolveWindowType(),
                WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                    WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL or
                    WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN,
                PixelFormat.TRANSLUCENT
            ).apply {
                gravity = Gravity.TOP or Gravity.END
                x = dp(16)
                y = dp(92)
            }

            runCatching {
                windowManager.addView(view, params)
                overlayView = view
                animateIn(view)
                handler.postDelayed(dismissRunnable, dismissAfterSeconds.coerceIn(1, 60) * 1000L)
                Diagnostics.record(appContext, "Overlay shown: $word")
                Log.d(TAG, "Overlay shown for word=$word")
            }.onFailure { error ->
                Diagnostics.record(appContext, "Overlay failed: ${error.message}")
                Log.w(TAG, "Failed to show overlay for word=$word", error)
            }
        }
    }

    fun dismiss() {
        handler.removeCallbacks(dismissRunnable)
        overlayView?.let { view ->
            runCatching { windowManager.removeView(view) }
        }
        overlayView = null
    }

    private fun buildOverlayView(word: String, meaning: String): View {
        val cardBackground = GradientDrawable(
            GradientDrawable.Orientation.TL_BR,
            intArrayOf(Color.rgb(13, 92, 98), Color.rgb(18, 55, 69), Color.rgb(184, 99, 42))
        ).apply {
            cornerRadius = dp(24).toFloat()
            setStroke(dp(1), Color.argb(70, 255, 255, 255))
        }

        return LinearLayout(windowContext).apply {
            orientation = LinearLayout.VERTICAL
            background = cardBackground
            elevation = dp(18).toFloat()
            minimumWidth = dp(230)
            setPadding(dp(18), dp(15), dp(18), dp(15))

            addView(TextView(windowContext).apply {
                text = "复制单词"
                textSize = 11f
                letterSpacing = 0.12f
                setTextColor(Color.argb(190, 255, 255, 255))
                typeface = Typeface.create(Typeface.SANS_SERIF, Typeface.BOLD)
            })

            addView(TextView(windowContext).apply {
                text = word
                textSize = 24f
                maxWidth = dp(310)
                setTextColor(Color.WHITE)
                typeface = Typeface.create(Typeface.SERIF, Typeface.BOLD)
                includeFontPadding = false
                setPadding(0, dp(5), 0, dp(8))
            })

            addView(TextView(windowContext).apply {
                text = meaning
                textSize = 14.5f
                maxWidth = dp(310)
                setTextColor(Color.argb(232, 255, 253, 247))
                setLineSpacing(dp(2).toFloat(), 1.04f)
                includeFontPadding = true
            })

            isClickable = true
            setOnClickListener { dismiss() }
        }
    }

    private fun animateIn(view: View) {
        view.alpha = 0f
        view.translationY = -dp(10).toFloat()
        view.scaleX = 0.96f
        view.scaleY = 0.96f
        view.animate()
            .alpha(1f)
            .translationY(0f)
            .scaleX(1f)
            .scaleY(1f)
            .setDuration(180L)
            .setInterpolator(DecelerateInterpolator())
            .start()
    }

    private fun resolveWindowType(): Int {
        if (overlayWindowType == OverlayWindowType.Accessibility) {
            return WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY
        }

        return WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
    }

    private fun dp(value: Int): Int {
        return (value * appContext.resources.displayMetrics.density).toInt()
    }

    companion object {
        private const val TAG = "WordOverlay"
    }
}
