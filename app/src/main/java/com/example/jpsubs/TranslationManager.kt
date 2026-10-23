package com.example.jpsubs

import android.util.Log
import com.google.android.gms.tasks.Tasks
import com.google.mlkit.common.model.DownloadConditions
import com.google.mlkit.common.model.RemoteModelManager
import com.google.mlkit.nl.translate.TranslateLanguage
import com.google.mlkit.nl.translate.TranslateRemoteModel
import com.google.mlkit.nl.translate.Translation
import com.google.mlkit.nl.translate.Translator
import com.google.mlkit.nl.translate.TranslatorOptions
import java.util.concurrent.TimeUnit

/**
 * Everything about translating Japanese to English with Google's ML Kit.
 *
 * ML Kit translates on the phone itself, so it is free and works offline. It
 * needs a small language model (about 30 MB) which it downloads once from Google.
 *
 * Note: functions marked "blocking" wait for the result, so they must only be
 * called from a background thread, never from the screen's main thread.
 */
object TranslationManager {

    private const val TAG = "TranslationManager"

    /** True once the Japanese language model is on the phone. */
    @Volatile
    var ready: Boolean = false

    private fun options(): TranslatorOptions =
        TranslatorOptions.Builder()
            .setSourceLanguage(TranslateLanguage.JAPANESE)
            .setTargetLanguage(TranslateLanguage.ENGLISH)
            .build()

    /**
     * Asks ML Kit which language models are on the phone and updates `ready`.
     * This does not wait: the answer arrives a moment later and sets `ready`.
     */
    fun refresh() {
        val japanese = TranslateRemoteModel.Builder(TranslateLanguage.JAPANESE).build()
        RemoteModelManager.getInstance()
            .getDownloadedModels(TranslateRemoteModel::class.java)
            .addOnSuccessListener { models -> ready = models.contains(japanese) }
            .addOnFailureListener { e -> Log.e(TAG, "Could not list translation models", e) }
    }

    /** Blocking. Downloads the language model(s) if they are missing. */
    fun downloadModels() {
        val translator = Translation.getClient(options())
        try {
            val conditions = DownloadConditions.Builder().build() // any network is fine
            Tasks.await(translator.downloadModelIfNeeded(conditions), 15, TimeUnit.MINUTES)
            ready = true
        } finally {
            translator.close()
        }
    }

    /**
     * Blocking. Gives back a translator that is ready to use.
     * (If the models are already on the phone this returns almost immediately.)
     * Remember to call close() on it when finished.
     */
    fun openTranslator(): Translator {
        val translator = Translation.getClient(options())
        try {
            val conditions = DownloadConditions.Builder().build()
            Tasks.await(translator.downloadModelIfNeeded(conditions), 2, TimeUnit.MINUTES)
            return translator
        } catch (e: Exception) {
            translator.close()
            throw e
        }
    }

    // Short reactions that ML Kit often mangles ("えっと" became "Ghost"). We answer these
    // ourselves. Add your own lines here: Japanese on the left, English on the right.
    private val REACTIONS = mapOf(
        "えっと" to "Um…", "えーと" to "Um…", "えーっと" to "Um…", "ええと" to "Um…",
        "あのー" to "Um…", "あの" to "Um…", "まあ" to "Well…", "なんか" to "Like…",
        "えっ" to "Huh?", "え" to "Huh?", "えー" to "Eh?", "ええ" to "Yeah", "うん" to "Yeah",
        "はい" to "Yes", "ううん" to "Nope", "あー" to "Ah", "あ" to "Ah", "おー" to "Oh!",
        "おお" to "Oh!", "うわ" to "Wow", "うわー" to "Wow", "あはは" to "Haha", "ははは" to "Haha",
        "ふふ" to "Heh", "へー" to "Oh, really?", "ほう" to "Oh?", "ん" to "Hm?", "んー" to "Hmm…"
    )

    // Filler words at the very start of a sentence. We cut them off before translating,
    // because they confuse the translator.
    private val LEADING_FILLERS = listOf("えーっと", "えっと", "えーと", "ええと", "あのー", "あの、", "あのね", "まあ、")

    /** True if the text still contains Japanese letters (hiragana, katakana or kanji). */
    private fun hasJapanese(text: String): Boolean =
        text.any { c ->
            c in '぀'..'ヿ' || c in '一'..'鿿'
        }

    private fun runMlKit(translator: Translator, text: String): String? {
        return try {
            Tasks.await(translator.translate(text), 3, TimeUnit.SECONDS)
        } catch (e: Exception) {
            Log.e(TAG, "Translation failed", e)
            null
        }
    }

    /**
     * Blocking. Japanese in, English out. Gives back null if it fails or takes too long.
     *
     * Before ML Kit sees the text we tidy it (spaces removed, filler words handled, a full
     * stop added so it treats the text as a sentence). If the answer still contains Japanese
     * letters, the translator failed on this text, so we try once more in a simpler form and
     * otherwise give back null (the screen then shows only the Japanese).
     */
    fun translate(translator: Translator, japanese: String): String? {
        // 1. Remove spaces the recognizer sometimes puts between words.
        var text = japanese.replace(" ", "").replace("　", "").trim()
        if (text.isEmpty()) return null

        // 2. Whole sentence is just a reaction: use our own table.
        val bare = text.trimEnd('。', '、', '！', '？', '!', '?', '…', 'ー')
        REACTIONS[text]?.let { return it }
        REACTIONS[bare]?.let { return it }

        // 3. Cut a leading filler word if something real follows it.
        for (filler in LEADING_FILLERS) {
            if (text.startsWith(filler) && text.length - filler.length >= 2) {
                text = text.removePrefix(filler)
                break
            }
        }

        // 4. Translate with a full stop at the end (helps ML Kit treat it as a sentence).
        val last = text.last()
        val withStop = if (last in "。！？!?") text else "$text。"
        var english = runMlKit(translator, withStop)

        // 5. Japanese letters in the answer = failed. Retry once without the full stop.
        if (english == null || hasJapanese(english)) {
            english = runMlKit(translator, text)
        }
        if (english == null || hasJapanese(english)) return null
        return english.trim()
    }
}
