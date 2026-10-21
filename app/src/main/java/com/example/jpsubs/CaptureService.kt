package com.example.jpsubs

import android.annotation.SuppressLint
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.content.pm.ServiceInfo
import android.Manifest
import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioPlaybackCaptureConfiguration
import android.media.AudioRecord
import android.media.projection.MediaProjection
import android.media.projection.MediaProjectionManager
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.util.Log
import androidx.core.app.NotificationCompat
import androidx.core.app.ServiceCompat
import androidx.core.content.ContextCompat
import androidx.core.content.IntentCompat
import kotlin.concurrent.thread
import kotlin.math.log10
import kotlin.math.sqrt

/**
 * A "foreground service" keeps running while you are in another app (like YouTube).
 * This one records whatever sound the phone is playing and measures how loud it is.
 *
 * LATER MILESTONES: the place marked "FUTURE HOOK" below is where we will send the
 * audio to the speech-to-text engine instead of only measuring the volume.
 */
class CaptureService : Service() {

    companion object {
        const val ACTION_START = "com.example.jpsubs.START"
        const val ACTION_STOP = "com.example.jpsubs.STOP"
        const val EXTRA_RESULT_CODE = "resultCode"
        const val EXTRA_RESULT_DATA = "resultData"

        // 16 kHz mono is what most speech-to-text engines (Whisper etc.) expect.
        const val SAMPLE_RATE = 16000

        private const val TAG = "CaptureService"
        private const val CHANNEL_ID = "capture"
        private const val NOTIFICATION_ID = 1

        // Shared with MainActivity so it can draw the bar.
        @Volatile var running: Boolean = false
        @Volatile var level: Float = 0f // 0.0 (silent) to 1.0 (very loud)
    }

    private var projection: MediaProjection? = null
    private var audioRecord: AudioRecord? = null
    private var worker: Thread? = null

    @Volatile
    private var keepReading = false

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_START -> startCapture(intent)
            ACTION_STOP -> stopCapture()
        }
        return START_NOT_STICKY // if Android kills us, do not auto-restart
    }

    override fun onDestroy() {
        releaseEverything()
        super.onDestroy()
    }

    @SuppressLint("MissingPermission") // we check the permission ourselves just below
    private fun startCapture(intent: Intent) {
        if (running) return

        val resultCode = intent.getIntExtra(EXTRA_RESULT_CODE, 0)
        val resultData = IntentCompat.getParcelableExtra(intent, EXTRA_RESULT_DATA, Intent::class.java)

        // Android 14+ wants the notification up BEFORE we touch the projection.
        showForegroundNotification()

        if (resultData == null ||
            ContextCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO)
            != PackageManager.PERMISSION_GRANTED
        ) {
            Log.e(TAG, "Missing permission or projection data")
            stopCapture()
            return
        }

        try {
            val manager = getSystemService(Context.MEDIA_PROJECTION_SERVICE) as MediaProjectionManager
            val proj = manager.getMediaProjection(resultCode, resultData)

            // If the person ends the capture from the system (status bar chip), we stop too.
            proj.registerCallback(object : MediaProjection.Callback() {
                override fun onStop() {
                    stopCapture()
                }
            }, Handler(Looper.getMainLooper()))
            projection = proj

            // "Capture the sound that media apps / games are playing."
            val captureConfig = AudioPlaybackCaptureConfiguration.Builder(proj)
                .addMatchingUsage(AudioAttributes.USAGE_MEDIA)
                .addMatchingUsage(AudioAttributes.USAGE_GAME)
                .addMatchingUsage(AudioAttributes.USAGE_UNKNOWN)
                .build()

            val format = AudioFormat.Builder()
                .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
                .setSampleRate(SAMPLE_RATE)
                .setChannelMask(AudioFormat.CHANNEL_IN_MONO)
                .build()

            val minBuffer = AudioRecord.getMinBufferSize(
                SAMPLE_RATE,
                AudioFormat.CHANNEL_IN_MONO,
                AudioFormat.ENCODING_PCM_16BIT
            )

            val record = AudioRecord.Builder()
                .setAudioPlaybackCaptureConfig(captureConfig)
                .setAudioFormat(format)
                .setBufferSizeInBytes(maxOf(minBuffer, SAMPLE_RATE * 2)) // at least 1 second
                .build()

            record.startRecording()
            audioRecord = record
            keepReading = true
            running = true

            // Do the reading on a separate thread so the app stays responsive.
            worker = thread(name = "audio-capture") {
                val buffer = ShortArray(SAMPLE_RATE / 10) // 100 milliseconds of sound
                while (keepReading) {
                    val count = record.read(buffer, 0, buffer.size)
                    if (count > 0) {
                        level = measureLevel(buffer, count)

                        // FUTURE HOOK: Milestone 2 will send `buffer` to speech-to-text here.
                    } else if (count < 0) {
                        Log.e(TAG, "AudioRecord.read failed with code $count")
                        Handler(Looper.getMainLooper()).post { stopCapture() }
                        break
                    }
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "Could not start capture", e)
            stopCapture()
        }
    }

    /** Turns a chunk of samples into a number from 0.0 to 1.0 (silent to loud). */
    private fun measureLevel(samples: ShortArray, count: Int): Float {
        var sumOfSquares = 0.0
        for (i in 0 until count) {
            val s = samples[i] / 32768.0 // each sample is -1.0 .. 1.0
            sumOfSquares += s * s
        }
        val rms = sqrt(sumOfSquares / count) // "average loudness"
        if (rms < 1e-6) return 0f

        val decibels = 20 * log10(rms) // 0 dB = max, -60 dB = nearly silent
        return ((decibels + 60) / 60).coerceIn(0.0, 1.0).toFloat()
    }

    private fun showForegroundNotification() {
        val manager = getSystemService(NotificationManager::class.java)
        manager.createNotificationChannel(
            NotificationChannel(CHANNEL_ID, "Audio capture", NotificationManager.IMPORTANCE_LOW)
        )

        val stopIntent = PendingIntent.getService(
            this,
            0,
            Intent(this, CaptureService::class.java).setAction(ACTION_STOP),
            PendingIntent.FLAG_IMMUTABLE
        )

        val notification = NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(android.R.drawable.ic_btn_speak_now)
            .setContentTitle(getString(R.string.app_name))
            .setContentText(getString(R.string.notif_text))
            .setOngoing(true)
            .addAction(0, getString(R.string.btn_stop), stopIntent)
            .build()

        ServiceCompat.startForeground(
            this,
            NOTIFICATION_ID,
            notification,
            ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PROJECTION
        )
    }

    /** Stops the audio, hides the notification and shuts the service down. */
    private fun stopCapture() {
        releaseEverything()
        stopForeground(STOP_FOREGROUND_REMOVE)
        stopSelf()
    }

    /** Cleans up. Safe to call more than once. */
    private fun releaseEverything() {
        keepReading = false
        worker?.join(500)
        worker = null

        audioRecord?.let {
            try {
                it.stop()
            } catch (_: IllegalStateException) {
                // already stopped, nothing to do
            }
            it.release()
        }
        audioRecord = null

        val p = projection
        projection = null
        p?.stop()

        running = false
        level = 0f
    }
}
