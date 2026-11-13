package clipfactory.app.library

import android.content.ContentValues
import android.content.Context
import android.net.Uri
import android.os.Handler
import android.os.Looper
import android.provider.MediaStore
import androidx.annotation.OptIn
import androidx.media3.common.MediaItem
import androidx.media3.common.util.UnstableApi
import androidx.media3.transformer.Composition
import androidx.media3.transformer.EditedMediaItem
import androidx.media3.transformer.EditedMediaItemSequence
import androidx.media3.transformer.ExportException
import androidx.media3.transformer.ExportResult
import androidx.media3.transformer.Transformer
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import java.io.File
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

/** Saves one scene as its own video in the gallery (Movies/Cenas/<video>), at the original resolution. */
@OptIn(UnstableApi::class)
object SceneExporter {
    suspend fun export(ctx: Context, v: LibVideo, s: LibScene): Uri {
        val tmp = File(ctx.cacheDir, "cena-${v.id}-${s.idx}.mp4")
        withContext(Dispatchers.Main) {
            val item = MediaItem.Builder().setUri(Uri.fromFile(File(v.file)))
                .setClippingConfiguration(MediaItem.ClippingConfiguration.Builder()
                    .setStartPositionMs((s.start * 1000).toLong()).setEndPositionMs((s.end * 1000).toLong()).build())
                .build()
            val comp = Composition.Builder(EditedMediaItemSequence.Builder(EditedMediaItem.Builder(item).build()).build())
                .setHdrMode(Composition.HDR_MODE_TONE_MAP_HDR_TO_SDR_USING_OPEN_GL).build()
            suspendCancellableCoroutine { cont ->
                val t = Transformer.Builder(ctx)
                    .setMaxDelayBetweenMuxerSamplesMs(androidx.media3.common.C.TIME_UNSET)
                    .addListener(object : Transformer.Listener {
                        override fun onCompleted(composition: Composition, exportResult: ExportResult) {
                            if (cont.isActive) cont.resume(Unit)
                        }
                        override fun onError(composition: Composition, exportResult: ExportResult, e: ExportException) {
                            if (cont.isActive) cont.resumeWithException(e)
                        }
                    }).build()
                t.start(comp, tmp.absolutePath)
                cont.invokeOnCancellation { Handler(Looper.getMainLooper()).post { t.cancel() } }
            }
        }
        return withContext(Dispatchers.IO) {
            val folder = "Movies/Cenas/" + v.title.replace(Regex("[^\\p{L}\\p{N} _-]"), "").take(40).trim()
            val values = ContentValues().apply {
                put(MediaStore.Video.Media.DISPLAY_NAME, "cena-%03d.mp4".format(s.idx + 1))
                put(MediaStore.Video.Media.MIME_TYPE, "video/mp4")
                put(MediaStore.Video.Media.RELATIVE_PATH, folder)
                put(MediaStore.Video.Media.IS_PENDING, 1)
            }
            val r = ctx.contentResolver
            val uri = r.insert(MediaStore.Video.Media.getContentUri(MediaStore.VOLUME_EXTERNAL_PRIMARY), values)
                ?: throw IllegalStateException("Não consegui salvar na galeria.")
            r.openOutputStream(uri)!!.use { o -> tmp.inputStream().use { it.copyTo(o) } }
            values.clear(); values.put(MediaStore.Video.Media.IS_PENDING, 0)
            r.update(uri, values, null, null)
            tmp.delete()
            uri
        }
    }
}
