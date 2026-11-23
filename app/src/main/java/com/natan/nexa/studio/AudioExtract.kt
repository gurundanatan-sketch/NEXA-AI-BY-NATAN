package com.natan.nexa.studio

import android.media.MediaCodec
import android.media.MediaExtractor
import android.media.MediaFormat
import android.media.MediaMuxer
import java.io.File
import java.nio.ByteBuffer

/** Ambil suara dari video galeri (tanpa encode ulang) untuk sampel kloning suara. */
object AudioExtract {

    /** Menyalin trek audio pertama dari [video] ke file .m4a. Melempar error bila video tanpa suara. */
    fun toM4a(video: File, out: File): File {
        val ex = MediaExtractor()
        try {
            ex.setDataSource(video.absolutePath)
            val track = (0 until ex.trackCount).firstOrNull {
                ex.getTrackFormat(it).getString(MediaFormat.KEY_MIME)?.startsWith("audio/") == true
            } ?: throw RuntimeException("Video ini tidak punya suara")
            ex.selectTrack(track)
            val format = ex.getTrackFormat(track)
            val muxer = MediaMuxer(out.absolutePath, MediaMuxer.OutputFormat.MUXER_OUTPUT_MPEG_4)
            try {
                val dst = muxer.addTrack(format)
                muxer.start()
                val size = if (format.containsKey(MediaFormat.KEY_MAX_INPUT_SIZE))
                    format.getInteger(MediaFormat.KEY_MAX_INPUT_SIZE) else 1 shl 20
                val buf = ByteBuffer.allocate(maxOf(size, 64 * 1024))
                val info = MediaCodec.BufferInfo()
                while (true) {
                    info.size = ex.readSampleData(buf, 0)
                    if (info.size < 0) break
                    info.offset = 0
                    info.presentationTimeUs = ex.sampleTime
                    info.flags = if (ex.sampleFlags and MediaExtractor.SAMPLE_FLAG_SYNC != 0) MediaCodec.BUFFER_FLAG_KEY_FRAME else 0
                    muxer.writeSampleData(dst, buf, info)
                    ex.advance()
                }
                muxer.stop()
            } finally {
                try { muxer.release() } catch (_: Exception) {}
            }
        } finally {
            ex.release()
        }
        return out
    }
}
