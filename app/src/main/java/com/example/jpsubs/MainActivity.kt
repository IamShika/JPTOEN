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
import android.widget.CheckBox
import android.widget.AdapterView
import android.widget.ArrayAdapter
import android.widget.ProgressBar
import android.widget.Spinner
import android.widget.SeekBar
import android.widget.TextView
import androidx.activity.ComponentActivity
import androidx.activity.result.contract.ActivityResultContracts
import androidx.core.content.ContextCompat
import java.util.Locale
import kotlin.math.roundToInt

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
    private lateinit var settingsButton: Button
    private lateinit var settingsPanel: View
    private lateinit var debugCheck: CheckBox
    private lateinit var overlayCheck: CheckBox
    private lateinit var energyCheck: CheckBox
    private lateinit var asrSpinner: Spinner
    private lateinit var asrNote: TextView
    private lateinit var asrBar: ProgressBar
    private lateinit var asrButton: Button
    private var asrSignature = "" // remembers what the dropdown shows, so we only rebuild it when needed
    private lateinit var overlayJpCheck: CheckBox
    private lateinit var denoiseCheck: CheckBox
    private lateinit var denoiseAsrCheck: CheckBox
    private lateinit var resetButton: Button

    // One entry per slider in the settings panel.
    private class Slider(
        val bar: SeekBar,
        val label: TextView,
        val title: String,
        val min: Float,
        val step: Float,
        val decimals: Int,
        val unit: String,
        val read: () -> Float,   // gets the current value from Settings
        val write: (Float) -> Unit, // puts a new value into Settings
    )

    private val sliders = mutableListOf<Slider>()

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

        setUpSettingsPanel()
    }

    // ---------------------------------------------------------------------------------------
    // The settings panel
    // ---------------------------------------------------------------------------------------

    private fun setUpSettingsPanel() {
        Settings.load(this)

        settingsButton = findViewById(R.id.settingsButton)
        settingsPanel = findViewById(R.id.settingsPanel)
        debugCheck = findViewById(R.id.debugCheck)
        overlayCheck = findViewById(R.id.overlayCheck)
        energyCheck = findViewById(R.id.energyCheck)
        asrSpinner = findViewById(R.id.asrSpinner)
        asrNote = findViewById(R.id.asrNote)
        asrBar = findViewById(R.id.asrBar)
        asrButton = findViewById(R.id.asrButton)
        overlayJpCheck = findViewById(R.id.overlayJpCheck)
        denoiseCheck = findViewById(R.id.denoiseCheck)
        denoiseAsrCheck = findViewById(R.id.denoiseAsrCheck)
        resetButton = findViewById(R.id.resetButton)

        settingsButton.setOnClickListener {
            val show = settingsPanel.visibility != View.VISIBLE
            settingsPanel.visibility = if (show) View.VISIBLE else View.GONE
            settingsButton.text = getString(if (show) R.string.btn_hide_settings else R.string.btn_show_settings)
        }

        // The last "true/false" says whether the voice detector must be rebuilt after a change.
        addSlider(
            R.id.sliderThreshold, R.id.labelThreshold,
            "Detector threshold (lower = hears quieter voices, higher = ignores more noise)",
            min = 0.1f, max = 0.9f, step = 0.05f, decimals = 2, unit = "",
            read = { Settings.vadThreshold }, write = { Settings.vadThreshold = it },
            affectsDetector = true,
        )
        addSlider(
            R.id.sliderSilence, R.id.labelSilence,
            "Pause that ends a sentence (shorter = subtitles sooner, but chopped up)",
            min = 0.1f, max = 1.0f, step = 0.05f, decimals = 2, unit = " s",
            read = { Settings.minSilenceSeconds }, write = { Settings.minSilenceSeconds = it },
            affectsDetector = true,
        )
        addSlider(
            R.id.sliderMaxSpeech, R.id.labelMaxSpeech,
            "Longest piece before cutting (shorter = copes better with fast talkers and noise)",
            min = 1.0f, max = 10.0f, step = 0.5f, decimals = 1, unit = " s",
            read = { Settings.maxSpeechSeconds }, write = { Settings.maxSpeechSeconds = it },
            affectsDetector = true,
        )
        addSlider(
            R.id.sliderMinSpeech, R.id.labelMinSpeech,
            "Shortest sound counted as speech (lower = catches short words like はい)",
            min = 0.05f, max = 0.5f, step = 0.05f, decimals = 2, unit = " s",
            read = { Settings.minSpeechSeconds }, write = { Settings.minSpeechSeconds = it },
            affectsDetector = true,
        )
        addSlider(
            R.id.sliderEnergy, R.id.labelEnergy,
            "Backup detector loudness limit (lower = triggers on quieter sounds; watch the Loudness line)",
            min = -50.0f, max = -10.0f, step = 1.0f, decimals = 0, unit = " dB",
            read = { Settings.energyThresholdDb }, write = { Settings.energyThresholdDb = it },
            affectsDetector = false,
        )
        addSlider(
            R.id.sliderPadding, R.id.labelPadding,
            "Extra audio around each piece (stops first and last sounds being clipped)",
            min = 0.0f, max = 0.5f, step = 0.05f, decimals = 2, unit = " s",
            read = { Settings.paddingSeconds }, write = { Settings.paddingSeconds = it },
            affectsDetector = false,
        )
        addSlider(
            R.id.sliderGain, R.id.labelGain,
            "Volume boost (helps quiet voices; also boosts noise)",
            min = 1.0f, max = 8.0f, step = 0.5f, decimals = 1, unit = "x",
            read = { Settings.gain }, write = { Settings.gain = it },
            affectsDetector = false,
        )
        addSlider(
            R.id.sliderMinChars, R.id.labelMinChars,
            "Hide results shorter than this many characters",
            min = 1f, max = 5f, step = 1f, decimals = 0, unit = "",
            read = { Settings.minCharacters.toFloat() }, write = { Settings.minCharacters = it.roundToInt() },
            affectsDetector = false,
        )

        debugCheck.setOnCheckedChangeListener { _, checked ->
            Settings.showDebug = checked
            Settings.save(this)
        }

        energyCheck.setOnCheckedChangeListener { _, checked ->
            Settings.energyBackup = checked
            Settings.save(this)
        }

        // Choosing a speech recognizer, downloading it, deleting it.
        asrSpinner.onItemSelectedListener = object : AdapterView.OnItemSelectedListener {
            override fun onItemSelected(parent: AdapterView<*>?, view: View?, position: Int, id: Long) {
                val picked = AsrModels.ALL[position].id
                if (picked != Settings.asrModel) {
                    Settings.asrModel = picked
                    Settings.save(this@MainActivity)
                }
                refreshAsrPanel()
            }

            override fun onNothingSelected(parent: AdapterView<*>?) {}
        }
        asrButton.setOnClickListener {
            val info = AsrModels.info(Settings.asrModel)
            if (AsrModels.isInstalled(info.id)) {
                if (info.release != null) AsrModels.delete(this, info.id)
            } else {
                AsrModels.download(this, info.id)
            }
            refreshAsrPanel()
        }

        // Floating subtitles. Android makes the person allow "Display over other apps"
        // on a special screen; we open it and the box starts working once they allow it.
        overlayCheck.setOnCheckedChangeListener { _, checked ->
            Settings.overlayEnabled = checked
            Settings.save(this)
            if (checked && !android.provider.Settings.canDrawOverlays(this)) {
                startActivity(
                    Intent(
                        android.provider.Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
                        android.net.Uri.parse("package:$packageName")
                    )
                )
            }
        }
        overlayJpCheck.setOnCheckedChangeListener { _, checked ->
            Settings.overlayShowJapanese = checked
            Settings.save(this)
        }

        // These two change how the audio is prepared, so the front end is rebuilt.
        denoiseCheck.setOnCheckedChangeListener { _, checked ->
            Settings.denoise = checked
            Settings.save(this)
            Settings.detectorChanged()
        }
        denoiseAsrCheck.setOnCheckedChangeListener { _, checked ->
            Settings.recognizerUsesDenoised = checked
            Settings.save(this)
            Settings.detectorChanged()
        }

        resetButton.setOnClickListener {
            Settings.resetToDefaults(this)
            refreshSettingsPanel()
        }

        refreshSettingsPanel()
    }

    private fun addSlider(
        barId: Int,
        labelId: Int,
        title: String,
        min: Float,
        max: Float,
        step: Float,
        decimals: Int,
        unit: String,
        read: () -> Float,
        write: (Float) -> Unit,
        affectsDetector: Boolean,
    ) {
        val slider = Slider(
            bar = findViewById(barId),
            label = findViewById(labelId),
            title = title, min = min, step = step, decimals = decimals, unit = unit,
            read = read, write = write,
        )
        // A SeekBar only knows whole numbers 0, 1, 2 ... so we convert to and from our value.
        slider.bar.max = ((max - min) / step).roundToInt()
        sliders += slider

        slider.bar.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(seekBar: SeekBar, progress: Int, fromUser: Boolean) {
                val value = min + progress * step
                slider.label.text = describeSlider(slider, value)
                if (fromUser) slider.write(value) // takes effect immediately
            }

            override fun onStartTrackingTouch(seekBar: SeekBar) {}

            // When you let go: remember the value, and rebuild the detector if needed.
            override fun onStopTrackingTouch(seekBar: SeekBar) {
                Settings.save(this@MainActivity)
                if (affectsDetector) Settings.detectorChanged()
            }
        })
    }

    private fun describeSlider(slider: Slider, value: Float): String =
        slider.title + ": " + String.format(Locale.US, "%.${slider.decimals}f", value) + slider.unit

    /** Updates the recognizer dropdown, its description and the download / delete button. */
    private fun refreshAsrPanel() {
        val models = AsrModels.ALL
        val installed = AsrModels.installed

        // Rebuild the dropdown only when something changed (so it does not flicker or close).
        val signature = models.joinToString("|") { it.id + installed.contains(it.id) } + Settings.asrModel
        if (signature != asrSignature) {
            asrSignature = signature
            val labels = models.map { it.label + if (installed.contains(it.id)) "  (downloaded)" else "" }
            asrSpinner.adapter = ArrayAdapter(this, android.R.layout.simple_spinner_dropdown_item, labels)
            asrSpinner.setSelection(models.indexOfFirst { it.id == Settings.asrModel }.coerceAtLeast(0), false)
        }

        val info = AsrModels.info(Settings.asrModel)
        val busy = AsrModels.busyId
        asrNote.text = info.note + if (AsrModels.message.isNotEmpty()) "\n" + AsrModels.message else ""
        asrBar.visibility = if (busy != null) View.VISIBLE else View.GONE
        asrBar.progress = (AsrModels.progress * 100).toInt()

        when {
            busy != null -> {
                asrButton.isEnabled = false
                asrButton.text = getString(R.string.btn_downloading)
            }
            AsrModels.isInstalled(info.id) && info.release == null -> {
                asrButton.isEnabled = false
                asrButton.text = "Built in (cannot be deleted)"
            }
            AsrModels.isInstalled(info.id) -> {
                asrButton.isEnabled = true
                asrButton.text = "Delete this model (frees about ${info.totalMb.toInt()} MB)"
            }
            else -> {
                asrButton.isEnabled = ModelManager.state != ModelManager.State.DOWNLOADING
                asrButton.text = "Download (about ${info.totalMb.toInt()} MB)"
            }
        }
    }

    /** Moves every slider to the value stored in Settings. */
    private fun refreshSettingsPanel() {
        for (s in sliders) {
            val value = s.read()
            s.bar.progress = ((value - s.min) / s.step).roundToInt()
            s.label.text = describeSlider(s, value)
        }
        debugCheck.isChecked = Settings.showDebug
        energyCheck.isChecked = Settings.energyBackup
        refreshAsrPanel()
        overlayCheck.isChecked = Settings.overlayEnabled
        overlayJpCheck.isChecked = Settings.overlayShowJapanese
        denoiseCheck.isChecked = Settings.denoise
        denoiseAsrCheck.isChecked = Settings.recognizerUsesDenoised
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
        refreshAsrPanel() // keeps the recognizer download progress moving

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

        // --- speed information, plus the debug lines if they are switched on
        val stats = if (!running) {
            ""
        } else if (Settings.showDebug) {
            DebugInfo.detector + (if (DebugInfo.loudness.isNotEmpty()) "\n" + DebugInfo.loudness else "") + "\n" + DebugInfo.summary() + "\n" + CaptureService.stats
        } else {
            CaptureService.stats
        }
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
