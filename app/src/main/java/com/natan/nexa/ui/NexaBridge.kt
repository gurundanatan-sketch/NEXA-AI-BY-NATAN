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
import com.natan.nexa.voice.Http
import com.natan.nexa.voice.VoiceOutput
import com.natan.nexa.wake.WakeWordService
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
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
            "app" -> Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.parse("package:${ctx.packageName}"))
            else -> Intent(Settings.ACTION_SETTINGS)
        }
        try { act.startActivity(i) } catch (e: Exception) {
            act.startActivity(Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.parse("package:${ctx.packageName}")))
        }
    }

    @JavascriptInterface fun requestPermissions() = act.runOnUiThread {
        val perms = mutableListOf(Manifest.permission.RECORD_AUDIO, Manifest.permission.ANSWER_PHONE_CALLS)
        if (Build.VERSION.SDK_INT >= 33) perms += Manifest.permission.POST_NOTIFICATIONS
        act.permissionLauncher.launch(perms.toTypedArray())
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
    @JavascriptInterface fun tiktokConnect(username: String) = TikTokLiveManager.connect(act, username)
    @JavascriptInterface fun tiktokDisconnect() = TikTokLiveManager.disconnect(act)
    @JavascriptInterface fun tiktokTest(type: String) = act.runOnUiThread { TikTokLiveManager.test(act, type) }
    @JavascriptInterface fun tiktokPause(paused: Boolean) { TikTokLiveManager.paused = paused }
    @JavascriptInterface fun tiktokStats(): String = TikTokLiveManager.stats()

    // ------------------------------------------------------------------ studio
    private fun media() = MediaClient(ctx, prefs.geminiApiKey, prefs.agentModel, prefs.imageModel,
        prefs.backendUrl, prefs.backendToken)

    @JavascriptInterface fun generateImage(prompt: String, enhance: Boolean) {
        scope.launch {
            emit("busy", JSONObject().put("text", "Membuat gambar…"))
            val r = try { media().generateImage(prompt, enhance) } catch (e: Exception) { "GAGAL: ${e.message}" }
            emit("image_done", JSONObject().put("result", r))
        }
    }

    /** Simpan hasil server (video/gambar) ke Galeri HP. */
    @JavascriptInterface fun saveFromServer(file: String) {
        scope.launch(Dispatchers.IO) {
            val r = try { media().saveFromServer(file) } catch (e: Exception) { "GAGAL: ${e.message}" }
            emit("toast", JSONObject().put("text", r))
        }
    }

    /** Rekam suara (untuk sampel kloning, persetujuan, atau narasi suara asli). */
    @JavascriptInterface fun recordStart(): String {
        if (!has(Manifest.permission.RECORD_AUDIO)) { requestPermissions(); return "GAGAL: izin mikrofon" }
        if (LiveVoiceService.isActive) LiveVoiceService.stop(act)
        if (WakeWordService.isRunning) WakeWordService.pause(act)
        recordStop("")
        val f = File(ctx.cacheDir, "rekaman_${System.currentTimeMillis()}.m4a")
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

    /** Hentikan rekaman lalu unggah ke server → event "uploaded" {file_id, purpose}. */
    @JavascriptInterface fun recordStop(purpose: String): String {
        val r = recorder ?: return "GAGAL: tidak sedang merekam"
        try { r.stop() } catch (_: Exception) {}
        r.release(); recorder = null
        val f = recordFile ?: return "GAGAL"
        if (purpose.isNotBlank()) upload(f, purpose)
        return "OK"
    }

    private fun upload(f: File, purpose: String) {
        scope.launch(Dispatchers.IO) {
            try {
                val res = JSONObject(String(Http.upload("${prefs.backendUrl}/upload", f, "file", emptyMap(),
                    mapOf("X-Nexa-Token" to prefs.backendToken))))
                emit("uploaded", JSONObject().put("file_id", res.getString("file_id")).put("purpose", purpose)
                    .put("path", f.absolutePath).put("name", f.name))
            } catch (e: Exception) {
                emit("toast", JSONObject().put("text", "Upload gagal: ${e.message}"))
            }
        }
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
