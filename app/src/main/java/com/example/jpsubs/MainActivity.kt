package com.example.jpsubs

import android.Manifest
import android.app.Activity
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.media.projection.MediaProjectionConfig
import android.media.projection.MediaProjectionManager
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.widget.Button
import android.widget.ProgressBar
import android.widget.TextView
import androidx.activity.ComponentActivity
import androidx.activity.result.contract.ActivityResultContracts
import androidx.core.content.ContextCompat

/**
 * The only screen of the app. It does three things:
 *  1. Asks Android for the permissions we need.
 *  2. Starts / stops CaptureService (which does the real audio work).
 *  3. Redraws the volume bar about 20 times per second.
 */
class MainActivity : ComponentActivity() {

    // The things on the screen (they come from res/layout/activity_main.xml)
    private lateinit var statusText: TextView
    private lateinit var levelBar: ProgressBar
    private lateinit var toggleButton: Button

    // A Handler lets us run a bit of code again and again on a timer.
    private val handler = Handler(Looper.getMainLooper())
    private var wasRunning = false

    // This little loop reads the latest volume from the service and updates the screen.
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
                statusText.text = getString(R.string.status_no_permission)
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
                statusText.text = getString(R.string.status_capture_denied)
            }
        }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        statusText = findViewById(R.id.statusText)
        levelBar = findViewById(R.id.levelBar)
        toggleButton = findViewById(R.id.toggleButton)

        toggleButton.setOnClickListener { onToggleClicked() }
    }

    // Only redraw while the screen is visible (saves battery).
    override fun onResume() {
        super.onResume()
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

        // level goes from 0.0 to 1.0, the bar goes from 0 to 100
        levelBar.progress = if (running) (CaptureService.level * 100).toInt() else 0
        toggleButton.text = getString(if (running) R.string.btn_stop else R.string.btn_start)

        if (running && !wasRunning) {
            statusText.text = getString(R.string.status_listening)
        } else if (!running && wasRunning) {
            statusText.text = getString(R.string.status_idle)
        }
        wasRunning = running
    }
}
