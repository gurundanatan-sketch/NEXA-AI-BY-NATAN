package com.natan.nexa.live

import android.annotation.SuppressLint
import android.content.Context
import android.media.AudioAttributes
import android.media.AudioDeviceInfo
import android.media.AudioFormat
import android.media.AudioManager
import android.media.AudioRecord
import android.media.AudioTrack
import android.media.MediaRecorder
import android.media.audiofx.AcousticEchoCanceler
import android.media.audiofx.NoiseSuppressor
import android.os.Build
import android.util.Base64
import com.natan.nexa.Prefs
import com.natan.nexa.agent.Prompts
import com.natan.nexa.agent.ToolExecutor
import com.natan.nexa.agent.Tools
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
            "wss://generativelanguage.googleapis.com/ws/google.ai.generativelanguage.v1beta.GenerativeService.BidiGenerateContent?key="
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
        setupAudioRoute(true)
        startPlayer()
        val req = Request.Builder().url(WS_URL + prefs.geminiApiKey).build()
        ws = client.newWebSocket(req, Listener())
        log("Menyambung ke Gemini Live (${prefs.liveModel})…")
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
        return send(JSONObject().put("realtimeInput", JSONObject().put("text", text)))
    }

    /** Info sistem (telepon masuk, video selesai) yang harus disampaikan Nexa. */
    fun sendSystemNote(note: String): Boolean =
        sendUserText("[INFO SISTEM, sampaikan ke pengguna dengan natural] $note")

    // ------------------------------------------------------------------ WEBSOCKET

    private inner class Listener : WebSocketListener() {
        override fun onOpen(webSocket: WebSocket, response: Response) {
            send(setupMessage())
        }

        override fun onMessage(webSocket: WebSocket, text: String) = handle(text)
        override fun onMessage(webSocket: WebSocket, bytes: ByteString) = handle(bytes.utf8())

        override fun onClosing(webSocket: WebSocket, code: Int, reason: String) {
            log("Live ditutup server ($code) $reason")
            webSocket.close(1000, null)
        }

        override fun onClosed(webSocket: WebSocket, code: Int, reason: String) {
            if (running.get()) stopFromCallback()
        }

        override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) {
            log("Live error: ${t.message ?: response?.message}")
            if (running.get()) stopFromCallback()
        }
    }

    private fun stopFromCallback() {
        LiveVoiceService.stop(context)
    }

    private fun setupMessage(): JSONObject {
        val system = Prompts.live(prefs.assistantName) + "\n\nAPLIKASI TERPASANG: " + tools.installedApps
        val setup = JSONObject()
            .put("model", "models/${prefs.liveModel}")
            .put("generationConfig", JSONObject()
                .put("responseModalities", JSONArray().put("AUDIO"))
                .put("speechConfig", JSONObject().put("voiceConfig", JSONObject()
                    .put("prebuiltVoiceConfig", JSONObject().put("voiceName", prefs.liveVoice)))))
            .put("systemInstruction", JSONObject().put("parts", JSONArray().put(JSONObject().put("text", system))))
            .put("tools", JSONArray().put(JSONObject().put("functionDeclarations", Tools.declarations(forLive = true))))
            .put("inputAudioTranscription", JSONObject())
            .put("outputAudioTranscription", JSONObject())
            // Sesi lebih panjang: konteks lama dipadatkan otomatis
            .put("contextWindowCompression", JSONObject().put("slidingWindow", JSONObject()))
        return JSONObject().put("setup", setup)
    }

    private fun handle(raw: String) {
        val msg = try { JSONObject(raw) } catch (e: Exception) { return }

        if (msg.has("setupComplete")) {
            ready.set(true)
            log("✅ Live tersambung. Silakan bicara (bisa menyela kapan saja).")
            lastActivity = System.currentTimeMillis()
            startMic()
            startIdleWatch()
            when {
                firstCommand.isNotBlank() -> sendUserText(firstCommand)
                greet -> sendUserText("[Pengguna baru saja memanggil namamu. Jawab sangat singkat, mis. 'Ya, ada apa?']")
            }
            return
        }

        msg.optJSONObject("serverContent")?.let { sc ->
            sc.optJSONObject("inputTranscription")?.optString("text")?.let { userText.append(it); lastActivity = System.currentTimeMillis() }
            sc.optJSONObject("outputTranscription")?.optString("text")?.let { modelText.append(it); lastActivity = System.currentTimeMillis() }

            if (sc.optBoolean("interrupted", false)) {
                // BARGE-IN: pengguna menyela → buang sisa audio Nexa
                playQueue.clear()
                track?.run { pause(); flush(); play() }
                flushTranscripts(" (disela)")
            }
            sc.optJSONObject("modelTurn")?.optJSONArray("parts")?.let { parts ->
                for (i in 0 until parts.length()) {
                    val data = parts.optJSONObject(i)?.optJSONObject("inlineData")?.optString("data")
                    if (!data.isNullOrEmpty()) playQueue.offer(Base64.decode(data, Base64.DEFAULT))
                }
            }
            if (sc.optBoolean("turnComplete", false)) flushTranscripts("")
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
                    val result = try { tools.execute(name, args) } catch (e: Exception) { "GAGAL: ${e.message}" }
                    send(JSONObject().put("toolResponse", JSONObject().put("functionResponses",
                        JSONArray().put(JSONObject()
                            .put("id", id)
                            .put("name", name)
                            .put("response", JSONObject().put("result", result))))))
                }
            }
        }

        if (msg.has("goAway")) log("⚠️ Sesi Live hampir habis waktunya; tekan Live lagi setelah berhenti.")
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
        val minBuf = AudioRecord.getMinBufferSize(IN_RATE, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT)
        val chunkBytes = IN_RATE * 2 * CHUNK_MS / 1000
        val rec = AudioRecord(
            MediaRecorder.AudioSource.VOICE_COMMUNICATION, IN_RATE,
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
                send(JSONObject().put("realtimeInput", JSONObject().put("audio",
                    JSONObject().put("data", b64).put("mimeType", "audio/pcm;rate=$IN_RATE"))))
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

    /** Mode komunikasi + speaker luar supaya echo canceller bekerja dan suara terdengar keras. */
    @Suppress("DEPRECATION")
    private fun setupAudioRoute(on: Boolean) {
        val am = context.getSystemService(Context.AUDIO_SERVICE) as AudioManager
        if (on) {
            am.mode = AudioManager.MODE_IN_COMMUNICATION
            if (Build.VERSION.SDK_INT >= 31) {
                am.availableCommunicationDevices
                    .firstOrNull { it.type == AudioDeviceInfo.TYPE_BUILTIN_SPEAKER }
                    ?.let { am.setCommunicationDevice(it) }
            } else {
                am.isSpeakerphoneOn = true
            }
        } else {
            if (Build.VERSION.SDK_INT >= 31) am.clearCommunicationDevice() else am.isSpeakerphoneOn = false
            am.mode = AudioManager.MODE_NORMAL
        }
    }
}
