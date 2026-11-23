package com.natan.nexa.media

import android.content.ContentValues
import android.content.Context
import android.os.Build
import android.os.Environment
import android.provider.MediaStore
import android.util.Base64
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.net.HttpURLConnection
import java.net.URL

/**
 * - generateImage(): langsung dari HP ke Gemini (Nano Banana 2, gemini-3.1-flash-image)
 * - makeVideo(): lewat server Nexa (folder server/) yang merakit naskah → video MP4
 * - webSearch(): Google Search grounding untuk info terkini
 * Hasil gambar/video disimpan ke Galeri (Pictures/Nexa, Movies/Nexa).
 */
class MediaClient(
    private val context: Context,
    private val apiKey: String,
    private val textModel: String,
    private val imageModel: String,
    private val backendUrl: String,
    private val backendToken: String,
) {
    companion object {
        private const val API = "https://generativelanguage.googleapis.com/v1beta"
    }

    // ------------------------------------------------------------------ SEARCH
    suspend fun webSearch(query: String): String = withContext(Dispatchers.IO) {
        val body = JSONObject()
            .put("contents", JSONArray().put(JSONObject().put("role", "user")
                .put("parts", JSONArray().put(JSONObject().put("text",
                    "Jawab singkat dan akurat dalam bahasa Indonesia berdasarkan hasil pencarian: $query")))))
            .put("tools", JSONArray().put(JSONObject().put("googleSearch", JSONObject())))
        val resp = JSONObject(String(http("$API/models/$textModel:generateContent", body,
            mapOf("x-goog-api-key" to apiKey))))
        val cand = resp.optJSONArray("candidates")?.optJSONObject(0) ?: return@withContext "GAGAL: tidak ada hasil"
        val parts = cand.optJSONObject("content")?.optJSONArray("parts")
        val answer = (0 until (parts?.length() ?: 0)).joinToString(" ") {
            parts!!.optJSONObject(it)?.optString("text", "") ?: ""
        }.trim()
        val chunks = cand.optJSONObject("groundingMetadata")?.optJSONArray("groundingChunks")
        val sources = (0 until (chunks?.length() ?: 0)).mapNotNull {
            chunks!!.optJSONObject(it)?.optJSONObject("web")?.optString("title")
        }.distinct().take(4)
        if (sources.isEmpty()) answer else "$answer\nSumber: ${sources.joinToString(", ")}"
    }

    // ------------------------------------------------------------------ IMAGE
    suspend fun generateImage(prompt: String, enhance: Boolean = true): String = withContext(Dispatchers.IO) {
        val finalPrompt = if (enhance) enhancePrompt(prompt) else prompt
        val body = JSONObject().put("model", imageModel).put("input", "$finalPrompt. No text, no watermark.")
        val resp = JSONObject(String(http("$API/interactions", body, mapOf("x-goog-api-key" to apiKey))))
        val (mime, b64) = lastOutput(resp, "image")
            ?: return@withContext "GAGAL: model tidak mengembalikan gambar"
        val ext = if (mime.contains("jpeg")) "jpg" else "png"
        val name = "nexa_${System.currentTimeMillis()}.$ext"
        saveToGallery(Base64.decode(b64, Base64.DEFAULT), name, mime, isVideo = false)
        "OK: gambar disimpan di Galeri (Pictures/NEXA/$name)"
    }

    /** Perkaya prompt singkat menjadi prompt fotografi detail. */
    private fun enhancePrompt(p: String): String = try {
        val body = JSONObject().put("contents", JSONArray().put(JSONObject().put("role", "user")
            .put("parts", JSONArray().put(JSONObject().put("text",
                "Ubah permintaan gambar ini menjadi prompt fotografi bahasa Inggris yang detail (subjek, suasana, " +
                    "pencahayaan, lensa, komposisi, photorealistic, sangat detail). Balas HANYA prompt-nya.\n\n$p")))))
        val resp = JSONObject(String(http("$API/models/$textModel:generateContent", body, mapOf("x-goog-api-key" to apiKey))))
        val parts = resp.getJSONArray("candidates").getJSONObject(0).getJSONObject("content").getJSONArray("parts")
        (0 until parts.length()).joinToString(" ") { parts.getJSONObject(it).optString("text") }.trim().ifBlank { p }
    } catch (e: Exception) { p }

    // ------------------------------------------------------------------ VIDEO
    /**
     * req (lihat server/nexa_server.py): {script | topic, duration_min, mode: images|clips,
     *  voice: "gemini:Kore" | "minimax:<id>" | "openai:coral" | "own", own_audio_id, bgm_id, aspect, subtitles}
     */
    suspend fun makeVideo(req: JSONObject, onProgress: (String) -> Unit): String =
        withContext(Dispatchers.IO) {
            if (backendUrl.isBlank()) return@withContext "GAGAL: URL server NEXA belum diisi"
            val headers = mapOf("X-Nexa-Token" to backendToken)
            val start = JSONObject(String(http("$backendUrl/video", req, headers)))
            val jobId = start.optString("job_id").ifEmpty { return@withContext "GAGAL: ${start.optString("error", start.toString())}" }

            var last = ""
            repeat(360) {                         // maks ±60 menit
                delay(10_000)
                val st = JSONObject(String(httpGet("$backendUrl/jobs/$jobId", headers)))
                val msg = st.optString("message")
                if (msg != last) { onProgress("${st.optInt("progress")}% · $msg"); last = msg }
                when (st.optString("status")) {
                    "done" -> return@withContext saveFromServer(st.getString("file"))
                    "error" -> return@withContext "GAGAL: $msg"
                }
            }
            "GAGAL: video terlalu lama, cek server"
        }

    /** Unduh file hasil dari server lalu simpan ke Galeri HP. */
    fun saveFromServer(file: String): String {
        val bytes = httpGet("$backendUrl/files/$file", mapOf("X-Nexa-Token" to backendToken))
        val isVideo = file.endsWith(".mp4")
        saveToGallery(bytes, file, if (isVideo) "video/mp4" else "image/png", isVideo)
        return "OK: tersimpan di Galeri (${if (isVideo) "Movies" else "Pictures"}/NEXA/$file)"
    }

    // ------------------------------------------------------------------ HELPERS
    /** Cari konten terakhir bertipe [type] di steps[].content[] (format Interactions API). */
    private fun lastOutput(resp: JSONObject, type: String): Pair<String, String>? {
        resp.optJSONObject("output_$type")?.let { o ->
            val d = o.optString("data"); if (d.isNotEmpty()) return o.optString("mime_type", "image/png") to d
        }
        var found: Pair<String, String>? = null
        val steps = resp.optJSONArray("steps") ?: return null
        for (i in 0 until steps.length()) {
            val content = steps.optJSONObject(i)?.optJSONArray("content") ?: continue
            for (j in 0 until content.length()) {
                val c = content.optJSONObject(j) ?: continue
                if (c.optString("type") == type && c.optString("data").isNotEmpty())
                    found = c.optString("mime_type", "image/png") to c.optString("data")
            }
        }
        return found
    }

    private fun saveToGallery(bytes: ByteArray, name: String, mime: String, isVideo: Boolean) {
        if (Build.VERSION.SDK_INT >= 29) {
            val values = ContentValues().apply {
                put(MediaStore.MediaColumns.DISPLAY_NAME, name)
                put(MediaStore.MediaColumns.MIME_TYPE, mime)
                put(MediaStore.MediaColumns.RELATIVE_PATH,
                    (if (isVideo) Environment.DIRECTORY_MOVIES else Environment.DIRECTORY_PICTURES) + "/NEXA")
            }
            val collection = if (isVideo) MediaStore.Video.Media.EXTERNAL_CONTENT_URI
            else MediaStore.Images.Media.EXTERNAL_CONTENT_URI
            val uri = context.contentResolver.insert(collection, values)
                ?: throw RuntimeException("Tidak bisa menyimpan ke Galeri")
            context.contentResolver.openOutputStream(uri)?.use { it.write(bytes) }
        } else {
            // Android 8–9: simpan di folder aplikasi
            val dir = context.getExternalFilesDir(if (isVideo) Environment.DIRECTORY_MOVIES else Environment.DIRECTORY_PICTURES)
            File(dir, name).writeBytes(bytes)
        }
    }

    private fun http(url: String, body: JSONObject, headers: Map<String, String>): ByteArray {
        val c = (URL(url).openConnection() as HttpURLConnection).apply {
            requestMethod = "POST"; doOutput = true
            connectTimeout = 20_000; readTimeout = 180_000
            setRequestProperty("Content-Type", "application/json")
            headers.forEach { (k, v) -> setRequestProperty(k, v) }
        }
        c.outputStream.use { it.write(body.toString().toByteArray()) }
        return read(c)
    }

    private fun httpGet(url: String, headers: Map<String, String>): ByteArray {
        val c = (URL(url).openConnection() as HttpURLConnection).apply {
            connectTimeout = 20_000; readTimeout = 180_000
            headers.forEach { (k, v) -> setRequestProperty(k, v) }
        }
        return read(c)
    }

    private fun read(c: HttpURLConnection): ByteArray {
        val code = c.responseCode
        if (code !in 200..299) {
            val e = c.errorStream?.bufferedReader()?.use { it.readText() } ?: ""
            throw RuntimeException("HTTP $code ${e.take(200)}")
        }
        return c.inputStream.use { it.readBytes() }
    }
}
