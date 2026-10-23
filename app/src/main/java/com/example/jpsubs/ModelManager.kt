package com.example.jpsubs

import android.content.Context
import android.util.Log
import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL
import kotlin.concurrent.thread

/**
 * Downloads the speech model files once and remembers where they are.
 *
 * The files live on the Releases page of your GitHub repo (the GitHub build
 * puts them there, see .github/workflows/build.yml). We download them into the
 * app's private folder. If the download is interrupted, tapping the button again
 * continues where it stopped.
 */
object ModelManager {

    private const val TAG = "ModelManager"

    /**
     * Every file the app needs. The name here must match the name on the Releases page.
     * The number is the rough size in megabytes, used only to make the progress bar smooth.
     */
    private val FILES = listOf(
        "silero_vad.onnx" to 2.0,                      // detects "someone is talking"
        "gtcrn_simple.onnx" to 0.6,                    // removes background noise
        "tokens.txt" to 0.1,                           // the list of Japanese word pieces
        "decoder-epoch-99-avg-1.onnx" to 11.0,         // part of the recognizer
        "joiner-epoch-99-avg-1.int8.onnx" to 2.6,      // part of the recognizer
        "encoder-epoch-99-avg-1.int8.onnx" to 148.0,   // the big part of the recognizer
    )

    // Names other files use to find the model files
    const val VAD_FILE = "silero_vad.onnx"
    const val DENOISER_FILE = "gtcrn_simple.onnx"
    const val TOKENS_FILE = "tokens.txt"
    const val ENCODER_FILE = "encoder-epoch-99-avg-1.int8.onnx"
    const val DECODER_FILE = "decoder-epoch-99-avg-1.onnx"
    const val JOINER_FILE = "joiner-epoch-99-avg-1.int8.onnx"

    enum class State { IDLE, DOWNLOADING, FAILED }

    // The screen reads these to draw the download progress.
    @Volatile var state: State = State.IDLE
    @Volatile var progress: Float = 0f // 0.0 to 1.0
    @Volatile var message: String = ""
    @Volatile var ready: Boolean = false // the speech files are on the phone

    /** Rough size of the translation model in MB, used only for the progress bar. */
    private const val TRANSLATION_MB = 30.0

    /** True when BOTH the speech model and the translation model are installed. */
    val allReady: Boolean
        get() = ready && TranslationManager.ready

    fun modelDir(context: Context): File = File(context.filesDir, "models")

    /** Full path of one model file, as text (the speech engine wants plain paths). */
    fun path(context: Context, name: String): String = File(modelDir(context), name).absolutePath

    /** Looks at the disk and updates `ready`. */
    fun refresh(context: Context) {
        val dir = modelDir(context)
        ready = FILES.all { (name, _) -> File(dir, name).let { it.exists() && it.length() > 0 } }
        AsrModels.refresh(context)
        TranslationManager.refresh() // the answer arrives a moment later
    }

    /** Starts the download on a background thread. Does nothing if one is already running. */
    fun startDownload(context: Context) {
        if (state == State.DOWNLOADING) return
        val appContext = context.applicationContext

        val dir = modelDir(appContext)
        dir.mkdirs()
        if (dir.usableSpace < 250L * 1024 * 1024) {
            state = State.FAILED
            message = "Not enough free storage. The model needs about 200 MB free."
            return
        }

        state = State.DOWNLOADING
        progress = 0f
        message = "Starting…"

        thread(name = "model-download") {
            try {
                val totalMb = FILES.sumOf { it.second } + TRANSLATION_MB
                var doneMb = 0.0
                for ((name, mb) in FILES) {
                    downloadOne(dir, name) { fractionOfThisFile ->
                        progress = ((doneMb + mb * fractionOfThisFile) / totalMb).toFloat()
                        message = "Downloading $name  ${(fractionOfThisFile * 100).toInt()}%"
                    }
                    doneMb += mb
                }

                // Last step: the translation model, which Google's ML Kit downloads for us.
                progress = (doneMb / totalMb).toFloat()
                message = "Downloading the translation model (about 30 MB)…"
                TranslationManager.downloadModels()

                progress = 1f
                refresh(appContext)
                state = State.IDLE
                message = ""
            } catch (e: Exception) {
                Log.e(TAG, "Download failed", e)
                state = State.FAILED
                message = "Download failed: ${e.message}. Tap the button to try again. " +
                    "It continues where it stopped."
            }
        }
    }

    /**
     * Downloads one file. It is first saved as "name.part" and only renamed to
     * "name" when complete, so a half-finished file is never mistaken for a good one.
     */
    internal fun downloadOne(
        dir: File,
        name: String,
        allowResume: Boolean = true,
        baseUrl: String = BuildConfig.MODEL_BASE_URL,
        assetName: String = name,
        onProgress: (Float) -> Unit,
    ) {
        val target = File(dir, name)
        if (target.exists() && target.length() > 0) {
            onProgress(1f)
            return
        }

        val part = File(dir, "$name.part")
        val alreadyHave = if (allowResume && part.exists()) part.length() else 0L
        if (!allowResume) part.delete()

        val connection = URL(baseUrl + assetName).openConnection() as HttpURLConnection
        try {
            connection.connectTimeout = 20_000
            connection.readTimeout = 30_000
            connection.setRequestProperty("User-Agent", "JpSubs")
            if (alreadyHave > 0) {
                // "Please send the file starting from byte N"
                connection.setRequestProperty("Range", "bytes=$alreadyHave-")
            }
            connection.connect()

            val code = connection.responseCode
            if (code == 416) {
                // Our partial file doesn't fit the server's file. Start over once.
                part.delete()
                connection.disconnect()
                downloadOne(dir, name, allowResume = false, baseUrl = baseUrl, assetName = assetName, onProgress = onProgress)
                return
            }
            if (code == 404) {
                throw IOException(
                    "$assetName was not found on your GitHub Releases page. " +
                        "Check that the GitHub build finished and the repo is public"
                )
            }
            if (code != 200 && code != 206) {
                throw IOException("server answered HTTP $code for $name")
            }

            // 206 = the server continues our file. 200 = it sends everything from the start.
            val resumed = code == 206
            val startOffset = if (resumed) alreadyHave else 0L
            val remaining = connection.contentLengthLong // -1 if the server doesn't say
            val expectedTotal = if (remaining >= 0) startOffset + remaining else -1L

            var received = startOffset
            connection.inputStream.use { input ->
                FileOutputStream(part, resumed).use { output ->
                    val buffer = ByteArray(64 * 1024)
                    var lastReport = 0L
                    while (true) {
                        val count = input.read(buffer)
                        if (count < 0) break
                        output.write(buffer, 0, count)
                        received += count

                        val now = System.currentTimeMillis()
                        if (expectedTotal > 0 && now - lastReport > 200) {
                            lastReport = now
                            onProgress((received.toFloat() / expectedTotal).coerceIn(0f, 1f))
                        }
                    }
                }
            }

            if (expectedTotal > 0 && received != expectedTotal) {
                throw IOException("$name was cut off (got $received of $expectedTotal bytes)")
            }
            if (!part.renameTo(target)) {
                throw IOException("could not save $name")
            }
            onProgress(1f)
        } finally {
            connection.disconnect()
        }
    }
}
