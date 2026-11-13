package clipfactory.app

import android.content.Context
import android.net.Uri
import clipfactory.core.Face
import java.io.DataInputStream
import java.io.DataOutputStream
import java.io.File
import java.security.MessageDigest

/** Remembers the slow analysis (audio loudness, faces, steady edges) per video, so re-runs with new settings are fast. */
object AnalysisCache {
    private const val VERSION = 1

    private fun file(ctx: Context, uri: Uri, info: VideoInfo): File {
        val key = "$VERSION|$uri|${info.width}x${info.height}|${info.durationS}"
        val hash = MessageDigest.getInstance("SHA-1").digest(key.toByteArray()).joinToString("") { "%02x".format(it) }
        return File(File(ctx.cacheDir, "analysis").apply { mkdirs() }, "$hash.bin")
    }

    class Entry(val loud: FloatArray, val samples: FrameAnalyzer.Samples)

    fun load(ctx: Context, uri: Uri, info: VideoInfo): Entry? = try {
        val f = file(ctx, uri, info)
        if (!f.exists()) null else DataInputStream(f.inputStream().buffered()).use { d ->
            val loud = FloatArray(d.readInt()) { d.readFloat() }
            val faces = List(d.readInt()) { List(d.readInt()) { Face(d.readDouble(), d.readDouble(), d.readDouble(), d.readDouble()) } }
            val w = d.readInt()
            val h = d.readInt()
            val freq = FloatArray(w * h) { d.readFloat() }
            Entry(loud, FrameAnalyzer.Samples(faces, freq, w, h))
        }
    } catch (e: Exception) {
        null
    }

    fun save(ctx: Context, uri: Uri, info: VideoInfo, e: Entry) {
        try {
            DataOutputStream(file(ctx, uri, info).outputStream().buffered()).use { d ->
                d.writeInt(e.loud.size); e.loud.forEach { d.writeFloat(it) }
                d.writeInt(e.samples.faces.size)
                for (fs in e.samples.faces) {
                    d.writeInt(fs.size)
                    for (f in fs) { d.writeDouble(f.cx); d.writeDouble(f.cy); d.writeDouble(f.w); d.writeDouble(f.h) }
                }
                d.writeInt(e.samples.w); d.writeInt(e.samples.h)
                e.samples.edgeFreq.forEach { d.writeFloat(it) }
            }
        } catch (_: Exception) {
        }
    }
}
