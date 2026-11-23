package com.natan.nexa.phone

import android.Manifest
import android.annotation.SuppressLint
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import android.telecom.TelecomManager
import androidx.core.content.ContextCompat
import com.natan.nexa.notify.NexaNotificationListener

/**
 * Mengangkat / menolak telepon.
 * 1) Coba tombol pada notifikasi telepon (paling kompatibel di Android baru).
 * 2) Cadangan: TelecomManager (butuh izin ANSWER_PHONE_CALLS).
 *
 * Catatan: InCallService penuh hanya bisa dipakai jika Nexa dijadikan
 * aplikasi Telepon bawaan — tidak dipakai di sini.
 */
class CallController(private val context: Context) {

    private val telecom = context.getSystemService(Context.TELECOM_SERVICE) as TelecomManager

    private fun hasPermission() = ContextCompat.checkSelfPermission(
        context, Manifest.permission.ANSWER_PHONE_CALLS
    ) == PackageManager.PERMISSION_GRANTED

    @SuppressLint("MissingPermission")
    @Suppress("DEPRECATION")
    fun answer(): String {
        if (NexaNotificationListener.instance?.pressCallAction(answer = true) == true)
            return "OK: telepon diangkat"
        if (!hasPermission()) return "GAGAL: izin 'Telepon' belum diberikan ke Nexa"
        return try {
            telecom.acceptRingingCall()
            "OK: telepon diangkat"
        } catch (e: Exception) {
            "GAGAL: ${e.message}"
        }
    }

    @SuppressLint("MissingPermission")
    @Suppress("DEPRECATION")
    fun reject(): String {
        if (NexaNotificationListener.instance?.pressCallAction(answer = false) == true)
            return "OK: telepon ditolak"
        if (!hasPermission()) return "GAGAL: izin 'Telepon' belum diberikan ke Nexa"
        if (Build.VERSION.SDK_INT < 28) return "GAGAL: menolak telepon butuh Android 9+"
        return try {
            if (telecom.endCall()) "OK: telepon ditolak/diakhiri" else "GAGAL: tidak ada telepon"
        } catch (e: Exception) {
            "GAGAL: ${e.message}"
        }
    }
}
