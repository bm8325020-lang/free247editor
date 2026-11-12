package clipfactory.app

import android.content.Context
import android.media.AudioFormat
import android.media.MediaCodec
import android.media.MediaExtractor
import android.media.MediaFormat
import android.net.Uri
import java.io.ByteArrayOutputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.math.log10
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sqrt

/** Decodes the audio track with the phone's own decoder (streaming, so multi-hour VODs are fine). */
class AudioAnalyzer(private val context: Context, private val uri: Uri) {

    private fun openAudio(): Pair<MediaExtractor, MediaFormat> {
        val ex = MediaExtractor()
        ex.setDataSource(context, uri, null)
        for (i in 0 until ex.trackCount) {
            val f = ex.getTrackFormat(i)
            if (f.getString(MediaFormat.KEY_MIME)?.startsWith("audio/") == true) {
                ex.selectTrack(i)
                return ex to f
            }
        }
        ex.release()
        throw IllegalStateException("Este vídeo não tem áudio, então não há como achar os momentos.")
    }

    /**
     * Decode [startS, endS) (whole file when endS is null) and hand mono float samples to [sink] together with the
     * sample rate. [onProgress] gets 0..1.
     */
    private fun decode(startS: Double, endS: Double?, onProgress: (Double) -> Unit, sink: (FloatArray, Int, Int) -> Unit) {
        val (ex, fmt) = openAudio()
        val mime = fmt.getString(MediaFormat.KEY_MIME)!!
        val durUs = if (fmt.containsKey(MediaFormat.KEY_DURATION)) fmt.getLong(MediaFormat.KEY_DURATION) else 0L
        val codec = MediaCodec.createDecoderByType(mime)
        codec.configure(fmt, null, null, 0)
        codec.start()
        val startUs = (startS * 1e6).toLong()
        val endUs = endS?.let { (it * 1e6).toLong() } ?: Long.MAX_VALUE
        if (startUs > 0) ex.seekTo(startUs, MediaExtractor.SEEK_TO_PREVIOUS_SYNC)
        var rate = fmt.getInteger(MediaFormat.KEY_SAMPLE_RATE)
        var channels = fmt.getInteger(MediaFormat.KEY_CHANNEL_COUNT)
        var encoding = AudioFormat.ENCODING_PCM_16BIT
        val info = MediaCodec.BufferInfo()
        var inputDone = false
        var mono = FloatArray(8192)
        try {
            while (true) {
                if (!inputDone) {
                    val ii = codec.dequeueInputBuffer(10_000)
                    if (ii >= 0) {
                        val buf = codec.getInputBuffer(ii)!!
                        val n = ex.readSampleData(buf, 0)
                        // endUs may be Long.MAX_VALUE (whole file): compare without overflowing
                        if (n < 0 || ex.sampleTime - 200_000 > endUs) {
                            codec.queueInputBuffer(ii, 0, 0, 0, MediaCodec.BUFFER_FLAG_END_OF_STREAM)
                            inputDone = true
                        } else {
                            codec.queueInputBuffer(ii, 0, n, ex.sampleTime, 0)
                            ex.advance()
                        }
                    }
                }
                val oi = codec.dequeueOutputBuffer(info, 10_000)
                if (oi == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED) {
                    val of = codec.outputFormat
                    rate = of.getInteger(MediaFormat.KEY_SAMPLE_RATE)
                    channels = of.getInteger(MediaFormat.KEY_CHANNEL_COUNT)
                    if (of.containsKey(MediaFormat.KEY_PCM_ENCODING)) encoding = of.getInteger(MediaFormat.KEY_PCM_ENCODING)
                } else if (oi >= 0) {
                    val out = codec.getOutputBuffer(oi)!!.order(ByteOrder.nativeOrder())
                    out.position(info.offset)
                    out.limit(info.offset + info.size)
                    val frames = if (encoding == AudioFormat.ENCODING_PCM_FLOAT) info.size / 4 / channels else info.size / 2 / channels
                    if (mono.size < frames) mono = FloatArray(frames)
                    if (encoding == AudioFormat.ENCODING_PCM_FLOAT) {
                        val fb = out.asFloatBuffer()
                        for (f in 0 until frames) {
                            var s = 0f
                            for (c in 0 until channels) s += fb.get()
                            mono[f] = s / channels
                        }
                    } else {
                        val sb = out.asShortBuffer()
                        for (f in 0 until frames) {
                            var s = 0f
                            for (c in 0 until channels) s += sb.get() / 32768f
                            mono[f] = s / channels
                        }
                    }
                    // trim to the requested range
                    val t0 = info.presentationTimeUs
                    var from = 0
                    var to = frames
                    if (t0 < startUs) from = min(frames, ((startUs - t0) * rate / 1_000_000L).toInt())
                    val lastUs = t0 + frames * 1_000_000L / max(1, rate)
                    if (lastUs > endUs) to = max(from, frames - ((lastUs - endUs) * rate / 1_000_000L).toInt())
                    if (to > from) sink(mono.copyOfRange(from, to), to - from, rate)
                    codec.releaseOutputBuffer(oi, false)
                    if (durUs > 0) {
                        val span = (min(endUs, durUs) - startUs).coerceAtLeast(1)
                        onProgress(((t0 - startUs).toDouble() / span).coerceIn(0.0, 1.0))
                    }
                    if ((info.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM) != 0 || t0 > endUs) break
                }
            }
        } finally {
            codec.stop()
            codec.release()
            ex.release()
        }
    }

    /** RMS loudness in dB every [hop] seconds, for the whole file (matches the Python engine). */
    fun loudness(hop: Double = 0.1, onProgress: (Double) -> Unit = {}): FloatArray {
        val out = FloatArrayBuilder()
        var acc = 0.0
        var count = 0
        var perHop = -1
        decode(0.0, null, onProgress) { samples, n, rate ->
            if (perHop < 0) perHop = (rate * hop).toInt()
            for (i in 0 until n) {
                val v = samples[i]
                acc += v * v
                count++
                if (count == perHop) {
                    out.add((20 * log10(sqrt(acc / count + 1e-10))).toFloat())
                    acc = 0.0
                    count = 0
                }
            }
        }
        return if (out.size == 0) floatArrayOf(-90f) else out.toArray()
    }

    /** 16 kHz mono 16-bit WAV of [startS, endS), for transcription. */
    fun wav16k(startS: Double, endS: Double): ByteArray {
        val pcm = ShortArrayBuilder()
        var pos = 0.0 // fractional read position for linear resampling
        var step = -1.0
        decode(startS, endS, {}) { samples, n, rate ->
            if (step < 0) step = rate / 16000.0
            var i = pos
            while (i < n) {
                val k = i.toInt()
                val frac = (i - k).toFloat()
                val a = samples[k]
                val b = if (k + 1 < n) samples[k + 1] else samples[k]
                val v = (a + (b - a) * frac).coerceIn(-1f, 1f)
                pcm.add((v * 32767).toInt().toShort())
                i += step
            }
            pos = i - n
        }
        val data = pcm.toArray()
        val bytes = ByteArrayOutputStream(44 + data.size * 2)
        val h = ByteBuffer.allocate(44).order(ByteOrder.LITTLE_ENDIAN)
        h.put("RIFF".toByteArray()); h.putInt(36 + data.size * 2); h.put("WAVE".toByteArray())
        h.put("fmt ".toByteArray()); h.putInt(16); h.putShort(1); h.putShort(1); h.putInt(16000)
        h.putInt(32000); h.putShort(2); h.putShort(16)
        h.put("data".toByteArray()); h.putInt(data.size * 2)
        bytes.write(h.array())
        val body = ByteBuffer.allocate(data.size * 2).order(ByteOrder.LITTLE_ENDIAN)
        body.asShortBuffer().put(data)
        bytes.write(body.array())
        return bytes.toByteArray()
    }
}

private class FloatArrayBuilder {
    private var a = FloatArray(1 shl 14)
    var size = 0
        private set

    fun add(v: Float) {
        if (size == a.size) a = a.copyOf(a.size * 2)
        a[size++] = v
    }

    fun toArray() = a.copyOf(size)
}

private class ShortArrayBuilder {
    private var a = ShortArray(1 shl 16)
    var size = 0
        private set

    fun add(v: Short) {
        if (size == a.size) a = a.copyOf(a.size * 2)
        a[size++] = v
    }

    fun toArray() = a.copyOf(size)
}
