package com.natan.nexa.agent

import android.content.Context
import com.natan.nexa.Prefs
import com.natan.nexa.live.LiveVoiceService
import com.natan.nexa.notify.NexaNotificationListener
import com.natan.nexa.voice.VoiceOutput
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch

/**
 * Menjalankan agen mode PERINTAH di scope sendiri (bukan scope Activity), supaya tugas
 * tetap berjalan ketika Nexa pindah ke belakang dan aplikasi lain terbuka.
 * Juga mengumumkan telepon masuk & hasil tugas latar belakang.
 */
object AgentRunner {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
    private var job: Job? = null
    private var agent: GeminiAgent? = null
    private var voice: VoiceOutput? = null
    private var appContext: Context? = null

    var listener: ((String) -> Unit)? = null
    fun log(s: String) = listener?.invoke(s)

    val isBusy: Boolean get() = job?.isActive == true

    /** Dipanggil sekali dari MainActivity. */
    fun init(context: Context) {
        val app = context.applicationContext
        appContext = app
        if (voice == null) voice = VoiceOutput(app)

        NexaNotificationListener.onIncomingCall = { caller ->
            announce("Ada telepon masuk dari $caller. Mau diangkat atau ditolak?")
        }
        ToolExecutor.onBackgroundResult = { msg -> announce(msg) }
        com.natan.nexa.studio.VideoJobs.onFinished = { job ->
            announce(if (job.optString("status") == "done") "Videonya sudah jadi dan tersimpan di Galeri."
            else "Pembuatan video gagal. ${job.optString("message").take(120)}")
        }
    }

    /** Umumkan lewat mode Live bila aktif, kalau tidak lewat TTS biasa. */
    fun announce(text: String) {
        log("Nexa: $text")
        if (LiveVoiceService.session?.sendSystemNote(text) == true) return
        scope.launch { voice?.speak(text)?.let { log("Suara gagal: $it") } }
    }

    fun submit(context: Context, text: String) {
        if (isBusy) { log("Masih mengerjakan tugas sebelumnya. Tekan Stop dulu."); return }
        val app = context.applicationContext
        val prefs = Prefs(app)
        if (prefs.geminiApiKey.isEmpty()) { log("Isi Gemini API key dulu."); return }
        val v = voice ?: VoiceOutput(app).also { voice = it }

        job = scope.launch {
            try {
                val pending = agent?.takeIf { it.waitingForAnswer }
                val reply = if (pending != null) {
                    log("Jawaban: $text")
                    pending.answer(text)
                } else {
                    log("Perintah: $text")
                    Prompts.userName = prefs.str("user_name"); Prompts.language = prefs.str("assistant_lang")
                    val a = GeminiAgent(prefs.geminiApiKey, prefs.agentModel, ToolExecutor(app, ::log), ::log)
                    agent = a
                    a.run(text)
                }
                log("Nexa: $reply")
                v.speak(reply)?.let { log("Suara gagal: $it") }
            } catch (e: Exception) {
                log("Error: ${e.message}")
            }
        }
    }

    fun stop() {
        job?.cancel()
        voice?.stop()
        agent = null
        log("Dihentikan.")
    }

    fun testVoice(context: Context, text: String) {
        val v = voice ?: VoiceOutput(context.applicationContext).also { voice = it }
        scope.launch {
            log("Tes suara…")
            log(v.speak(text)?.let { "Suara gagal: $it" } ?: "Suara OK")
        }
    }
}
