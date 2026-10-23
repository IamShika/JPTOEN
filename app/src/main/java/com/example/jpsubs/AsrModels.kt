package com.example.jpsubs

import android.content.Context
import android.util.Log
import java.io.File
import kotlin.concurrent.thread

/**
 * The list of speech recognizers you can choose from, and downloading / deleting them.
 *
 * The first one (ReazonSpeech) is the "built-in" one: it is downloaded together with the voice
 * detector in ModelManager, so it is not handled here. The others are optional downloads.
 * Each optional model lives on its own page of your GitHub Releases (the GitHub build puts it
 * there, see .github/scripts/publish-asr-model.sh) and is saved in its own folder, so deleting
 * one is just deleting that folder.
 */
object AsrModels {

    private const val TAG = "AsrModels"

    /** The recognizer that is always there. */
    const val DEFAULT_ID = "reazon"

    /** One file of a model. `asset` = name on the Releases page, `local` = where we keep it. */
    class F(val asset: String, val local: String, val mb: Double)

    class Info(
        val id: String,
        val label: String,
        val note: String,
        /** Name of the release on GitHub, or null for the built-in model. */
        val release: String?,
        val files: List<F>,
    ) {
        val totalMb: Double get() = files.sumOf { it.mb }
    }

    val ALL: List<Info> = listOf(
        Info(
            id = DEFAULT_ID,
            label = "ReazonSpeech (built in)",
            note = "Fast and made for Japanese. Good all-rounder. About 165 MB.",
            release = null,
            files = emptyList(),
        ),
        Info(
            id = "sensevoice",
            label = "SenseVoice",
            note = "Fast, handles several languages, adds punctuation. About 230 MB.",
            release = "model-sensevoice",
            files = listOf(
                F("model.int8.onnx", "model.int8.onnx", 228.0),
                F("tokens.txt", "tokens.txt", 0.4),
            ),
        ),
        Info(
            id = "whisper-small",
            label = "Whisper small",
            note = "OpenAI Whisper. Good with names and slang, slower, sometimes invents words " +
                "in silence. About 370 MB.",
            release = "model-whisper-small",
            files = listOf(
                F("encoder.onnx", "encoder.onnx", 110.0),
                F("decoder.onnx", "decoder.onnx", 260.0),
                F("tokens.txt", "tokens.txt", 0.8),
            ),
        ),
        Info(
            id = "qwen3asr",
            label = "Qwen3-ASR 0.6B",
            note = "A new AI model, usually the most accurate. Needs a strong phone and is slower. " +
                "About 940 MB.",
            release = "model-qwen3asr",
            files = listOf(
                F("conv_frontend.onnx", "conv_frontend.onnx", 42.0),
                F("encoder.int8.onnx", "encoder.int8.onnx", 174.0),
                F("decoder.int8.onnx", "decoder.int8.onnx", 721.0),
                F("tokenizer_merges.txt", "tokenizer/merges.txt", 1.6),
                F("tokenizer_vocab.json", "tokenizer/vocab.json", 2.6),
                F("tokenizer_config.json", "tokenizer/tokenizer_config.json", 0.1),
            ),
        ),
        Info(
            id = "funasr",
            label = "Fun-ASR Nano",
            note = "A new AI model for Chinese, English and Japanese. Needs a strong phone and is " +
                "slower. About 950 MB.",
            release = "model-funasr",
            files = listOf(
                F("embedding.int8.onnx", "embedding.int8.onnx", 149.0),
                F("encoder_adaptor.int8.onnx", "encoder_adaptor.int8.onnx", 227.0),
                F("llm.int8.onnx", "llm.int8.onnx", 573.0),
                F("tokenizer_merges.txt", "tokenizer/merges.txt", 1.6),
                F("tokenizer_tokenizer.json", "tokenizer/tokenizer.json", 11.0),
                F("tokenizer_vocab.json", "tokenizer/vocab.json", 2.7),
            ),
        ),
    )

    fun info(id: String): Info = ALL.firstOrNull { it.id == id } ?: ALL.first()

    // --- state the screen reads -------------------------------------------------------------

    /** Which model is downloading right now (null = none). */
    @Volatile var busyId: String? = null
    @Volatile var progress: Float = 0f
    @Volatile var message: String = ""

    /** Ids of the models that are fully on the phone. Updated by refresh(). */
    @Volatile var installed: Set<String> = setOf(DEFAULT_ID)

    // --- files --------------------------------------------------------------------------------

    fun folder(context: Context, id: String): File =
        File(ModelManager.modelDir(context), "asr/$id")

    /** Full path of one of a model's files, as text. */
    fun path(context: Context, id: String, local: String): String =
        File(folder(context, id), local).absolutePath

    private fun filesPresent(context: Context, info: Info): Boolean =
        info.files.all { f -> File(folder(context, info.id), f.local).let { it.exists() && it.length() > 0 } }

    /** Looks at the disk and updates `installed`. */
    fun refresh(context: Context) {
        val set = mutableSetOf<String>()
        for (info in ALL) {
            if (info.release == null) {
                if (ModelManager.ready) set.add(info.id)
            } else if (filesPresent(context, info)) {
                set.add(info.id)
            }
        }
        installed = set
    }

    fun isInstalled(id: String): Boolean = installed.contains(id)

    /** Deletes a downloaded model (never the built-in one). */
    fun delete(context: Context, id: String) {
        if (busyId != null) return
        val info = info(id)
        if (info.release == null) return
        folder(context, id).deleteRecursively()
        refresh(context)
    }

    /** Starts downloading a model on a background thread. */
    fun download(context: Context, id: String) {
        if (busyId != null || ModelManager.state == ModelManager.State.DOWNLOADING) return
        val info = info(id)
        val release = info.release ?: return
        val appContext = context.applicationContext

        val root = folder(appContext, id)
        root.mkdirs()
        val needBytes = (info.totalMb * 1.1 + 50).toLong() * 1024 * 1024
        if (root.usableSpace < needBytes) {
            message = "Not enough free storage for ${info.label} (needs about ${info.totalMb.toInt() + 50} MB)."
            return
        }

        busyId = id
        progress = 0f
        message = "Starting…"
        val baseUrl = BuildConfig.MODEL_BASE_URL.replace("model-v1", release)

        thread(name = "asr-download") {
            try {
                var doneMb = 0.0
                for (f in info.files) {
                    val target = File(root, f.local)
                    target.parentFile?.mkdirs()
                    ModelManager.downloadOne(
                        dir = target.parentFile ?: root,
                        name = target.name,
                        baseUrl = baseUrl,
                        assetName = f.asset,
                    ) { fraction ->
                        progress = ((doneMb + f.mb * fraction) / info.totalMb).toFloat()
                        message = "Downloading ${f.asset}  ${(fraction * 100).toInt()}%"
                    }
                    doneMb += f.mb
                }
                message = ""
            } catch (e: Exception) {
                Log.e(TAG, "Download failed", e)
                message = "Download failed: ${e.message}. Tap the button to try again " +
                    "(it continues where it stopped)."
            } finally {
                busyId = null
                refresh(appContext)
            }
        }
    }
}
