package com.natan.nexa.studio

import android.content.Context
import com.natan.nexa.Prefs
import com.natan.nexa.media.MediaClient
import com.natan.nexa.voice.Tts
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.util.UUID

/**
 * Pabrik video di dalam aplikasi (pengganti server Termux).
 *
 * Permintaan (JSON, sama seperti versi server):
 *   script | topic + duration_min | voice="own" + own_audio (path)
 *   voice: "gemini:Kore" | "openai:coral" | "fish:<id kloning>"
 *   mode: images | clips   aspect: 9:16 | 16:9 | 1:1   subtitles: bool   bgm: path   bgm_volume: 0..1
 */
object VideoJobs {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val jobs = linkedMapOf<String, JSONObject>()
    private val running = mutableMapOf<String, Job>()

    /** Dipanggil setiap ada perubahan status (untuk UI & notifikasi). */
    var onUpdate: ((JSONObject) -> Unit)? = null
    var onFinished: ((JSONObject) -> Unit)? = null

    val activeCount: Int get() = running.count { it.value.isActive }

    fun outputDir(ctx: Context) = File(ctx.filesDir, "nexa_media").apply { mkdirs() }

    fun list(ctx: Context): JSONArray {
        if (jobs.isEmpty()) load(ctx)
        return JSONArray(jobs.values.toList().reversed().take(30))
    }

    fun start(ctx: Context, req: JSONObject): String {
        val app = ctx.applicationContext
        if (jobs.isEmpty()) load(app)
        val id = UUID.randomUUID().toString().take(10)
        val title = req.optString("topic").ifBlank { req.optString("script") }.ifBlank { "Suara asli" }.take(60)
        val job = JSONObject().put("id", id).put("title", title).put("status", "running")
            .put("message", "Antre…").put("progress", 0).put("created", System.currentTimeMillis())
        jobs[id] = job
        update(app, job)
        StudioService.start(app)
        running[id] = scope.launch {
            try {
                run(app, job, req)
            } catch (e: Exception) {
                job.put("status", "error").put("message", e.message ?: e.toString())
                update(app, job)
            } finally {
                running.remove(id)
                onFinished?.invoke(job)
                if (activeCount == 0) StudioService.stop(app)
            }
        }
        return id
    }

    fun cancel(id: String) { running[id]?.cancel() }

    // ------------------------------------------------------------------ pipeline
    private suspend fun run(ctx: Context, job: JSONObject, req: JSONObject) {
        val p = Prefs(ctx)
        val api = StudioApi(p)
        val id = job.getString("id")
        val work = File(ctx.cacheDir, "job_$id").apply { mkdirs() }
        val aspect = req.optString("aspect", "9:16")
        val (w, h) = when (aspect) { "16:9" -> 1920 to 1080; "1:1" -> 1080 to 1080; else -> 1080 to 1920 }
        val mode = req.optString("mode", "images")
        val voice = req.optString("voice").ifBlank { p.str("video_voice") }
        val subs = req.optBoolean("subtitles", true)
        val renderer = VideoRenderer(ctx)

        fun status(msg: String, pct: Int? = null) {
            job.put("message", msg); if (pct != null) job.put("progress", pct); update(ctx, job)
        }

        fun visual(i: Int, prompt: String): Pair<File, Boolean> = if (mode == "clips") {
            File(work, "c$i.mp4").apply { writeBytes(api.clip(prompt, aspect)) } to true
        } else {
            File(work, "i$i.png").apply { writeBytes(api.image(prompt, aspect)) } to false
        }

        val shots = mutableListOf<VideoRenderer.Shot>()
        val narration = mutableListOf<File>()
        val cues = mutableListOf<VideoRenderer.Cue>()

        if (voice == "own") {
            // ---------------- SUARA ASLI ----------------
            val own = File(req.getString("own_audio"))
            require(own.exists()) { "Rekaman suara asli tidak ditemukan" }
            status("Mentranskrip suara asli…", 5)
            val segs = api.transcribe(own)
            require(segs.isNotEmpty()) { "Transkrip kosong" }
            // gabungkan menjadi adegan ±8 detik
            val scenes = mutableListOf<MutableList<StudioApi.Segment>>()
            for (s in segs) {
                val cur = scenes.lastOrNull()
                if (cur != null && s.end - cur.first().start <= 9.0) cur += s else scenes += mutableListOf(s)
            }
            val total = renderer.durationUs(own)
            val starts = scenes.mapIndexed { i, sc -> if (i == 0) 0L else (sc.first().start * 1e6).toLong() }
            status("Menyusun visual…", 10)
            val vis = api.visualsFor(scenes.map { sc -> sc.joinToString(" ") { it.text } })
            scenes.forEachIndexed { i, sc ->
                val end = if (i + 1 < scenes.size) starts[i + 1] else total
                val dur = (end - starts[i]).coerceAtLeast(500_000)
                status("Adegan ${i + 1}/${scenes.size}: visual…", 10 + 70 * i / scenes.size)
                val (file, clip) = visual(i, vis[i].first)
                shots += VideoRenderer.Shot(file, clip, dur, vis[i].second)
                if (subs) sc.forEach { seg -> cues += chunk(seg.text, seg.start, seg.end, seg.words, 0.0) }
            }
            narration += own
        } else {
            // ---------------- NASKAH / TOPIK + SUARA AI / KLONING ----------------
            var script = req.optString("script").trim()
            if (script.isBlank()) {
                status("Menulis naskah dari topik…", 2)
                script = api.writeScript(req.optString("topic"), req.optDouble("duration_min", 1.0))
                job.put("script", script)
            }
            status("Menyusun adegan…", 5)
            val scenes = api.planScenes(script)
            require(scenes.isNotEmpty()) { "Naskah kosong" }
            val tts = Tts.fromSpec(p, voice)
            var t = 0.0
            scenes.forEachIndexed { i, sc ->
                status("Adegan ${i + 1}/${scenes.size}: suara…", 5 + 75 * i / scenes.size)
                val audio = File(work, "a$i.mp3").apply { writeBytes(tts.synthesize(sc.narration)) }
                val durUs = renderer.durationUs(audio).coerceAtLeast(500_000)
                status("Adegan ${i + 1}/${scenes.size}: visual…")
                val (file, clip) = visual(i, sc.visual)
                shots += VideoRenderer.Shot(file, clip, durUs, sc.camera)
                narration += audio
                if (subs) cues += chunk(sc.narration, 0.0, durUs / 1e6, api.wordTimes(audio), t)
                t += durUs / 1e6
            }
        }

        // ---------------- RENDER ----------------
        status("Merender video…", 85)
        val name = "nexa_${id}.mp4"
        val out = File(outputDir(ctx), name)
        val bgm = req.optString("bgm").takeIf { it.isNotBlank() }?.let { File(it) }?.takeIf { it.exists() }
        renderer.render(shots, narration, cues, bgm, req.optDouble("bgm_volume", 0.25).toFloat(), w, h, out) { pct ->
            job.put("progress", 85 + pct * 14 / 100); update(ctx, job)
        }

        // simpan juga ke Galeri HP
        withContext(Dispatchers.IO) { MediaClient.saveToGallery(ctx, out.readBytes(), name, "video/mp4", isVideo = true) }
        job.put("status", "done").put("file", name).put("path", out.absolutePath)
            .put("progress", 100).put("message", "Selesai · tersimpan di Galeri (Movies/NEXA)")
            .put("duration", renderer.durationUs(out) / 1_000_000)
        update(ctx, job)
        work.deleteRecursively()
    }

    /** Subtitle pendek ±4 kata. Pakai timestamp kata bila ada. offset = awal adegan (detik). */
    private fun chunk(text: String, start: Double, end: Double, words: List<StudioApi.Word>, offset: Double, per: Int = 4)
        : List<VideoRenderer.Cue> {
        fun us(s: Double) = ((s + offset) * 1e6).toLong()
        if (words.isNotEmpty()) return words.chunked(per).map { g ->
            VideoRenderer.Cue(us(g.first().start), us(g.last().end), g.joinToString(" ") { it.word.trim() })
        }
        val groups = text.split(Regex("\\s+")).filter { it.isNotBlank() }.chunked(per).map { it.joinToString(" ") }
        val total = groups.sumOf { it.length }.coerceAtLeast(1)
        var t = start
        return groups.map { g ->
            val d = (end - start) * g.length / total
            VideoRenderer.Cue(us(t), us(t + d), g).also { t += d }
        }
    }

    // ------------------------------------------------------------------ penyimpanan status
    private fun update(ctx: Context, job: JSONObject) {
        save(ctx)
        onUpdate?.invoke(job)
    }

    @Synchronized private fun save(ctx: Context) {
        File(ctx.filesDir, "jobs.json").writeText(JSONArray(jobs.values.toList().takeLast(50)).toString())
    }

    @Synchronized private fun load(ctx: Context) {
        try {
            val arr = JSONArray(File(ctx.filesDir, "jobs.json").readText())
            for (i in 0 until arr.length()) {
                val j = arr.getJSONObject(i)
                if (j.optString("status") == "running") j.put("status", "error").put("message", "Terhenti (aplikasi ditutup)")
                jobs[j.getString("id")] = j
            }
        } catch (_: Exception) { }
    }
}
