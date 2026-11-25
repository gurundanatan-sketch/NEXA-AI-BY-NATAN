package com.natan.nexa.ui

import android.Manifest
import android.content.ComponentName
import android.content.Intent
import android.content.pm.PackageManager
import android.media.MediaRecorder
import android.net.Uri
import android.os.Build
import android.os.PowerManager
import android.provider.Settings
import android.speech.RecognizerIntent
import android.webkit.JavascriptInterface
import androidx.core.content.ContextCompat
import com.natan.nexa.MainActivity
import com.natan.nexa.Prefs
import com.natan.nexa.accessibility.NexaAccessibilityService
import com.natan.nexa.agent.AgentRunner
import com.natan.nexa.agent.SystemActions
import com.natan.nexa.live.LiveVoiceService
import com.natan.nexa.media.MediaClient
import com.natan.nexa.notify.NexaNotificationListener
import com.natan.nexa.tiktok.TikTokLiveManager
import com.natan.nexa.studio.StudioApi
import com.natan.nexa.studio.VideoJobs
import com.natan.nexa.voice.Http
import com.natan.nexa.voice.VoiceOutput
import com.natan.nexa.wake.WakeWordService
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.io.File

/**
 * Jembatan JavaScript ⇄ Kotlin. Di JS dipanggil sebagai window.Android.namaFungsi(...).
 * Fungsi yang lama (gambar, upload, dll.) mengirim hasilnya lewat event NEXA.onEvent.
 */
class NexaBridge(private val act: MainActivity) {

    private val ctx = act.applicationContext
    private val prefs = Prefs(ctx)
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
    private val system = SystemActions(ctx)
    private var recorder: MediaRecorder? = null
    private var recordFile: File? = null

    private fun emit(type: String, data: JSONObject = JSONObject()) =
        act.emit(JSONObject().put("type", type).put("data", data))

    private fun has(p: String) = ContextCompat.checkSelfPermission(ctx, p) == PackageManager.PERMISSION_GRANTED

    // ------------------------------------------------------------------ pengaturan & status
    @JavascriptInterface fun getSettings(): String = prefs.toJson().toString()

    @JavascriptInterface fun saveSettings(json: String) {
        prefs.applyJson(JSONObject(json))
        act.runOnUiThread { act.emitStatus() }
    }

    @JavascriptInterface fun getStatus(): String {
        val pm = ctx.getSystemService(PowerManager::class.java)
        return JSONObject()
            .put("accessibility", NexaAccessibilityService.isRunning)
            .put("notifications", NexaNotificationListener.isRunning)
            .put("mic", has(Manifest.permission.RECORD_AUDIO))
        .put("media", if (Build.VERSION.SDK_INT >= 33) has(Manifest.permission.READ_MEDIA_AUDIO) && has(Manifest.permission.READ_MEDIA_VIDEO)
            else has(Manifest.permission.READ_EXTERNAL_STORAGE))
            .put("phone", has(Manifest.permission.ANSWER_PHONE_CALLS))
            .put("postNotif", Build.VERSION.SDK_INT < 33 || has(Manifest.permission.POST_NOTIFICATIONS))
            .put("battery", pm.isIgnoringBatteryOptimizations(ctx.packageName))
            .put("live", LiveVoiceService.isActive)
            .put("wake", WakeWordService.isRunning)
            .put("tiktok", TikTokLiveManager.connected)
            .put("apiKey", prefs.geminiApiKey.isNotBlank())
            .put("name", prefs.assistantName)
            .put("version", ctx.packageManager.getPackageInfo(ctx.packageName, 0).versionName)
            .toString()
    }

    @JavascriptInterface fun openSetting(which: String) = act.runOnUiThread {
        val i = when (which) {
            "accessibility" -> Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS)
            "notifications" -> Intent(Settings.ACTION_NOTIFICATION_LISTENER_SETTINGS)
            "battery" -> Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS, Uri.parse("package:${ctx.packageName}"))
            "assistant" -> Intent(Settings.ACTION_VOICE_INPUT_SETTINGS)
            "app", "restricted" -> Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.parse("package:${ctx.packageName}"))
            "overlay" -> Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION, Uri.parse("package:${ctx.packageName}"))
            "language" -> Intent(Settings.ACTION_LOCALE_SETTINGS)
            "sound" -> Intent(Settings.ACTION_SOUND_SETTINGS)
            "bluetooth" -> Intent(Settings.ACTION_BLUETOOTH_SETTINGS)
            "tts" -> Intent("com.android.settings.TTS_SETTINGS")
            else -> Intent(Settings.ACTION_SETTINGS)
        }
        try { act.startActivity(i) } catch (e: Exception) {
            act.startActivity(Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.parse("package:${ctx.packageName}")))
        }
    }

    @JavascriptInterface fun requestPermissions() = act.runOnUiThread {
        val perms = mutableListOf(Manifest.permission.RECORD_AUDIO, Manifest.permission.ANSWER_PHONE_CALLS)
        if (Build.VERSION.SDK_INT >= 33) perms += Manifest.permission.POST_NOTIFICATIONS
        if (Build.VERSION.SDK_INT >= 31) perms += Manifest.permission.BLUETOOTH_CONNECT
        // Akses audio & video galeri (impor sampel kloning, narasi suara asli, musik latar)
        if (Build.VERSION.SDK_INT >= 33) { perms += Manifest.permission.READ_MEDIA_AUDIO; perms += Manifest.permission.READ_MEDIA_VIDEO }
        else perms += Manifest.permission.READ_EXTERNAL_STORAGE
        act.permissionLauncher.launch(perms.toTypedArray())
    }

    /** Status izin penting untuk ditampilkan di Pengaturan. */
    @JavascriptInterface fun permStatus(): String = JSONObject()
        .put("overlay", Build.VERSION.SDK_INT < 23 || Settings.canDrawOverlays(ctx))
        .put("mic", has(Manifest.permission.RECORD_AUDIO))
        .put("accessibility", com.natan.nexa.accessibility.NexaAccessibilityService.instance != null)
        .put("notifications", androidx.core.app.NotificationManagerCompat.getEnabledListenerPackages(ctx).contains(ctx.packageName))
        .put("battery", (ctx.getSystemService(android.content.Context.POWER_SERVICE) as android.os.PowerManager)
            .isIgnoringBatteryOptimizations(ctx.packageName))
        .put("restricted_hint", Build.VERSION.SDK_INT >= 33)
        .toString()

    @JavascriptInterface fun copyText(text: String) { act.runOnUiThread { SystemActions(ctx).copyText(text) } }

    /** ID perangkat untuk kode Premium (unik per HP + aplikasi). */
    @JavascriptInterface fun deviceId(): String = com.natan.nexa.Route.device

    /** Mulai Live dengan perintah/sapaan awal (dipakai setelah login: menyapa nama pengguna). */
    @JavascriptInterface fun liveStart(command: String) = act.runOnUiThread {
        if (!has(Manifest.permission.RECORD_AUDIO)) { requestPermissions(); return@runOnUiThread }
        if (!LiveVoiceService.isActive) LiveVoiceService.start(act, greet = false, command = command)
        else LiveVoiceService.session?.sendSystemNote(command)
    }

    /**
     * Panggilan ke server NEXA (akun, profil, premium, saran). Hasil dikirim sebagai event
     * "api" {id, ok, status, data} supaya UI tidak macet.
     */
    @JavascriptInterface fun api(id: String, method: String, path: String, body: String) {
        scope.launch {
            val out = JSONObject().put("id", id)
            try {
                require(com.natan.nexa.Route.active) { "Server NEXA belum diatur (jalankan tools/setup_server.sh di Termux)" }
                val (code, text) = withContext(Dispatchers.IO) {
                    val c = com.natan.nexa.Route.openServer(path).apply {
                        requestMethod = method
                        connectTimeout = 15_000; readTimeout = 30_000
                        if (method == "POST") {
                            doOutput = true
                            setRequestProperty("Content-Type", "application/json")
                            outputStream.use { it.write(body.toByteArray()) }
                        }
                    }
                    val rc = c.responseCode
                    rc to ((if (rc in 200..299) c.inputStream else c.errorStream)?.bufferedReader()?.use { it.readText() } ?: "{}")
                }
                val data = try { JSONObject(text) } catch (e: Exception) { JSONObject().put("error", text.take(200)) }
                out.put("ok", code in 200..299).put("status", code).put("data", data)
            } catch (e: Exception) {
                out.put("ok", false).put("status", 0).put("data", JSONObject().put("error", e.message ?: "Gagal terhubung"))
            }
            emit("api", out)
        }
    }

    // ------------------------------------------------------------------ asisten
    @JavascriptInterface fun toggleLive() = act.runOnUiThread {
        if (!LiveVoiceService.isActive && !has(Manifest.permission.RECORD_AUDIO)) { requestPermissions(); return@runOnUiThread }
        LiveVoiceService.toggle(act)
    }

    @JavascriptInterface fun setWake(on: Boolean) = act.runOnUiThread {
        prefs.put("wake_enabled", on)
        if (on) {
            if (!has(Manifest.permission.RECORD_AUDIO)) { requestPermissions(); return@runOnUiThread }
            WakeWordService.start(act)
        } else WakeWordService.pause(act)
        act.emitStatus()
    }

    @JavascriptInterface fun sendText(text: String) = act.runOnUiThread {
        val live = LiveVoiceService.session
        if (live != null && live.sendUserText(text)) AgentRunner.log("Kamu: $text")
        else AgentRunner.submit(act, text)
    }

    @JavascriptInterface fun stopAgent() = act.runOnUiThread { AgentRunner.stop() }

    @JavascriptInterface fun dictate() = act.runOnUiThread {
        val i = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH)
            .putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
            .putExtra(RecognizerIntent.EXTRA_LANGUAGE, "id-ID")
        try { act.speechLauncher.launch(i) } catch (e: Exception) { AgentRunner.log("Pengenal suara tidak tersedia") }
    }

    @JavascriptInterface fun testVoice(text: String, engine: String) {
        if (locked()) return
        scope.launch {
            val err = VoiceOutput(ctx).speak(text, engine.ifBlank { null }, 1f)
            emit("toast", JSONObject().put("text", err?.let { "Suara cadangan dipakai: $it" } ?: "Suara OK"))
        }
    }

    // ------------------------------------------------------------------ kontrol HP
    @JavascriptInterface fun quick(action: String, arg: String): String = try { quickInner(action, arg) } catch (e: Exception) {
        "GAGAL: ${e.message}"
    }

    private fun quickInner(action: String, arg: String): String = when (action) {
        "flash_on" -> system.flashlight(true)
        "flash_off" -> system.flashlight(false)
        "volume" -> system.setVolume(arg.toIntOrNull() ?: 50)
        "wifi", "bluetooth", "display", "internet", "volume_panel" -> system.openSettings(action.removeSuffix("_panel"))
        "open_app" -> system.openApp(arg)
        "timer" -> system.setTimer(arg.toIntOrNull() ?: 60, "NEXA")
        "alarm" -> arg.split(":").let { system.setAlarm(it[0].toInt(), it.getOrElse(1) { "0" }.toInt(), "NEXA") }
        "device" -> system.deviceInfo()
        "home" -> NexaAccessibilityService.instance?.pressKey("home") ?: "GAGAL: aksesibilitas belum aktif"
        "screen" -> NexaAccessibilityService.instance?.dumpScreen() ?: "GAGAL: aksesibilitas belum aktif"
        "notifications" -> NexaNotificationListener.instance?.recent("", 15) ?: "GAGAL: akses notifikasi belum aktif"
        else -> "GAGAL: aksi tidak dikenal"
    }

    @JavascriptInterface fun apps(): String =
        JSONArray(system.listApps().map { JSONObject().put("name", it.first).put("pkg", it.second) }).toString()

    @JavascriptInterface fun notes(): String = prefs.str("notes")
    @JavascriptInterface fun saveNotes(json: String) = prefs.put("notes", json)

    // ------------------------------------------------------------------ TikTok
    /** Fitur asisten AI terkunci tanpa Premium (mode mandiri). */
    private fun locked(): Boolean {
        if (com.natan.nexa.Premium.allowed(prefs)) return false
        emit("toast", JSONObject().put("text", com.natan.nexa.Premium.LOCKED))
        return true
    }

    /** Status kode Premium untuk HP ini (dicek offline). */
    @JavascriptInterface fun premiumCheck(code: String): String =
        (com.natan.nexa.Premium.info(code, com.natan.nexa.Route.device) ?: JSONObject().put("invalid", true)).toString()
    @JavascriptInterface fun premiumAllowed(): Boolean = com.natan.nexa.Premium.allowed(prefs)
    @JavascriptInterface fun isOwner(): Boolean = com.natan.nexa.Premium.isOwner(prefs)

    @JavascriptInterface fun tiktokConnect(username: String) { if (!locked()) TikTokLiveManager.connect(act, username) }
    @JavascriptInterface fun tiktokDisconnect() = TikTokLiveManager.disconnect(act)
    @JavascriptInterface fun tiktokTest(type: String) = act.runOnUiThread { TikTokLiveManager.test(act, type) }
    @JavascriptInterface fun tiktokPause(paused: Boolean) { TikTokLiveManager.paused = paused }
    @JavascriptInterface fun tiktokStats(): String = TikTokLiveManager.stats()

    // ------------------------------------------------------------------ studio (semua berjalan di HP)
    private fun media() = MediaClient(ctx, prefs.geminiApiKey, prefs.agentModel, prefs.imageModel,
        prefs.backendUrl, prefs.backendToken)

    @JavascriptInterface fun generateImage(prompt: String, enhance: Boolean) {
        if (locked()) return
        scope.launch {
            emit("busy", JSONObject().put("text", "Membuat gambar…"))
            val r = try { media().generateImage(prompt, enhance) } catch (e: Exception) { "GAGAL: ${e.message}" }
            emit("image_done", JSONObject().put("result", r))
        }
    }

    /** Pilih file audio dari HP (sampel suara, suara asli, musik latar) → event "uploaded" {purpose, path}. */
    @JavascriptInterface fun pickAudio(purpose: String) = act.runOnUiThread { act.pickAudio(purpose) }
    @JavascriptInterface fun pickVideoAudio(purpose: String) = act.runOnUiThread { act.pickVideoAudio(purpose) }

    @JavascriptInterface fun makeVideo(json: String): String = if (!com.natan.nexa.Premium.allowed(prefs)) "ERR:" + com.natan.nexa.Premium.LOCKED else try {
        "OK:" + VideoJobs.start(act, JSONObject(json))
    } catch (e: Exception) { "GAGAL: ${e.message}" }

    @JavascriptInterface fun jobs(): String = VideoJobs.list(ctx).toString()
    @JavascriptInterface fun cancelJob(id: String) = VideoJobs.cancel(id)

    /** Hasil (video & gambar) di penyimpanan aplikasi; ditampilkan lewat https://appassets…/media/<nama>. */
    @JavascriptInterface fun gallery(): String {
        val files = VideoJobs.outputDir(ctx).listFiles()?.filter { it.isFile }?.sortedByDescending { it.lastModified() }
            ?: emptyList()
        return JSONArray(files.take(100).map {
            JSONObject().put("file", it.name).put("size", it.length()).put("time", it.lastModified())
                .put("type", if (it.extension == "mp4") "video" else "image")
        }).toString()
    }

    @JavascriptInterface fun deleteMedia(name: String) {
        File(VideoJobs.outputDir(ctx), File(name).name).delete()
    }

    // ---- suara & kloning ----
    private val geminiVoices = listOf("Kore", "Puck", "Aoede", "Charon", "Fenrir", "Leda", "Orus", "Zephyr", "Callirrhoe", "Autonoe",
        "Enceladus", "Iapetus", "Umbriel", "Algieba", "Despina", "Erinome", "Algenib", "Rasalgethi", "Laomedeia", "Achernar",
        "Alnilam", "Schedar", "Gacrux", "Pulcherrima", "Achird", "Zubenelgenubi", "Vindemiatrix", "Sadachbia", "Sadaltager", "Sulafat")

    @JavascriptInterface fun voices(): String {
        val out = JSONArray()
        val saved = try { JSONArray(prefs.str("voices")) } catch (e: Exception) { JSONArray() }
        for (i in 0 until saved.length()) out.put(saved.getJSONObject(i))
        geminiVoices.forEach { out.put(JSONObject().put("id", "gemini:$it").put("name", "Gemini $it").put("cloned", false)) }
        return out.toString()
    }

    @JavascriptInterface fun deleteVoice(id: String) {
        val saved = try { JSONArray(prefs.str("voices")) } catch (e: Exception) { JSONArray() }
        val keep = JSONArray()
        for (i in 0 until saved.length()) if (saved.getJSONObject(i).optString("id") != id) keep.put(saved.getJSONObject(i))
        prefs.put("voices", keep.toString())
    }

    /** json: {name, engine: fish|gemini, sample: path, consent: path, agree: true} → event "cloned". */
    @JavascriptInterface fun cloneVoice(json: String) {
        if (locked()) return
        scope.launch {
            try {
                val o = JSONObject(json)
                require(o.optBoolean("agree")) { "Wajib menyatakan suara ini milik Anda / ada izin pemiliknya" }
                val sample = File(o.getString("sample"))
                require(sample.exists()) { "Sampel suara tidak ditemukan" }
                val name = o.optString("name").ifBlank { "Suara Saya" }
                val spec = withContext(Dispatchers.IO) {
                    when (o.optString("engine", "fish")) {
                        "gemini" -> "gemini:" + cloneGemini(sample, File(o.optString("consent")), name)
                        else -> "fish:" + StudioApi(prefs).cloneFish(sample, name)
                    }
                }
                val saved = try { JSONArray(prefs.str("voices")) } catch (e: Exception) { JSONArray() }
                val list = JSONArray().put(JSONObject().put("id", spec).put("name", name).put("cloned", true)
                    .put("created", System.currentTimeMillis()))
                for (i in 0 until saved.length()) list.put(saved.get(i))
                prefs.put("voices", list.toString())
                emit("cloned", JSONObject().put("id", spec).put("name", name))
            } catch (e: Exception) {
                emit("cloned", JSONObject().put("error", e.message ?: e.toString()))
            }
        }
    }

    /** Gemini voice replication lewat REST (eksperimental; butuh rekaman persetujuan). */
    private fun cloneGemini(sample: File, consent: File, name: String): String {
        require(consent.exists()) { "Gemini butuh rekaman persetujuan dari orang yang sama" }
        fun audio(f: File) = JSONObject().put("mime_type", if (f.extension == "wav") "audio/wav" else "audio/aac")
            .put("data", android.util.Base64.encodeToString(f.readBytes(), android.util.Base64.NO_WRAP))
        val body = JSONObject().put("store", true).put("voice", JSONObject()
            .put("model", prefs.geminiTtsModel).put("type", "replicated").put("display_name", name)
            .put("replicated", JSONObject().put("source_audio", audio(sample)).put("consent_audio", audio(consent))))
        val res = JSONObject(String(Http.post("https://generativelanguage.googleapis.com/v1beta/voices", body,
            mapOf("x-goog-api-key" to prefs.geminiApiKey), 300_000)))
        return res.optString("id").ifBlank { res.optString("name").substringAfterLast('/') }
            .ifBlank { throw RuntimeException("Gemini tidak mengembalikan ID suara") }
    }

    /** Putar contoh suara dari spesifikasi mis. fish:<id>, gemini:Kore. */
    @JavascriptInterface fun previewVoice(spec: String, text: String) {
        if (locked()) return
        scope.launch {
            val err = try { VoiceOutput(ctx).speakSpec(text, spec); null } catch (e: Exception) { e.message }
            emit("toast", JSONObject().put("text", err?.let { "Gagal: $it" } ?: "Contoh suara selesai"))
        }
    }

    /** Cek semua kunci API sekaligus; hasil dikirim sebagai event "keys". */
    @JavascriptInterface fun checkKeys() {
        scope.launch {
            if (com.natan.nexa.Route.active) {
                // Mode server: server yang mengecek semua kunci.
                val res = try {
                    JSONObject(String(withContext(Dispatchers.IO) {
                        Http.get("${com.natan.nexa.Route.server}/health?deep=1",
                            mapOf("x-nexa-app" to com.natan.nexa.Route.token), 30_000)
                    })).put("server", "ok")
                } catch (e: Exception) {
                    JSONObject().put("server", "gagal: ${e.message?.take(90)}")
                }
                emit("keys", res)
                return@launch
            }
            val out = JSONObject()
            suspend fun test(name: String, key: String, block: () -> Unit) {
                out.put(name, when {
                    key.isBlank() -> "kosong"
                    else -> try { withContext(Dispatchers.IO) { block() }; "ok" } catch (e: Exception) { "gagal: ${e.message?.take(90)}" }
                })
            }
            test("gemini", prefs.geminiApiKey) {
                Http.get("https://generativelanguage.googleapis.com/v1beta/models?pageSize=1",
                    mapOf("x-goog-api-key" to prefs.geminiApiKey), 20_000)
            }
            test("fish", prefs.fishKey) {
                Http.get("https://api.fish.audio/model?page_size=1", mapOf("Authorization" to "Bearer ${prefs.fishKey}"), 20_000)
            }
            test("tiktok", prefs.str("tt_api_key")) { }
            emit("keys", out)
        }
    }

    /** Rekam suara (sampel kloning, persetujuan, atau narasi suara asli). */
    @JavascriptInterface fun recordStart(): String {
        if (!has(Manifest.permission.RECORD_AUDIO)) { requestPermissions(); return "GAGAL: izin mikrofon" }
        if (LiveVoiceService.isActive) LiveVoiceService.stop(act)
        if (WakeWordService.isRunning) WakeWordService.pause(act)
        recordStop("")
        val dir = File(ctx.filesDir, "rekaman").apply { mkdirs() }
        val f = File(dir, "rekaman_${System.currentTimeMillis()}.m4a")
        val r = if (Build.VERSION.SDK_INT >= 31) MediaRecorder(ctx) else @Suppress("DEPRECATION") MediaRecorder()
        r.setAudioSource(MediaRecorder.AudioSource.MIC)
        r.setOutputFormat(MediaRecorder.OutputFormat.MPEG_4)
        r.setAudioEncoder(MediaRecorder.AudioEncoder.AAC)
        r.setAudioSamplingRate(44_100)
        r.setAudioEncodingBitRate(128_000)
        r.setOutputFile(f.absolutePath)
        r.prepare(); r.start()
        recorder = r; recordFile = f
        return "OK"
    }

    /** Hentikan rekaman → event "uploaded" {purpose, path}. */
    @JavascriptInterface fun recordStop(purpose: String): String {
        val r = recorder ?: return "GAGAL: tidak sedang merekam"
        try { r.stop() } catch (_: Exception) {}
        r.release(); recorder = null
        val f = recordFile ?: return "GAGAL"
        if (purpose.isNotBlank()) emit("uploaded", JSONObject().put("purpose", purpose)
            .put("path", f.absolutePath).put("name", f.name))
        return "OK"
    }

    @JavascriptInterface fun openUrl(url: String) = act.runOnUiThread {
        act.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url)))
    }

    @JavascriptInterface fun openAccessibilityComponent() = act.runOnUiThread {
        // Beberapa HP mendukung lompat langsung ke layanan NEXA
        val cn = ComponentName(ctx, NexaAccessibilityService::class.java).flattenToString()
        val i = Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS).putExtra(":settings:fragment_args_key", cn)
        act.startActivity(i)
    }
}
