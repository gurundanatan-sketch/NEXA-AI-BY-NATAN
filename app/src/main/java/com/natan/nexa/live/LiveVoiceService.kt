package com.natan.nexa.live

import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import androidx.core.app.ServiceCompat
import androidx.core.content.ContextCompat
import com.natan.nexa.Prefs
import com.natan.nexa.agent.AgentRunner
import com.natan.nexa.wake.Notifications
import com.natan.nexa.wake.WakeWordService

/**
 * Foreground service (tipe mikrofon) yang menjaga sesi Live tetap hidup
 * saat NEXA membuka aplikasi lain atau layar dimatikan.
 * Setelah sesi selesai, pendengar wake word ("Nexa") dinyalakan lagi bila aktif.
 */
class LiveVoiceService : Service() {

    companion object {
        private const val EXTRA_GREET = "greet"
        private const val EXTRA_COMMAND = "command"
        private const val ACTION_STOP = "com.natan.nexa.STOP_LIVE"

        @Volatile
        var session: LiveSession? = null
            private set

        val isActive: Boolean get() = session != null

        /** Dipanggil saat Live mulai/berhenti (untuk UI & tile). */
        var onStateChange: ((Boolean) -> Unit)? = null

        fun start(context: Context, greet: Boolean = false, command: String = "") {
            if (!com.natan.nexa.Premium.allowed(com.natan.nexa.Prefs(context))) {
                com.natan.nexa.agent.AgentRunner.log(com.natan.nexa.Premium.LOCKED); return
            }
            ContextCompat.startForegroundService(context,
                Intent(context, LiveVoiceService::class.java)
                    .putExtra(EXTRA_GREET, greet).putExtra(EXTRA_COMMAND, command))
        }

        fun stop(context: Context) {
            context.stopService(Intent(context, LiveVoiceService::class.java))
        }

        fun toggle(context: Context) = if (isActive) stop(context) else start(context)
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_STOP) { stopSelf(); return START_NOT_STICKY }

        val type = if (Build.VERSION.SDK_INT >= 30) ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE else 0
        val stopIntent = Intent(this, LiveVoiceService::class.java).setAction(ACTION_STOP)
        ServiceCompat.startForeground(this, Notifications.LIVE_ID,
            Notifications.ongoing(this, "${Prefs(this).assistantName} sedang mendengarkan",
                "Ngobrol real-time aktif · bisa disela", stopIntent), type)

        // Mikrofon tidak bisa dipakai dua layanan sekaligus → jeda wake word
        WakeWordService.pause(this)

        if (session == null) {
            session = LiveSession(applicationContext) { AgentRunner.log(it) }
                .also {
                    it.start(intent?.getBooleanExtra(EXTRA_GREET, false) == true,
                        intent?.getStringExtra(EXTRA_COMMAND).orEmpty())
                }
            onStateChange?.invoke(true)
        }
        return START_NOT_STICKY
    }

    override fun onDestroy() {
        session?.stop()
        session = null
        onStateChange?.invoke(false)
        if (Prefs(this).bool("wake_enabled")) WakeWordService.start(this)
        super.onDestroy()
    }
}
