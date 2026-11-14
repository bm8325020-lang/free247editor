package clipfactory.app

import android.content.ContentValues
import android.content.Context
import android.graphics.Bitmap
import android.media.MediaMetadataRetriever
import android.net.Uri
import android.provider.MediaStore
import android.util.Log
import clipfactory.core.Captions
import clipfactory.core.Clip
import clipfactory.core.Facecam
import clipfactory.core.Hype
import clipfactory.core.Layout
import clipfactory.core.LayoutMath
import clipfactory.core.Settings
import clipfactory.core.Word
import kotlinx.coroutines.ensureActive
import java.io.File
import java.io.FileOutputStream
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import kotlin.coroutines.coroutineContext
import kotlin.math.log10
import kotlin.math.max
import kotlin.math.min
import kotlin.math.pow

data class Options(
    val clips: Int = 8,
    val maxSeconds: Int = 50,
    val layout: String = "auto", // auto | stack | split | pip | game | follow
    val captions: Boolean = true,
    val hook: Boolean = true,
    val groqKey: String = "",
    val claudeKey: String = "",
    val language: String = "",
    val credit: String = "",
    val hashtags: String = "",
    val chooseMoments: Boolean = false, // off: fully automatic; on: the user picks the moments before rendering
    val captionStyle: String = "amarelo",
)

/**
 * Long video in, vertical clips out, in two steps so the user can step in between:
 *  analyze() finds and ranks the moments (and saves everything needed to render or re-render later);
 *  render() makes one clip from a moment and its edit, and saves it to the gallery.
 * Mirrors tools/clipper/pipeline.py in gaming mode.
 */
object Engine {
    private const val HOP = 0.1

    suspend fun analyze(ctx: Context, projectId: String, uri: Uri, name: String, opt: Options, testWords: List<Word>? = null,
                        report: (String, Double) -> Unit): Project {
        val notes = ArrayList<String>()
        val stamp = SimpleDateFormat("yyyy-MM-dd HH-mm", Locale.US).format(Date())
        val folder = "Movies/Clipes/" + name.substringBeforeLast('.').take(40).replace(Regex("[^\\w -]"), "") + " " + stamp
        // saved before anything can fail, so an error shows up in the app and not only in the notification
        var project = Project(projectId, name.substringBeforeLast('.'), uri.toString(), System.currentTimeMillis(), folder = folder)
        ProjectStore.put(ctx, project)
        val frames = FrameAnalyzer(ctx, uri)
        val info = frames.info()
        project = project.copy(width = info.width, height = info.height, durationS = info.durationS)
        ProjectStore.put(ctx, project)
        val s = settings(opt)

        val audio = AudioAnalyzer(ctx, uri)
        val (loud, samples) = analysis(ctx, uri, info, frames, audio, report)
        coroutineContext.ensureActive()

        report("Achando os momentos de hype", 0.33)
        val ex = Hype.audioExcitement(loud, HOP)
        val cands = Hype.audioCandidates(ex, info.durationS, s)
        var words: List<Word> = emptyList()
        var language: String? = opt.language.ifBlank { null }
        if (testWords != null) {
            words = testWords
        } else if (opt.groqKey.isNotBlank()) {
            try {
                val tr = Transcriber(opt.groqKey.trim(), language)
                val ranges = Hype.transcriptionRanges(cands, info.durationS).flatMap { (a, b) ->
                    val parts = ArrayList<Pair<Double, Double>>() // keep each upload well under Groq's 25 MB limit
                    var x = a
                    while (x < b) { parts.add(x to min(b, x + 300)); x += 300 }
                    parts
                }
                val all = ArrayList<Word>()
                for ((i, r) in ranges.withIndex()) {
                    coroutineContext.ensureActive()
                    report("Ouvindo o que foi dito", 0.36 + 0.3 * i / ranges.size)
                    all += tr.words(audio.wav16k(r.first, r.second), r.first)
                }
                words = all.sortedBy { it.start }.distinctBy { (it.start * 10).toLong() to it.text }
                language = language ?: tr.detectedLanguage
            } catch (e: Exception) {
                Log.w("Engine", "transcription failed", e)
                notes.add("A transcrição falhou (${e.message?.take(80)}). Os clipes saem sem legenda e escolhidos só pelo áudio.")
            }
        } else {
            notes.add("Sem chave do Groq: momentos escolhidos só pelo áudio e sem legendas. Coloque a chave grátis em Configurações.")
        }

        val ranked = Hype.rescore(cands, ex, words, info.durationS, s.maxSeconds)
        var picked: List<Clip>? = null
        if (opt.claudeKey.isNotBlank()) {
            report("Claude escolhendo os melhores", 0.7)
            picked = ClaudePicker.pick(opt.claudeKey.trim(), ranked.take(max(opt.clips * 3, 12)), words, opt.clips, language)
            if (picked == null) notes.add("Claude indisponível; usei o seletor automático.")
            else picked = picked.fold(ArrayList()) { acc, c -> if (acc.all { c.end <= it.start || c.start >= it.end }) acc.add(c); acc }
        }
        val strong = ranked.count { Hype.isStrong(it, s) }
        val best = picked ?: Hype.pick(ranked, words, s)
        // extra candidates the user can choose from: more strong moments that do not overlap the best ones
        val extra = Hype.pick(ranked.filter { c -> best.all { c.end <= it.start || c.start >= it.end } }, words,
            s.copy(clips = max(4, opt.clips)))
        // extras rank below the chosen ones
        val floor = (best.minOfOrNull { it.score } ?: 70) - 1
        extra.forEach { it.score = minOf(it.score, floor).coerceAtLeast(1) }
        val all = best + extra
        Hype.fillText(all, words)

        report("Separando as miniaturas", 0.8)
        val thumbs = momentThumbs(ctx, uri, projectId, all.map { (it.start + it.end) / 2 })
        val moments = all.mapIndexed { i, c ->
            Moment(i, c.start, c.end, c.peak, c.title, c.hook, c.reason, c.score, c.text, thumbs.getOrElse(i) { "" }, i < best.size)
        }
        if (best.size < opt.clips) {
            notes.add(0, Hype.explain(opt.clips, if (picked == null) strong else null, best.size,
                if (picked == null) "automático" else "Claude", best.size, emptyList()).replace("Saíram", "Achei"))
        }
        project = project.copy(language = language, words = words.filter { w -> all.any { w.start >= it.start - 6 && w.start < it.end + 6 } },
            moments = moments, notes = notes, status = if (opt.chooseMoments) "choosing" else "rendering")
        ProjectStore.put(ctx, project)
        return project
    }

    /** The clips to make for the chosen moments (default edit from the moment and the user's defaults). */
    fun clipsFor(project: Project, momentIds: List<Int>, opt: Options): List<PClip> = momentIds.mapNotNull { id ->
        project.moments.firstOrNull { it.id == id }?.let { m ->
            PClip(m.id, m.title, m.score, m.reason, ClipEdit(m.start, m.end, if (opt.hook) m.hook else "", opt.captionStyle, opt.layout))
        }
    }

    /** Render one clip of a project; replaces an earlier version of the same clip in the gallery. */
    suspend fun render(ctx: Context, project: Project, index: Int, opt: Options, report: (Double) -> Unit): PClip {
        val pc = project.clips[index]
        val uri = Uri.parse(project.source)
        val frames = FrameAnalyzer(ctx, uri)
        val info = frames.info()
        val (loud, samples) = analysis(ctx, uri, info, frames, AudioAnalyzer(ctx, uri)) { _, _ -> }
        val det = LayoutMath.detectFacecam(samples.faces, samples.edgeFreq, samples.w, samples.h)
        val bigFace = samples.faces.flatten().maxByOrNull { it.h }
        val layout = LayoutMath.choose(det, info.width, info.height, pc.edit.layout, bigFace)
        val c = Clip(peak = (pc.edit.start + pc.edit.end) / 2, start = pc.edit.start, end = pc.edit.end, audio = 0.0)
        val events = if (opt.captions) Captions.build(project.words, c.start, c.end) else emptyList()
        val (cy, hy) = textPositions(layout, info.width, info.height)
        val overlay = if (events.isNotEmpty() || pc.edit.hook.isNotBlank())
            CaptionOverlay(ctx, events, pc.edit.hook, 3.0, cy, hy, pc.edit.style) else null
        val out = File(File(ctx.cacheDir, "render").apply { mkdirs() }, "${project.id}-$index.mp4")
        val camOn = if (layout is Layout.Stack && det is Facecam) runCatching { frames.presence(det, info, c.start, c.end) }.getOrNull() else null
        try {
            var attempt = 0 // a busy phone can fail to start the video encoder; try twice more
            while (true) {
                try {
                    Renderer.render(ctx, uri, c, layout, info.width, info.height, overlay, gainFor(loud, c), out, camOn, report)
                    break
                } catch (e: androidx.media3.transformer.ExportException) {
                    if (++attempt >= 3) throw e
                    Log.w("Engine", "render attempt $attempt failed, retrying", e)
                    out.delete()
                    kotlinx.coroutines.delay(2000L * attempt)
                }
            }
            val saved = saveToGallery(ctx, out, project.folder, "clip-%02d-%s.mp4".format(index + 1, slug(pc.title)))
            if (pc.uri.isNotEmpty()) runCatching { ctx.contentResolver.delete(Uri.parse(pc.uri), null, null) }
            return pc.copy(uri = saved.toString(), status = "done", error = "", layoutName = layoutName(layout))
        } finally {
            overlay?.recycle()
            out.delete()
        }
    }

    private fun settings(opt: Options) = Settings(
        clips = opt.clips, minSeconds = 15.0, maxSeconds = opt.maxSeconds.toDouble(),
        targetSeconds = min(28.0, opt.maxSeconds.toDouble()).coerceAtLeast(15.0),
    )

    /** Loudness and webcam samples, from the cache when this video was analysed before. */
    private suspend fun analysis(ctx: Context, uri: Uri, info: VideoInfo, frames: FrameAnalyzer, audio: AudioAnalyzer,
                                 report: (String, Double) -> Unit): Pair<FloatArray, FrameAnalyzer.Samples> {
        AnalysisCache.load(ctx, uri, info)?.let { return it.loud to it.samples }
        report("Ouvindo o vídeo inteiro", 0.0)
        val loud = audio.loudness(HOP) { report("Ouvindo o vídeo inteiro", 0.25 * it) }
        coroutineContext.ensureActive()
        report("Procurando a webcam", 0.25)
        val samples = frames.sample(info, 40) { report("Procurando a webcam", 0.25 + 0.08 * it) }
        AnalysisCache.save(ctx, uri, info, AnalysisCache.Entry(loud, samples))
        return loud to samples
    }

    private fun momentThumbs(ctx: Context, uri: Uri, projectId: String, times: List<Double>): List<String> {
        val dir = ProjectStore.dir(ctx, projectId)
        val r = MediaMetadataRetriever()
        return try {
            r.setDataSource(ctx, uri)
            times.mapIndexed { i, t ->
                val f = File(dir, "m%02d.jpg".format(i))
                r.getScaledFrameAtTime((t * 1e6).toLong(), MediaMetadataRetriever.OPTION_CLOSEST_SYNC, 480, 270)?.let { b ->
                    FileOutputStream(f).use { b.compress(Bitmap.CompressFormat.JPEG, 80, it) }
                    b.recycle()
                }
                f.path
            }
        } catch (e: Exception) {
            times.map { "" }
        } finally {
            r.release()
        }
    }

    fun layoutName(l: Layout) = when (l) {
        is Layout.Stack -> "webcam em cima"
        is Layout.Pip -> if (l.round) "webcam redonda no canto" else "webcam no canto"
        is Layout.Split -> "webcam em faixa"
        is Layout.Game -> "só gameplay"
        is Layout.Follow -> "rosto"
    }

    /** Caption and hook positions (fractions of the height) for each layout. */
    private fun textPositions(l: Layout, w: Int, h: Int): Pair<Double, Double> = when (l) {
        is Layout.Stack -> {
            val below = l.bandY + l.bandH // free space under the gameplay band: hook at its top, captions lower
            (below + (1 - below) * 0.62) to (below + 0.015)
        }
        is Layout.Pip -> 0.70 to (l.dst.y + l.dst.h + 0.03) // hook just under the webcam, never over the face
        is Layout.Split -> l.panel to 0.80
        is Layout.Game -> {
            val band = min(1.0, l.zoom * LayoutMath.OUT_W / w * h / LayoutMath.OUT_H)
            min(0.9, 0.5 + band / 2 + 0.07) to max(0.05, 0.5 - band / 2 - 0.13)
        }
        is Layout.Follow -> 0.68 to 0.12
    }

    /** Bring the clip to about -16 dBFS RMS (roughly -14 LUFS for speech and game audio). */
    private fun gainFor(loud: FloatArray, c: Clip): Float {
        val a = (c.start / HOP).toInt().coerceIn(0, loud.size - 1)
        val b = (c.end / HOP).toInt().coerceIn(a + 1, loud.size)
        var p = 0.0
        for (i in a until b) p += 10.0.pow(loud[i] / 10.0)
        val db = 10 * log10(p / (b - a) + 1e-12)
        return 10.0.pow((-16.0 - db) / 20).coerceIn(0.5, 4.0).toFloat()
    }

    private fun slug(t: String) = t.lowercase().replace(Regex("[^\\p{L}\\p{N}]+"), "-").trim('-').take(40).ifEmpty { "clipe" }

    private fun saveToGallery(ctx: Context, file: File, folder: String, displayName: String): Uri {
        val resolver = ctx.contentResolver
        val values = ContentValues().apply {
            put(MediaStore.Video.Media.DISPLAY_NAME, displayName)
            put(MediaStore.Video.Media.MIME_TYPE, "video/mp4")
            put(MediaStore.Video.Media.RELATIVE_PATH, folder)
            put(MediaStore.Video.Media.IS_PENDING, 1)
        }
        val uri = resolver.insert(MediaStore.Video.Media.getContentUri(MediaStore.VOLUME_EXTERNAL_PRIMARY), values)
            ?: throw IllegalStateException("Não consegui salvar na galeria.")
        resolver.openOutputStream(uri)!!.use { o -> file.inputStream().use { it.copyTo(o) } }
        values.clear()
        values.put(MediaStore.Video.Media.IS_PENDING, 0)
        resolver.update(uri, values, null, null)
        return uri
    }
}
