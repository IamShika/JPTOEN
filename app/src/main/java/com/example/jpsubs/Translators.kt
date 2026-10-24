package com.example.jpsubs

import android.util.Log
import com.google.mlkit.nl.translate.Translator
import org.json.JSONArray
import org.json.JSONObject
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder

/**
 * The translators you can choose from, and the code that talks to each one.
 *
 * Every translator does the same job: Japanese text in, English text out. `history` holds the
 * last few (Japanese, English) pairs so a smart translator can use them as context.
 * All functions here are "blocking" (they wait for the answer), so they only run on the
 * background decode thread, never on the screen's thread.
 */
interface TextTranslator {
    /** Gives the English text, or throws an exception if something went wrong. */
    fun translate(japanese: String, history: List<Pair<String, String>>): String?
}

object Translators {

    private const val TAG = "Translators"

    const val ML_KIT = "mlkit"
    const val GOOGLE = "google"
    const val DEEPL = "deepl"
    const val AI_API = "ai"

    class Option(val id: String, val label: String, val note: String)

    val OPTIONS = listOf(
        Option(
            ML_KIT, "Google ML Kit (on the phone)",
            "Works offline and is free, but it is basic: one sentence at a time, weak with slang.",
        ),
        Option(
            GOOGLE, "Google Translate (online, no key)",
            "Free and needs no account. Better than ML Kit. Sends the Japanese text to Google. " +
                "It is an unofficial web address, so it may stop working or be limited.",
        ),
        Option(
            DEEPL, "DeepL (online, free key)",
            "Very natural English. Get a free key at deepl.com (API Free plan, 500,000 characters " +
                "a month) and paste it below. Sends the Japanese text to DeepL.",
        ),
        Option(
            AI_API, "AI chat model (online, your key)",
            "The best with slang and context: it also sees the last lines. Works with any " +
                "OpenAI-compatible service (Gemini, Groq, OpenAI, OpenRouter...). Paste the address, " +
                "key and model name below. The defaults are for Google Gemini (free key at " +
                "aistudio.google.com). Sends the Japanese text to that service.",
        ),
    )

    fun option(id: String): Option = OPTIONS.firstOrNull { it.id == id } ?: OPTIONS.first()

    /** The last problem an online translator had (shown in the status line). */
    @Volatile
    var lastError: String = ""

    // ---------------------------------------------------------------------------------------
    // Building the translator the person picked
    // ---------------------------------------------------------------------------------------

    /**
     * Gives the translator chosen in Settings. If it is an online one and it fails, ML Kit
     * (when available) answers instead, so subtitles keep coming.
     */
    fun create(mlKit: Translator?): TextTranslator? {
        val fallback: TextTranslator? = mlKit?.let { MlKitEngine(it) }
        val primary: TextTranslator = when (Settings.translator) {
            GOOGLE -> GoogleWebEngine()
            DEEPL -> DeepLEngine(Settings.deeplKey)
            AI_API -> AiApiEngine(Settings.aiBaseUrl, Settings.aiKey, Settings.aiModel)
            else -> return fallback
        }
        return object : TextTranslator {
            override fun translate(japanese: String, history: List<Pair<String, String>>): String? {
                return try {
                    val result = primary.translate(japanese, history)
                    if (!result.isNullOrBlank()) {
                        lastError = ""
                        result
                    } else {
                        fallback?.translate(japanese, history)
                    }
                } catch (e: Exception) {
                    Log.e(TAG, "Online translator failed", e)
                    lastError = e.message ?: e.javaClass.simpleName
                    fallback?.translate(japanese, history)
                }
            }
        }
    }

    /** Used by the "Test translator" button. Returns a sentence describing what happened. */
    fun test(): String {
        val sample = "こんにちは、今日はいい天気ですね。"
        val engine: TextTranslator = when (Settings.translator) {
            GOOGLE -> GoogleWebEngine()
            DEEPL -> DeepLEngine(Settings.deeplKey)
            AI_API -> AiApiEngine(Settings.aiBaseUrl, Settings.aiKey, Settings.aiModel)
            else -> {
                val translator = TranslationManager.openTranslator()
                try {
                    return "Test OK: " + (TranslationManager.translate(translator, sample) ?: "(no answer)")
                } finally {
                    translator.close()
                }
            }
        }
        val start = System.currentTimeMillis()
        val result = engine.translate(sample, emptyList())
        val ms = System.currentTimeMillis() - start
        return "Test OK (${ms} ms): $result"
    }

    // ---------------------------------------------------------------------------------------
    // The translators
    // ---------------------------------------------------------------------------------------

    /** Google ML Kit, on the phone. */
    class MlKitEngine(private val translator: Translator) : TextTranslator {
        override fun translate(japanese: String, history: List<Pair<String, String>>): String? =
            TranslationManager.translate(translator, japanese)
    }

    /** Google Translate's free web address (the same one browser extensions use). */
    class GoogleWebEngine : TextTranslator {
        override fun translate(japanese: String, history: List<Pair<String, String>>): String? {
            val url = "https://translate.googleapis.com/translate_a/single?client=gtx" +
                "&sl=ja&tl=en&dt=t&q=" + URLEncoder.encode(japanese, "UTF-8")
            val body = http(url, "GET", emptyMap(), null)
            // The answer looks like [[["Hello","こんにちは",...],["more","more jp",...]],...]
            val parts = JSONArray(body).getJSONArray(0)
            val out = StringBuilder()
            for (i in 0 until parts.length()) {
                out.append(parts.getJSONArray(i).optString(0, ""))
            }
            return out.toString().trim()
        }
    }

    /** DeepL's API. Keys that end in ":fx" belong to the free plan and use another address. */
    class DeepLEngine(private val key: String) : TextTranslator {
        override fun translate(japanese: String, history: List<Pair<String, String>>): String? {
            if (key.isBlank()) throw IOException("No DeepL key. Paste it in Settings.")
            val host = if (key.trim().endsWith(":fx")) "api-free.deepl.com" else "api.deepl.com"
            val form = "text=" + URLEncoder.encode(japanese, "UTF-8") +
                "&source_lang=JA&target_lang=EN-US"
            val body = http(
                "https://$host/v2/translate", "POST",
                mapOf(
                    "Authorization" to "DeepL-Auth-Key ${key.trim()}",
                    "Content-Type" to "application/x-www-form-urlencoded",
                ),
                form,
            )
            return JSONObject(body).getJSONArray("translations").getJSONObject(0).getString("text").trim()
        }
    }

    /** Any "OpenAI-compatible" chat service (Gemini, Groq, OpenAI, OpenRouter, Ollama ...). */
    class AiApiEngine(
        private val baseUrl: String,
        private val key: String,
        private val model: String,
    ) : TextTranslator {

        override fun translate(japanese: String, history: List<Pair<String, String>>): String? {
            if (key.isBlank()) throw IOException("No API key. Paste it in Settings.")
            if (baseUrl.isBlank() || model.isBlank()) throw IOException("API address or model name is empty.")

            val prompt = StringBuilder()
            if (history.isNotEmpty()) {
                prompt.append("Previous lines (for context only, do not translate them again):\n")
                for ((jp, en) in history) prompt.append("JA: ").append(jp).append("\nEN: ").append(en).append("\n")
                prompt.append("\n")
            }
            prompt.append("Translate this line:\n").append(japanese)

            val messages = JSONArray()
                .put(JSONObject().put("role", "system").put("content", SYSTEM_PROMPT))
                .put(JSONObject().put("role", "user").put("content", prompt.toString()))
            val request = JSONObject()
                .put("model", model.trim())
                .put("messages", messages)
                .put("temperature", 0.2)
                .put("max_tokens", 300)

            val url = baseUrl.trim().trimEnd('/') + "/chat/completions"
            val body = http(
                url, "POST",
                mapOf(
                    "Authorization" to "Bearer ${key.trim()}",
                    "Content-Type" to "application/json",
                ),
                request.toString(),
                timeoutMs = 10_000,
            )
            val content = JSONObject(body).getJSONArray("choices").getJSONObject(0)
                .getJSONObject("message").optString("content", "")
            return content.trim().trim('"')
        }

        private companion object {
            const val SYSTEM_PROMPT =
                "You translate live Japanese subtitles from a streamer or VTuber into natural, " +
                    "concise English. Reply with ONLY the English translation: no notes, no quotes, " +
                    "no romaji, no explanations. The Japanese comes from speech recognition and may " +
                    "contain mistakes or half sentences: use the previous lines to guess what was meant. " +
                    "Keep names of people in romaji. Translate slang and game talk the way a fan " +
                    "would. Keep short reactions short (for example \"Wow!\" or \"Huh?\")."
        }
    }

    // ---------------------------------------------------------------------------------------

    /** One web request. Throws an IOException with a readable message when it fails. */
    private fun http(
        url: String,
        method: String,
        headers: Map<String, String>,
        body: String?,
        timeoutMs: Int = 6_000,
    ): String {
        val connection = URL(url).openConnection() as HttpURLConnection
        try {
            connection.requestMethod = method
            connection.connectTimeout = timeoutMs
            connection.readTimeout = timeoutMs
            connection.setRequestProperty("User-Agent", "JpSubs")
            for ((name, value) in headers) connection.setRequestProperty(name, value)
            if (body != null) {
                connection.doOutput = true
                connection.outputStream.use { it.write(body.toByteArray(Charsets.UTF_8)) }
            }
            val code = connection.responseCode
            val stream = if (code in 200..299) connection.inputStream else connection.errorStream
            val text = stream?.bufferedReader(Charsets.UTF_8)?.use { it.readText() } ?: ""
            if (code !in 200..299) throw IOException("HTTP $code: ${text.take(160)}")
            return text
        } finally {
            connection.disconnect()
        }
    }
}
