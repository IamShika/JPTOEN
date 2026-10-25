package com.example.jpsubs

import android.content.Context
import android.graphics.Color
import android.graphics.PixelFormat
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.os.Handler
import android.os.Looper
import android.util.Log
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.WindowManager
import android.widget.LinearLayout
import android.widget.TextView

/**
 * The floating subtitle box that sits on top of other apps (Milestone 4).
 *
 * How it works:
 *  - Android lets an app draw over other apps if the person allowed "Display over other apps".
 *  - We add one small window (a dark rounded box with two lines of text) to the screen.
 *  - Every 200 ms we look at the Transcript. When a new line arrives we show it; when nothing
 *    new has arrived for a few seconds the box hides itself so it does not cover the video.
 *  - You can drag the box with your finger to move it.
 *
 * Everything here runs on the main (screen) thread through one Handler.
 * It is switched on and off live by Settings.overlayEnabled.
 */
class SubtitleOverlay(private val context: Context) {

    private val handler = Handler(Looper.getMainLooper())
    private val windowManager = context.getSystemService(Context.WINDOW_SERVICE) as WindowManager

    private var box: LinearLayout? = null
    private var englishView: TextView? = null
    private var japaneseView: TextView? = null
    private var params: WindowManager.LayoutParams? = null

    private var screenW = 0
    private var screenH = 0
    private var dragging = false
    private var shownVersion = -1
    private var lastChangeTime = 0L
    private var started = false

    private val tick = object : Runnable {
        override fun run() {
            update()
            if (started) handler.postDelayed(this, 200)
        }
    }

    /** Starts watching the Transcript. Call from the main thread. */
    fun start() {
        if (started) return
        started = true
        handler.post(tick)
    }

    /** Removes the box and stops watching. Call from the main thread. */
    fun stop() {
        started = false
        handler.removeCallbacks(tick)
        removeBox()
    }

    /** Real screen size right now (changes when the phone is rotated). */
    @Suppress("DEPRECATION")
    private fun currentScreenSize(): Pair<Int, Int> {
        val dm = android.util.DisplayMetrics()
        windowManager.defaultDisplay.getRealMetrics(dm)
        return Pair(dm.widthPixels, dm.heightPixels)
    }

    /** Puts the box back inside the screen after a rotation (or when it is first shown). */
    private fun fitToScreen() {
        val b = box ?: return
        val lp = params ?: return
        val (w, h) = currentScreenSize()
        screenW = w
        screenH = h
        // The box is as wide as its text (up to 90% of the screen) and centred.
        lp.width = WindowManager.LayoutParams.WRAP_CONTENT
        lp.x = 0
        val maxTextWidth = (w * 0.9f).toInt() - (28 * context.resources.displayMetrics.density).toInt()
        englishView?.maxWidth = maxTextWidth
        japaneseView?.maxWidth = maxTextWidth
        val maxY = (h - b.height).coerceAtLeast(0)
        lp.y = (Settings.overlayYFraction * h).toInt().coerceIn(0, maxY)
        try {
            windowManager.updateViewLayout(b, lp)
        } catch (_: Exception) {
        }
    }

    /**
     * Keeps the whole box on the screen. Long text makes the box taller, and because it hangs
     * from its top edge it would run off the bottom. So we slide it up just enough, and when
     * the next line is shorter it goes back to the spot you chose.
     */
    private fun keepOnScreen() {
        val b = box ?: return
        val lp = params ?: return
        if (dragging || b.height <= 0 || screenH <= 0) return
        val margin = (32 * context.resources.displayMetrics.density).toInt() // gesture bar room
        val maxY = (screenH - b.height - margin).coerceAtLeast(0)
        val wanted = (Settings.overlayYFraction * screenH).toInt()
        val y = minOf(wanted, maxY).coerceAtLeast(0)
        if (lp.y != y) {
            lp.y = y
            try {
                windowManager.updateViewLayout(b, lp)
            } catch (_: Exception) {
            }
        }
    }

    private fun update() {
        val allowed = Settings.overlayEnabled &&
            android.provider.Settings.canDrawOverlays(context)
        if (!allowed) {
            removeBox()
            return
        }

        val version = Transcript.version
        if (version != shownVersion) {
            shownVersion = version
            val newest = Transcript.snapshot().firstOrNull()
            // Notes like "(skipped some speech...)" have no English: show them in the first line.
            if (newest != null) {
                val english = newest.english
                ensureBox()
                if (english.isNullOrBlank()) {
                    englishView?.text = newest.japanese
                    japaneseView?.visibility = View.GONE
                } else {
                    englishView?.text = english
                    japaneseView?.text = newest.japanese
                    japaneseView?.visibility = if (Settings.overlayShowJapanese) View.VISIBLE else View.GONE
                }
                box?.visibility = View.VISIBLE
                lastChangeTime = System.currentTimeMillis()
            }
        }

        // Did the phone rotate? Then move the box so it is not off the screen.
        if (box != null) {
            val (w, h) = currentScreenSize()
            if (w != screenW || h != screenH) fitToScreen()
        }

        keepOnScreen()

        // Hide after a while without new speech.
        val hideAfterMs = Settings.overlaySeconds * 1000L
        if (box != null && System.currentTimeMillis() - lastChangeTime > hideAfterMs) {
            box?.visibility = View.INVISIBLE
        }
    }

    /** Builds the box and adds it to the screen the first time it is needed. */
    private fun ensureBox() {
        if (box != null) return

        val density = context.resources.displayMetrics.density
        fun dp(v: Int) = (v * density).toInt()

        val english = TextView(context).apply {
            setTextColor(Color.WHITE)
            setTypeface(typeface, Typeface.BOLD)
            textSize = 20f
            gravity = Gravity.CENTER
        }
        val japanese = TextView(context).apply {
            setTextColor(Color.parseColor("#CCCCCC"))
            textSize = 13f
            gravity = Gravity.CENTER
        }
        val layout = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(14), dp(8), dp(14), dp(8))
            background = GradientDrawable().apply {
                setColor(Color.parseColor("#B3000000")) // black, about 70% opaque
                cornerRadius = dp(12).toFloat()
            }
            addView(english)
            addView(japanese)
        }

        val (screenWidth, screenHeight) = currentScreenSize()
        screenW = screenWidth
        screenH = screenHeight
        val maxTextWidth = (screenWidth * 0.9f).toInt() - dp(28)
        english.maxWidth = maxTextWidth
        japanese.maxWidth = maxTextWidth
        val lp = WindowManager.LayoutParams(
            WindowManager.LayoutParams.WRAP_CONTENT, // as wide as the text
            WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
            // Do not take focus (so the app underneath keeps working with the keyboard etc.)
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS,
            PixelFormat.TRANSLUCENT
        ).apply {
            gravity = Gravity.TOP or Gravity.CENTER_HORIZONTAL
            x = 0
            y = (Settings.overlayYFraction * screenHeight).toInt()
        }

        // Drag with a finger to move the box up or down (and sideways).
        var startX = 0
        var startY = 0
        var touchX = 0f
        var touchY = 0f
        layout.setOnTouchListener { _, event ->
            when (event.action) {
                MotionEvent.ACTION_DOWN -> {
                    dragging = true
                    startX = lp.x
                    startY = lp.y
                    touchX = event.rawX
                    touchY = event.rawY
                    true
                }
                MotionEvent.ACTION_MOVE -> {
                    lp.x = startX + (event.rawX - touchX).toInt()
                    lp.y = startY + (event.rawY - touchY).toInt()
                    try {
                        windowManager.updateViewLayout(layout, lp)
                    } catch (_: Exception) {
                    }
                    true
                }
                MotionEvent.ACTION_UP -> {
                    dragging = false
                    Settings.overlayYFraction = (lp.y.toFloat() / screenH).coerceIn(0f, 0.95f)
                    Settings.save(context)
                    true
                }
                else -> false
            }
        }

        layout.addOnLayoutChangeListener { _, _, top, _, bottom, _, oldTop, _, oldBottom ->
            if (bottom - top != oldBottom - oldTop) handler.post { keepOnScreen() }
        }

        try {
            windowManager.addView(layout, lp)
            box = layout
            englishView = english
            japaneseView = japanese
            params = lp
        } catch (e: Exception) {
            Log.e("SubtitleOverlay", "Could not show the overlay", e)
        }
    }

    private fun removeBox() {
        val b = box ?: return
        try {
            windowManager.removeView(b)
        } catch (_: Exception) {
        }
        box = null
        englishView = null
        japaneseView = null
        params = null
        shownVersion = -1 // so the current line shows again when the box comes back
    }
}
