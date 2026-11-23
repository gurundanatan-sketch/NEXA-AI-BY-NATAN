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
// 2) SERVER NEXA /tts — MiniMax (suara kloning), OpenAI, Gemini, GPT-SoVITS
//    Kunci API-nya ada di Termux, bukan di HP.
// -------------------------------------------------------------------------
class ServerTts(
    private val baseUrl: String,
    private val token: String,
    private val voice: String, // mis. "minimax:NexaVoice01", "openai:alloy", "gemini:Kore"
) : TtsEngine {
    override suspend fun synthesize(text: String): ByteArray = withContext(Dispatchers.IO) {
        Http.post("$baseUrl/tts", JSONObject().put("text", text).put("voice", voice),
            mapOf("X-Nexa-Token" to token))
    }
}

// -------------------------------------------------------------------------
// 3) GPT-SoVITS langsung (PC/GPU) — zh, en, ja, ko, yue
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
// 4) TTS bawaan Android — paling cepat & offline (cocok untuk TikTok Live)
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
        "gemini" -> if (p.geminiApiKey.isNotEmpty()) GeminiTts(p.geminiApiKey, p.geminiTtsModel, p.geminiVoice) else null
        "server" -> if (p.backendUrl.isNotEmpty() && p.str("server_voice").isNotEmpty())
            ServerTts(p.backendUrl, p.backendToken, p.str("server_voice")) else null
        "sovits" -> if (p.sovitsUrl.isNotEmpty() && p.sovitsRefPath.isNotEmpty())
            GptSovitsTts(p.sovitsUrl, p.sovitsRefPath, p.sovitsPromptText, p.sovitsLang) else null
        else -> null
    }

    /**
     * Ucapkan teks. engineName: gemini | server | sovits | android (default = pengaturan).
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
    fun upload(url: String, file: File, field: String, extra: Map<String, String>, headers: Map<String, String>): ByteArray {
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
