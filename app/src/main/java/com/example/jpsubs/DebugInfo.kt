package com.example.jpsubs

import java.util.Locale

/**
 * Notes about what the pipeline is doing, shown under the Start button when
 * "Show debug info" is ticked. This is how we find out WHICH part is failing:
 *
 *  - "hearing speech for 6.0 s" stuck for a long time: the detector thinks noise is speech.
 *  - many pieces marked "empty": the detector sent audio but the recognizer found no words.
 *  - many pieces of exactly the maximum length: the detector never finds a pause.
 */
object DebugInfo {

    /** What the voice detector hears right now. Updated by the audio thread. */
    @Volatile
    var detector: String = ""

    /** Whether noise suppression is running. */
    @Volatile
    var frontEnd: String = ""

    /** Current loudness and how many pieces the backup detector made. */
    @Volatile
    var loudness: String = ""

    private const val RECENT_COUNT = 8

    private var queued = 0
    private var withText = 0
    private var empty = 0
    private var tooShort = 0
    private var skipped = 0
    private var mappedOk = 0
    private var mappedMismatch = 0
    private var mappedUnavailable = 0
    private val recent = ArrayDeque<String>()

    @Synchronized
    fun reset() {
        detector = ""
        frontEnd = ""
        mappedOk = 0
        mappedMismatch = 0
        mappedUnavailable = 0
        queued = 0
        withText = 0
        empty = 0
        tooShort = 0
        skipped = 0
        recent.clear()
    }

    /** The detector handed a piece of speech to the waiting line. */
    @Synchronized
    fun onQueued() {
        queued++
    }

    /**
     * Result of fitting padded audio around a piece: "ok", "mismatch" or "unavailable".
     * Anything other than "ok" means that piece was sent without padding.
     */
    @Synchronized
    fun onMapping(kind: String) {
        when (kind) {
            "ok" -> mappedOk++
            "mismatch" -> mappedMismatch++
            else -> mappedUnavailable++
        }
    }

    /** A piece was thrown away because the phone could not keep up. */
    @Synchronized
    fun onSkipped() {
        skipped++
    }

    /** The recognizer finished a piece. `kind` is "ok", "empty" or "short". */
    @Synchronized
    fun onResult(seconds: Double, kind: String) {
        when (kind) {
            "ok" -> withText++
            "empty" -> empty++
            else -> tooShort++
        }
        recent.addLast(String.format(Locale.US, "%.1fs %s", seconds, kind))
        while (recent.size > RECENT_COUNT) {
            recent.removeFirst()
        }
    }

    /** The text for the screen. Newest pieces last. */
    @Synchronized
    fun summary(): String {
        val counts = "Pieces: $queued sent, $withText with text, $empty empty, " +
            "$tooShort too short, $skipped skipped"
        val padding = "Padding: $mappedOk ok, $mappedMismatch mismatch, $mappedUnavailable unavailable"
        val head = frontEnd + "\n" + counts + "\n" + padding
        return if (recent.isEmpty()) head else head + "\nRecent: " + recent.joinToString(" | ")
    }
}
