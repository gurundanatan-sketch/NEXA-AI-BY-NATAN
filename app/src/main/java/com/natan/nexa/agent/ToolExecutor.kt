package com.natan.nexa.agent

import android.content.Context
import com.natan.nexa.Prefs
import com.natan.nexa.accessibility.NexaAccessibilityService
import com.natan.nexa.live.LiveVoiceService
import com.natan.nexa.media.MediaClient
import com.natan.nexa.notify.NexaNotificationListener
import com.natan.nexa.phone.CallController
import com.natan.nexa.studio.VideoJobs
import com.natan.nexa.tiktok.TikTokLiveManager
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Menjalankan fungsi yang dipanggil Gemini. Dipakai bersama oleh
 * GeminiAgent (mode perintah) dan LiveSession (mode ngobrol real-time).
 */
class ToolExecutor(context: Context, private val log: (String) -> Unit) {

    companion object {
        private val background = CoroutineScope(SupervisorJob() + Dispatchers.IO)

        /** Dipanggil saat tugas latar belakang (mis. video) selesai, untuk diumumkan. */
        var onBackgroundResult: ((String) -> Unit)? = null
    }

    private val app = context.applicationContext
    private val prefs = Prefs(app)
    private val system = SystemActions(app)
    private val calls = CallController(app)
    private val media = MediaClient(
        app, prefs.geminiApiKey, prefs.agentModel, prefs.imageModel,
        prefs.backendUrl, prefs.backendToken,
    )

    /** Fungsi yang mengubah layar → hasilnya disertai isi layar terbaru. */
    private val screenChanging = setOf(
        "open_app", "click", "type_text", "scroll", "press_key", "open_url",
        "dial", "send_whatsapp", "open_settings", "wait", "read_screen",
    )

    val assistantName: String get() = prefs.assistantName

    val installedApps: String get() = system.listApps().joinToString(", ") { it.first }.take(4000)

    fun screen(): String =
        NexaAccessibilityService.instance?.dumpScreen()
            ?: "LAYAR: layanan aksesibilitas NEXA belum aktif"

    /** Menjalankan satu fungsi; mengembalikan hasil (+ layar terbaru bila relevan). */
    suspend fun execute(name: String, a: JSONObject): String {
        val result = withContext(Dispatchers.Main) {
            try { run(name, a) } catch (e: Exception) { "GAGAL: ${e.message}" }
        }
        log("  → ${result.take(200)}")
        if (name !in screenChanging) return result
        delay(1200)
        return "$result\n\nLAYAR SAAT INI:\n${screen()}"
    }

    private suspend fun run(name: String, a: JSONObject): String {
        val svc = NexaAccessibilityService.instance
        fun needSvc(): NexaAccessibilityService =
            svc ?: throw IllegalStateException("layanan aksesibilitas belum aktif")
        val notif = NexaNotificationListener.instance

        return when (name) {
            // --- layar ---
            "click" -> needSvc().click(a.getInt("index"))
            "type_text" -> {
                val s = needSvc()
                val r = s.typeText(a.getInt("index"), a.getString("text"))
                if (a.optBoolean("press_enter", false) && r.startsWith("OK"))
                    r + "; " + s.pressEnter(a.getInt("index")) else r
            }
            "scroll" -> needSvc().scroll(a.optString("direction", "down"))
            "press_key" -> needSvc().pressKey(a.getString("key"))
            "read_screen" -> "OK"
            "wait" -> { delay(a.optLong("seconds", 2).coerceIn(1L, 10L) * 1000); "OK: menunggu" }

            // --- sistem ---
            "open_app" -> system.openApp(a.getString("name"))
            "open_url" -> system.openUrl(a.getString("url"))
            "dial" -> system.dial(a.getString("number"))
            "send_whatsapp" -> system.whatsapp(a.getString("phone"), a.getString("message"))
            "flashlight" -> system.flashlight(a.getBoolean("on"))
            "set_volume" -> system.setVolume(a.getInt("percent"))
            "open_settings" -> system.openSettings(a.optString("panel", "main"))
            "set_alarm" -> system.setAlarm(a.getInt("hour"), a.getInt("minute"), a.optString("label", "NEXA"))
            "set_timer" -> system.setTimer(a.getInt("seconds"), a.optString("label", "NEXA"))
            "device_info" -> system.deviceInfo()

            // --- notifikasi & chat ---
            "read_notifications" -> notif?.recent(a.optString("app", ""), a.optInt("limit", 10))
                ?: "GAGAL: akses notifikasi NEXA belum diaktifkan"
            "reply_notification" -> notif?.reply(a.getInt("index"), a.getString("message"), a.optString("app", ""))
                ?: "GAGAL: akses notifikasi NEXA belum diaktifkan"

            // --- telepon ---
            "answer_call" -> calls.answer()
            "reject_call" -> calls.reject()

            // --- catatan ---
            "save_note" -> saveNote(a.getString("text"))
            "read_notes" -> readNotes()

            // --- TikTok Live ---
            "tiktok_connect" -> TikTokLiveManager.connect(app, a.getString("username"))
            "tiktok_disconnect" -> TikTokLiveManager.disconnect(app)
            "tiktok_stats" -> TikTokLiveManager.stats()
            "tiktok_reading" -> {
                TikTokLiveManager.paused = !a.getBoolean("on")
                if (a.getBoolean("on")) "OK: pembacaan event TikTok aktif" else "OK: pembacaan event TikTok dijeda"
            }

            // --- info & media ---
            "google_search" -> media.webSearch(a.getString("query"))
            "generate_image" -> { log("Membuat gambar…"); media.generateImage(a.getString("prompt"), true) }
            "make_video" -> {
                // Video dibuat di dalam aplikasi (latar belakang); pengguna diberi tahu saat selesai.
                val req = JSONObject()
                    .put("mode", a.optString("mode", "images"))
                    .put("voice", a.optString("voice").ifBlank { prefs.str("video_voice") })
                    .put("aspect", a.optString("aspect", "9:16"))
                    .put("duration_min", a.optDouble("duration_min", 1.0))
                if (a.optString("script").isNotBlank()) req.put("script", a.getString("script"))
                else req.put("topic", a.optString("topic"))
                VideoJobs.start(app, req)
                "OK: video sedang dibuat (bisa beberapa menit). Pengguna akan diberi tahu saat selesai."
            }

            // --- percakapan ---
            "end_conversation" -> {
                background.launch { delay(2500); LiveVoiceService.stop(app) }
                "OK: percakapan akan ditutup. Ucapkan salam penutup singkat."
            }

            else -> "GAGAL: fungsi '$name' tidak dikenal"
        }
    }

    private fun saveNote(text: String): String {
        val arr = try { JSONArray(prefs.str("notes")) } catch (e: Exception) { JSONArray() }
        val time = SimpleDateFormat("dd MMM HH:mm", Locale("id", "ID")).format(Date())
        arr.put(JSONObject().put("time", time).put("text", text))
        prefs.put("notes", arr.toString())
        return "OK: catatan disimpan"
    }

    private fun readNotes(): String {
        val arr = try { JSONArray(prefs.str("notes")) } catch (e: Exception) { JSONArray() }
        if (arr.length() == 0) return "Belum ada catatan."
        return (maxOf(0, arr.length() - 10) until arr.length()).joinToString("\n") {
            val o = arr.getJSONObject(it); "- ${o.optString("time")}: ${o.optString("text")}"
        }
    }
}
