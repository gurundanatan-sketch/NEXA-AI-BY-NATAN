package com.natan.nexa.studio

import android.util.Base64
import com.natan.nexa.Prefs
import com.natan.nexa.voice.Http
import org.json.JSONArray
import org.json.JSONObject
import java.io.File

/**
 * Semua panggilan AI untuk Studio — langsung dari HP (tanpa server Termux).
 * Semua fungsi di sini blocking → panggil dari Dispatchers.IO.
 */
class StudioApi(private val p: Prefs) {

    companion object {
        private const val GEMINI = "https://generativelanguage.googleapis.com/v1beta"
        const val MAX_SCENES = 60
    }

    private fun gemini(path: String, body: JSONObject, timeoutMs: Int = 300_000): JSONObject {
        require(p.geminiApiKey.isNotBlank()) { "Kunci Gemini belum diisi di Pengaturan" }
        return JSONObject(String(Http.post("$GEMINI/$path", body, mapOf("x-goog-api-key" to p.geminiApiKey), timeoutMs)))
    }

    /** Teks (opsional JSON schema). parts tambahan mis. audio inline. */
    fun text(prompt: String, schema: JSONObject? = null, extraParts: JSONArray? = null): String {
        val parts = JSONArray()
        if (extraParts != null) for (i in 0 until extraParts.length()) parts.put(extraParts.get(i))
        parts.put(JSONObject().put("text", prompt))
        val body = JSONObject().put("contents", JSONArray().put(JSONObject().put("role", "user").put("parts", parts)))
        if (schema != null) body.put("generationConfig", JSONObject()
            .put("responseMimeType", "application/json").put("responseSchema", schema))
        val resp = gemini("models/${p.agentModel}:generateContent", body)
        val out = resp.getJSONArray("candidates").getJSONObject(0).getJSONObject("content").getJSONArray("parts")
        return (0 until out.length()).joinToString("") { out.getJSONObject(it).optString("text") }
    }

    /** Ambil output terakhir bertipe kind (audio/image/video) dari Interactions API. */
    private fun lastOutput(resp: JSONObject, kind: String): ByteArray {
        resp.optJSONObject("output_$kind")?.optString("data")?.takeIf { it.isNotEmpty() }?.let {
            return Base64.decode(it, Base64.DEFAULT)
        }
        var found: String? = null
        val steps = resp.optJSONArray("steps") ?: JSONArray()
        for (i in 0 until steps.length()) {
            val content = steps.optJSONObject(i)?.optJSONArray("content") ?: continue
            for (j in 0 until content.length()) {
                val c = content.optJSONObject(j) ?: continue
                if (c.optString("type") == kind && c.optString("data").isNotEmpty()) found = c.optString("data")
            }
        }
        return Base64.decode(found ?: throw RuntimeException("Model tidak mengembalikan $kind"), Base64.DEFAULT)
    }

    // ------------------------------------------------------------------ naskah & adegan
    private val cameraEnum = JSONArray(listOf("zoom_in", "zoom_out", "pan_left", "pan_right", "static"))

    fun writeScript(topic: String, minutes: Double, characters: List<String> = emptyList()): String {
        val words = (maxOf(0.3, minutes) * 140).toInt()
        if (characters.isNotEmpty()) {
            val names = characters.joinToString(", ")
            return text("Tulis naskah video berbahasa Indonesia berbentuk DIALOG antar tokoh tentang: $topic\n" +
                "Tokoh: $names. Panjang sekitar $words kata (≈$minutes menit). Setiap baris WAJIB diawali nama tokoh " +
                "lalu titik dua, mis. \"${characters.first()}: Halo semuanya!\". Bahasa lisan yang hidup & natural: " +
                "pakai seruan, tanya balik, tawa (\"haha\"), kaget (\"hah?!\"), jeda (\"…\"), dan emosi yang terasa " +
                "lewat pilihan kata & tanda baca. Tanpa judul, tanpa keterangan adegan dalam kurung, tanpa emoji.").trim()
        }
        return text("Tulis naskah narasi video berbahasa Indonesia tentang: $topic\n" +
            "Panjang sekitar $words kata (≈$minutes menit dibacakan). Pembuka memancing rasa penasaran, isi padat, " +
            "penutup dengan ajakan. Gunakan bahasa lisan yang hidup & ekspresif (tanda baca, seruan, jeda) agar " +
            "dibacakan dengan natural. Tulis HANYA teks narasi, tanpa judul, tanpa tanda adegan, tanpa emoji.").trim()
    }

    data class Scene(val narration: String, val visual: String, val camera: String)

    fun planScenes(script: String): List<Scene> {
        val schema = JSONObject().put("type", "ARRAY").put("items", JSONObject().put("type", "OBJECT")
            .put("properties", JSONObject()
                .put("narration", JSONObject().put("type", "STRING"))
                .put("visual", JSONObject().put("type", "STRING"))
                .put("camera", JSONObject().put("type", "STRING").put("enum", cameraEnum)))
            .put("required", JSONArray(listOf("narration", "visual", "camera"))))
        val arr = JSONArray(text(
            "Pecah naskah ini menjadi adegan berurutan (maks $MAX_SCENES, tiap adegan 1–3 kalimat, ±5–10 detik). " +
                "narration = teks PERSIS dari naskah (jangan diringkas/diubah; bila ada label nama tokoh seperti " +
                "\"Budi: …\", label itu WAJIB dipertahankan dan tiap baris tokoh dipisah baris baru). visual = deskripsi sinematik bahasa Inggris " +
                "untuk AI gambar/video, tokoh & gaya konsisten antar adegan, tanpa teks di gambar. camera = gerakan kamera.\n\n" +
                "NASKAH:\n$script", schema))
        return (0 until minOf(arr.length(), MAX_SCENES)).map {
            val o = arr.getJSONObject(it)
            Scene(o.optString("narration").trim(), o.optString("visual"), o.optString("camera", "zoom_in"))
        }.filter { it.narration.isNotBlank() }
    }

    fun visualsFor(texts: List<String>): List<Pair<String, String>> {
        val schema = JSONObject().put("type", "ARRAY").put("items", JSONObject().put("type", "OBJECT")
            .put("properties", JSONObject()
                .put("visual", JSONObject().put("type", "STRING"))
                .put("camera", JSONObject().put("type", "STRING").put("enum", cameraEnum)))
            .put("required", JSONArray(listOf("visual", "camera"))))
        val numbered = texts.mapIndexed { i, t -> "${i + 1}. $t" }.joinToString("\n")
        val arr = JSONArray(text("Untuk tiap baris narasi berikut (${texts.size} baris), buat 1 deskripsi visual sinematik " +
            "bahasa Inggris (konsisten gaya & tokoh, tanpa teks) dan gerakan kamera. Jumlah output HARUS ${texts.size}." +
            "\n\n$numbered", schema))
        return texts.indices.map { i ->
            val o = arr.optJSONObject(i)
            (o?.optString("visual") ?: texts[i]) to (o?.optString("camera") ?: "zoom_in")
        }
    }

    fun enhancePrompt(prompt: String): String = try {
        text("Ubah permintaan gambar ini menjadi prompt fotografi bahasa Inggris yang detail (subjek, suasana, " +
            "pencahayaan, lensa, komposisi, photorealistic, sangat detail). Balas HANYA prompt-nya.\n\nPermintaan: $prompt")
            .trim().ifBlank { prompt }
    } catch (e: Exception) { prompt }

    // ------------------------------------------------------------------ visual
    fun image(prompt: String, aspect: String): ByteArray = lastOutput(gemini("interactions", JSONObject()
        .put("model", p.imageModel).put("input", "$prompt. Aspect ratio $aspect, no text, no watermark.")), "image")

    fun clip(prompt: String, aspect: String): ByteArray = lastOutput(gemini("interactions", JSONObject()
        .put("model", "gemini-omni-1.1-flash").put("input", prompt)
        .put("response_format", JSONObject().put("type", "video").put("aspect_ratio", aspect)), 900_000), "video")

    // ------------------------------------------------------------------ transkrip (suara asli)
    data class Word(val start: Double, val end: Double, val word: String)
    data class Segment(val start: Double, val end: Double, val text: String, val words: List<Word>)

    /** OpenAI whisper (timestamp kata) → Fish Audio ASR → Gemini, mana yang tersedia. */
    fun transcribe(audio: File): List<Segment> {
        if (p.openaiKey.isNotBlank()) try {
            val res = JSONObject(String(Http.upload("https://api.openai.com/v1/audio/transcriptions", audio, "file",
                listOf("model" to p.str("openai_stt_model"), "response_format" to "verbose_json", "language" to "id",
                    "timestamp_granularities[]" to "segment", "timestamp_granularities[]" to "word"),
                mapOf("Authorization" to "Bearer ${p.openaiKey}"))))
            val words = res.optJSONArray("words")?.let { w ->
                (0 until w.length()).map { val o = w.getJSONObject(it); Word(o.getDouble("start"), o.getDouble("end"), o.getString("word")) }
            } ?: emptyList()
            val segs = res.optJSONArray("segments")
            if (segs != null && segs.length() > 0) return (0 until segs.length()).map {
                val s = segs.getJSONObject(it)
                val a = s.getDouble("start"); val b = s.getDouble("end")
                Segment(a, b, s.getString("text").trim(), words.filter { w -> w.start >= a - 0.01 && w.start < b })
            }
            if (words.isNotEmpty()) return groupWords(words)
        } catch (_: Exception) { /* coba mesin berikutnya */ }

        if (p.fishKey.isNotBlank()) try {
            val res = JSONObject(String(Http.upload("https://api.fish.audio/v1/asr", audio, "audio",
                listOf("language" to "id", "ignore_timestamps" to "false"),
                mapOf("Authorization" to "Bearer ${p.fishKey}", "model" to "transcribe-1-pro"))))
            val segs = res.optJSONArray("segments") ?: JSONArray()
            val words = (0 until segs.length()).map {
                val o = segs.getJSONObject(it); Word(o.getDouble("start"), o.getDouble("end"), o.getString("text"))
            }
            if (words.isNotEmpty()) return groupWords(words)
        } catch (_: Exception) { }

        // Cadangan: Gemini memahami audio & memberi waktu per kalimat
        val schema = JSONObject().put("type", "ARRAY").put("items", JSONObject().put("type", "OBJECT")
            .put("properties", JSONObject()
                .put("start", JSONObject().put("type", "NUMBER")).put("end", JSONObject().put("type", "NUMBER"))
                .put("text", JSONObject().put("type", "STRING")))
            .put("required", JSONArray(listOf("start", "end", "text"))))
        val mime = when (audio.extension.lowercase()) { "mp3" -> "audio/mpeg"; "wav" -> "audio/wav"; else -> "audio/aac" }
        val part = JSONObject().put("inline_data", JSONObject().put("mime_type", mime)
            .put("data", Base64.encodeToString(audio.readBytes(), Base64.NO_WRAP)))
        val arr = JSONArray(text("Transkripsikan audio ini kata per kata (bahasa aslinya). Pecah per kalimat, " +
            "beri waktu mulai & selesai dalam detik.", schema, JSONArray().put(part)))
        return (0 until arr.length()).map {
            val o = arr.getJSONObject(it); Segment(o.getDouble("start"), o.getDouble("end"), o.getString("text"), emptyList())
        }
    }

    /** Kelompokkan kata menjadi kalimat (berakhir . ! ? atau maks 14 kata). */
    private fun groupWords(words: List<Word>): List<Segment> {
        val out = mutableListOf<Segment>(); var cur = mutableListOf<Word>()
        for (w in words) {
            cur.add(w)
            if (w.word.trim().endsWith(".") || w.word.trim().endsWith("?") || w.word.trim().endsWith("!") || cur.size >= 14) {
                out += Segment(cur.first().start, cur.last().end, cur.joinToString(" ") { it.word.trim() }, cur); cur = mutableListOf()
            }
        }
        if (cur.isNotEmpty()) out += Segment(cur.first().start, cur.last().end, cur.joinToString(" ") { it.word.trim() }, cur)
        return out
    }

    /** Timestamp kata untuk audio TTS (bila ada kunci OpenAI) → subtitle sinkron kata. */
    fun wordTimes(audio: File): List<Word> {
        if (p.openaiKey.isBlank()) return emptyList()
        return try {
            val res = JSONObject(String(Http.upload("https://api.openai.com/v1/audio/transcriptions", audio, "file",
                listOf("model" to p.str("openai_stt_model"), "response_format" to "verbose_json",
                    "timestamp_granularities[]" to "word"),
                mapOf("Authorization" to "Bearer ${p.openaiKey}"))))
            val w = res.optJSONArray("words") ?: return emptyList()
            (0 until w.length()).map { val o = w.getJSONObject(it); Word(o.getDouble("start"), o.getDouble("end"), o.getString("word")) }
        } catch (e: Exception) { emptyList() }
    }

    // ------------------------------------------------------------------ kloning suara Fish Audio
    /** Kloning suara: POST /model (type=tts, train_mode=fast). Mengembalikan reference_id. */
    fun cloneFish(sample: File, name: String): String {
        require(p.fishKey.isNotBlank()) { "Kunci Fish Audio belum diisi di Pengaturan" }
        val res = JSONObject(String(Http.upload("https://api.fish.audio/model", sample, "voices",
            listOf("type" to "tts", "title" to name, "train_mode" to "fast", "visibility" to "private",
                "enhance_audio_quality" to "true"),
            mapOf("Authorization" to "Bearer ${p.fishKey}"))))
        return res.optString("_id").ifBlank { res.optString("id") }.ifBlank {
            throw RuntimeException("Fish Audio tidak mengembalikan id suara: ${res.toString().take(200)}")
        }
    }
}
