package com.example.jpsubs

import android.content.Context
import android.util.Log
import com.k2fsa.sherpa.onnx.OfflineSpeechDenoiserGtcrnModelConfig
import com.k2fsa.sherpa.onnx.OfflineSpeechDenoiserModelConfig
import com.k2fsa.sherpa.onnx.OnlineSpeechDenoiser
import com.k2fsa.sherpa.onnx.OnlineSpeechDenoiserConfig
import com.k2fsa.sherpa.onnx.SileroVadModelConfig
import com.k2fsa.sherpa.onnx.SpeechSegment
import com.k2fsa.sherpa.onnx.Vad
import com.k2fsa.sherpa.onnx.VadModelConfig
import kotlin.math.abs

/**
 * A rolling memory of the most recent audio. Old audio is overwritten by new audio.
 * Positions are counted from the very start of the session, so "position 80000" always
 * means the same moment (5 seconds in), even after the memory has wrapped around.
 */
class AudioRing(private val capacity: Int) {

    private val data = FloatArray(capacity)

    /** How many samples have been written in total since the start. */
    var total: Long = 0L
        private set

    fun append(samples: FloatArray) {
        for (i in samples.indices) {
            data[((total + i) % capacity).toInt()] = samples[i]
        }
        total += samples.size
    }

    /** Pretend that `position` samples have already passed (used when this ring starts late). */
    fun skipTo(position: Long) {
        total = position
    }

    /** Copies out the audio between two positions, or null if it is no longer (or not yet) available. */
    fun read(start: Long, end: Long): FloatArray? {
        if (start < 0 || end <= start || end > total || total - start > capacity) return null
        val count = (end - start).toInt()
        val out = FloatArray(count)
        for (i in 0 until count) {
            out[i] = data[((start + i) % capacity).toInt()]
        }
        return out
    }
}

/**
 * Everything that happens to the audio BEFORE the recognizer sees it:
 *
 *   audio --> [noise suppression] --> [voice detector] --> pieces of speech
 *
 *  - The noise suppressor (GTCRN, run by sherpa-onnx) removes background noise. It is optional.
 *  - The voice detector decides where sentences start and end. It looks at the cleaned audio,
 *    so noise is less likely to fool it.
 *  - For each sentence we then cut out the matching audio, plus a little extra before and after
 *    ("padding") so the first and last sounds of a word are not clipped. By default the
 *    recognizer gets the ORIGINAL audio, not the cleaned one, because noise suppression can
 *    leave small distortions that confuse a speech recognizer. A setting lets you test the
 *    cleaned audio instead.
 *
 * Only the audio thread uses this class.
 */
class FrontEnd(private val context: Context) {

    private companion object {
        const val TAG = "FrontEnd"
        const val RING_SECONDS = 30
    }

    private val sampleRate = CaptureService.SAMPLE_RATE

    // Original audio, and (when noise suppression is on) the cleaned audio.
    private val rawRing = AudioRing(RING_SECONDS * sampleRate)
    private val cleanRing = AudioRing(RING_SECONDS * sampleRate)

    private var denoiser: OnlineSpeechDenoiser? = null
    private var frameShift = 256
    private var carry = FloatArray(0) // leftover samples that don't fill a whole frame yet

    private var vad: Vad? = null

    // The detector counts positions from 0 every time it is rebuilt. Adding this number
    // turns its positions into positions in our rings.
    private var vadBase = 0L

    // Where the previous piece ended, so padding never reaches into it.
    private var previousEnd = 0L

    private var seenSettingsVersion = -1 // -1 forces a build on the first call

    /** Builds (or rebuilds) the noise suppressor and detector if Settings changed. */
    fun applySettingsIfChanged() {
        if (Settings.version == seenSettingsVersion) return
        seenSettingsVersion = Settings.version
        rebuild()
    }

    private fun rebuild() {
        // --- noise suppression
        if (Settings.denoise) {
            if (denoiser == null) {
                try {
                    val created = createDenoiser()
                    denoiser = created
                    carry = FloatArray(0)
                    // The cleaned audio starts now, so line its positions up with the original.
                    cleanRing.skipTo(rawRing.total)
                } catch (e: Throwable) {
                    Log.e(TAG, "Noise suppression unavailable", e)
                    DebugInfo.frontEnd = "Noise suppression: unavailable (${e.message})"
                }
            }
            if (denoiser != null) DebugInfo.frontEnd = "Noise suppression: on"
        } else {
            denoiser?.release()
            denoiser = null
            DebugInfo.frontEnd = "Noise suppression: off"
        }

        // --- voice detector (always rebuilt, because its numbers may have changed)
        val fresh = createVad()
        vad?.release()
        vad = fresh
        vadBase = feedRing().total
    }

    /** The ring that holds exactly what the voice detector is fed. */
    private fun feedRing(): AudioRing = if (denoiser != null) cleanRing else rawRing

    /** Gives in 100 ms of new audio. Returns the sentences (if any) that just finished. */
    fun feed(samples: FloatArray): List<FloatArray> {
        val detector = vad ?: return emptyList()

        rawRing.append(samples)

        val d = denoiser
        val forDetector = if (d != null) {
            val cleaned = denoise(d, samples)
            cleanRing.append(cleaned)
            cleaned
        } else {
            samples
        }
        if (forDetector.isNotEmpty()) detector.acceptWaveform(forDetector)

        val pieces = ArrayList<FloatArray>()
        while (!detector.empty()) {
            val segment = detector.front()
            detector.pop()
            pieces.add(buildPiece(segment))
        }
        return pieces
    }

    fun isHearingSpeech(): Boolean = vad?.isSpeechDetected() ?: false

    fun release() {
        denoiser?.release()
        denoiser = null
        vad?.release()
        vad = null
    }

    // ---------------------------------------------------------------------------------------

    /** The suppressor wants audio in whole frames, so we feed it frame by frame. */
    private fun denoise(d: OnlineSpeechDenoiser, samples: FloatArray): FloatArray {
        val all = carry + samples
        val parts = ArrayList<FloatArray>()
        var position = 0
        while (position + frameShift <= all.size) {
            val frame = all.copyOfRange(position, position + frameShift)
            val result = d.run(frame, sampleRate).samples
            if (result.isNotEmpty()) parts.add(result)
            position += frameShift
        }
        carry = all.copyOfRange(position, all.size)

        val out = FloatArray(parts.sumOf { it.size })
        var offset = 0
        for (part in parts) {
            part.copyInto(out, offset)
            offset += part.size
        }
        return out
    }

    /**
     * Turns the detector's sentence into the audio the recognizer will get:
     * the sentence plus a little padding, taken from the original (or cleaned) audio.
     * If anything looks wrong we simply use the detector's own copy.
     */
    private fun buildPiece(segment: SpeechSegment): FloatArray {
        val length = segment.samples.size
        val start = vadBase + segment.start
        val end = start + length
        val before = previousEnd
        previousEnd = end

        // Safety check: the audio we find in our ring at the detector's reported position
        // must be identical to what the detector handed us. If not, our position maths is
        // wrong and we don't touch anything.
        if (!positionsMatch(feedRing(), start, segment.samples)) {
            DebugInfo.onMapping("mismatch")
            return segment.samples
        }

        val padding = (Settings.paddingSeconds * sampleRate).toInt()
        // A piece cut only because it reached the maximum length is followed directly by
        // more speech. Padding it would make the same words appear twice.
        val cutByLimit = length >= (Settings.maxSpeechSeconds * sampleRate * 0.95f)

        val padBefore = if (padding > 0) minOf(padding.toLong(), maxOf(0L, start - before)) else 0L
        val padAfter = if (padding > 0 && !cutByLimit) padding.toLong() else 0L

        val source = if (Settings.recognizerUsesDenoised && denoiser != null) cleanRing else rawRing
        val audio = source.read(start - padBefore, minOf(end + padAfter, source.total))

        if (audio == null || audio.size < length / 2) {
            DebugInfo.onMapping("unavailable")
            return segment.samples
        }
        DebugInfo.onMapping("ok")
        return audio
    }

    private fun positionsMatch(ring: AudioRing, start: Long, expected: FloatArray): Boolean {
        val n = expected.size
        if (n == 0) return false
        val actual = ring.read(start, start + n) ?: return false
        for (i in intArrayOf(0, n / 4, n / 2, (3 * n) / 4, n - 1)) {
            if (abs(actual[i] - expected[i]) > 1e-5f) return false
        }
        return true
    }

    private fun createVad(): Vad {
        val config = VadModelConfig(
            sileroVadModelConfig = SileroVadModelConfig(
                model = ModelManager.path(context, ModelManager.VAD_FILE),
                threshold = Settings.vadThreshold,
                minSilenceDuration = Settings.minSilenceSeconds,
                minSpeechDuration = Settings.minSpeechSeconds,
                windowSize = 512,
                maxSpeechDuration = Settings.maxSpeechSeconds,
            ),
            sampleRate = sampleRate,
            numThreads = 1,
            provider = "cpu",
            debug = false,
        )
        // assetManager = null means "load from normal files", not from inside the APK.
        return Vad(assetManager = null, config = config)
    }

    private fun createDenoiser(): OnlineSpeechDenoiser {
        val config = OnlineSpeechDenoiserConfig(
            model = OfflineSpeechDenoiserModelConfig(
                gtcrn = OfflineSpeechDenoiserGtcrnModelConfig(
                    model = ModelManager.path(context, ModelManager.DENOISER_FILE),
                ),
                numThreads = 1,
                provider = "cpu",
                debug = false,
            ),
        )
        val created = OnlineSpeechDenoiser(assetManager = null, config = config)
        frameShift = created.frameShiftInSamples
        return created
    }
}
