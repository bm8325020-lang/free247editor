package clipfactory.app.library

import android.graphics.Bitmap
import android.media.MediaCodec
import android.media.MediaCodecInfo
import android.media.MediaExtractor
import android.media.MediaFormat
import android.media.MediaMetadataRetriever
import clipfactory.core.SceneCut
import clipfactory.core.SceneDetect
import java.io.File
import java.io.FileOutputStream
import kotlin.math.abs

/** Finds the scenes of a downloaded video with the phone's decoder, and saves a thumbnail of each. */
object SceneScanner {
    private const val TW = 32
    private const val TH = 18

    /** Per-frame difference and brightness of a 32x18 luma thumbnail, decoded with MediaCodec (hardware). */
    fun scan(path: String, onProgress: (Double) -> Unit): List<SceneCut> {
        val ex = MediaExtractor()
        ex.setDataSource(path)
        var track = -1
        for (i in 0 until ex.trackCount) {
            if (ex.getTrackFormat(i).getString(MediaFormat.KEY_MIME)?.startsWith("video/") == true) { track = i; break }
        }
        if (track < 0) { ex.release(); throw IllegalStateException("O arquivo não tem vídeo.") }
        ex.selectTrack(track)
        val fmt = ex.getTrackFormat(track)
        val durUs = if (fmt.containsKey(MediaFormat.KEY_DURATION)) fmt.getLong(MediaFormat.KEY_DURATION) else 1L
        fmt.setInteger(MediaFormat.KEY_COLOR_FORMAT, MediaCodecInfo.CodecCapabilities.COLOR_FormatYUV420Flexible)
        val codec = MediaCodec.createDecoderByType(fmt.getString(MediaFormat.KEY_MIME)!!)
        codec.configure(fmt, null, null, 0)
        codec.start()
        val times = ArrayList<Double>()
        val diffs = ArrayList<Double>()
        val lumas = ArrayList<Double>()
        var prev: IntArray? = null
        val cur = IntArray(TW * TH)
        val info = MediaCodec.BufferInfo()
        var inputDone = false
        try {
            while (true) {
                if (!inputDone) {
                    val ii = codec.dequeueInputBuffer(10_000)
                    if (ii >= 0) {
                        val n = ex.readSampleData(codec.getInputBuffer(ii)!!, 0)
                        if (n < 0) {
                            codec.queueInputBuffer(ii, 0, 0, 0, MediaCodec.BUFFER_FLAG_END_OF_STREAM); inputDone = true
                        } else {
                            codec.queueInputBuffer(ii, 0, n, ex.sampleTime, 0); ex.advance()
                        }
                    }
                }
                val oi = codec.dequeueOutputBuffer(info, 10_000)
                if (oi >= 0) {
                    val img = codec.getOutputImage(oi)
                    if (img != null && info.size > 0) {
                        val y = img.planes[0]
                        val buf = y.buffer
                        val rs = y.rowStride
                        val ps = y.pixelStride
                        var sum = 0L
                        for (ty in 0 until TH) {
                            val py = (ty * 2 + 1) * img.height / (TH * 2)
                            for (tx in 0 until TW) {
                                val px = (tx * 2 + 1) * img.width / (TW * 2)
                                val v = buf.get(py * rs + px * ps).toInt() and 0xff
                                cur[ty * TW + tx] = v
                                sum += v
                            }
                        }
                        var d = 0L
                        prev?.let { p -> for (k in cur.indices) d += abs(cur[k] - p[k]) }
                        times.add(info.presentationTimeUs / 1e6)
                        diffs.add(if (prev == null) 0.0 else d.toDouble() / cur.size)
                        lumas.add(sum.toDouble() / cur.size)
                        prev = (prev ?: IntArray(cur.size)).also { System.arraycopy(cur, 0, it, 0, cur.size) }
                        if (times.size % 30 == 0) onProgress((info.presentationTimeUs.toDouble() / durUs).coerceIn(0.0, 1.0))
                    }
                    img?.close()
                    codec.releaseOutputBuffer(oi, false)
                    if ((info.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM) != 0) break
                }
            }
        } finally {
            codec.stop(); codec.release(); ex.release()
        }
        // decoders may emit frames slightly out of order: sort by time first
        val order = times.indices.sortedBy { times[it] }
        return SceneDetect.cuts(order.map { times[it] }.toDoubleArray(), order.map { diffs[it] }.toDoubleArray(),
            order.map { lumas[it] }.toDoubleArray(), durUs / 1e6)
    }

    /** A JPEG thumbnail from the middle of each scene. */
    fun thumbnails(path: String, scenes: List<SceneCut>, outDir: File, width: Int = 480): List<String> {
        outDir.mkdirs()
        val r = MediaMetadataRetriever()
        r.setDataSource(path)
        val w = r.extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_WIDTH)?.toIntOrNull() ?: 16
        val h = r.extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_HEIGHT)?.toIntOrNull() ?: 9
        try {
            return scenes.mapIndexed { i, s ->
                val f = File(outDir, "%04d.jpg".format(i))
                val t = ((s.start + s.end) / 2 * 1e6).toLong()
                val bmp: Bitmap? = r.getScaledFrameAtTime(t, MediaMetadataRetriever.OPTION_CLOSEST, width, width * h / w) // exact frame: a keyframe could belong to the previous scene
                if (bmp != null) {
                    FileOutputStream(f).use { bmp.compress(Bitmap.CompressFormat.JPEG, 82, it) }
                    bmp.recycle()
                }
                f.path
            }
        } finally {
            r.release()
        }
    }
}
