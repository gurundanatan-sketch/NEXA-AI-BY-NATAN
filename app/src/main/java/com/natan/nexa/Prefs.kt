package com.natan.nexa

import android.content.Context
import org.json.JSONObject

/**
 * Semua pengaturan NEXA disimpan di HP (SharedPreferences).
 * UI (assets/web) membaca & menulis lewat toJson()/applyJson().
 * Semua kunci API (Gemini, OpenAI, Fish Audio, TikTok) disimpan di sini — aplikasi berjalan mandiri tanpa Termux.
 */
class Prefs(context: Context) {
    private val sp = context.applicationContext.getSharedPreferences("nexa", Context.MODE_PRIVATE)

    companion object {
        /** key → nilai default. Tipe default menentukan tipe penyimpanan. */
        /** Kolom kunci API: di layar ditampilkan kosong bila memakai kunci bawaan. */
        val KEY_FIELDS = setOf("gemini_key", "openai_key", "fish_key", "tt_api_key")
        val DEFAULTS: Map<String, Any> = linkedMapOf(
            // --- Asisten ---
            "assistant_name" to "Nexa",
            "wake_words" to "nexa, neksa, nexsa, next a, nek sa, nexa ai",
            "wake_enabled" to false,
            "wake_mute_beep" to true,
            "live_idle_sec" to 45,
            // --- Gemini ---
            "gemini_key" to "",
            "agent_model" to "gemini-3.8-flash",
            "live_model" to "gemini-3.8-live",
            "live_voice" to "Kore",
            // Suara Gemini otomatis: satu saklar, satu pilihan suara untuk semua (perintah, Live, video).
            "gemini_voice_auto" to true,
            "gemini_voice" to "Kore",
            "gemini_style" to "hangat, ramah, natural",
            "live_barge_in" to true,
            "image_model" to "gemini-3.1-flash-image",
            "enhance_prompt" to true,
            // --- Kunci API lain (semua tersimpan di HP, tanpa server) ---
            "openai_key" to "",
            "fish_key" to "",
            "fish_model" to "s2.1-pro",           // s2.1-pro | s2.1-pro-free | s2-pro
            "openai_tts_model" to "gpt-4o-mini-tts",
            "openai_stt_model" to "whisper-1",
            // --- Suara mode perintah ---
            "voice_engine" to "gemini",          // gemini | openai | fish | android | sovits
            "tts_model" to "gemini-3.8-flash-tts",
            "tts_voice" to "Kore",
            "openai_voice" to "coral",
            "fish_voice" to "",                  // reference_id suara Fish (kloning)
            "video_voice" to "gemini:Kore",      // gemini:Kore | openai:coral | fish:<id>
            "voices" to "[]",                    // suara kloning tersimpan
            // --- GPT-SoVITS (opsional, PC) ---
            "sovits_url" to "",
            "sovits_ref" to "",
            "sovits_prompt" to "",
            "sovits_lang" to "en",
            // --- TikTok Live ---
            "tt_provider" to "euler",            // euler | tiktool | termux
            "tt_api_key" to "",
            "tt_bridge" to "ws://127.0.0.1:8090",
            "tt_username" to "",
            "tt_engine" to "android",            // android | gemini | openai | fish
            "tt_rate" to 1.05f,
            "tt_on_follow" to true,
            "tt_on_like" to true,
            "tt_on_gift" to true,
            "tt_on_share" to true,
            "tt_on_guest" to true,
            "tt_on_join" to false,
            "tt_on_chat" to false,
            "tt_ai_answer" to false,
            "tt_min_coins" to 1,
            "tt_like_min" to 15,
            "tt_cooldown_sec" to 30,
            "tt_queue_max" to 25,
            "tt_tpl_follow" to "Terima kasih {nama} sudah follow! Selamat datang di keluarga kita!\nMakasih banyak {nama} udah follow, jangan lupa nyalain notifikasinya ya!",
            "tt_tpl_like" to "Wah {nama} tap tap layar {like} kali, makasih banyak ya!\nTerima kasih {nama} untuk tap-tapnya!",
            "tt_tpl_gift" to "Terima kasih {nama} untuk {jumlah} {hadiah}, senilai {total_koin} koin! Kamu luar biasa!",
            "tt_tpl_share" to "Makasih {nama} sudah membagikan live ini!",
            "tt_tpl_guest" to "{nama} ingin naik ke live. Host, ada permintaan bergabung dari {nama}.",
            "tt_tpl_join" to "Halo {nama}, selamat datang di live!",
            "tt_tpl_chat" to "{nama} bilang: {komentar}",
            // --- Catatan ---
            "notes" to "[]",
        )
    }

    // ---------------------------------------------------------------- generic
    /** Nilai tersimpan apa adanya (tanpa kunci bawaan). */
    fun raw(k: String): String = sp.getString(k, DEFAULTS[k] as? String ?: "") ?: ""
    /** Kosong → pakai kunci bawaan dari build (GitHub Secrets), jadi tidak perlu diisi manual. */
    fun str(k: String): String = raw(k).ifBlank { builtIn(k) }
    fun builtIn(k: String): String = when (k) {
        "gemini_key" -> BuildConfig.GEMINI_KEY
        "openai_key" -> BuildConfig.OPENAI_KEY
        "fish_key" -> BuildConfig.FISH_KEY
        "tt_api_key" -> BuildConfig.TIKTOK_KEY
        else -> ""
    }
    fun bool(k: String): Boolean = sp.getBoolean(k, DEFAULTS[k] as? Boolean ?: false)
    fun int(k: String): Int = sp.getInt(k, DEFAULTS[k] as? Int ?: 0)
    fun float(k: String): Float = sp.getFloat(k, DEFAULTS[k] as? Float ?: 0f)
    fun put(k: String, v: Any) {
        val e = sp.edit()
        when (DEFAULTS[k]) {
            is Boolean -> e.putBoolean(k, v.toString().toBoolean() || v == true)
            is Int -> e.putInt(k, (v as? Number)?.toInt() ?: v.toString().toDoubleOrNull()?.toInt() ?: 0)
            is Float -> e.putFloat(k, (v as? Number)?.toFloat() ?: v.toString().toFloatOrNull() ?: 0f)
            else -> e.putString(k, v.toString().trim())
        }
        e.apply()
    }

    fun toJson(): JSONObject = JSONObject().apply {
        DEFAULTS.forEach { (k, d) ->
            put(k, when (d) {
                is Boolean -> bool(k); is Int -> int(k); is Float -> float(k).toDouble()
                else -> if (k in KEY_FIELDS) raw(k) else str(k)
            })
        }
        // true = kunci sudah tertanam otomatis dari build (tidak perlu diisi)
        KEY_FIELDS.forEach { k -> put("builtin_$k", builtIn(k).isNotEmpty()) }
    }

    fun applyJson(json: JSONObject) {
        json.keys().forEach { k -> if (DEFAULTS.containsKey(k)) put(k, json.get(k)) }
    }

    // ---------------------------------------------------------------- named (dipakai kode lama)
    var geminiApiKey: String get() = str("gemini_key"); set(v) = put("gemini_key", v)
    var agentModel: String get() = str("agent_model"); set(v) = put("agent_model", v)
    var liveModel: String get() = str("live_model"); set(v) = put("live_model", v)
    /** true = semua suara memakai Gemini otomatis (tanpa isi kode/model manual). */
    val geminiAuto: Boolean get() = bool("gemini_voice_auto") && geminiApiKey.isNotEmpty()
    val autoVoice: String get() = str("gemini_voice").ifBlank { "Kore" }
    var liveVoice: String get() = if (geminiAuto) autoVoice else str("live_voice"); set(v) = put("live_voice", v)
    var liveBargeIn: Boolean get() = bool("live_barge_in"); set(v) = put("live_barge_in", v)
    var imageModel: String get() = str("image_model"); set(v) = put("image_model", v)
    var voiceEngine: String get() = if (geminiAuto) "gemini" else str("voice_engine"); set(v) = put("voice_engine", v)
    var geminiTtsModel: String
        get() = str("tts_model").ifBlank { DEFAULTS["tts_model"] as String }
        set(v) = put("tts_model", v)
    var geminiVoice: String get() = if (geminiAuto) autoVoice else str("tts_voice"); set(v) = put("tts_voice", v)
    val videoVoice: String get() = if (geminiAuto) "gemini:$autoVoice" else str("video_voice")
    var sovitsUrl: String get() = str("sovits_url").trimEnd('/'); set(v) = put("sovits_url", v)
    var sovitsRefPath: String get() = str("sovits_ref"); set(v) = put("sovits_ref", v)
    var sovitsPromptText: String get() = str("sovits_prompt"); set(v) = put("sovits_prompt", v)
    var sovitsLang: String get() = str("sovits_lang"); set(v) = put("sovits_lang", v)
    var backendUrl: String get() = str("backend_url").trimEnd('/'); set(v) = put("backend_url", v)
    var backendToken: String get() = str("backend_token"); set(v) = put("backend_token", v)
    val openaiKey: String get() = str("openai_key")
    val fishKey: String get() = str("fish_key")
    val assistantName: String get() = str("assistant_name").ifBlank { "Nexa" }
}
