package com.natan.nexa.live

import android.annotation.SuppressLint
import android.content.Context
import android.media.AudioAttributes
import android.media.AudioDeviceCallback
import android.media.AudioDeviceInfo
import android.media.AudioFormat
import android.media.AudioManager
import android.media.AudioRecord
import android.media.AudioTrack
import android.media.MediaRecorder
import android.media.audiofx.AcousticEchoCanceler
import android.media.audiofx.NoiseSuppressor
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.util.Base64
import com.natan.nexa.Prefs
import com.natan.nexa.agent.Prompts
import com.natan.nexa.agent.ToolExecutor
import com.natan.nexa.agent.Tools
import com.natan.nexa.voice.Http
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import okio.ByteString
import org.json.JSONArray
import org.json.JSONObject
import java.net.URLEncoder
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.TimeZone
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Obrolan suara REAL-TIME dua arah (full-duplex) dengan Gemini Live API lewat WebSocket.
 *
 *  Mikrofon (PCM 16 kHz) ──► WebSocket ──► Gemini Live ──► audio (PCM 24 kHz) ──► speaker
 *                                              │
 *                                              └─► toolCall ──► ToolExecutor (kontrol HP) ──► toolResponse
 *
 * - Deteksi suara (VAD) & penyelaan (barge-in) dilakukan otomatis oleh server Live API:
 *   saat pengguna bicara di tengah jawaban, server mengirim `interrupted` → antrean audio dibuang.
 * - AcousticEchoCanceler + mode komunikasi mencegah Nexa "mendengar" suaranya sendiri.
 *   Kalau HP Anda echo-nya parah, matikan "barge-in" di pengaturan (mic dibisukan saat Nexa bicara).
 */
class LiveSession(
    private val context: Context,
    private val log: (String) -> Unit,
) {
    companion object {
        private const val IN_RATE = 16_000
        private const val OUT_RATE = 24_000
        private const val CHUNK_MS = 100
        private const val WS_URL =
            "wss://generativelanguage.googleapis.com/ws/google.ai.generativelanguage.v1beta.GenerativeService.BidiGenerateContent"
        private const val WS_TOKEN_URL =
            "wss://generativelanguage.googleapis.com/ws/google.ai.generativelanguage.v1alpha.GenerativeService.BidiGenerateContentConstrained"
        /** Cara masuk yang terakhir berhasil (0 header, 1 ?key=, 2 token sementara). */
        @Volatile private var goodMode = 0
    }

    /** Cara mengirim kunci: kunci baru AI Studio (AQ.…) & kunci lama (AIza…) didukung otomatis. */
    private var authMode = 0

    private fun enc(v: String) = URLEncoder.encode(v, "UTF-8")

    private fun iso(ms: Long): String =
        SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss'Z'", Locale.US).apply { timeZone = TimeZone.getTimeZone("UTC") }.format(Date(ms))

    /** Token sementara untuk Live (cara resmi untuk aplikasi di HP). */
    private fun ephemeralToken(): String {
        val now = System.currentTimeMillis()
        val body = JSONObject()
            .put("uses", 1)
            .put("expireTime", iso(now + 30 * 60_000L))
            .put("newSessionExpireTime", iso(now + 2 * 60_000L))
        val resp = JSONObject(String(Http.post(
            "https://generativelanguage.googleapis.com/v1alpha/auth_tokens",
            body, mapOf("x-goog-api-key" to prefs.geminiApiKey), 20_000)))
        return resp.optString("name").ifBlank { throw RuntimeException("token kosong") }
    }

    private fun connect() {
        scope.launch {
            if (oa) {
                val model = prefs.str("openai_live_model").ifBlank { "gpt-realtime-2.1" }
                val b = if (prefs.openaiKey == com.natan.nexa.Route.SERVER) {
                    prefs.syncRoute()
                    Request.Builder().url("${com.natan.nexa.Route.wsBase()}/openai-ws?model=${enc(model)}").also { rb ->
                        com.natan.nexa.Route.authHeaders().forEach { (k, v) -> rb.header(k, v) }
                    }
                } else {
                    Request.Builder().url("wss://api.openai.com/v1/realtime?model=${enc(model)}")
                        .header("Authorization", "Bearer ${prefs.openaiKey}")
                }
                ws = client.newWebSocket(b.build(), Listener())
                return@launch
            }
            val key = prefs.geminiApiKey
            if (key == com.natan.nexa.Route.SERVER) {
                // Lewat server NEXA: kunci Gemini ada di server, bukan di HP.
                prefs.syncRoute()
                val b = Request.Builder().url("${com.natan.nexa.Route.wsBase()}/gemini-ws")
                com.natan.nexa.Route.authHeaders().forEach { (k, v) -> b.header(k, v) }
                ws = client.newWebSocket(b.build(), Listener())
                return@launch
            }
            val req = try {
                when (authMode) {
                    0 -> Request.Builder().url(WS_URL).header("x-goog-api-key", key).build()
                    1 -> Request.Builder().url("$WS_URL?key=${enc(key)}").build()
                    else -> Request.Builder().url("$WS_TOKEN_URL?access_token=${enc(ephemeralToken())}").build()
                }
            } catch (e: Exception) {
                log("Live: gagal menyiapkan koneksi (${e.message?.take(120)})")
                null
            }
            if (req == null) { if (running.get()) stopFromCallback(); return@launch }
            ws = client.newWebSocket(req, Listener())
        }
    }

    private val prefs = Prefs(context)
    private val tools = ToolExecutor(context, log)
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val client = OkHttpClient.Builder().readTimeout(0, TimeUnit.MILLISECONDS).build()

    private var ws: WebSocket? = null
    private val running = AtomicBoolean(false)
    private val ready = AtomicBoolean(false)

    private var recorder: AudioRecord? = null
    private var aec: AcousticEchoCanceler? = null
    private var ns: NoiseSuppressor? = null
    private var track: AudioTrack? = null
    private val playQueue = LinkedBlockingQueue<ByteArray>()
    @Volatile private var lastPlayMs = 0L

    /** Mesin Live: Gemini (default) atau OpenAI Realtime bila suara Live yang dipilih "openai:…". */
    private val liveSpec: String get() = prefs.str("live_voice_spec")
    private var oa = false
    private val inRate: Int get() = if (oa) 24_000 else IN_RATE

    // Sesi tanpa batas: sambung ulang otomatis memakai handle resumption Gemini.
    @Volatile private var resumeHandle: String? = null
    @Volatile private var everReady = false
    @Volatile private var reconnectTries = 0
    // Mode diam: Nexa tidak bersuara sampai dipanggil lagi.
    @Volatile private var quiet = false
    @Volatile private var pendingQuiet = false
    private val recentHeard = StringBuilder()

    private val userText = StringBuilder()
    private val modelText = StringBuilder()

    val isConnected: Boolean get() = running.get() && ready.get()

    // ------------------------------------------------------------------ START / STOP

    /** greet = true bila dipicu wake word: NEXA langsung menyahut. */
    private var greet = false
    private var firstCommand = ""
    @Volatile private var lastActivity = System.currentTimeMillis()

    fun start(greet: Boolean = false, command: String = "") {
        this.greet = greet
        this.firstCommand = command
        if (running.getAndSet(true)) return
        if (prefs.geminiApiKey.isEmpty()) { log("Isi Gemini API key dulu."); running.set(false); return }
        resumeHandle = null; everReady = false; reconnectTries = 0; quiet = false; pendingQuiet = false
        oa = liveSpec.startsWith("openai:")
        if (oa && prefs.openaiKey.isEmpty()) { log("Suara Live OpenAI butuh kunci OpenAI di server."); running.set(false); return }
        setupAudioRoute(true)
        startPlayer()
        authMode = goodMode
        connect()
        log(if (oa) "Menyambung ke OpenAI Realtime (suara ${liveSpec.substringAfter(':')})…" else "Menyambung ke Gemini Live (${prefs.liveModel})…")
    }

    fun stop() {
        if (!running.getAndSet(false)) return
        ready.set(false)
        ws?.close(1000, "bye")
        ws = null
        recorder?.run { try { stop() } catch (_: Exception) {}; release() }
        recorder = null
        aec?.release(); ns?.release(); aec = null; ns = null
        playQueue.clear()
        track?.run { try { stop() } catch (_: Exception) {}; release() }
        track = null
        setupAudioRoute(false)
        scope.cancel()
        log("Mode Live berhenti.")
    }

    /** Kirim teks ketikan pengguna ke percakapan Live. */
    fun sendUserText(text: String): Boolean {
        if (!isConnected) return false
        if (oa) {
            send(JSONObject().put("type", "conversation.item.create").put("item", JSONObject()
                .put("type", "message").put("role", "user")
                .put("content", JSONArray().put(JSONObject().put("type", "input_text").put("text", text)))))
            return send(JSONObject().put("type", "response.create"))
        }
        return send(JSONObject().put("realtimeInput", JSONObject().put("text", text)))
    }

    /** Info sistem (telepon masuk, video selesai) yang harus disampaikan Nexa. */
    fun sendSystemNote(note: String): Boolean =
        sendUserText("[INFO SISTEM, sampaikan ke pengguna dengan natural] $note")

    // ------------------------------------------------------------------ WEBSOCKET

    private inner class Listener : WebSocketListener() {
        override fun onOpen(webSocket: WebSocket, response: Response) {
            webSocket.send((if (oa) openAiSession() else setupMessage()).toString())
        }

        override fun onMessage(webSocket: WebSocket, text: String) = handle(text)
        override fun onMessage(webSocket: WebSocket, bytes: ByteString) = handle(bytes.utf8())

        override fun onClosing(webSocket: WebSocket, code: Int, reason: String) {
            webSocket.close(1000, null)
            if (webSocket !== ws) return
            if (retryAuth(code, reason)) return
            if (running.get() && everReady && code != 1007 && reconnectTries < 6) return  // onClosed akan menyambung ulang
            log("Live ditutup server ($code) $reason")
            if (code == 1007 || "API key" in reason)
                log("Tips: buka Pengaturan → 🔌 Cek koneksi semua API. Bila Gemini ❌, buat kunci baru di aistudio.google.com/apikey lalu jalankan setup_keys.sh lagi (atau tempel di Pengaturan).")
        }

        override fun onClosed(webSocket: WebSocket, code: Int, reason: String) {
            if (webSocket !== ws) return
            if (running.get() && everReady && code != 1007 && reconnectTries < 6) { reconnect(); return }
            if (running.get()) stopFromCallback()
        }

        override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) {
            if (webSocket !== ws) return
            if (retryAuth(response?.code ?: 0, t.message.orEmpty() + " " + response?.message.orEmpty())) return
            if (running.get() && everReady && reconnectTries < 6) { reconnect(); return }
            val why = when (response?.code) {
                401 -> "Silakan login dulu di menu Akun."
                402 -> "Live khusus Premium. Aktifkan di Akun → Langganan Premium."
                else -> t.message ?: response?.message
            }
            log("Live error: $why")
            if (running.get()) stopFromCallback()
        }
    }

    /** Kunci ditolak sebelum tersambung → coba cara masuk berikutnya (header → ?key= → token sementara). */
    private fun retryAuth(code: Int, reason: String): Boolean {
        val r = reason.lowercase()
        val authErr = code == 1007 || code == 1008 || code == 401 || code == 403 ||
            "api key" in r || "credential" in r || "unauthenticated" in r || "permission" in r
        if (!authErr || ready.get() || !running.get() || authMode >= 2) return false
        if (oa || prefs.geminiApiKey == com.natan.nexa.Route.SERVER) return false
        authMode++
        log("Kunci ditolak, mencoba cara masuk lain (${authMode + 1}/3)…")
        ws = null
        connect()
        return true
    }

    private fun onReady() {
        ready.set(true)
        reconnectTries = 0
        lastActivity = System.currentTimeMillis()
        if (everReady) { if (recorder == null) startMic(); return }  // sambung ulang diam-diam
        everReady = true
        log("✅ Live tersambung. Silakan bicara (bisa menyela kapan saja).")
        startMic()
        when {
            firstCommand.isNotBlank() -> sendUserText(firstCommand)
            greet -> sendUserText("[Pengguna baru saja memanggil namamu. Jawab sangat singkat, mis. 'Ya, ada apa?']")
        }
    }

    private fun onTurnComplete() {
        if (quiet) modelText.clear()
        flushTranscripts("")
        if (pendingQuiet) { pendingQuiet = false; quiet = true; log("🤫 Nexa diam. Panggil \"${prefs.assistantName}\" atau bilang \"lanjut\" untuk ngobrol lagi.") }
    }

    private suspend fun runTool(name: String, args: JSONObject): String = if (name == "quiet_mode") {
        if (args.optBoolean("on", true)) { pendingQuiet = true; "OK: mode diam aktif setelah kamu selesai bicara" }
        else { quiet = false; pendingQuiet = false; "OK: kembali ngobrol" }
    } else try { tools.execute(name, args) } catch (e: Exception) { "GAGAL: ${e.message}" }

    // ------------------------------------------------------------------ OPENAI REALTIME (suara OpenAI di Live)

    /** Skema fungsi Gemini (tipe huruf besar) → JSON Schema OpenAI (huruf kecil). */
    private fun toJsonSchema(o: JSONObject): JSONObject {
        val out = JSONObject()
        for (k in o.keys()) {
            val v = o.get(k)
            out.put(k, when {
                k == "type" && v is String -> v.lowercase()
                v is JSONObject -> toJsonSchema(v)
                else -> v
            })
        }
        return out
    }

    private fun openAiSession(): JSONObject {
        Prompts.userName = prefs.str("user_name"); Prompts.language = prefs.str("assistant_lang")
        val system = Prompts.live(prefs.assistantName) + "\n\nAPLIKASI TERPASANG: " + tools.installedApps +
            "\n\nUntuk info terkini/fakta, panggil fungsi google_search lalu sebutkan sumbernya."
        val decl = Tools.declarations(forLive = true)
        val toolsArr = JSONArray()
        for (i in 0 until decl.length()) {
            val f = decl.getJSONObject(i)
            toolsArr.put(JSONObject().put("type", "function").put("name", f.getString("name"))
                .put("description", f.optString("description"))
                .put("parameters", f.optJSONObject("parameters")?.let { toJsonSchema(it) }
                    ?: JSONObject().put("type", "object").put("properties", JSONObject())))
        }
        val pcm = JSONObject().put("type", "audio/pcm").put("rate", 24_000)
        return JSONObject().put("type", "session.update").put("session", JSONObject()
            .put("type", "realtime")
            .put("model", prefs.str("openai_live_model").ifBlank { "gpt-realtime-2.1" })
            .put("output_modalities", JSONArray().put("audio"))
            .put("instructions", system)
            .put("audio", JSONObject()
                .put("input", JSONObject().put("format", pcm)
                    .put("transcription", JSONObject().put("model", "gpt-4o-mini-transcribe"))
                    .put("turn_detection", JSONObject().put("type", "semantic_vad")
                        .put("interrupt_response", prefs.liveBargeIn)))
                .put("output", JSONObject().put("format", JSONObject(pcm.toString()))
                    .put("voice", liveSpec.substringAfter(':').ifBlank { "marin" })))
            .put("tools", toolsArr)
            .put("tool_choice", "auto"))
    }

    private fun handleOpenAi(msg: JSONObject) {
        when (msg.optString("type")) {
            "session.updated" -> if (!ready.get()) onReady()
            "input_audio_buffer.speech_started" -> if (prefs.liveBargeIn) {
                playQueue.clear()
                track?.run { pause(); flush(); play() }
                if (modelText.isNotBlank()) flushTranscripts(" (disela)")
            }
            "conversation.item.input_audio_transcription.delta" -> msg.optString("delta").let {
                userText.append(it); lastActivity = System.currentTimeMillis()
                if (quiet) checkWakeFromQuiet(it)
            }
            "conversation.item.input_audio_transcription.completed" -> {
                val t = msg.optString("transcript")
                if (userText.isBlank()) { userText.append(t); if (quiet) checkWakeFromQuiet(t) }
                lastActivity = System.currentTimeMillis()
            }
            "response.output_audio.delta" -> if (!quiet) msg.optString("delta").takeIf { it.isNotEmpty() }
                ?.let { playQueue.offer(Base64.decode(it, Base64.DEFAULT)) }
            "response.output_audio_transcript.delta" -> if (!quiet) { modelText.append(msg.optString("delta")); lastActivity = System.currentTimeMillis() }
            "response.done" -> {
                val output = msg.optJSONObject("response")?.optJSONArray("output") ?: JSONArray()
                val calls = (0 until output.length()).mapNotNull { output.optJSONObject(it) }
                    .filter { it.optString("type") == "function_call" }
                onTurnComplete()
                if (calls.isNotEmpty()) scope.launch {
                    for (c in calls) {
                        val name = c.optString("name")
                        val args = try { JSONObject(c.optString("arguments").ifBlank { "{}" }) } catch (e: Exception) { JSONObject() }
                        log("🔧 $name $args")
                        val result = runTool(name, args)
                        send(JSONObject().put("type", "conversation.item.create").put("item", JSONObject()
                            .put("type", "function_call_output").put("call_id", c.optString("call_id")).put("output", result)))
                    }
                    send(JSONObject().put("type", "response.create"))
                }
            }
            "error" -> log("Live OpenAI: ${msg.optJSONObject("error")?.optString("message") ?: msg}")
        }
    }

    /** Sambung ulang Live tanpa memutus obrolan (dipakai saat goAway / koneksi putus). */
    private fun reconnect() {
        if (!running.get()) return
        reconnectTries++
        ready.set(false)
        val old = ws
        ws = null
        try { old?.close(1000, "resume") } catch (_: Exception) {}
        greet = false; firstCommand = ""
        Handler(Looper.getMainLooper()).postDelayed({ if (running.get() && ws == null) connect() },
            if (reconnectTries <= 1) 200L else 1500L * reconnectTries)
    }

    /** Saat mode diam: aktif lagi bila nama dipanggil atau pengguna bilang sudah boleh lanjut. */
    private fun checkWakeFromQuiet(piece: String) {
        recentHeard.append(piece.lowercase())
        if (recentHeard.length > 160) recentHeard.delete(0, recentHeard.length - 160)
        val heard = recentHeard.toString()
        val names = (prefs.str("wake_words").split(",") + prefs.assistantName)
            .map { it.trim().lowercase() }.filter { it.length >= 3 }
        val phrases = listOf("lanjut", "udah bisa", "sudah bisa", "ngobrol lagi", "bicara lagi", "boleh ngomong",
            "udah selesai", "sudah selesai", "balik lagi", "ayo ngobrol")
        if (names.any { it in heard } || phrases.any { it in heard }) {
            quiet = false; pendingQuiet = false; recentHeard.clear()
            log("🔊 Nexa aktif lagi.")
            sendUserText("[INFO SISTEM] Pengguna memanggilmu lagi / sudah selesai bicara dengan orang lain. " +
                "Sapa super singkat dengan gaya santai lalu lanjutkan obrolan.")
        }
    }

    private fun stopFromCallback() {
        LiveVoiceService.stop(context)
    }

    private fun setupMessage(): JSONObject {
        Prompts.userName = prefs.str("user_name"); Prompts.language = prefs.str("assistant_lang")
        val system = Prompts.live(prefs.assistantName) + "\n\nAPLIKASI TERPASANG: " + tools.installedApps
        val setup = JSONObject()
            .put("model", "models/${prefs.liveModel}")
            .put("generationConfig", JSONObject()
                .put("responseModalities", JSONArray().put("AUDIO"))
                .put("speechConfig", JSONObject().put("voiceConfig", JSONObject()
                    .put("prebuiltVoiceConfig", JSONObject().put("voiceName", prefs.liveVoice)))))
            .put("systemInstruction", JSONObject().put("parts", JSONArray().put(JSONObject().put("text", system))))
            .put("tools", JSONArray()
                .put(JSONObject().put("functionDeclarations", Tools.declarations(forLive = true)))
                .put(JSONObject().put("googleSearch", JSONObject())))
            .put("inputAudioTranscription", JSONObject())
            .put("outputAudioTranscription", JSONObject())
            // Sesi lebih panjang: konteks lama dipadatkan otomatis
            .put("contextWindowCompression", JSONObject().put("slidingWindow", JSONObject()))
            // Sambung ulang tanpa kehilangan obrolan (Live aktif tanpa batas waktu)
            .put("sessionResumption", JSONObject().apply { resumeHandle?.let { put("handle", it) } })
        return JSONObject().put("setup", setup)
    }

    private fun handle(raw: String) {
        val msg = try { JSONObject(raw) } catch (e: Exception) { return }

        if (oa) { handleOpenAi(msg); return }

        if (msg.has("setupComplete")) {
            goodMode = authMode
            onReady()
            return
        }

        msg.optJSONObject("serverContent")?.let { sc ->
            sc.optJSONObject("inputTranscription")?.optString("text")?.let {
                userText.append(it); lastActivity = System.currentTimeMillis()
                if (quiet) checkWakeFromQuiet(it)
            }
            if (!quiet) sc.optJSONObject("outputTranscription")?.optString("text")?.let { modelText.append(it); lastActivity = System.currentTimeMillis() }

            if (sc.optBoolean("interrupted", false)) {
                // BARGE-IN: pengguna menyela → buang sisa audio Nexa
                playQueue.clear()
                track?.run { pause(); flush(); play() }
                flushTranscripts(" (disela)")
            }
            sc.optJSONObject("modelTurn")?.optJSONArray("parts")?.let { parts ->
                for (i in 0 until parts.length()) {
                    val data = parts.optJSONObject(i)?.optJSONObject("inlineData")?.optString("data")
                    if (!data.isNullOrEmpty() && !quiet) playQueue.offer(Base64.decode(data, Base64.DEFAULT))
                }
            }
            if (sc.optBoolean("turnComplete", false)) onTurnComplete()
        }

        msg.optJSONObject("toolCall")?.optJSONArray("functionCalls")?.let { calls ->
            for (i in 0 until calls.length()) {
                val fc = calls.getJSONObject(i)
                val id = fc.optString("id")
                val name = fc.getString("name")
                val args = fc.optJSONObject("args") ?: JSONObject()
                log("🔧 $name $args")
                lastActivity = System.currentTimeMillis()
                scope.launch {
                    val result = runTool(name, args)
                    send(JSONObject().put("toolResponse", JSONObject().put("functionResponses",
                        JSONArray().put(JSONObject()
                            .put("id", id)
                            .put("name", name)
                            .put("response", JSONObject().put("result", result))))))
                }
            }
        }

        msg.optJSONObject("sessionResumptionUpdate")?.let { u ->
            if (u.optBoolean("resumable", true)) u.optString("newHandle").takeIf { it.isNotBlank() }?.let { resumeHandle = it }
        }
        // Server akan memutus sesi sebentar lagi → sambung ulang duluan, obrolan tetap nyambung.
        if (msg.has("goAway")) reconnect()
    }

    /** Tutup sesi otomatis bila tidak ada percakapan selama live_idle_sec (hemat kuota). */
    private fun startIdleWatch() {
        val idleMs = prefs.int("live_idle_sec") * 1000L
        if (idleMs <= 0) return
        Thread {
            while (running.get()) {
                Thread.sleep(2000)
                val quiet = System.currentTimeMillis() - lastActivity > idleMs &&
                    System.currentTimeMillis() - lastPlayMs > idleMs && playQueue.isEmpty()
                if (quiet && running.get()) {
                    log("Live ditutup otomatis (tidak ada percakapan).")
                    stopFromCallback()
                    break
                }
            }
        }.apply { name = "nexa-idle"; isDaemon = true; start() }
    }

    private fun flushTranscripts(suffix: String) {
        if (userText.isNotBlank()) log("Kamu: ${userText.toString().trim()}")
        if (modelText.isNotBlank()) log("Nexa: ${modelText.toString().trim()}$suffix")
        userText.clear(); modelText.clear()
    }

    private fun send(obj: JSONObject): Boolean = ws?.send(obj.toString()) ?: false

    // ------------------------------------------------------------------ AUDIO

    @SuppressLint("MissingPermission") // izin RECORD_AUDIO diminta di MainActivity
    private fun startMic() {
        val rate = inRate
        val minBuf = AudioRecord.getMinBufferSize(rate, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT)
        val chunkBytes = rate * 2 * CHUNK_MS / 1000
        val rec = AudioRecord(
            MediaRecorder.AudioSource.VOICE_COMMUNICATION, rate,
            AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT,
            maxOf(minBuf, chunkBytes * 4)
        )
        if (rec.state != AudioRecord.STATE_INITIALIZED) {
            log("GAGAL: mikrofon tidak bisa dibuka (izin mikrofon?)")
            return
        }
        // Simpan referensinya; kalau tidak, efek bisa dilepas oleh garbage collector.
        if (AcousticEchoCanceler.isAvailable())
            aec = AcousticEchoCanceler.create(rec.audioSessionId)?.also { it.setEnabled(true) }
        if (NoiseSuppressor.isAvailable())
            ns = NoiseSuppressor.create(rec.audioSessionId)?.also { it.setEnabled(true) }
        recorder = rec
        rec.startRecording()

        Thread {
            val buf = ByteArray(chunkBytes)
            while (running.get() && recorder === rec) {
                val n = rec.read(buf, 0, buf.size)
                if (n <= 0) continue
                // Mode tanpa barge-in: bisukan mic selama Nexa bicara
                val speaking = System.currentTimeMillis() - lastPlayMs < 400
                if (!prefs.liveBargeIn && speaking) continue
                val b64 = Base64.encodeToString(buf, 0, n, Base64.NO_WRAP)
                if (oa) send(JSONObject().put("type", "input_audio_buffer.append").put("audio", b64))
                else send(JSONObject().put("realtimeInput", JSONObject().put("audio",
                    JSONObject().put("data", b64).put("mimeType", "audio/pcm;rate=$rate"))))
            }
        }.apply { name = "nexa-mic"; start() }
    }

    private fun startPlayer() {
        val minBuf = AudioTrack.getMinBufferSize(OUT_RATE, AudioFormat.CHANNEL_OUT_MONO, AudioFormat.ENCODING_PCM_16BIT)
        val t = AudioTrack.Builder()
            .setAudioAttributes(AudioAttributes.Builder()
                .setUsage(AudioAttributes.USAGE_VOICE_COMMUNICATION)
                .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
                .build())
            .setAudioFormat(AudioFormat.Builder()
                .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
                .setSampleRate(OUT_RATE)
                .setChannelMask(AudioFormat.CHANNEL_OUT_MONO)
                .build())
            .setBufferSizeInBytes(minBuf * 4)
            .setTransferMode(AudioTrack.MODE_STREAM)
            .build()
        track = t
        t.play()
        Thread {
            while (running.get() && track === t) {
                val chunk = playQueue.poll(200, TimeUnit.MILLISECONDS) ?: continue
                lastPlayMs = System.currentTimeMillis()
                t.write(chunk, 0, chunk.size)
                lastPlayMs = System.currentTimeMillis()
            }
        }.apply { name = "nexa-speaker"; start() }
    }

    // Urutan prioritas: headset Bluetooth (LE / SCO) → headset kabel / USB → speaker HP.
    private val headsetTypes: List<Int> = buildList {
        if (Build.VERSION.SDK_INT >= 31) add(AudioDeviceInfo.TYPE_BLE_HEADSET)
        add(AudioDeviceInfo.TYPE_BLUETOOTH_SCO)
        add(AudioDeviceInfo.TYPE_WIRED_HEADSET)
        add(AudioDeviceInfo.TYPE_WIRED_HEADPHONES)
        if (Build.VERSION.SDK_INT >= 26) add(AudioDeviceInfo.TYPE_USB_HEADSET)
    }
    private var deviceCallback: AudioDeviceCallback? = null
    @Volatile private var scoStarted = false

    /**
     * Mode komunikasi (echo canceller aktif). Suara Nexa otomatis masuk ke headset/earphone
     * (kabel, USB, Bluetooth) bila terpasang, dan pindah otomatis saat headset dicolok/dicabut.
     * Tanpa headset → speaker HP yang keras.
     */
    @Suppress("DEPRECATION")
    private fun setupAudioRoute(on: Boolean) {
        val am = context.getSystemService(Context.AUDIO_SERVICE) as AudioManager
        if (on) {
            am.mode = AudioManager.MODE_IN_COMMUNICATION
            applyRoute(am)
            if (deviceCallback == null) {
                val cb = object : AudioDeviceCallback() {
                    override fun onAudioDevicesAdded(added: Array<out AudioDeviceInfo>) { if (running.get()) applyRoute(am) }
                    override fun onAudioDevicesRemoved(removed: Array<out AudioDeviceInfo>) { if (running.get()) applyRoute(am) }
                }
                deviceCallback = cb
                am.registerAudioDeviceCallback(cb, Handler(Looper.getMainLooper()))
            }
        } else {
            deviceCallback?.let { cb -> am.unregisterAudioDeviceCallback(cb) }
            deviceCallback = null
            if (Build.VERSION.SDK_INT >= 31) am.clearCommunicationDevice()
            else {
                if (scoStarted) { try { am.stopBluetoothSco() } catch (_: Exception) {}; am.isBluetoothScoOn = false; scoStarted = false }
                am.isSpeakerphoneOn = false
            }
            am.mode = AudioManager.MODE_NORMAL
        }
    }

    @Suppress("DEPRECATION")
    private fun applyRoute(am: AudioManager) {
        try {
            if (Build.VERSION.SDK_INT >= 31) {
                val devs = am.availableCommunicationDevices
                val headset = headsetTypes.firstNotNullOfOrNull { t -> devs.firstOrNull { it.type == t } }
                val target = headset ?: devs.firstOrNull { it.type == AudioDeviceInfo.TYPE_BUILTIN_SPEAKER }
                if (target != null) am.setCommunicationDevice(target)
                log(if (headset != null) "🎧 Suara Nexa lewat headset (${headset.productName})" else "🔊 Suara Nexa lewat speaker")
            } else {
                val outs = am.getDevices(AudioManager.GET_DEVICES_OUTPUTS)
                val bt = outs.any { it.type == AudioDeviceInfo.TYPE_BLUETOOTH_SCO || it.type == AudioDeviceInfo.TYPE_BLUETOOTH_A2DP }
                val wired = outs.any { it.type == AudioDeviceInfo.TYPE_WIRED_HEADSET || it.type == AudioDeviceInfo.TYPE_WIRED_HEADPHONES ||
                    (Build.VERSION.SDK_INT >= 26 && it.type == AudioDeviceInfo.TYPE_USB_HEADSET) }
                when {
                    wired -> { am.isSpeakerphoneOn = false; log("🎧 Suara Nexa lewat headset kabel") }
                    bt && am.isBluetoothScoAvailableOffCall -> {
                        am.isSpeakerphoneOn = false
                        if (!scoStarted) { am.startBluetoothSco(); am.isBluetoothScoOn = true; scoStarted = true }
                        log("🎧 Suara Nexa lewat headset Bluetooth")
                    }
                    else -> {
                        if (scoStarted) { try { am.stopBluetoothSco() } catch (_: Exception) {}; am.isBluetoothScoOn = false; scoStarted = false }
                        am.isSpeakerphoneOn = true
                    }
                }
            }
        } catch (e: Exception) {
            log("Rute audio: ${e.message}")
        }
    }
}
