package com.natan.nexa.agent

import android.content.ActivityNotFoundException
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.hardware.camera2.CameraCharacteristics
import android.hardware.camera2.CameraManager
import android.media.AudioManager
import android.net.Uri
import android.provider.AlarmClock
import android.provider.CalendarContract
import android.view.KeyEvent
import java.text.SimpleDateFormat
import java.util.Locale
import android.provider.Settings

/**
 * Perintah cepat lewat Intent & System API — tanpa perlu "melihat" layar.
 */
class SystemActions(private val context: Context) {

    private fun start(intent: Intent): Boolean = try {
        intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        context.startActivity(intent)
        true
    } catch (e: ActivityNotFoundException) {
        false
    }

    /** Daftar aplikasi yang bisa dibuka: "Nama|package" per baris. */
    fun listApps(): List<Pair<String, String>> {
        val pm = context.packageManager
        val main = Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER)
        return pm.queryIntentActivities(main, 0)
            .map { it.loadLabel(pm).toString() to it.activityInfo.packageName }
            .distinctBy { it.second }
            .sortedBy { it.first.lowercase() }
    }

    /** Buka aplikasi berdasarkan nama ("YouTube") atau package ("com.google.android.youtube"). */
    fun openApp(nameOrPackage: String): String {
        val pm = context.packageManager
        val q = nameOrPackage.trim().lowercase()
        val apps = listApps()
        val pkg = apps.firstOrNull { it.second.lowercase() == q }?.second
            ?: apps.firstOrNull { it.first.lowercase() == q }?.second
            ?: apps.firstOrNull { it.first.lowercase().contains(q) }?.second
            ?: return "GAGAL: aplikasi '$nameOrPackage' tidak ditemukan"
        val intent = pm.getLaunchIntentForPackage(pkg) ?: return "GAGAL: $pkg tidak bisa dibuka"
        return if (start(intent)) "OK: membuka $pkg" else "GAGAL: membuka $pkg"
    }

    fun openUrl(url: String): String {
        val u = if (url.startsWith("http")) url else "https://$url"
        return if (start(Intent(Intent.ACTION_VIEW, Uri.parse(u)))) "OK: membuka $u"
        else "GAGAL: membuka URL"
    }

    /** Membuka dialer dengan nomor terisi (pengguna yang menekan tombol panggil). */
    fun dial(number: String): String =
        if (start(Intent(Intent.ACTION_DIAL, Uri.parse("tel:${Uri.encode(number)}"))))
            "OK: dialer dibuka untuk $number" else "GAGAL: dialer"

    /**
     * Membuka chat WhatsApp dengan pesan terisi.
     * Nomor format internasional tanpa '+', mis. 6281234567890.
     * Setelah ini agen perlu membaca layar dan mengklik tombol Kirim.
     */
    fun whatsapp(phone: String, message: String): String {
        val digits = phone.filter { it.isDigit() }
        val uri = Uri.parse("https://wa.me/$digits?text=${Uri.encode(message)}")
        return if (start(Intent(Intent.ACTION_VIEW, uri))) "OK: chat WhatsApp dibuka, klik tombol kirim"
        else "GAGAL: WhatsApp"
    }

    fun flashlight(on: Boolean): String {
        val cm = context.getSystemService(Context.CAMERA_SERVICE) as CameraManager
        return try {
            val id = cm.cameraIdList.firstOrNull {
                cm.getCameraCharacteristics(it)
                    .get(CameraCharacteristics.FLASH_INFO_AVAILABLE) == true
            } ?: return "GAGAL: HP tidak punya senter"
            cm.setTorchMode(id, on)
            if (on) "OK: senter menyala" else "OK: senter mati"
        } catch (e: Exception) {
            "GAGAL: senter (${e.message})"
        }
    }

    /** percent 0–100 untuk volume media. */
    fun setVolume(percent: Int): String {
        val am = context.getSystemService(Context.AUDIO_SERVICE) as AudioManager
        val max = am.getStreamMaxVolume(AudioManager.STREAM_MUSIC)
        val v = (percent.coerceIn(0, 100) * max) / 100
        am.setStreamVolume(AudioManager.STREAM_MUSIC, v, AudioManager.FLAG_SHOW_UI)
        return "OK: volume media $percent%"
    }

    /**
     * Sejak Android 10 aplikasi biasa tidak boleh menyalakan/mematikan Wi-Fi
     * sendiri, jadi Nexa membuka panel pengaturannya, lalu agen mengklik toggle.
     */
    fun openSettings(panel: String): String {
        val action = when (panel) {
            "wifi" -> if (android.os.Build.VERSION.SDK_INT >= 29)
                Settings.Panel.ACTION_WIFI else Settings.ACTION_WIFI_SETTINGS
            "internet" -> if (android.os.Build.VERSION.SDK_INT >= 29)
                Settings.Panel.ACTION_INTERNET_CONNECTIVITY else Settings.ACTION_WIRELESS_SETTINGS
            "volume" -> if (android.os.Build.VERSION.SDK_INT >= 29)
                Settings.Panel.ACTION_VOLUME else Settings.ACTION_SOUND_SETTINGS
            "bluetooth" -> Settings.ACTION_BLUETOOTH_SETTINGS
            "display" -> Settings.ACTION_DISPLAY_SETTINGS
            "accessibility" -> Settings.ACTION_ACCESSIBILITY_SETTINGS
            else -> Settings.ACTION_SETTINGS
        }
        return if (start(Intent(action))) "OK: pengaturan $panel dibuka" else "GAGAL: pengaturan"
    }

    /** Alarm di aplikasi Jam bawaan. */
    fun setAlarm(hour: Int, minute: Int, label: String): String {
        val i = Intent(AlarmClock.ACTION_SET_ALARM)
            .putExtra(AlarmClock.EXTRA_HOUR, hour.coerceIn(0, 23))
            .putExtra(AlarmClock.EXTRA_MINUTES, minute.coerceIn(0, 59))
            .putExtra(AlarmClock.EXTRA_MESSAGE, label)
            .putExtra(AlarmClock.EXTRA_SKIP_UI, true)
        return if (start(i)) "OK: alarm %02d:%02d dipasang".format(hour, minute) else "GAGAL: aplikasi Jam tidak mendukung"
    }

    fun setTimer(seconds: Int, label: String): String {
        val i = Intent(AlarmClock.ACTION_SET_TIMER)
            .putExtra(AlarmClock.EXTRA_LENGTH, seconds.coerceIn(1, 86_400))
            .putExtra(AlarmClock.EXTRA_MESSAGE, label)
            .putExtra(AlarmClock.EXTRA_SKIP_UI, true)
        return if (start(i)) "OK: timer $seconds detik dimulai" else "GAGAL: aplikasi Jam tidak mendukung"
    }

    /** Baterai, pengisian daya, dan memori. */
    fun deviceInfo(): String {
        val bm = context.getSystemService(Context.BATTERY_SERVICE) as android.os.BatteryManager
        val level = bm.getIntProperty(android.os.BatteryManager.BATTERY_PROPERTY_CAPACITY)
        val charging = bm.isCharging
        val am = context.getSystemService(Context.ACTIVITY_SERVICE) as android.app.ActivityManager
        val mi = android.app.ActivityManager.MemoryInfo().also { am.getMemoryInfo(it) }
        val freeGb = mi.availMem / 1e9
        val stat = android.os.StatFs(android.os.Environment.getDataDirectory().path)
        val storageGb = stat.availableBytes / 1e9
        return "Baterai $level%%${if (charging) " (sedang diisi)" else ""}, RAM kosong %.1f GB, penyimpanan kosong %.1f GB, Android %s, %s %s"
            .format(freeGb, storageGb, android.os.Build.VERSION.RELEASE, android.os.Build.MANUFACTURER, android.os.Build.MODEL)
    }

    @Suppress("unused")
    // ------------------------------------------------------------ fitur pendukung
    fun copyText(text: String): String {
        val cm = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
        cm.setPrimaryClip(ClipData.newPlainText("NEXA", text))
        return "OK: teks disalin ke papan klip"
    }

    fun readClipboard(): String {
        val cm = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
        val clip = cm.primaryClip
        val t = if (clip != null && clip.itemCount > 0) clip.getItemAt(0).coerceToText(context)?.toString() else null
        return if (t.isNullOrBlank()) "Papan klip kosong (Android 10+ hanya mengizinkan saat NEXA terbuka)"
        else "PAPAN KLIP: ${t.take(3000)}"
    }

    /** action: play_pause | next | previous | stop */
    fun mediaControl(action: String): String {
        val am = context.getSystemService(Context.AUDIO_SERVICE) as AudioManager
        val code = when (action) {
            "next" -> KeyEvent.KEYCODE_MEDIA_NEXT
            "previous" -> KeyEvent.KEYCODE_MEDIA_PREVIOUS
            "stop" -> KeyEvent.KEYCODE_MEDIA_STOP
            else -> KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE
        }
        am.dispatchMediaKeyEvent(KeyEvent(KeyEvent.ACTION_DOWN, code))
        am.dispatchMediaKeyEvent(KeyEvent(KeyEvent.ACTION_UP, code))
        return "OK: media $action"
    }

    /** mode: drive | walk | motor */
    fun navigate(place: String, mode: String): String {
        val m = when (mode) { "walk" -> "w"; "motor" -> "l"; else -> "d" }
        val ok = start(Intent(Intent.ACTION_VIEW, Uri.parse("google.navigation:q=${Uri.encode(place)}&mode=$m")))
            || start(Intent(Intent.ACTION_VIEW, Uri.parse("geo:0,0?q=${Uri.encode(place)}")))
        return if (ok) "OK: navigasi ke $place dibuka" else "GAGAL: aplikasi peta tidak ditemukan"
    }

    fun shareText(text: String): String {
        val send = Intent(Intent.ACTION_SEND).setType("text/plain").putExtra(Intent.EXTRA_TEXT, text)
        return if (start(Intent.createChooser(send, "Bagikan lewat"))) "OK: menu bagikan dibuka" else "GAGAL: tidak bisa membagikan"
    }

    /** start: "yyyy-MM-dd HH:mm" (waktu HP). */
    fun addCalendarEvent(title: String, startAt: String, durationMin: Int): String {
        val begin = try {
            SimpleDateFormat("yyyy-MM-dd HH:mm", Locale.US).parse(startAt.trim())?.time
        } catch (e: Exception) { null } ?: return "GAGAL: format waktu harus yyyy-MM-dd HH:mm"
        val i = Intent(Intent.ACTION_INSERT).setData(CalendarContract.Events.CONTENT_URI)
            .putExtra(CalendarContract.Events.TITLE, title)
            .putExtra(CalendarContract.EXTRA_EVENT_BEGIN_TIME, begin)
            .putExtra(CalendarContract.EXTRA_EVENT_END_TIME, begin + durationMin.coerceAtLeast(5) * 60_000L)
        return if (start(i)) "OK: jadwal '$title' dibuka di Kalender, tinggal simpan" else "GAGAL: aplikasi kalender tidak ada"
    }

    private fun isInstalled(pkg: String): Boolean = try {
        context.packageManager.getPackageInfo(pkg, 0); true
    } catch (e: PackageManager.NameNotFoundException) {
        false
    }
}
