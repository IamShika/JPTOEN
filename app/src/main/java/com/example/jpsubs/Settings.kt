package com.example.jpsubs

import android.content.Context

/**
 * The numbers you can change from the Settings panel while the app is running.
 * They are remembered between app launches. The starting values come from Tuning.kt.
 *
 * Each value is @Volatile because the screen changes it while the audio thread reads it.
 */
object Settings {

    private const val PREFS = "jpsubs_settings"

    /** How sure the detector must be that someone is talking. Lower = hears quieter speech. */
    @Volatile var vadThreshold: Float = Tuning.VAD_THRESHOLD

    /** How long a pause (seconds) ends a sentence. */
    @Volatile var minSilenceSeconds: Float = Tuning.MIN_SILENCE_SECONDS

    /** Longest piece (seconds) before the speech is cut, even without a pause. */
    @Volatile var maxSpeechSeconds: Float = Tuning.MAX_SPEECH_SECONDS

    /** Makes quiet audio louder before it is analyzed (1.0 = unchanged). */
    @Volatile var gain: Float = 1.0f

    /** Sounds shorter than this (seconds) are not counted as speech. */
    @Volatile var minSpeechSeconds: Float = Tuning.MIN_SPEECH_SECONDS

    /** Extra audio (seconds) given to the recognizer before and after each piece. */
    @Volatile var paddingSeconds: Float = 0.2f

    /** Clean the audio with noise suppression before the voice detector listens to it. */
    @Volatile var denoise: Boolean = true

    /** Also give the recognizer the cleaned audio (instead of the original). For testing. */
    @Volatile var recognizerUsesDenoised: Boolean = false

    /** Results shorter than this many characters are hidden. */
    @Volatile var minCharacters: Int = Tuning.MIN_CHARACTERS

    /** Show floating subtitles on top of other apps (needs "Display over other apps"). */
    @Volatile var overlayEnabled: Boolean = false

    /** Also show the Japanese line in the floating box. */
    @Volatile var overlayShowJapanese: Boolean = true

    /** How many seconds the floating box stays after the last new line. */
    @Volatile var overlaySeconds: Int = 6

    /** Where the floating box sits, as a fraction of the screen height (0 = top, 1 = bottom).
     *  A fraction (not pixels) so it stays on screen when the phone is rotated. */
    @Volatile var overlayYFraction: Float = 0.12f

    /** Which speech recognizer is used (an id from AsrModels). */
    @Volatile var asrModel: String = AsrModels.DEFAULT_ID

    /** Backup detector: treat any sound louder than the limit below as speech when the
     *  normal voice detector hears nothing (for high-pitched voices and sudden loud sounds). */
    @Volatile var energyBackup: Boolean = false

    /** Loudness limit for the backup detector, in dB (0 = loudest possible, -50 = very quiet). */
    @Volatile var energyThresholdDb: Float = -30f

    /** Which translator is used (an id from Translators). */
    @Volatile var translator: String = Translators.ML_KIT

    /** Keys and addresses for the online translators. They stay on this phone only. */
    @Volatile var deeplKey: String = ""
    @Volatile var aiBaseUrl: String = "https://generativelanguage.googleapis.com/v1beta/openai"
    @Volatile var aiKey: String = ""
    @Volatile var aiModel: String = "gemini-2.5-flash"

    /** True once the first-run guide has been finished or skipped. */
    @Volatile var onboarded: Boolean = false

    /** Update checker: never ask, ask again after this time (ms), and when we last looked (ms). */
    @Volatile var updateNever: Boolean = false
    @Volatile var updateRemindAt: Long = 0L
    @Volatile var updateLastCheck: Long = 0L

    /** Show the debug lines under the Start button. */
    @Volatile var showDebug: Boolean = true

    /**
     * Goes up whenever a setting changes that needs the voice detector to be rebuilt.
     * The audio thread watches this number.
     */
    @Volatile var version: Int = 0

    /** Reads the saved values from the phone's storage. */
    fun load(context: Context) {
        val p = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        vadThreshold = p.getFloat("vadThreshold", Tuning.VAD_THRESHOLD)
        minSilenceSeconds = p.getFloat("minSilenceSeconds", Tuning.MIN_SILENCE_SECONDS)
        maxSpeechSeconds = p.getFloat("maxSpeechSeconds", Tuning.MAX_SPEECH_SECONDS)
        gain = p.getFloat("gain", 1.0f)
        minSpeechSeconds = p.getFloat("minSpeechSeconds", Tuning.MIN_SPEECH_SECONDS)
        paddingSeconds = p.getFloat("paddingSeconds", 0.2f)
        denoise = p.getBoolean("denoise", true)
        recognizerUsesDenoised = p.getBoolean("recognizerUsesDenoised", false)
        minCharacters = p.getInt("minCharacters", Tuning.MIN_CHARACTERS)
        showDebug = p.getBoolean("showDebug", true)
        overlayEnabled = p.getBoolean("overlayEnabled", false)
        onboarded = p.getBoolean("onboarded", false)
        updateNever = p.getBoolean("updateNever", false)
        updateRemindAt = p.getLong("updateRemindAt", 0L)
        updateLastCheck = p.getLong("updateLastCheck", 0L)
        translator = p.getString("translator", Translators.ML_KIT) ?: Translators.ML_KIT
        deeplKey = p.getString("deeplKey", "") ?: ""
        aiBaseUrl = p.getString("aiBaseUrl", aiBaseUrl) ?: aiBaseUrl
        aiKey = p.getString("aiKey", "") ?: ""
        aiModel = p.getString("aiModel", aiModel) ?: aiModel
        energyBackup = p.getBoolean("energyBackup", false)
        energyThresholdDb = p.getFloat("energyThresholdDb", -30f)
        overlayShowJapanese = p.getBoolean("overlayShowJapanese", true)
        overlaySeconds = p.getInt("overlaySeconds", 6)
        overlayYFraction = p.getFloat("overlayYFraction", 0.12f)
        asrModel = p.getString("asrModel", AsrModels.DEFAULT_ID) ?: AsrModels.DEFAULT_ID
    }

    /** Saves the current values to the phone's storage. */
    fun save(context: Context) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit()
            .putFloat("vadThreshold", vadThreshold)
            .putFloat("minSilenceSeconds", minSilenceSeconds)
            .putFloat("maxSpeechSeconds", maxSpeechSeconds)
            .putFloat("gain", gain)
            .putFloat("minSpeechSeconds", minSpeechSeconds)
            .putFloat("paddingSeconds", paddingSeconds)
            .putBoolean("denoise", denoise)
            .putBoolean("recognizerUsesDenoised", recognizerUsesDenoised)
            .putInt("minCharacters", minCharacters)
            .putBoolean("showDebug", showDebug)
            .putBoolean("overlayEnabled", overlayEnabled)
            .putBoolean("onboarded", onboarded)
            .putBoolean("updateNever", updateNever)
            .putLong("updateRemindAt", updateRemindAt)
            .putLong("updateLastCheck", updateLastCheck)
            .putString("translator", translator)
            .putString("deeplKey", deeplKey)
            .putString("aiBaseUrl", aiBaseUrl)
            .putString("aiKey", aiKey)
            .putString("aiModel", aiModel)
            .putBoolean("energyBackup", energyBackup)
            .putFloat("energyThresholdDb", energyThresholdDb)
            .putBoolean("overlayShowJapanese", overlayShowJapanese)
            .putInt("overlaySeconds", overlaySeconds)
            .putFloat("overlayYFraction", overlayYFraction)
            .putString("asrModel", asrModel)
            .apply()
    }

    /** Tells the audio thread to rebuild the voice detector with the new numbers. */
    fun detectorChanged() {
        version++
    }

    fun resetToDefaults(context: Context) {
        vadThreshold = Tuning.VAD_THRESHOLD
        minSilenceSeconds = Tuning.MIN_SILENCE_SECONDS
        maxSpeechSeconds = Tuning.MAX_SPEECH_SECONDS
        gain = 1.0f
        minSpeechSeconds = Tuning.MIN_SPEECH_SECONDS
        paddingSeconds = 0.2f
        denoise = true
        recognizerUsesDenoised = false
        minCharacters = Tuning.MIN_CHARACTERS
        showDebug = true
        overlayShowJapanese = true
        overlaySeconds = 6
        energyBackup = false
        energyThresholdDb = -30f
        save(context)
        detectorChanged()
    }
}
