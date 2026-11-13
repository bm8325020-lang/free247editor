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

    /**
     * Is the webcam on screen, every half second over [fromS, toS)? A face in the webcam area, or (webcam with a
     * border) its border line still there. Smoothed so it only flips after holding for a second.
     */
    fun presence(cam: clipfactory.core.Facecam, info: VideoInfo, fromS: Double, toS: Double): BooleanArray {
        val ext = clipfactory.core.LayoutMath.webcamExtent(cam, info.width, info.height)
        val detector = FaceDetection.getClient(
            FaceDetectorOptions.Builder().setPerformanceMode(FaceDetectorOptions.PERFORMANCE_MODE_FAST)
                .setMinFaceSize(0.15f).build()
        )
        val r = MediaMetadataRetriever()
        val raw = ArrayList<Boolean>()
        try {
            r.setDataSource(context, uri)
            var t = fromS
            while (t < toS) {
                val frame = r.getScaledFrameAtTime((t * 1e6).toLong(), MediaMetadataRetriever.OPTION_CLOSEST,
                    960, (960.0 * info.height / info.width).toInt())
                if (frame == null) { raw.add(raw.lastOrNull() ?: true); t += 0.5; continue }
                val x0 = (ext.x * frame.width).toInt().coerceIn(0, frame.width - 2)
                val y0 = (ext.y * frame.height).toInt().coerceIn(0, frame.height - 2)
                val w = (ext.w * frame.width).toInt().coerceIn(2, frame.width - x0)
                val h = (ext.h * frame.height).toInt().coerceIn(2, frame.height - y0)
                val crop = Bitmap.createBitmap(frame, x0, y0, w, h)
                var seen = try {
                    Tasks.await(detector.process(InputImage.fromBitmap(crop, 0))).isNotEmpty()
                } catch (e: Exception) { false }
                if (!seen && cam.shape in setOf("rect", "round")) seen = borderStillThere(crop, cam, ext, info)
                raw.add(seen)
                crop.recycle(); frame.recycle()
                t += 0.5
            }
        } finally {
            r.release()
            detector.close()
        }
        return clipfactory.core.LayoutMath.steadyPresence(raw)
    }

    private fun borderStillThere(crop: Bitmap, cam: clipfactory.core.Facecam, ext: clipfactory.core.Box, info: VideoInfo): Boolean {
        val w = crop.width
        val h = crop.height
        val px = IntArray(w * h)
        crop.getPixels(px, 0, w, 0, 0, w, h)
        val g = IntArray(w * h) { k -> val c = px[k]; ((c shr 16 and 255) * 77 + (c shr 8 and 255) * 150 + (c and 255) * 29) shr 8 }
        fun edge(x: Int, y: Int): Boolean {
            if (x < 1 || y < 1 || x >= w - 1 || y >= h - 1) return false
            val k = y * w + x
            val gx = -g[k - w - 1] - 2 * g[k - 1] - g[k + w - 1] + g[k - w + 1] + 2 * g[k + 1] + g[k + w + 1]
            val gy = -g[k - w - 1] - 2 * g[k - w] - g[k - w + 1] + g[k + w - 1] + 2 * g[k + w] + g[k + w + 1]
            return abs(gx) + abs(gy) > 160
        }
        if (cam.shape == "rect") {
            var hit = 0; var n = 0
            for (x in 1 until w - 1 step 2) { n += 2; if (edge(x, 1) || edge(x, 2)) hit++; if (edge(x, h - 2) || edge(x, h - 3)) hit++ }
            for (y in 1 until h - 1 step 2) { n += 2; if (edge(1, y) || edge(2, y)) hit++; if (edge(w - 2, y) || edge(w - 3, y)) hit++ }
            return n > 0 && hit.toDouble() / n > 0.25
        }
        val c = cam.circle ?: return false
        val cx = (c[0] - ext.x) / ext.w * w
        val cy = (c[1] - ext.y) / ext.h * h
        val rad = c[2] / ext.w * w
        var hit = 0
        for (k in 0 until 72) {
            val a = k * 2 * Math.PI / 72
            val x = Math.rint(cx + rad * Math.cos(a)).toInt()
            val y = Math.rint(cy + rad * Math.sin(a)).toInt()
            if (edge(x, y) || edge(x + 1, y) || edge(x, y + 1)) hit++
        }
        return hit / 72.0 > 0.45
    }
}
