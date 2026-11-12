package clipfactory.app

import android.content.Context
import android.net.Uri
import android.os.Handler
import android.os.Looper
import androidx.annotation.OptIn
import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.common.MimeTypes
import androidx.media3.common.audio.AudioProcessor
import androidx.media3.common.audio.BaseAudioProcessor
import androidx.media3.common.util.UnstableApi
import androidx.media3.effect.OverlayEffect
import androidx.media3.transformer.Composition
import androidx.media3.transformer.EditedMediaItem
import androidx.media3.transformer.EditedMediaItemSequence
import androidx.media3.transformer.Effects
import androidx.media3.transformer.ExportException
import androidx.media3.transformer.ExportResult
import androidx.media3.transformer.ProgressHolder
import androidx.media3.transformer.Transformer
import clipfactory.core.Clip
import clipfactory.core.Layout
import com.google.common.collect.ImmutableList
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import java.io.File
import java.nio.ByteBuffer
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException
import kotlin.math.abs
import kotlin.math.tanh

/** Fixed gain with a soft limiter so loud reactions do not clip. */
@OptIn(UnstableApi::class)
class GainProcessor(private val gain: Float) : BaseAudioProcessor() {
    private var float = false

    override fun onConfigure(inputAudioFormat: AudioProcessor.AudioFormat): AudioProcessor.AudioFormat {
        if (abs(gain - 1f) < 0.02f) return AudioProcessor.AudioFormat.NOT_SET
        return when (inputAudioFormat.encoding) {
            C.ENCODING_PCM_16BIT -> { float = false; inputAudioFormat }
            C.ENCODING_PCM_FLOAT -> { float = true; inputAudioFormat }
            else -> AudioProcessor.AudioFormat.NOT_SET
        }
    }

    private fun limit(v: Float): Float {
        val a = abs(v)
        if (a <= 0.9f) return v
        val y = 0.9f + 0.1f * tanh((a - 0.9f) / 0.1f)
        return if (v < 0) -y else y
    }

    override fun queueInput(inputBuffer: ByteBuffer) {
        val n = inputBuffer.remaining()
        if (n == 0) return
        val out = replaceOutputBuffer(n)
        if (float) {
            while (inputBuffer.remaining() >= 4) out.putFloat(limit(inputBuffer.float * gain))
        } else {
            while (inputBuffer.remaining() >= 2) {
                out.putShort((limit(inputBuffer.short / 32768f * gain) * 32767f).toInt().toShort())
            }
        }
        out.flip()
    }
}

@OptIn(UnstableApi::class)
object Renderer {

    /** Export one vertical clip to [out]. [onProgress] gets 0..1. */
    suspend fun render(
        context: Context, uri: Uri, clip: Clip, layout: Layout, srcW: Int, srcH: Int,
        overlay: CaptionOverlay?, gain: Float, out: File, onProgress: (Double) -> Unit,
    ) = withContext(Dispatchers.Main) {
        val item = MediaItem.Builder()
            .setUri(uri)
            .setClippingConfiguration(
                MediaItem.ClippingConfiguration.Builder()
                    .setStartPositionMs((clip.start * 1000).toLong())
                    .setEndPositionMs((clip.end * 1000).toLong())
                    .build()
            )
            .build()
        val video = ArrayList<androidx.media3.common.Effect>()
        video.add(LayoutEffect(layout, srcW, srcH))
        if (overlay != null) video.add(OverlayEffect(ImmutableList.of<androidx.media3.effect.TextureOverlay>(overlay)))
        val edited = EditedMediaItem.Builder(item)
            .setEffects(Effects(listOf(GainProcessor(gain)), video))
            .build()
        val composition = Composition.Builder(EditedMediaItemSequence.Builder(edited).build())
            .setHdrMode(Composition.HDR_MODE_TONE_MAP_HDR_TO_SDR_USING_OPEN_GL)
            .build()

        suspendCancellableCoroutine { cont ->
            val handler = Handler(Looper.getMainLooper())
            lateinit var poll: Runnable
            val transformer = Transformer.Builder(context)
                .setVideoMimeType(MimeTypes.VIDEO_H264)
                .setAudioMimeType(MimeTypes.AUDIO_AAC)
                // slow phones can take a while per frame: never abort an export for being slow
                .setMaxDelayBetweenMuxerSamplesMs(C.TIME_UNSET)
                .addListener(object : Transformer.Listener {
                    override fun onCompleted(composition: Composition, exportResult: ExportResult) {
                        handler.removeCallbacks(poll)
                        if (cont.isActive) cont.resume(Unit)
                    }

                    override fun onError(composition: Composition, exportResult: ExportResult, exportException: ExportException) {
                        handler.removeCallbacks(poll)
                        if (cont.isActive) cont.resumeWithException(exportException)
                    }
                })
                .build()
            val holder = ProgressHolder()
            poll = Runnable {
                if (transformer.getProgress(holder) == Transformer.PROGRESS_STATE_AVAILABLE) {
                    onProgress(holder.progress / 100.0)
                }
                handler.postDelayed(poll, 400)
            }
            out.parentFile?.mkdirs()
            transformer.start(composition, out.absolutePath)
            handler.post(poll)
            cont.invokeOnCancellation {
                handler.removeCallbacks(poll)
                handler.post { transformer.cancel() }
            }
        }
    }
}
