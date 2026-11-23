package com.natan.nexa.notify

import android.app.Notification
import android.app.RemoteInput
import android.content.Intent
import android.os.Bundle
import android.service.notification.NotificationListenerService
import android.service.notification.StatusBarNotification

/**
 * Menangkap notifikasi masuk (WhatsApp, Telegram, SMS, telepon, dll.).
 * - recent(): daftar notifikasi terbaru untuk dibacakan
 * - reply(): Direct Reply lewat RemoteInput tanpa membuka aplikasinya
 * - onIncomingCall: dipanggil saat ada telepon masuk (untuk diumumkan)
 *
 * Diaktifkan pengguna lewat: Pengaturan → Notifikasi → Akses notifikasi → Nexa.
 */
class NexaNotificationListener : NotificationListenerService() {

    data class Item(
        val key: String,
        val app: String,
        val sender: String,
        val text: String,
        val time: Long,
        val canReply: Boolean,
        val isCall: Boolean,
    )

    companion object {
        @Volatile
        var instance: NexaNotificationListener? = null
            private set
        val isRunning get() = instance != null

        /** Dipanggil dengan nama penelepon saat telepon masuk. */
        var onIncomingCall: ((String) -> Unit)? = null

        private const val MAX_ITEMS = 50
        private val ANSWER_WORDS = listOf("answer", "jawab", "terima", "angkat")
        private val DECLINE_WORDS = listOf("decline", "tolak", "reject", "hang up", "akhiri")
        private val CHAT_APPS = mapOf(
            "com.whatsapp" to "WhatsApp",
            "com.whatsapp.w4b" to "WhatsApp Business",
            "org.telegram.messenger" to "Telegram",
            "com.google.android.apps.messaging" to "Pesan",
            "com.instagram.android" to "Instagram",
            "com.facebook.orca" to "Messenger",
            "jp.naver.line.android" to "LINE",
        )
    }

    private val items = ArrayDeque<Item>()
    private val announcedCalls = HashSet<String>()
    private val sbnByKey = HashMap<String, StatusBarNotification>()

    override fun onListenerConnected() { instance = this }
    override fun onListenerDisconnected() { instance = null }
    override fun onDestroy() { instance = null; super.onDestroy() }

    override fun onNotificationPosted(sbn: StatusBarNotification) {
        val n = sbn.notification ?: return
        if (sbn.packageName == packageName) return
        // Abaikan ringkasan grup ("5 pesan baru dari 3 chat")
        if (n.flags and Notification.FLAG_GROUP_SUMMARY != 0) return

        val extras = n.extras
        val title = extras.getCharSequence(Notification.EXTRA_TITLE)?.toString() ?: ""
        val text = (extras.getCharSequence(Notification.EXTRA_BIG_TEXT)
            ?: extras.getCharSequence(Notification.EXTRA_TEXT))?.toString() ?: ""
        if (title.isBlank() && text.isBlank()) return

        val isCall = n.category == Notification.CATEGORY_CALL
        val item = Item(
            key = sbn.key,
            app = CHAT_APPS[sbn.packageName] ?: appLabel(sbn.packageName),
            sender = title,
            text = text,
            time = sbn.postTime,
            canReply = replyAction(n) != null,
            isCall = isCall,
        )
        synchronized(items) {
            items.removeAll { it.key == item.key }
            items.addFirst(item)
            while (items.size > MAX_ITEMS) items.removeLast()
            sbnByKey[sbn.key] = sbn
        }
        // Umumkan sekali per telepon, hanya saat masih berdering (ada tombol Jawab)
        if (isCall && hasAction(n, ANSWER_WORDS) && announcedCalls.add(sbn.key)) {
            onIncomingCall?.invoke(title.ifBlank { "nomor tidak dikenal" })
        }
    }

    override fun onNotificationRemoved(sbn: StatusBarNotification) {
        announcedCalls.remove(sbn.key)
        synchronized(items) {
            sbnByKey.remove(sbn.key)
            items.removeAll { it.isCall && it.key == sbn.key }
        }
    }

    /** Teks ringkas untuk Gemini. filterApp mis. "WhatsApp" atau kosong untuk semua. */
    fun recent(filterApp: String = "", limit: Int = 10): String {
        val list = synchronized(items) {
            items.filter { !it.isCall && (filterApp.isBlank() || it.app.contains(filterApp, true)) }
                .take(limit)
        }
        if (list.isEmpty()) return "Tidak ada notifikasi chat baru."
        return list.mapIndexed { i, it ->
            val ago = (System.currentTimeMillis() - it.time) / 60000
            "[$i] ${it.app} – ${it.sender}: ${it.text.take(300)} ($ago menit lalu" +
                (if (it.canReply) ", bisa dibalas)" else ")")
        }.joinToString("\n")
    }

    /** Balas notifikasi nomor [index] dari hasil recent(filterApp) terakhir. */
    fun reply(index: Int, message: String, filterApp: String = ""): String {
        val item = synchronized(items) {
            items.filter { !it.isCall && (filterApp.isBlank() || it.app.contains(filterApp, true)) }
                .getOrNull(index)
        } ?: return "GAGAL: notifikasi [$index] tidak ada"
        val sbn = synchronized(items) { sbnByKey[item.key] }
            ?: return "GAGAL: notifikasi sudah hilang, buka chatnya lewat layar"
        val action = replyAction(sbn.notification)
            ?: return "GAGAL: notifikasi ini tidak mendukung balas langsung"

        val intent = Intent()
        val bundle = Bundle()
        action.remoteInputs.forEach { bundle.putCharSequence(it.resultKey, message) }
        RemoteInput.addResultsToIntent(action.remoteInputs, intent, bundle)
        return try {
            action.actionIntent.send(this, 0, intent)
            "OK: balasan terkirim ke ${item.sender} (${item.app})"
        } catch (e: Exception) {
            "GAGAL: ${e.message}"
        }
    }

    /** Jalankan tombol pada notifikasi telepon, mis. "Jawab"/"Tolak". */
    fun pressCallAction(answer: Boolean): Boolean {
        val words = if (answer) ANSWER_WORDS else DECLINE_WORDS
        val call = synchronized(items) { items.firstOrNull { it.isCall } } ?: return false
        val sbn = synchronized(items) { sbnByKey[call.key] } ?: return false
        val action = sbn.notification.actions?.firstOrNull { a ->
            val t = a.title?.toString()?.lowercase() ?: ""
            words.any { t.contains(it) }
        } ?: return false
        return try { action.actionIntent.send(); true } catch (e: Exception) { false }
    }

    private fun hasAction(n: Notification, words: List<String>): Boolean =
        n.actions?.any { a -> val t = a.title?.toString()?.lowercase() ?: ""; words.any { t.contains(it) } } == true

    private fun replyAction(n: Notification): Notification.Action? =
        n.actions?.firstOrNull { a -> a.remoteInputs?.any { it.allowFreeFormInput } == true }

    private fun appLabel(pkg: String): String = try {
        packageManager.getApplicationLabel(packageManager.getApplicationInfo(pkg, 0)).toString()
    } catch (e: Exception) {
        pkg
    }
}
