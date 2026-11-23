package com.natan.nexa.wake

import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.media.AudioManager
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.speech.RecognitionListener
import android.speech.RecognizerIntent
import android.speech.SpeechRecognizer
import androidx.core.app.ServiceCompat
import androidx.core.content.ContextCompat
import com.natan.nexa.Prefs
import com.natan.nexa.agent.AgentRunner
import com.natan.nexa.live.LiveVoiceService

/**
 * Pendengar WAKE WORD di latar belakang: ucapkan "Nexa" (atau nama asisten Anda)
 * dan NEXA langsung menyahut lewat mode Live — tanpa membuka aplikasi.
 *
 * Memakai pengenal suara bawaan Android (on-device bila tersedia) secara berulang.
 * Bisa juga langsung memberi perintah: "Nexa, buka YouTube".
 *
 * Catatan: ini bukan detektor hotword khusus berdaya-rendah seperti "Ok Google";
 * pemakaian baterai lebih tinggi. Untuk deteksi hotword sangat hemat, ganti dengan
 * Picovoice Porcupine (butuh AccessKey) di fungsi startListening().
 */
class WakeWordService : Service() {

    companion object {
        @Volatile var isRunning = false
            private set

        fun start(context: Context) {
            if (LiveVoiceService.isActive) return
            try {
                ContextCompat.startForegroundService(context, Intent(context, WakeWordService::class.java))
            } catch (e: Exception) {
                // Android 12+ melarang memulai layanan mikrofon dari latar belakang tertentu
                Notifications.info(context, "NEXA siap dipanggil", "Ketuk untuk mengaktifkan pendengar \"Nexa\"")
            }
        }

        fun pause(context: Context) {
            context.stopService(Intent(context, WakeWordService::class.java))
        }
    }

    private val main = Handler(Looper.getMainLooper())
    private var recognizer: SpeechRecognizer? = null
    private var stopped = false
    private var errorStreak = 0
    private lateinit var prefs: Prefs
    private var mutedSystem = false

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        prefs = Prefs(this)
        val type = if (Build.VERSION.SDK_INT >= 30) ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE else 0
        ServiceCompat.startForeground(this, Notifications.WAKE_ID,
            Notifications.ongoing(this, "Ucapkan \"${prefs.assistantName}\" untuk memanggil",
                "NEXA AI siaga di latar belakang", null), type)
        isRunning = true
        stopped = false
        if (recognizer == null) main.post { startListening() }
        return START_STICKY
    }

    override fun onDestroy() {
        stopped = true
        isRunning = false
        main.removeCallbacksAndMessages(null)
        recognizer?.destroy()
        recognizer = null
        setBeepMuted(false)
        super.onDestroy()
    }

    // ------------------------------------------------------------------ mendengar
    private fun wakeWords(): List<String> =
        (prefs.str("wake_words") + "," + prefs.assistantName)
            .split(',').map { it.trim().lowercase() }.filter { it.length >= 3 }.distinct()

    private fun startListening() {
        if (stopped) return
        if (!SpeechRecognizer.isRecognitionAvailable(this)) {
            AgentRunner.log("Wake word: pengenal suara tidak tersedia di HP ini (pasang/aktifkan Google app).")
            stopSelf(); return
        }
        recognizer?.destroy()
        val r = if (Build.VERSION.SDK_INT >= 33 && SpeechRecognizer.isOnDeviceRecognitionAvailable(this))
            SpeechRecognizer.createOnDeviceSpeechRecognizer(this)
        else SpeechRecognizer.createSpeechRecognizer(this)
        recognizer = r
        r.setRecognitionListener(listener)
        setBeepMuted(prefs.bool("wake_mute_beep"))
        val i = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
            putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
            putExtra(RecognizerIntent.EXTRA_LANGUAGE, "id-ID")
            putExtra(RecognizerIntent.EXTRA_PARTIAL_RESULTS, true)
            putExtra(RecognizerIntent.EXTRA_PREFER_OFFLINE, true)
            putExtra(RecognizerIntent.EXTRA_MAX_RESULTS, 3)
        }
        try { r.startListening(i) } catch (e: Exception) { restart(1500) }
    }

    private fun restart(delayMs: Long) {
        if (stopped) return
        main.removeCallbacksAndMessages(null)
        main.postDelayed({ startListening() }, delayMs)
    }

    /** Cek apakah teks mengandung wake word; kembalikan sisa kalimat (perintah) atau null. */
    private fun match(texts: List<String>): String? {
        val words = wakeWords()
        for (raw in texts) {
            val t = raw.lowercase()
            for (w in words) {
                val idx = t.indexOf(w)
                if (idx >= 0) return t.substring(idx + w.length).trim(' ', ',', '.', '!', '?')
            }
        }
        return null
    }

    private fun onWake(command: String) {
        stopped = true
        recognizer?.destroy(); recognizer = null
        setBeepMuted(false)
        val suffix = if (command.isNotBlank()) ": \"$command\"" else ""
        AgentRunner.log("👋 Dipanggil$suffix")
        LiveVoiceService.start(this, greet = true, command = command)
        stopSelf()
    }

    private val listener = object : RecognitionListener {
        override fun onReadyForSpeech(params: Bundle?) {}
        override fun onBeginningOfSpeech() {}
        override fun onRmsChanged(rmsdB: Float) {}
        override fun onBufferReceived(buffer: ByteArray?) {}
        override fun onEndOfSpeech() {}
        override fun onEvent(eventType: Int, params: Bundle?) {}

        // Hasil sementara diabaikan: tunggu hasil akhir supaya perintah setelah nama ikut tertangkap
        override fun onPartialResults(partialResults: Bundle?) {}

        override fun onResults(results: Bundle?) {
            errorStreak = 0
            val list = results?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION) ?: emptyList()
            val cmd = match(list)
            if (cmd != null) onWake(cmd) else restart(250)
        }

        override fun onError(error: Int) {
            when (error) {
                SpeechRecognizer.ERROR_INSUFFICIENT_PERMISSIONS -> {
                    AgentRunner.log("Wake word: izin mikrofon belum diberikan."); stopSelf()
                }
                SpeechRecognizer.ERROR_NO_MATCH, SpeechRecognizer.ERROR_SPEECH_TIMEOUT -> restart(200)
                else -> {
                    errorStreak++
                    restart((1000L * errorStreak).coerceAtMost(15_000))
                }
            }
        }
    }

    /** Bisukan bunyi "bip" pengenal suara (stream sistem) selama mendengarkan. */
    @Suppress("DEPRECATION")
    private fun setBeepMuted(mute: Boolean) {
        if (mute == mutedSystem) return
        val am = getSystemService(Context.AUDIO_SERVICE) as AudioManager
        try {
            am.adjustStreamVolume(AudioManager.STREAM_SYSTEM,
                if (mute) AudioManager.ADJUST_MUTE else AudioManager.ADJUST_UNMUTE, 0)
            mutedSystem = mute
        } catch (_: Exception) { /* mode Jangan Ganggu dapat menolak */ }
    }
}
