package com.natan.nexa.voice

import android.content.Context
import android.media.MediaPlayer
import android.os.Bundle
import android.speech.tts.TextToSpeech
import android.speech.tts.UtteranceProgressListener
import android.util.Base64
import com.natan.nexa.Prefs
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.net.HttpURLConnection
import java.net.URL
import java.util.Locale
import java.util.UUID
import kotlin.coroutines.resume

/** Mesin suara yang menghasilkan file audio (WAV/MP3). */
interface TtsEngine {
    suspend fun synthesize(text: String): ByteArray
}

// -------------------------------------------------------------------------
// 1) GEMINI TTS — bahasa Indonesia, suara bawaan atau voice_... kustom
// -------------------------------------------------------------------------
class GeminiTts(
    private val apiKey: String,
    private val model: String,
    private val voice: String,
    private val style: String = "hangat, ramah, natural",
) : TtsEngine {
    override suspend fun synthesize(text: String): ByteArray = withContext(Dispatchers.IO) {
        val body = JSONObject()
            .put("model", model)
            .put("input", JSONArray().put(JSONObject()
                .put("type", "user_input")
                .put("content", JSONArray().put(JSONObject()
                    .put("type", "text")
                    .put("text", text)
                    .put("annotations", JSONArray().put(JSONObject()
                        .put("type", "speech_metadata").put("style", style)))))))
            .put("response_format", JSONObject().put("type", "audio"))
            .put("generation_config", JSONObject()
                .put("speech_config", JSONArray().put(JSONObject().put("voice", voice))))
        val resp = JSONObject(String(Http.post(
            "https://generativelanguage.googleapis.com/v1beta/interactions",
            body, mapOf("x-goog-api-key" to apiKey))))
        val b64 = lastAudio(resp) ?: throw RuntimeException("Gemini TTS tidak mengembalikan audio")
        Base64.decode(b64, Base64.DEFAULT)
    }

    private fun lastAudio(resp: JSONObject): String? {
        resp.optJSONObject("output_audio")?.optString("data")?.takeIf { it.isNotEmpty() }?.let { return it }
        val steps = resp.optJSONArray("steps") ?: return null
        var found: String? = null
        for (i in 0 until steps.length()) {
            val content = steps.optJSONObject(i)?.optJSONArray("content") ?: continue
            for (j in 0 until content.length()) {
                val c = content.optJSONObject(j) ?: continue
                if (c.optString("type") == "audio" && c.optString("data").isNotEmpty()) found = c.optString("data")
            }
        }
        return found
    }
}

// -------------------------------------------------------------------------
// 2) OPENAI TTS — langsung dari HP
// -------------------------------------------------------------------------
class OpenAiTts(
    private val apiKey: String,
    private val model: String,   // gpt-4o-mini-tts
    private val voice: String,   // coral, alloy, nova, shimmer, echo, sage…
) : TtsEngine {
    override suspend fun synthesize(text: String): ByteArray = withContext(Dispatchers.IO) {
        Http.post("https://api.openai.com/v1/audio/speech", JSONObject()
            .put("model", model).put("voice", voice).put("input", text)
            .put("response_format", "mp3")
            .put("instructions", "Bicara dalam bahasa Indonesia, hangat, ekspresif, dan jelas."),
            mapOf("Authorization" to "Bearer $apiKey"))
    }
}

// -------------------------------------------------------------------------
// 3) FISH AUDIO TTS — suara kloning (reference_id) langsung dari HP
// -------------------------------------------------------------------------
class FishTts(
    private val apiKey: String,
    private val model: String,       // s2.1-pro | s2.1-pro-free | s2-pro
    private val referenceId: String, // id suara kloning; kosong = suara default
) : TtsEngine {
    override suspend fun synthesize(text: String): ByteArray = withContext(Dispatchers.IO) {
        val body = JSONObject().put("text", text).put("format", "mp3")
        if (referenceId.isNotBlank()) body.put("reference_id", referenceId)
        Http.post("https://api.fish.audio/v1/tts", body,
            mapOf("Authorization" to "Bearer $apiKey", "model" to model), 180_000)
    }
}

/**
 * Gemini TTS otomatis: mencoba model pilihan dulu, lalu model cadangan bila model itu
 * tidak tersedia untuk kunci ini. Model yang berhasil diingat untuk panggilan berikutnya.
 */
class GeminiAutoTts(private val p: Prefs, private val voice: String) : TtsEngine {
    companion object {
        val MODELS = listOf(
            "gemini-3.8-flash-tts",
            "gemini-3.1-flash-tts-preview",
            "gemini-2.5-flash-preview-tts",
            "gemini-2.5-pro-preview-tts",
        )
        @Volatile private var working: String? = null
    }

    override suspend fun synthesize(text: String): ByteArray {
        val chosen = p.geminiTtsModel
        val order = listOfNotNull(working?.takeIf { it == chosen }, chosen) + MODELS
        var last: Exception? = null
        for (m in order.distinct()) {
            try {
                val audio = GeminiTts(p.geminiApiKey, m, voice, p.str("gemini_style").ifBlank { "hangat, ramah, natural" })
                    .synthesize(text)
                working = m
                return audio
            } catch (e: Exception) {
                last = e
                // Kunci salah / kuota habis: model lain tidak akan membantu.
                val msg = e.message.orEmpty()
                if ("API_KEY" in msg || "401" in msg || "403" in msg || "429" in msg) break
            }
        }
        throw last ?: RuntimeException("Gemini TTS gagal")
    }
}

/** Membuat mesin suara dari spesifikasi "mesin:id", mis. gemini:Kore, openai:coral, fish:<id>. */
object Tts {
    fun fromSpec(p: Prefs, spec: String): TtsEngine {
        val engine = spec.substringBefore(':')
        val id = spec.substringAfter(':', "")
        return when (engine) {
            "openai" -> {
                require(p.openaiKey.isNotBlank()) { "Kunci OpenAI belum diisi di Pengaturan" }
                OpenAiTts(p.openaiKey, p.str("openai_tts_model"), id.ifBlank { p.str("openai_voice") })
            }
            "fish" -> {
                require(p.fishKey.isNotBlank()) { "Kunci Fish Audio belum diisi di Pengaturan" }
                FishTts(p.fishKey, p.str("fish_model"), id.ifBlank { p.str("fish_voice") })
            }
            else -> {
                require(p.geminiApiKey.isNotBlank()) { "Kunci Gemini belum diisi di Pengaturan" }
                GeminiAutoTts(p, id.ifBlank { p.geminiVoice })
            }
        }
    }
}

// -------------------------------------------------------------------------
// 4) GPT-SoVITS langsung (PC/GPU) — zh, en, ja, ko, yue
// -------------------------------------------------------------------------
class GptSovitsTts(
    private val baseUrl: String,
    private val refAudioPath: String,
    private val promptText: String,
    private val lang: String,
) : TtsEngine {
    override suspend fun synthesize(text: String): ByteArray = withContext(Dispatchers.IO) {
        Http.post("$baseUrl/tts", JSONObject()
            .put("text", text).put("text_lang", lang)
            .put("ref_audio_path", refAudioPath)
            .put("prompt_text", promptText).put("prompt_lang", lang)
            .put("text_split_method", "cut5").put("media_type", "wav")
            .put("streaming_mode", false), emptyMap())
    }
}

// -------------------------------------------------------------------------
// 5) TTS bawaan Android — paling cepat & offline (cocok untuk TikTok Live)
// -------------------------------------------------------------------------
class AndroidTts(context: Context) {
    @Volatile private var ready = false
    private lateinit var tts: TextToSpeech

    init {
        tts = TextToSpeech(context.applicationContext) { status ->
            ready = status == TextToSpeech.SUCCESS
            if (ready) tts.language = Locale("id", "ID")
        }
    }

    suspend fun speak(text: String, rate: Float = 1f) = suspendCancellableCoroutine<Unit> { cont ->
        if (!ready) { cont.resume(Unit); return@suspendCancellableCoroutine }
        val id = UUID.randomUUID().toString()
        tts.setSpeechRate(rate)
        tts.setOnUtteranceProgressListener(object : UtteranceProgressListener() {
            override fun onStart(utteranceId: String?) {}
            override fun onDone(utteranceId: String?) { if (utteranceId == id && cont.isActive) cont.resume(Unit) }
            @Deprecated("Deprecated in Java")
            override fun onError(utteranceId: String?) { if (utteranceId == id && cont.isActive) cont.resume(Unit) }
        })
        tts.speak(text, TextToSpeech.QUEUE_ADD, Bundle(), id)
        cont.invokeOnCancellation { tts.stop() }
    }

    fun stop() = tts.stop()
}

// -------------------------------------------------------------------------
// Pemilih mesin + pemutar audio
// -------------------------------------------------------------------------
class VoiceOutput(private val context: Context) {

    private var player: MediaPlayer? = null
    private val android by lazy { AndroidTts(context) }

    private fun engine(p: Prefs, name: String): TtsEngine? = when (name) {
        "gemini" -> if (p.geminiApiKey.isNotEmpty()) GeminiAutoTts(p, p.geminiVoice) else null
        "openai" -> if (p.openaiKey.isNotEmpty()) OpenAiTts(p.openaiKey, p.str("openai_tts_model"), p.str("openai_voice")) else null
        "fish" -> if (p.fishKey.isNotEmpty()) FishTts(p.fishKey, p.str("fish_model"), p.str("fish_voice")) else null
        "sovits" -> if (p.sovitsUrl.isNotEmpty() && p.sovitsRefPath.isNotEmpty())
            GptSovitsTts(p.sovitsUrl, p.sovitsRefPath, p.sovitsPromptText, p.sovitsLang) else null
        else -> null
    }

    /**
     * Ucapkan teks. engineName: gemini | openai | fish | sovits | android (default = pengaturan).
     * Jika mesin utama gagal → Gemini → TTS Android sebagai cadangan terakhir.
     * Mengembalikan pesan error, atau null jika berhasil.
     */
    suspend fun speak(text: String, engineName: String? = null, rate: Float = 1f): String? {
        val p = Prefs(context)
        val first = engineName ?: p.voiceEngine
        val errors = mutableListOf<String>()
        for (name in listOf(first, "gemini").distinct()) {
            if (name == "android") break
            val e = engine(p, name) ?: continue
            try {
                play(e.synthesize(text)); return null
            } catch (ex: Exception) {
                errors += "$name: ${ex.message}"
            }
        }
        android.speak(text, rate)
        return if (errors.isEmpty()) null else errors.joinToString("; ")
    }

    /** Ucapkan dengan spesifikasi suara tertentu (gemini:Kore, openai:coral, fish:<id>). Melempar error bila gagal. */
    suspend fun speakSpec(text: String, spec: String) = play(Tts.fromSpec(Prefs(context), spec).synthesize(text))

    fun stop() {
        player?.release(); player = null
        android.stop()
    }

    private suspend fun play(audio: ByteArray) {
        val file = File(context.cacheDir, "nexa_tts_${System.nanoTime()}.audio")
        withContext(Dispatchers.IO) { file.writeBytes(audio) }
        withContext(Dispatchers.Main) {
            player?.release()
            suspendCancellableCoroutine<Unit> { cont ->
                val mp = MediaPlayer()
                player = mp
                mp.setDataSource(file.absolutePath)
                mp.setOnCompletionListener { file.delete(); if (cont.isActive) cont.resume(Unit) }
                mp.setOnErrorListener { _, _, _ -> file.delete(); if (cont.isActive) cont.resume(Unit); true }
                mp.prepare()
                mp.start()
                cont.invokeOnCancellation { mp.release() }
            }
        }
    }
}

// -------------------------------------------------------------------------
object Http {
    fun post(url: String, body: JSONObject, headers: Map<String, String>, timeoutMs: Int = 120_000): ByteArray {
        val conn = (URL(url).openConnection() as HttpURLConnection).apply {
            requestMethod = "POST"
            connectTimeout = 15_000
            readTimeout = timeoutMs
            doOutput = true
            setRequestProperty("Content-Type", "application/json")
            headers.forEach { (k, v) -> setRequestProperty(k, v) }
        }
        conn.outputStream.use { it.write(body.toString().toByteArray()) }
        return read(conn)
    }

    fun get(url: String, headers: Map<String, String>, timeoutMs: Int = 120_000): ByteArray {
        val conn = (URL(url).openConnection() as HttpURLConnection).apply {
            connectTimeout = 15_000; readTimeout = timeoutMs
            headers.forEach { (k, v) -> setRequestProperty(k, v) }
        }
        return read(conn)
    }

    /** Unggah file multipart/form-data. */
    fun upload(url: String, file: File, field: String, extra: List<Pair<String, String>>, headers: Map<String, String>): ByteArray {
        val boundary = "----nexa${System.nanoTime()}"
        val conn = (URL(url).openConnection() as HttpURLConnection).apply {
            requestMethod = "POST"; doOutput = true
            connectTimeout = 15_000; readTimeout = 300_000
            setRequestProperty("Content-Type", "multipart/form-data; boundary=$boundary")
            headers.forEach { (k, v) -> setRequestProperty(k, v) }
        }
        conn.outputStream.use { out ->
            extra.forEach { (k, v) ->
                out.write("--$boundary\r\nContent-Disposition: form-data; name=\"$k\"\r\n\r\n$v\r\n".toByteArray())
            }
            out.write(("--$boundary\r\nContent-Disposition: form-data; name=\"$field\"; filename=\"${file.name}\"\r\n" +
                "Content-Type: application/octet-stream\r\n\r\n").toByteArray())
            file.inputStream().use { it.copyTo(out) }
            out.write("\r\n--$boundary--\r\n".toByteArray())
        }
        return read(conn)
    }

    private fun read(c: HttpURLConnection): ByteArray {
        val code = c.responseCode
        if (code !in 200..299) {
            val e = c.errorStream?.bufferedReader()?.use { it.readText() } ?: ""
            throw RuntimeException("HTTP $code ${e.take(200)}")
        }
        return c.inputStream.use { it.readBytes() }
    }
}
