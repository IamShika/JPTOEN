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

    /** Blocking. Japanese in, English out. Gives back null if it fails or takes too long. */
    fun translate(translator: Translator, japanese: String): String? {
        return try {
            Tasks.await(translator.translate(japanese), 3, TimeUnit.SECONDS)
        } catch (e: Exception) {
            Log.e(TAG, "Translation failed", e)
            null
        }
    }
}
