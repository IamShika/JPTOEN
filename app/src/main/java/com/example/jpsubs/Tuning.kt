package com.example.jpsubs

/**
 * Knobs you can change to trade speed against accuracy.
 * Change a number, push to GitHub, install the new APK, and see what happens.
 */
object Tuning {

    // How sure the voice detector must be that someone is talking (0.0 to 1.0).
    // Higher = ignores more background music, but may miss quiet speech.
    const val VAD_THRESHOLD = 0.5f

    // How long a pause (in seconds) ends a sentence. Smaller = subtitles appear
    // sooner but sentences get chopped into smaller pieces.
    const val MIN_SILENCE_SECONDS = 0.3f

    // Ignore sounds shorter than this (clicks, laughs, bumps).
    const val MIN_SPEECH_SECONDS = 0.25f

    // If someone talks without stopping, cut the speech into pieces this long.
    // Smaller = lower delay. Bigger = the recognizer sees more context.
    const val MAX_SPEECH_SECONDS = 6.0f

    // How many CPU threads the recognizer may use. 2 is safe on both of your phones.
    const val RECOGNIZER_THREADS = 2

    // If the phone falls behind, we skip the oldest waiting speech instead of
    // letting the delay grow forever. This is how many pieces may wait.
    const val MAX_WAITING_SEGMENTS = 4
}
