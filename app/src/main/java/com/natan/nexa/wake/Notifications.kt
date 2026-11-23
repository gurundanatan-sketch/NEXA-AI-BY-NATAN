package com.natan.nexa.wake

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.os.Build
import androidx.core.app.NotificationCompat
import com.natan.nexa.MainActivity
import com.natan.nexa.R

/** Kanal & notifikasi NEXA (layanan latar belakang wajib menampilkan notifikasi). */
object Notifications {
    const val CHANNEL = "nexa_bg"
    const val LIVE_ID = 42
    const val WAKE_ID = 43
    const val INFO_ID = 44

    fun ensureChannel(ctx: Context) {
        if (Build.VERSION.SDK_INT < 26) return
        val nm = ctx.getSystemService(NotificationManager::class.java)
        if (nm.getNotificationChannel(CHANNEL) == null) {
            nm.createNotificationChannel(
                NotificationChannel(CHANNEL, "NEXA latar belakang", NotificationManager.IMPORTANCE_LOW)
            )
        }
    }

    fun ongoing(ctx: Context, title: String, text: String, stopIntent: Intent?): Notification {
        ensureChannel(ctx)
        val open = PendingIntent.getActivity(
            ctx, 0, Intent(ctx, MainActivity::class.java), PendingIntent.FLAG_IMMUTABLE
        )
        val b = NotificationCompat.Builder(ctx, CHANNEL)
            .setSmallIcon(R.drawable.ic_nexa)
            .setContentTitle(title)
            .setContentText(text)
            .setOngoing(true)
            .setContentIntent(open)
        if (stopIntent != null) {
            b.addAction(0, "Berhenti", PendingIntent.getService(ctx, 1, stopIntent, PendingIntent.FLAG_IMMUTABLE))
        }
        return b.build()
    }

    /** Notifikasi biasa (mis. "ketuk untuk mengaktifkan wake word setelah restart"). */
    fun info(ctx: Context, title: String, text: String) {
        ensureChannel(ctx)
        val open = PendingIntent.getActivity(
            ctx, 2, Intent(ctx, MainActivity::class.java).putExtra("start_wake", true),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )
        val n = NotificationCompat.Builder(ctx, CHANNEL)
            .setSmallIcon(R.drawable.ic_nexa)
            .setContentTitle(title).setContentText(text)
            .setAutoCancel(true).setContentIntent(open).build()
        try {
            ctx.getSystemService(NotificationManager::class.java).notify(INFO_ID, n)
        } catch (_: SecurityException) { /* izin notifikasi belum diberikan */ }
    }
}
