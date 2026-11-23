package com.natan.nexa.system

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.os.Build
import android.service.quicksettings.Tile
import android.service.quicksettings.TileService
import androidx.annotation.RequiresApi
import com.natan.nexa.Prefs
import com.natan.nexa.live.LiveVoiceService
import com.natan.nexa.wake.Notifications
import com.natan.nexa.wake.WakeWordService

/**
 * Setelah HP dinyalakan ulang: coba aktifkan lagi pendengar "Nexa".
 * Android 14+ membatasi layanan mikrofon dari boot, jadi bila ditolak NEXA
 * menampilkan notifikasi "ketuk untuk mengaktifkan".
 */
class BootReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != Intent.ACTION_BOOT_COMPLETED && intent.action != Intent.ACTION_MY_PACKAGE_REPLACED) return
        if (!Prefs(context).bool("wake_enabled")) return
        if (Build.VERSION.SDK_INT >= 34) {
            Notifications.info(context, "NEXA siap dipanggil", "Ketuk untuk mengaktifkan pendengar \"Nexa\" setelah restart")
        } else {
            WakeWordService.start(context)
        }
    }
}

/** Tombol di Pengaturan Cepat (tarik bilah notifikasi) untuk mulai/berhenti ngobrol dengan NEXA. */
@RequiresApi(24)
class NexaTileService : TileService() {
    override fun onStartListening() {
        super.onStartListening()
        refresh()
    }

    override fun onClick() {
        super.onClick()
        LiveVoiceService.toggle(applicationContext)
        qsTile?.state = if (LiveVoiceService.isActive) Tile.STATE_INACTIVE else Tile.STATE_ACTIVE
        qsTile?.updateTile()
    }

    private fun refresh() {
        qsTile?.state = if (LiveVoiceService.isActive) Tile.STATE_ACTIVE else Tile.STATE_INACTIVE
        qsTile?.updateTile()
    }
}
