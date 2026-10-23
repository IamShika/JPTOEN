package com.example.jpsubs

import android.Manifest
import android.annotation.SuppressLint
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.content.pm.ServiceInfo
import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioPlaybackCaptureConfiguration
import android.media.AudioRecord
import android.media.projection.MediaProjection
import android.media.projection.MediaProjectionManager
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.os.SystemClock
import android.util.Log
import androidx.core.app.NotificationCompat
import androidx.core.app.ServiceCompat
import androidx.core.content.ContextCompat
import androidx.core.content.IntentCompat
import com.google.mlkit.nl.translate.Translator
import com.k2fsa.sherpa.onnx.FeatureConfig
import com.k2fsa.sherpa.onnx.OfflineFunAsrNanoModelConfig
import com.k2fsa.sherpa.onnx.OfflineModelConfig
import com.k2fsa.sherpa.onnx.OfflineQwen3AsrModelConfig
import com.k2fsa.sherpa.onnx.OfflineRecognizer
import com.k2fsa.sherpa.onnx.OfflineRecognizerConfig
import com.k2fsa.sherpa.onnx.OfflineSenseVoiceModelConfig
import com.k2fsa.sherpa.onnx.OfflineTransducerModelConfig
import com.k2fsa.sherpa.onnx.OfflineWhisperModelConfig
import java.util.Locale
import java.util.concurrent.LinkedBlockingQueue
import kotlin.concurrent.thread
import kotlin.math.log10
import kotlin.math.sqrt

/**
 * A "foreground service" keeps running while you are in another app (like YouTube).
 *
 * The pipeline inside it:
 *
 *   phone audio --> [noise suppression] --> [voice detector] --> pieces of speech
 *       --> [Japanese recognizer] --> Japanese text --> [translator] --> English text
 *
 *  - The first two steps live in FrontEnd.kt: noise suppression (optional) and the voice
 *    detector (Silero VAD), which cuts the audio into sentences by finding pauses.
 *  - The recognizer (ReazonSpeech zipformer, run by sherpa-onnx) turns each sentence into text.
 *  - The translator (Google ML Kit, on the phone) turns the Japanese into English.
 *  - Both texts go into `Transcript`, which the screen displays.
 *
 * Two threads work at once: one keeps reading audio (it must never stall, or sound is lost),
 * and one does the slow recognition and translation work.
 *
 * LATER MILESTONE: the place marked "FUTURE HOOK" is where the English text will also be
 * sent to the floating subtitles (Milestone 4).
 */
class CaptureService : Service() {

    companion object {
        const val ACTION_START = "com.example.jpsubs.START"
        const val ACTION_STOP = "com.example.jpsubs.STOP"
        const val EXTRA_RESULT_CODE = "resultCode"
        const val EXTRA_RESULT_DATA = "resultData"

        // 16 kHz mono is what the speech engine expects.
        const val SAMPLE_RATE = 16000

        private const val TAG = "CaptureService"
        private const val CHANNEL_ID = "capture"
        private const val NOTIFICATION_ID = 1

        // Shared with MainActivity so it can draw things.
        @Volatile var running: Boolean = false
        @Volatile var level: Float = 0f // 0.0 (silent) to 1.0 (very loud)
        @Volatile var status: String = "" // what we are doing right now
        @Volatile var error: String = "" // why we stopped, if something went wrong
        @Volatile var stats: String = "" // speed information, useful for testing phones
    }

    private var projection: MediaProjection? = null
    private var audioRecord: AudioRecord? = null
    private var worker: Thread? = null
    private var overlay: SubtitleOverlay? = null

    /** Shown in the status line, e.g. which recognizer is in use. */
    @Volatile
    private var recognizerNote = ""

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

        error = ""
        status = ""
        stats = ""
        Settings.load(this)
        DebugInfo.reset()

        val resultCode = intent.getIntExtra(EXTRA_RESULT_CODE, 0)
        val resultData = IntentCompat.getParcelableExtra(intent, EXTRA_RESULT_DATA, Intent::class.java)

        // Android 14+ wants the notification up BEFORE we touch the projection.
        showForegroundNotification()

        ModelManager.refresh(this)
        if (!ModelManager.ready) {
            error = "The speech model is not downloaded yet."
            stopCapture()
            return
        }

        if (resultData == null ||
            ContextCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO)
            != PackageManager.PERMISSION_GRANTED
        ) {
            Log.e(TAG, "Missing permission or projection data")
            error = "Missing permission."
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

            audioRecord = record
            Transcript.clear()
            keepReading = true
            running = true
            status = "Loading the speech model…"

            // Floating subtitles (only appear if the person switched them on and allowed them).
            overlay = SubtitleOverlay(this).also { it.start() }

            // Everything slow happens on a separate thread so the app stays responsive.
            worker = thread(name = "audio-pipeline") { runPipeline(record) }
        } catch (e: Exception) {
            Log.e(TAG, "Could not start capture", e)
            error = "Could not start capture: ${e.message}"
            stopCapture()
        }
    }

    // ---------------------------------------------------------------------------------------
    // The pipeline
    // ---------------------------------------------------------------------------------------

    private fun runPipeline(record: AudioRecord) {
        var frontEnd: FrontEnd? = null
        var recognizer: OfflineRecognizer? = null
        var translator: Translator? = null
        var decoderThread: Thread? = null

        // Pieces of speech wait here for the recognizer.
        val waiting = LinkedBlockingQueue<FloatArray>()

        try {
            // Loading takes a few seconds (the model is about 150 MB).
            val loadedRecognizer = createRecognizer()
            recognizer = loadedRecognizer

            // The translator is optional: if it fails we still show the Japanese text.
            var translatorNote = ""
            try {
                translator = TranslationManager.openTranslator()
            } catch (e: Exception) {
                Log.e(TAG, "Translator unavailable", e)
                translatorNote = " (translation unavailable: ${e.message})"
            }
            val loadedTranslator = translator

            // The front end = noise suppression + voice detector (see FrontEnd.kt).
            val loadedFrontEnd = FrontEnd(this)
            frontEnd = loadedFrontEnd

            if (!keepReading) return // the person pressed Stop while we were loading

            status = "Listening…$recognizerNote$translatorNote"
            record.startRecording()

            // Thread 2: turns waiting speech into text, then into English.
            decoderThread = thread(name = "asr-decode") {
                decodeLoop(loadedRecognizer, loadedTranslator, waiting)
            }

            // Thread 1 (this one): reads audio and finds the sentences.
            val buffer = ShortArray(SAMPLE_RATE / 10) // 100 milliseconds of sound
            var hearingSince = 0L // when the detector started hearing speech (0 = not hearing)

            while (keepReading) {
                // Did the person change a detector setting? Then rebuild the front end.
                // (The very first time round, this builds it.)
                loadedFrontEnd.applySettingsIfChanged()

                val count = record.read(buffer, 0, buffer.size)
                if (count > 0) {
                    level = measureLevel(buffer, count)

                    // The speech engine wants numbers between -1.0 and 1.0.
                    // `gain` makes quiet audio louder (clamped so it can't go past the limits).
                    val gain = Settings.gain
                    val samples = FloatArray(count) { (buffer[it] / 32768f * gain).coerceIn(-1f, 1f) }

                    // Noise suppression, voice detector, padding. Out come finished sentences.
                    val pieces = loadedFrontEnd.feed(samples)

                    // For the debug lines: is the detector hearing speech right now, and for how long?
                    val now = SystemClock.elapsedRealtime()
                    if (loadedFrontEnd.isHearingSpeech()) {
                        if (hearingSince == 0L) hearingSince = now
                        DebugInfo.detector = String.format(
                            Locale.US, "Detector: hearing speech for %.1f s", (now - hearingSince) / 1000.0
                        )
                    } else {
                        hearingSince = 0L
                        DebugInfo.detector = "Detector: quiet"
                    }

                    for (piece in pieces) {
                        enqueue(waiting, piece)
                    }
                } else if (count < 0) {
                    if (keepReading) {
                        Log.e(TAG, "AudioRecord.read failed with code $count")
                        error = "Audio capture stopped unexpectedly (code $count)."
                        Handler(Looper.getMainLooper()).post { stopCapture() }
                    }
                    break
                }
            }
        } catch (t: Throwable) {
            Log.e(TAG, "Pipeline failed", t)
            error = "Error: ${t.message ?: t.javaClass.simpleName}"
            Handler(Looper.getMainLooper()).post { stopCapture() }
        } finally {
            // Tidy up, in the right order: stop the decoder first, then free the models.
            waiting.clear()
            waiting.put(FloatArray(0)) // an empty piece means "stop"
            try {
                decoderThread?.join()
            } catch (_: InterruptedException) {
                // we are shutting down anyway
            }
            recognizer?.release()
            frontEnd?.release()
            translator?.close()
        }
    }

    /**
     * Runs on its own thread: takes speech pieces off the queue, recognizes the
     * Japanese, translates it, and writes both into the Transcript.
     */
    private fun decodeLoop(
        recognizer: OfflineRecognizer,
        translator: Translator?,
        waiting: LinkedBlockingQueue<FloatArray>,
    ) {
        while (true) {
            val piece = waiting.take() // sleeps until something arrives
            if (piece.isEmpty()) break

            // Step 1: Japanese speech -> Japanese text
            val recognizeStart = SystemClock.elapsedRealtime()
            val japanese = try {
                cleanRecognized(transcribe(recognizer, piece).trim())
            } catch (t: Throwable) {
                Log.e(TAG, "Recognition failed", t)
                ""
            }
            val recognizeSeconds = (SystemClock.elapsedRealtime() - recognizeStart) / 1000.0

            // Ignore empty results and one-letter noises like "え".
            val pieceSeconds = piece.size / SAMPLE_RATE.toDouble()
            if (japanese.length < Settings.minCharacters) {
                DebugInfo.onResult(pieceSeconds, if (japanese.isEmpty()) "empty" else "short")
                continue
            }
            DebugInfo.onResult(pieceSeconds, "ok")

            // Step 2: Japanese text -> English text (stays null if no translator)
            val translateStart = SystemClock.elapsedRealtime()
            val english = translator?.let { TranslationManager.translate(it, japanese) }
            val translateSeconds = (SystemClock.elapsedRealtime() - translateStart) / 1000.0

            stats = String.format(
                Locale.US,
                "Last piece: %.1f s of speech, recognized in %.2f s, translated in %.2f s (%d waiting)",
                pieceSeconds, recognizeSeconds, translateSeconds, waiting.size
            )

            Transcript.add(japanese, english)

            // FUTURE HOOK: Milestone 4 will also send `english` to the floating subtitles here.
        }
    }

    /** One piece of speech in, Japanese text out (no translation yet). */
    private fun transcribe(recognizer: OfflineRecognizer, samples: FloatArray): String {
        val stream = recognizer.createStream()
        try {
            stream.acceptWaveform(samples, SAMPLE_RATE)
            recognizer.decode(stream)
            return recognizer.getResult(stream).text
        } finally {
            stream.release()
        }
    }

    /**
     * Adds a piece to the waiting line. If the phone is too slow and the line is
     * too long, the oldest pieces are skipped so the subtitles don't fall further
     * and further behind.
     */
    private fun enqueue(waiting: LinkedBlockingQueue<FloatArray>, piece: FloatArray) {
        var skipped = false
        while (waiting.size >= Tuning.MAX_WAITING_SEGMENTS) {
            waiting.poll()
            DebugInfo.onSkipped()
            skipped = true
        }
        waiting.add(piece)
        DebugInfo.onQueued()
        if (skipped) {
            Transcript.add("(skipped some speech: the phone could not keep up)")
        }
    }

    /** The recognizer the person picked in Settings (falls back to ReazonSpeech). */
    private fun createRecognizer(): OfflineRecognizer {
        AsrModels.refresh(this)
        val id = Settings.asrModel
        if (id != AsrModels.DEFAULT_ID) {
            if (AsrModels.isInstalled(id)) {
                recognizerNote = " (recognizer: ${AsrModels.info(id).label})"
                return createOptionalRecognizer(id)
            }
            recognizerNote = " (${AsrModels.info(id).label} is not downloaded, using ReazonSpeech)"
        }
        return createReazonRecognizer()
    }

    /**
     * Tidies the recognizer's text. The newer AI recognizers sometimes leak control words
     * ("<|endoftext|>", "language Chinese", "###") or invent Chinese words for Japanese speech.
     * Returns "" when nothing real is left, so the piece is dropped.
     */
    private fun cleanRecognized(raw: String): String {
        var t = raw.replace(Regex("<\\|[^|>]*\\|>"), "").replace("###", "")
        t = t.replace(Regex("^\\s*language\\s*[A-Za-z]+", RegexOption.IGNORE_CASE), "").trim()
        if (t.none { it.isLetterOrDigit() }) return ""

        // For these models, real Japanese always contains kana. Kanji with no kana is almost
        // always an invented Chinese word.
        val id = Settings.asrModel
        if (id == "qwen3asr" || id == "funasr" || id == "whisper-small") {
            val hasKana = t.any { it in '\u3040'..'\u30FF' }
            val hasHan = t.any { it in '\u4E00'..'\u9FFF' }
            if (!hasKana && hasHan) return ""
        }
        return t
    }

    /** Builds one of the optional recognizers from the files in its own folder. */
    private fun createOptionalRecognizer(id: String): OfflineRecognizer {
        fun p(local: String) = AsrModels.path(this, id, local)
        val model = when (id) {
            "sensevoice" -> OfflineModelConfig(
                senseVoice = OfflineSenseVoiceModelConfig(
                    model = p("model.int8.onnx"),
                    language = "ja",
                    useInverseTextNormalization = true,
                ),
                tokens = p("tokens.txt"),
                numThreads = Tuning.RECOGNIZER_THREADS,
                provider = "cpu",
            )
            "whisper-small" -> OfflineModelConfig(
                whisper = OfflineWhisperModelConfig(
                    encoder = p("encoder.onnx"),
                    decoder = p("decoder.onnx"),
                    language = "ja",
                    task = "transcribe",
                ),
                tokens = p("tokens.txt"),
                numThreads = Tuning.RECOGNIZER_THREADS,
                provider = "cpu",
            )
            "qwen3asr" -> OfflineModelConfig(
                qwen3Asr = OfflineQwen3AsrModelConfig(
                    convFrontend = p("conv_frontend.onnx"),
                    encoder = p("encoder.int8.onnx"),
                    decoder = p("decoder.int8.onnx"),
                    tokenizer = AsrModels.folder(this, id).absolutePath + "/tokenizer",
                    maxTotalLen = 512,
                    maxNewTokens = 128,
                ),
                numThreads = 3,
                provider = "cpu",
            )
            "funasr" -> OfflineModelConfig(
                funasrNano = OfflineFunAsrNanoModelConfig(
                    encoderAdaptor = p("encoder_adaptor.int8.onnx"),
                    llm = p("llm.int8.onnx"),
                    embedding = p("embedding.int8.onnx"),
                    tokenizer = AsrModels.folder(this, id).absolutePath + "/tokenizer",
                ),
                numThreads = 3,
                provider = "cpu",
            )
            else -> throw IllegalArgumentException("Unknown recognizer: $id")
        }
        val config = OfflineRecognizerConfig(
            featConfig = FeatureConfig(sampleRate = SAMPLE_RATE, featureDim = 80),
            modelConfig = model,
            decodingMethod = "greedy_search",
        )
        return OfflineRecognizer(assetManager = null, config = config)
    }

    private fun createReazonRecognizer(): OfflineRecognizer {
        val config = OfflineRecognizerConfig(
            featConfig = FeatureConfig(sampleRate = SAMPLE_RATE, featureDim = 80),
            modelConfig = OfflineModelConfig(
                transducer = OfflineTransducerModelConfig(
                    encoder = ModelManager.path(this, ModelManager.ENCODER_FILE),
                    decoder = ModelManager.path(this, ModelManager.DECODER_FILE),
                    joiner = ModelManager.path(this, ModelManager.JOINER_FILE),
                ),
                tokens = ModelManager.path(this, ModelManager.TOKENS_FILE),
                modelType = "transducer",
                numThreads = Tuning.RECOGNIZER_THREADS,
                provider = "cpu",
                debug = false,
            ),
            decodingMethod = "greedy_search",
        )
        return OfflineRecognizer(assetManager = null, config = config)
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

    // ---------------------------------------------------------------------------------------
    // Notification and shutting down
    // ---------------------------------------------------------------------------------------

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
        overlay?.stop()
        overlay = null

        // The worker thread frees the speech models itself when it finishes.
        // We only wait a moment so the screen doesn't freeze.
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
        status = ""
    }
}
