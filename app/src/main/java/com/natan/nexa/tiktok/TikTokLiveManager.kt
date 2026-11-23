package com.natan.nexa.tiktok

import android.content.Context
import com.natan.nexa.Prefs
import com.natan.nexa.voice.Http
import com.natan.nexa.voice.VoiceOutput
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import org.json.JSONArray
import org.json.JSONObject
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.TimeUnit

/**
 * Asisten TikTok LIVE:
 *  - menerima event dari server/tiktok/server.js (WebSocket lokal)
 *  - mengisi template kalimat ({nama}, {hadiah}, {jumlah}, {koin}, {total_koin}, {like}, {komentar}, {username})
 *  - membacakan lewat antrean suara (anti-spam: cooldown per orang, batas antrean, prioritas gift)
 *  - opsional: menjawab pertanyaan di komentar dengan Gemini
 */
object TikTokLiveManager {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
    private val client = OkHttpClient.Builder().readTimeout(0, TimeUnit.MILLISECONDS).build()
    private var ws: WebSocket? = null
    private var appContext: Context? = null
    private var voice: VoiceOutput? = null

    @Volatile var connected = false
        private set
    @Volatile var paused = false
    private var lastStats = JSONObject()

    /** Event untuk UI (feed), sudah berisi kalimat yang dibacakan. */
    var onEvent: ((JSONObject) -> Unit)? = null

    private data class Speech(val text: String, val priority: Int)
    private val queue = Channel<Speech>(Channel.UNLIMITED)
    private var queued = 0
    private val cooldown = ConcurrentHashMap<String, Long>()
    private var speakerStarted = false

    // ------------------------------------------------------------------ koneksi
    fun connect(context: Context, username: String): String {
        val app = context.applicationContext
        appContext = app
        if (voice == null) voice = VoiceOutput(app)
        val p = Prefs(app)
        p.put("tt_username", username)
        startSpeaker()

        val base = p.str("tt_bridge").trimEnd('/')
        val httpBase = base.replace("ws://", "http://").replace("wss://", "https://")
        scope.launch {
            try {
                // 1) buka WebSocket event
                openSocket("$base/events?token=${p.backendToken}")
                // 2) minta bridge menyambung ke live
                val r = withContext(Dispatchers.IO) {
                    JSONObject(String(Http.post("$httpBase/connect", JSONObject().put("username", username),
                        mapOf("X-Nexa-Token" to p.backendToken), 60_000)))
                }
                emitInfo("Tersambung ke live @${r.optString("username")}")
            } catch (e: Exception) {
                emitInfo("Gagal menyambung: ${e.message}. Pastikan server.js jalan di Termux dan @$username sedang live.")
            }
        }
        return "OK: menyambung ke live @$username…"
    }

    fun disconnect(context: Context): String {
        val p = Prefs(context)
        val httpBase = p.str("tt_bridge").trimEnd('/').replace("ws://", "http://").replace("wss://", "https://")
        scope.launch(Dispatchers.IO) {
            try { Http.post("$httpBase/disconnect", JSONObject(), mapOf("X-Nexa-Token" to p.backendToken)) } catch (_: Exception) {}
        }
        ws?.close(1000, null); ws = null; connected = false
        emitInfo("Live TikTok diputus")
        return "OK: live TikTok diputus"
    }

    /** Kirim event contoh untuk mencoba template tanpa sedang live. */
    fun test(context: Context, type: String) {
        appContext = context.applicationContext
        if (voice == null) voice = VoiceOutput(context.applicationContext)
        startSpeaker()
        val user = JSONObject().put("uniqueId", "natan_test").put("nickname", "Natan")
        val ev = JSONObject().put("type", type).put("user", user)
        when (type) {
            "gift" -> ev.put("gift", JSONObject().put("name", "Mawar").put("count", 5).put("diamonds", 1).put("totalCoins", 5))
            "like" -> ev.put("likeCount", 42)
            "chat" -> ev.put("comment", "Halo kak, ini live jam berapa sampai?")
        }
        handle(ev)
    }

    fun stats(): String {
        val s = lastStats
        if (s.length() == 0) return if (connected) "Statistik belum tersedia." else "Belum tersambung ke live TikTok."
        val top = s.optJSONArray("topGifters") ?: JSONArray()
        val topText = (0 until minOf(3, top.length())).joinToString(", ") {
            val t = top.getJSONObject(it); "${t.optString("nickname")} (${t.optInt("coins")} koin)"
        }
        return "Live @${s.optString("username")}: ${s.optInt("viewers")} penonton, ${s.optInt("follows")} follow baru, " +
            "${s.optInt("likes")} like, ${s.optInt("shares")} share, ${s.optInt("coins")} koin dari ${s.optInt("gifts")} hadiah." +
            if (topText.isNotEmpty()) " Pemberi hadiah teratas: $topText." else ""
    }

    private fun openSocket(url: String) {
        ws?.close(1000, null)
        ws = client.newWebSocket(Request.Builder().url(url).build(), object : WebSocketListener() {
            override fun onOpen(webSocket: WebSocket, response: Response) { connected = true }
            override fun onMessage(webSocket: WebSocket, text: String) {
                val ev = try { JSONObject(text) } catch (e: Exception) { return }
                scope.launch { handle(ev) }
            }
            override fun onClosed(webSocket: WebSocket, code: Int, reason: String) { connected = false }
            override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) {
                connected = false
                emitInfo("Koneksi ke server.js terputus: ${t.message}")
            }
        })
    }

    // ------------------------------------------------------------------ event → kalimat
    private fun handle(ev: JSONObject) {
        val ctx = appContext ?: return
        val p = Prefs(ctx)
        val type = ev.optString("type")
        val user = ev.optJSONObject("user") ?: JSONObject()
        val nama = user.optString("nickname").ifBlank { user.optString("uniqueId", "seseorang") }

        if (type == "stats") { lastStats = ev.optJSONObject("stats") ?: JSONObject(); onEvent?.invoke(ev); return }
        if (type in setOf("connected", "disconnected", "stream_end", "error", "hello")) {
            if (type == "stream_end") emitInfo("Live sudah berakhir")
            onEvent?.invoke(ev); return
        }

        val vars = mutableMapOf(
            "nama" to nama,
            "username" to user.optString("uniqueId"),
        )
        var priority = 1
        val enabledKey: String
        val tplKey: String
        when (type) {
            "follow" -> { enabledKey = "tt_on_follow"; tplKey = "tt_tpl_follow"; priority = 2 }
            "share" -> { enabledKey = "tt_on_share"; tplKey = "tt_tpl_share" }
            "join" -> { enabledKey = "tt_on_join"; tplKey = "tt_tpl_join"; priority = 0 }
            "guest_request" -> { enabledKey = "tt_on_guest"; tplKey = "tt_tpl_guest"; priority = 3 }
            "like" -> {
                enabledKey = "tt_on_like"; tplKey = "tt_tpl_like"; priority = 0
                val n = ev.optInt("likeCount")
                vars["like"] = n.toString()
                if (n < p.int("tt_like_min")) { onEvent?.invoke(ev); return }
            }
            "gift" -> {
                enabledKey = "tt_on_gift"; tplKey = "tt_tpl_gift"; priority = 3
                val g = ev.optJSONObject("gift") ?: JSONObject()
                vars["hadiah"] = g.optString("name", "hadiah")
                vars["jumlah"] = g.optInt("count", 1).toString()
                vars["koin"] = g.optInt("diamonds").toString()
                vars["total_koin"] = g.optInt("totalCoins").toString()
                if (g.optInt("totalCoins") < p.int("tt_min_coins")) { onEvent?.invoke(ev); return }
            }
            "chat" -> {
                enabledKey = "tt_on_chat"; tplKey = "tt_tpl_chat"; priority = 0
                val comment = ev.optString("comment")
                vars["komentar"] = comment
                if (p.bool("tt_ai_answer") && looksLikeQuestion(comment, p.assistantName)) answerChat(p, nama, comment)
            }
            else -> { onEvent?.invoke(ev); return }
        }

        val text = fill(randomTemplate(p.str(tplKey)), vars)
        ev.put("speech", text)
        onEvent?.invoke(ev)
        if (!p.bool(enabledKey) || paused || text.isBlank()) return

        // Cooldown per orang per jenis (gift & permintaan tamu tidak dibatasi)
        if (type !in setOf("gift", "guest_request")) {
            val key = "$type:${user.optString("uniqueId", nama)}"
            val now = System.currentTimeMillis()
            val last = cooldown[key] ?: 0L
            if (now - last < p.int("tt_cooldown_sec") * 1000L) return
            cooldown[key] = now
        }
        enqueue(Speech(text, priority), p.int("tt_queue_max"))
    }

    private fun looksLikeQuestion(c: String, name: String) =
        c.trim().endsWith("?") || c.contains(name, ignoreCase = true) ||
            Regex("\\b(apa|kapan|berapa|gimana|bagaimana|kenapa|siapa|dimana|mana)\\b", RegexOption.IGNORE_CASE).containsMatchIn(c)

    private fun answerChat(p: Prefs, nama: String, comment: String) {
        if (p.geminiApiKey.isBlank()) return
        scope.launch {
            try {
                val prompt = "Kamu adalah ${p.assistantName}, asisten host live TikTok @${p.str("tt_username")}. " +
                    "Jawab komentar penonton berikut dengan ramah, maksimal 2 kalimat pendek, bahasa Indonesia santai. " +
                    "Jika tidak tahu jawabannya, bilang host akan menjawab sebentar lagi.\nPenonton $nama: $comment"
                val body = JSONObject().put("contents", JSONArray().put(JSONObject().put("role", "user")
                    .put("parts", JSONArray().put(JSONObject().put("text", prompt)))))
                val resp = withContext(Dispatchers.IO) {
                    JSONObject(String(Http.post(
                        "https://generativelanguage.googleapis.com/v1beta/models/${p.agentModel}:generateContent",
                        body, mapOf("x-goog-api-key" to p.geminiApiKey), 30_000)))
                }
                val parts = resp.optJSONArray("candidates")?.optJSONObject(0)?.optJSONObject("content")?.optJSONArray("parts")
                val answer = (0 until (parts?.length() ?: 0)).joinToString(" ") { parts!!.getJSONObject(it).optString("text") }.trim()
                if (answer.isNotBlank()) {
                    onEvent?.invoke(JSONObject().put("type", "ai_answer").put("speech", "$nama, $answer"))
                    enqueue(Speech("$nama, $answer", 2), p.int("tt_queue_max"))
                }
            } catch (_: Exception) {}
        }
    }

    private fun randomTemplate(t: String): String =
        t.lines().map { it.trim() }.filter { it.isNotEmpty() }.randomOrNull() ?: ""

    private fun fill(t: String, vars: Map<String, String>): String {
        var s = t
        vars.forEach { (k, v) -> s = s.replace("{$k}", v) }
        return s.replace(Regex("\\{[a-z_]+\\}"), "")
    }

    // ------------------------------------------------------------------ antrean suara
    private fun enqueue(s: Speech, max: Int) {
        // Antrean penuh → buang yang prioritas rendah (like/join/chat), gift & tamu tetap masuk
        if (queued >= max && s.priority < 2) return
        queued++
        queue.trySend(s)
    }

    private fun startSpeaker() {
        if (speakerStarted) return
        speakerStarted = true
        scope.launch {
            for (s in queue) {
                queued = (queued - 1).coerceAtLeast(0)
                val ctx = appContext ?: continue
                val p = Prefs(ctx)
                val v = voice ?: continue
                try {
                    v.speak(s.text, p.str("tt_engine"), p.float("tt_rate"))
                } catch (_: Exception) {}
                delay(150)
            }
        }
    }

    private fun emitInfo(msg: String) {
        onEvent?.invoke(JSONObject().put("type", "info").put("message", msg))
    }
}
