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
        save(context)
        detectorChanged()
    }
}
