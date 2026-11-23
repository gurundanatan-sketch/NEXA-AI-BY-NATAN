package com.natan.nexa.studio

import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import androidx.core.app.ServiceCompat
import androidx.core.content.ContextCompat
import com.natan.nexa.wake.Notifications

/** Menjaga proses render video tetap hidup saat aplikasi di latar belakang. */
class StudioService : Service() {

    companion object {
        fun start(ctx: Context) {
            try {
                ContextCompat.startForegroundService(ctx, Intent(ctx, StudioService::class.java))
            } catch (_: Exception) { /* render tetap berjalan selama aplikasi terbuka */ }
        }

        fun stop(ctx: Context) = ctx.stopService(Intent(ctx, StudioService::class.java))
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val type = if (Build.VERSION.SDK_INT >= 29) ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC else 0
        ServiceCompat.startForeground(this, Notifications.STUDIO_ID,
            Notifications.ongoing(this, "NEXA sedang membuat video", "Proses berjalan di latar belakang…", null), type)
        return START_NOT_STICKY
    }
}
