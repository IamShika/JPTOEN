package com.example.jpsubs

import android.Manifest
import android.app.Activity
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.media.projection.MediaProjectionConfig
import android.media.projection.MediaProjectionManager
import android.graphics.Color
import android.graphics.Typeface
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.text.SpannableStringBuilder
import android.text.Spanned
import android.text.style.ForegroundColorSpan
import android.text.style.RelativeSizeSpan
import android.text.style.StyleSpan
import android.view.View
import android.widget.Button
import android.widget.ProgressBar
import android.widget.TextView
import androidx.activity.ComponentActivity
import androidx.activity.result.contract.ActivityResultContracts
import androidx.core.content.ContextCompat

/**
 * The only screen of the app. It does these things:
 *  1. Lets you download the speech and translation models (once).
 *  2. Asks Android for the permissions we need.
 *  3. Starts / stops CaptureService (which does the real audio work).
 *  4. Redraws the screen about 20 times per second: volume bar, status, English + Japanese text.
 */
class MainActivity : ComponentActivity() {

    // The things on the screen (they come from res/layout/activity_main.xml)
    private lateinit var statusText: TextView
    private lateinit var levelBar: ProgressBar
    private lateinit var toggleButton: Button
    private lateinit var downloadPanel: View
    private lateinit var downloadBar: ProgressBar
    private lateinit var downloadText: TextView
    private lateinit var downloadButton: Button
    private lateinit var statsText: TextView
    private lateinit var transcriptText: TextView

    // A Handler lets us run a bit of code again and again on a timer.
    private val handler = Handler(Looper.getMainLooper())

    // Remembers which version of the transcript is on screen, so we only redraw when it changes.
    private var shownTranscriptVersion = -1

    // This little loop reads the latest state from the service and updates the screen.
    private val refreshLoop = object : Runnable {
        override fun run() {
            refreshScreen()
            handler.postDelayed(this, 50) // run again in 50 milliseconds
        }
    }

    // STEP 1: ask for microphone (+ notification) permission.
    // When the person answers, Android calls the code inside { ... }.
    private val permissionLauncher =
        registerForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) {
            if (hasMicPermission()) {
                askForScreenCapture()
            } else {
                CaptureService.error = getString(R.string.status_no_permission)
            }
        }

    // STEP 2: the "Start recording or casting?" popup.
    // If the person taps "Start now", we hand the approval to CaptureService.
    private val captureLauncher =
        registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
            val data = result.data
            if (result.resultCode == Activity.RESULT_OK && data != null) {
                val intent = Intent(this, CaptureService::class.java).apply {
                    action = CaptureService.ACTION_START
                    putExtra(CaptureService.EXTRA_RESULT_CODE, result.resultCode)
                    putExtra(CaptureService.EXTRA_RESULT_DATA, data)
                }
                ContextCompat.startForegroundService(this, intent)
            } else {
                CaptureService.error = getString(R.string.status_capture_denied)
            }
        }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        statusText = findViewById(R.id.statusText)
        levelBar = findViewById(R.id.levelBar)
        toggleButton = findViewById(R.id.toggleButton)
        downloadPanel = findViewById(R.id.downloadPanel)
        downloadBar = findViewById(R.id.downloadBar)
        downloadText = findViewById(R.id.downloadText)
        downloadButton = findViewById(R.id.downloadButton)
        statsText = findViewById(R.id.statsText)
        transcriptText = findViewById(R.id.transcriptText)

        toggleButton.setOnClickListener { onToggleClicked() }
        downloadButton.setOnClickListener { ModelManager.startDownload(this) }
    }

    // Only redraw while the screen is visible (saves battery).
    override fun onResume() {
        super.onResume()
        ModelManager.refresh(this) // is the model already on the phone?
        shownTranscriptVersion = -1 // force a redraw of the text
        handler.post(refreshLoop)
    }

    override fun onPause() {
        super.onPause()
        handler.removeCallbacks(refreshLoop)
    }

    private fun onToggleClicked() {
        if (CaptureService.running) {
            // Already listening, so this click means "stop".
            val stop = Intent(this, CaptureService::class.java).apply {
                action = CaptureService.ACTION_STOP
            }
            startService(stop)
        } else {
            CaptureService.error = ""
            requestPermissionsThenCapture()
        }
    }

    private fun requestPermissionsThenCapture() {
        val missing = mutableListOf<String>()
        if (!hasMicPermission()) {
            missing += Manifest.permission.RECORD_AUDIO
        }
        if (Build.VERSION.SDK_INT >= 33 &&
            ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS)
            != PackageManager.PERMISSION_GRANTED
        ) {
            missing += Manifest.permission.POST_NOTIFICATIONS
        }

        if (missing.isEmpty()) {
            askForScreenCapture()
        } else {
            permissionLauncher.launch(missing.toTypedArray())
        }
    }

    private fun askForScreenCapture() {
        val manager = getSystemService(Context.MEDIA_PROJECTION_SERVICE) as MediaProjectionManager
        val intent = if (Build.VERSION.SDK_INT >= 34) {
            // On newer Android, ask for the whole screen so the audio is not limited to one app.
            manager.createScreenCaptureIntent(MediaProjectionConfig.createConfigForDefaultDisplay())
        } else {
            manager.createScreenCaptureIntent()
        }
        captureLauncher.launch(intent)
    }

    private fun hasMicPermission(): Boolean =
        ContextCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO) ==
            PackageManager.PERMISSION_GRANTED

    private fun refreshScreen() {
        val running = CaptureService.running
        val modelReady = ModelManager.allReady // speech model AND translation model

        // --- volume bar: level goes from 0.0 to 1.0, the bar goes from 0 to 100
        levelBar.progress = if (running) (CaptureService.level * 100).toInt() else 0

        // --- model download area (only shown until the model is installed)
        if (modelReady) {
            downloadPanel.visibility = View.GONE
        } else {
            downloadPanel.visibility = View.VISIBLE
            downloadBar.progress = (ModelManager.progress * 100).toInt()
            downloadText.text = ModelManager.message

            when (ModelManager.state) {
                ModelManager.State.DOWNLOADING -> {
                    downloadButton.isEnabled = false
                    downloadButton.text = getString(R.string.btn_downloading)
                }
                ModelManager.State.FAILED -> {
                    downloadButton.isEnabled = true
                    downloadButton.text = getString(R.string.btn_retry)
                }
                ModelManager.State.IDLE -> {
                    downloadButton.isEnabled = true
                    // If the speech model is already here, only the small translation model is missing.
                    downloadButton.text = getString(
                        if (ModelManager.ready) R.string.btn_download_translation else R.string.btn_download
                    )
                }
            }
        }

        // --- start / stop button: needs the model, except that Stop must always work
        toggleButton.isEnabled = modelReady || running
        toggleButton.text = getString(if (running) R.string.btn_stop else R.string.btn_start)

        // --- the line of status text under the title
        val status = when {
            running -> CaptureService.status
            CaptureService.error.isNotEmpty() -> CaptureService.error
            !modelReady -> getString(R.string.status_need_model)
            else -> getString(R.string.status_idle)
        }
        if (statusText.text.toString() != status) {
            statusText.text = status
        }

        // --- speed information
        val stats = if (running) CaptureService.stats else ""
        if (statsText.text.toString() != stats) {
            statsText.text = stats
        }

        // --- the Japanese text (only redrawn when it changed)
        val version = Transcript.version
        if (version != shownTranscriptVersion) {
            shownTranscriptVersion = version
            transcriptText.text = if (Transcript.isEmpty()) {
                getString(R.string.transcript_empty)
            } else {
                buildTranscriptText()
            }
        }
    }

    /**
     * Newest sentence first. Each sentence shows the English in bold,
     * with the Japanese underneath in smaller gray letters.
     * If a sentence could not be translated, only the Japanese is shown.
     */
    private fun buildTranscriptText(): CharSequence {
        val flags = Spanned.SPAN_EXCLUSIVE_EXCLUSIVE
        val out = SpannableStringBuilder()

        for (line in Transcript.snapshot()) {
            if (out.isNotEmpty()) out.append("\n\n")

            val english = line.english
            if (english.isNullOrBlank()) {
                out.append(line.japanese)
            } else {
                out.append(english, StyleSpan(Typeface.BOLD), flags)
                out.append("\n")

                val start = out.length
                out.append(line.japanese)
                out.setSpan(RelativeSizeSpan(0.75f), start, out.length, flags)
                out.setSpan(ForegroundColorSpan(Color.GRAY), start, out.length, flags)
            }
        }
        return out
    }
}
