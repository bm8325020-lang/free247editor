package clipfactory.app

import android.content.Context
import android.graphics.Bitmap
import android.media.MediaMetadataRetriever
import android.net.Uri
import clipfactory.core.Face
import com.google.android.gms.tasks.Tasks
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.face.FaceDetection
import com.google.mlkit.vision.face.FaceDetectorOptions
import kotlin.math.abs

data class VideoInfo(val width: Int, val height: Int, val durationS: Double, val rotation: Int)

/** Looks at frames: video size, faces (ML Kit, on the phone), and which edges stay put (the webcam border). */
class FrameAnalyzer(private val context: Context, private val uri: Uri) {

    fun info(): VideoInfo {
        val r = MediaMetadataRetriever()
        try {
            r.setDataSource(context, uri)
            var w = r.extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_WIDTH)?.toIntOrNull() ?: 0
            var h = r.extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_HEIGHT)?.toIntOrNull() ?: 0
            val rot = r.extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_ROTATION)?.toIntOrNull() ?: 0
            if (rot % 180 == 90) w = h.also { h = w }
            val d = (r.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION)?.toLongOrNull() ?: 0L) / 1000.0
            if (w == 0 || h == 0) throw IllegalStateException("Não consegui ler o vídeo (sem trilha de vídeo?).")
            return VideoInfo(w, h, d, rot)
        } finally {
            r.release()
        }
    }

    class Samples(val faces: List<List<Face>>, val edgeFreq: FloatArray, val w: Int, val h: Int)

    /** Sample [count] frames across [fromS, toS): faces per frame and edge persistence. */
    fun sample(info: VideoInfo, count: Int, fromS: Double = 0.0, toS: Double = info.durationS,
               onProgress: (Double) -> Unit = {}): Samples {
        val dw = 640
        val dh = (Math.round(dw * info.height.toDouble() / info.width / 2) * 2).toInt()
        val detector = FaceDetection.getClient(
            FaceDetectorOptions.Builder()
                .setPerformanceMode(FaceDetectorOptions.PERFORMANCE_MODE_FAST)
                .setMinFaceSize(0.04f)
                .build()
        )
        val r = MediaMetadataRetriever()
        val faces = ArrayList<List<Face>>()
        val freq = FloatArray(dw * dh)
        var got = 0
        try {
            r.setDataSource(context, uri)
            val gray = IntArray(dw * dh)
            val px = IntArray(dw * dh)
            for (i in 0 until count) {
                onProgress(i.toDouble() / count)
                val t = fromS + (toS - fromS) * (i + 0.5) / count
                val raw = r.getScaledFrameAtTime((t * 1e6).toLong(), MediaMetadataRetriever.OPTION_CLOSEST_SYNC, dw, dh)
                    ?: continue
                val bmp = if (raw.width == dw && raw.height == dh) raw else Bitmap.createScaledBitmap(raw, dw, dh, true)
                got++
                val found = try {
                    Tasks.await(detector.process(InputImage.fromBitmap(bmp, 0)))
                } catch (e: Exception) {
                    emptyList()
                }
                faces.add(found.map { f ->
                    val b = f.boundingBox
                    Face(b.exactCenterX().toDouble() / dw, b.exactCenterY().toDouble() / dh,
                        b.width().toDouble() / dw, b.height().toDouble() / dh)
                })
                // edges: Sobel magnitude on luma
                bmp.getPixels(px, 0, dw, 0, 0, dw, dh)
                for (k in px.indices) {
                    val c = px[k]
                    gray[k] = ((c shr 16 and 255) * 77 + (c shr 8 and 255) * 150 + (c and 255) * 29) shr 8
                }
                for (y in 1 until dh - 1) {
                    val row = y * dw
                    for (x in 1 until dw - 1) {
                        val k = row + x
                        val gx = -gray[k - dw - 1] - 2 * gray[k - 1] - gray[k + dw - 1] +
                            gray[k - dw + 1] + 2 * gray[k + 1] + gray[k + dw + 1]
                        val gy = -gray[k - dw - 1] - 2 * gray[k - dw] - gray[k - dw + 1] +
                            gray[k + dw - 1] + 2 * gray[k + dw] + gray[k + dw + 1]
                        if (abs(gx) + abs(gy) > 160) freq[k] += 1f
                    }
                }
                if (bmp !== raw) bmp.recycle()
                raw.recycle()
            }
        } finally {
            r.release()
            detector.close()
        }
        if (got > 0) for (k in freq.indices) freq[k] /= got
        onProgress(1.0)
        return Samples(faces, freq, dw, dh)
    }
}
