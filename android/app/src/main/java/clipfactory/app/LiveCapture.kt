package clipfactory.app

import android.media.MediaCodec
import android.media.MediaExtractor
import android.media.MediaFormat
import android.media.MediaMuxer
import java.io.File
import java.nio.ByteBuffer

/**
 * Rolling buffer of the screen recording: ~10 s MP4 segments (each starts on a key frame), the oldest deleted once
 * they fall out of `keepUs`. Times are the encoders' monotonic clock in microseconds.
 */
class SegmentWriter(private val dir: File, private val keepUs: Long = 180_000_000L, private val segUs: Long = 10_000_000L) {
    data class Segment(val file: File, val startUs: Long, val endUs: Long)

    private var videoFormat: MediaFormat? = null
    private var audioFormat: MediaFormat? = null
    var audioEnabled = true
    private var muxer: MediaMuxer? = null
    private var file: File? = null
    private var vTrack = -1
    private var aTrack = -1
    private var segStart = 0L
    private var lastUs = 0L
    private var n = 0
    private val done = ArrayList<Segment>()
    private val tmp = MediaCodec.BufferInfo()

    init { dir.deleteRecursively(); dir.mkdirs() }

    @Synchronized fun setVideoFormat(f: MediaFormat) { videoFormat = f }
    @Synchronized fun setAudioFormat(f: MediaFormat) { audioFormat = f }

    @Synchronized
    fun writeVideo(buf: ByteBuffer, info: MediaCodec.BufferInfo) {
        if (info.size == 0 || info.flags and MediaCodec.BUFFER_FLAG_CODEC_CONFIG != 0) return
        val key = info.flags and MediaCodec.BUFFER_FLAG_KEY_FRAME != 0
        if (key && (muxer == null || info.presentationTimeUs - segStart >= segUs)) open(info.presentationTimeUs)
        val m = muxer ?: return
        write(m, vTrack, buf, info)
    }

    @Synchronized
    fun writeAudio(buf: ByteBuffer, info: MediaCodec.BufferInfo) {
        if (info.size == 0 || info.flags and MediaCodec.BUFFER_FLAG_CODEC_CONFIG != 0) return
        val m = muxer ?: return
        if (aTrack < 0 || info.presentationTimeUs < segStart) return
        write(m, aTrack, buf, info)
    }

    private fun write(m: MediaMuxer, track: Int, buf: ByteBuffer, info: MediaCodec.BufferInfo) {
        tmp.set(info.offset, info.size, info.presentationTimeUs - segStart, info.flags)
        m.writeSampleData(track, buf, tmp)
        lastUs = maxOf(lastUs, info.presentationTimeUs)
    }

    private fun open(at: Long) {
        val vf = videoFormat ?: return
        if (audioEnabled && audioFormat == null) return // wait for the audio track so every segment has sound
        close()
        val f = File(dir, "seg_%05d.mp4".format(n++))
        val m = MediaMuxer(f.path, MediaMuxer.OutputFormat.MUXER_OUTPUT_MPEG_4)
        vTrack = m.addTrack(vf)
        aTrack = audioFormat?.takeIf { audioEnabled }?.let { m.addTrack(it) } ?: -1
        m.start()
        muxer = m; file = f; segStart = at; lastUs = at
        // forget what fell out of the buffer
        while (done.isNotEmpty() && at - done.first().startUs > keepUs) done.removeAt(0).file.delete()
    }

    private fun close() {
        val m = muxer ?: return
        runCatching { m.stop() }
        runCatching { m.release() }
        file?.let { done.add(Segment(it, segStart, lastUs)) }
        muxer = null; file = null
    }

    /** Closed segments only (the open one is still being written). */
    @Synchronized fun closed(): List<Segment> = done.toList()

    @Synchronized fun newestUs() = lastUs

    @Synchronized fun hasAudioFormat() = audioFormat != null

    @Synchronized fun finish() = close()

    /** Join the segments covering [fromUs, toUs] into one MP4 (stream copy). Returns its start time, or null. */
    fun join(fromUs: Long, toUs: Long, out: File): Long? {
        val segs = closed().filter { it.endUs > fromUs && it.startUs < toUs && it.file.exists() }
        if (segs.isEmpty()) return null
        val first = segs.first().startUs
        val m = MediaMuxer(out.path, MediaMuxer.OutputFormat.MUXER_OUTPUT_MPEG_4)
        val map = HashMap<String, Int>() // mime -> track
        val buf = ByteBuffer.allocate(4 * 1024 * 1024)
        val info = MediaCodec.BufferInfo()
        try {
            // tracks from the first segment
            MediaExtractor().run {
                setDataSource(segs.first().file.path)
                for (i in 0 until trackCount) {
                    val f = getTrackFormat(i)
                    map[f.getString(MediaFormat.KEY_MIME)!!] = m.addTrack(f)
                }
                release()
            }
            m.start()
            for (s in segs) {
                val ex = MediaExtractor()
                ex.setDataSource(s.file.path)
                val tracks = (0 until ex.trackCount).associateWith { map[ex.getTrackFormat(it).getString(MediaFormat.KEY_MIME)] }
                tracks.keys.forEach { ex.selectTrack(it) }
                while (true) {
                    val size = ex.readSampleData(buf, 0)
                    if (size < 0) break
                    val t = tracks[ex.sampleTrackIndex]
                    if (t != null) {
                        val flags = if (ex.sampleFlags and MediaExtractor.SAMPLE_FLAG_SYNC != 0) MediaCodec.BUFFER_FLAG_KEY_FRAME else 0
                        info.set(0, size, ex.sampleTime + (s.startUs - first), flags)
                        m.writeSampleData(t, buf, info)
                    }
                    ex.advance()
                }
                ex.release()
            }
            m.stop()
        } finally {
            runCatching { m.release() }
        }
        return first
    }
}
