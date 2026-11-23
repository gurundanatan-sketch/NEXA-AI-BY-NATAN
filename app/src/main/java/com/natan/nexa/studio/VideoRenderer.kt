package com.natan.nexa.studio

import android.content.Context
import android.graphics.Color
import android.graphics.Matrix
import android.graphics.Typeface
import android.media.MediaMetadataRetriever
import android.net.Uri
import android.os.Handler
import android.os.Looper
import android.text.Spannable
import android.text.SpannableString
import android.text.style.AbsoluteSizeSpan
import android.text.style.BackgroundColorSpan
import android.text.style.ForegroundColorSpan
import android.text.style.StyleSpan
import androidx.annotation.OptIn
import androidx.media3.common.MediaItem
import androidx.media3.common.MimeTypes
import androidx.media3.common.OverlaySettings
import androidx.media3.common.audio.AudioProcessor
import androidx.media3.common.audio.ChannelMixingAudioProcessor
import androidx.media3.common.audio.ChannelMixingMatrix
import androidx.media3.common.util.UnstableApi
import androidx.media3.effect.MatrixTransformation
import androidx.media3.effect.OverlayEffect
import androidx.media3.effect.Presentation
import androidx.media3.effect.StaticOverlaySettings
import androidx.media3.effect.TextOverlay
import androidx.media3.effect.TextureOverlay
import androidx.media3.transformer.Composition
import androidx.media3.transformer.EditedMediaItem
import androidx.media3.transformer.EditedMediaItemSequence
import androidx.media3.transformer.Effects
import androidx.media3.transformer.ExportException
import androidx.media3.transformer.ExportResult
import androidx.media3.transformer.ProgressHolder
import androidx.media3.transformer.Transformer
import com.google.common.collect.ImmutableList
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import java.io.File
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

/**
 * Perakit video di HP (pengganti FFmpeg) memakai Jetpack Media3 Transformer:
 *  - adegan gambar (durasi = panjang narasi) atau klip video
 *  - gerak kamera Ken Burns (zoom in/out, geser kiri/kanan)
 *  - narasi berurutan + musik latar (volume kecil, diulang)
 *  - subtitle per 3–4 kata ditanam ke video
 * Semua efek dipasang di level Composition, jadi waktunya memakai timeline video akhir.
 */
@OptIn(UnstableApi::class)
class VideoRenderer(private val context: Context) {

    /** Satu adegan pada timeline akhir. */
    data class Shot(
        val visual: File,          // .png/.jpg atau .mp4
        val isClip: Boolean,
        val durationUs: Long,
        val camera: String,
    )

    data class Cue(val startUs: Long, val endUs: Long, val text: String)

    fun durationUs(file: File): Long {
        val r = MediaMetadataRetriever()
        return try {
            r.setDataSource(file.absolutePath)
            (r.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION)?.toLongOrNull() ?: 0L) * 1000
        } finally { r.release() }
    }

    /**
     * @param narration  file audio berurutan (satu per adegan) — atau satu file suara asli
     * @param bgm        musik latar opsional (diulang, volume bgmVolume)
     */
    suspend fun render(
        shots: List<Shot>,
        narration: List<File>,
        cues: List<Cue>,
        bgm: File?,
        bgmVolume: Float,
        width: Int,
        height: Int,
        output: File,
        onProgress: (Int) -> Unit,
    ) {
        // ---- urutan video ----
        val videoItems = shots.flatMap { shot ->
            if (!shot.isClip) {
                val item = MediaItem.Builder()
                    .setUri(Uri.fromFile(shot.visual))
                    .setImageDurationMs(shot.durationUs / 1000)
                    .build()
                listOf(EditedMediaItem.Builder(item).setFrameRate(30).setEffects(fit(width, height)).build())
            } else {
                // Klip diulang/dipotong mengikuti panjang narasi; audio klip dibuang
                val clipUs = durationUs(shot.visual).coerceAtLeast(500_000)
                var left = shot.durationUs
                val parts = mutableListOf<EditedMediaItem>()
                while (left > 0) {
                    val take = minOf(left, clipUs)
                    val item = MediaItem.Builder().setUri(Uri.fromFile(shot.visual))
                        .setClippingConfiguration(MediaItem.ClippingConfiguration.Builder()
                            .setEndPositionMs(take / 1000).build())
                        .build()
                    parts += EditedMediaItem.Builder(item).setRemoveAudio(true).setEffects(fit(width, height)).build()
                    left -= take
                }
                parts
            }
        }
        val sequences = mutableListOf(EditedMediaItemSequence(videoItems))

        // ---- narasi ----
        sequences += EditedMediaItemSequence(narration.map {
            EditedMediaItem.Builder(MediaItem.fromUri(Uri.fromFile(it))).build()
        })

        // ---- musik latar ----
        if (bgm != null) {
            val mixer = ChannelMixingAudioProcessor().apply {
                for (ch in 1..2) putChannelMixingMatrix(ChannelMixingMatrix.create(ch, ch).scaleBy(bgmVolume))
            }
            val bgmItem = EditedMediaItem.Builder(MediaItem.fromUri(Uri.fromFile(bgm)))
                .setEffects(Effects(listOf<AudioProcessor>(mixer), emptyList()))
                .build()
            sequences += EditedMediaItemSequence(listOf(bgmItem), /* isLooping= */ true)
        }

        // ---- efek global: gerak kamera + subtitle ----
        val starts = LongArray(shots.size)
        var acc = 0L
        shots.forEachIndexed { i, s -> starts[i] = acc; acc += s.durationUs }
        val kenBurns = MatrixTransformation { t -> cameraMatrix(t, shots, starts) }
        val videoEffects = mutableListOf<androidx.media3.common.Effect>(kenBurns)
        if (cues.isNotEmpty()) {
            videoEffects += OverlayEffect(ImmutableList.of<TextureOverlay>(SubtitleOverlay(cues, height)))
        }
        val composition = Composition.Builder(sequences)
            .setEffects(Effects(emptyList(), videoEffects))
            .build()

        // ---- ekspor (Transformer wajib dijalankan di thread dengan Looper) ----
        withContext(Dispatchers.Main) {
            suspendCancellableCoroutine<Unit> { cont ->
                val handler = Handler(Looper.getMainLooper())
                lateinit var transformer: Transformer
                val holder = ProgressHolder()
                val poll = object : Runnable {
                    override fun run() {
                        if (transformer.getProgress(holder) == Transformer.PROGRESS_STATE_AVAILABLE) onProgress(holder.progress)
                        if (cont.isActive) handler.postDelayed(this, 1000)
                    }
                }
                transformer = Transformer.Builder(context)
                    .setVideoMimeType(MimeTypes.VIDEO_H264)
                    .setAudioMimeType(MimeTypes.AUDIO_AAC)
                    .addListener(object : Transformer.Listener {
                        override fun onCompleted(composition: Composition, exportResult: ExportResult) {
                            handler.removeCallbacks(poll)
                            if (cont.isActive) cont.resume(Unit)
                        }

                        override fun onError(composition: Composition, exportResult: ExportResult, exportException: ExportException) {
                            handler.removeCallbacks(poll)
                            if (cont.isActive) cont.resumeWithException(
                                RuntimeException("Render gagal: ${exportException.errorCodeName} ${exportException.message}"))
                        }
                    })
                    .build()
                output.parentFile?.mkdirs()
                transformer.start(composition, output.absolutePath)
                handler.post(poll)
                cont.invokeOnCancellation { handler.post { transformer.cancel() } }
            }
        }
    }

    private fun fit(w: Int, h: Int) =
        Effects(emptyList(), listOf(Presentation.createForWidthAndHeight(w, h, Presentation.LAYOUT_SCALE_TO_FIT_WITH_CROP)))

    /** Ken Burns pada ruang NDC (-1..1): skala ≥ 1 supaya tidak ada tepi hitam. */
    private fun cameraMatrix(tUs: Long, shots: List<Shot>, starts: LongArray): Matrix {
        val m = Matrix()
        var i = starts.indexOfLast { it <= tUs }
        if (i < 0) i = 0
        val shot = shots.getOrNull(i) ?: return m
        if (shot.isClip) return m
        val p = ((tUs - starts[i]).toFloat() / shot.durationUs.coerceAtLeast(1)).coerceIn(0f, 1f)
        when (shot.camera) {
            "zoom_in" -> { val s = 1f + 0.18f * p; m.setScale(s, s) }
            "zoom_out" -> { val s = 1.18f - 0.18f * p; m.setScale(s, s) }
            "pan_left" -> { m.setScale(1.15f, 1.15f); m.postTranslate(0.15f * (1f - 2f * p), 0f) }
            "pan_right" -> { m.setScale(1.15f, 1.15f); m.postTranslate(-0.15f * (1f - 2f * p), 0f) }
            else -> m.setScale(1.05f, 1.05f)
        }
        return m
    }

    /** Subtitle putih tebal berlatar gelap, di bawah layar. */
    private class SubtitleOverlay(private val cues: List<Cue>, private val frameHeight: Int) : TextOverlay() {
        private val settings: OverlaySettings = StaticOverlaySettings.Builder()
            .setBackgroundFrameAnchor(0f, -0.72f)
            .build()
        private val textPx = (frameHeight * 0.034f).toInt().coerceAtLeast(28)

        override fun getText(presentationTimeUs: Long): SpannableString {
            val cue = cues.firstOrNull { presentationTimeUs >= it.startUs && presentationTimeUs < it.endUs }
            val text = " ${cue?.text?.trim().orEmpty()} ".ifBlank { " " }
            return SpannableString(text).apply {
                val f = Spannable.SPAN_EXCLUSIVE_EXCLUSIVE
                setSpan(AbsoluteSizeSpan(textPx), 0, length, f)
                setSpan(ForegroundColorSpan(Color.WHITE), 0, length, f)
                setSpan(StyleSpan(Typeface.BOLD), 0, length, f)
                if (cue != null) setSpan(BackgroundColorSpan(Color.argb(150, 0, 0, 0)), 0, length, f)
            }
        }

        override fun getOverlaySettings(presentationTimeUs: Long): OverlaySettings = settings
    }
}
