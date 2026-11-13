package clipfactory.app

import android.content.ContentValues
import android.content.Context
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
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
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
    val layout: String = "auto", // auto | split | game | follow
    val captions: Boolean = true,
    val hook: Boolean = true,
    val groqKey: String = "",
    val claudeKey: String = "",
    val language: String = "",
)

data class ClipResult(
    val rank: Int, val title: String, val score: Int, val reason: String, val seconds: Double,
    val sourceStart: Double, val uri: String, val layout: String,
)

data class JobResult(val name: String, val date: String, val clips: List<ClipResult>, val notes: List<String>)

/** Long video in, vertical clips in the gallery out. Mirrors tools/clipper/pipeline.py in gaming mode. */
class Engine(private val ctx: Context, private val uri: Uri, private val name: String, private val opt: Options,
             private val testWords: List<Word>? = null, private val report: (String, Double) -> Unit) {
    private val notes = ArrayList<String>()
    private val hop = 0.1

    suspend fun run(): JobResult {
        val frames = FrameAnalyzer(ctx, uri)
        val info = frames.info()
        val s = Settings(
            clips = opt.clips, minSeconds = 15.0, maxSeconds = opt.maxSeconds.toDouble(),
            targetSeconds = min(28.0, opt.maxSeconds.toDouble()).coerceAtLeast(15.0),
        )

        val audio = AudioAnalyzer(ctx, uri)
        val cached = AnalysisCache.load(ctx, uri, info)
        val loud: FloatArray
        val samples: FrameAnalyzer.Samples
        if (cached != null) {
            report("Usando a análise salva deste vídeo", 0.33)
            loud = cached.loud
            samples = cached.samples
        } else {
            report("Ouvindo o vídeo inteiro", 0.0)
            loud = audio.loudness(hop) { report("Ouvindo o vídeo inteiro", 0.25 * it) }
            coroutineContext.ensureActive()
            report("Procurando a webcam", 0.25)
            samples = frames.sample(info, 40) { report("Procurando a webcam", 0.25 + 0.08 * it) }
            AnalysisCache.save(ctx, uri, info, AnalysisCache.Entry(loud, samples))
        }
        val det = LayoutMath.detectFacecam(samples.faces, samples.edgeFreq, samples.w, samples.h)
        val bigFace = samples.faces.flatten().maxByOrNull { it.h }
        val layout = LayoutMath.choose(det, info.width, info.height, opt.layout, bigFace)
        if (opt.layout in setOf("split", "pip") && det !is Facecam) notes.add("Não achei webcam; usei o layout só de gameplay.")
        coroutineContext.ensureActive()

        report("Achando os momentos de hype", 0.33)
        val ex = Hype.audioExcitement(loud, hop)
        val cands = Hype.audioCandidates(ex, info.durationS, s)
        var words: List<Word> = emptyList()
        var language: String? = opt.language.ifBlank { null }
        if (testWords != null) {
            words = testWords
        } else if (opt.groqKey.isNotBlank()) {
            try {
                val tr = Transcriber(opt.groqKey.trim(), language)
                val ranges = Hype.transcriptionRanges(cands, info.durationS).flatMap { (a, b) ->
                    // keep each upload well under Groq's 25 MB limit
                    val parts = ArrayList<Pair<Double, Double>>()
                    var x = a
                    while (x < b) { parts.add(x to min(b, x + 300)); x += 300 }
                    parts
                }
                val all = ArrayList<Word>()
                for ((i, r) in ranges.withIndex()) {
                    coroutineContext.ensureActive()
                    report("Transcrevendo os momentos", 0.33 + 0.17 * i / ranges.size)
                    all += tr.words(audio.wav16k(r.first, r.second), r.first)
                }
                words = all.sortedBy { it.start }.distinctBy { (it.start * 10).toLong() to it.text }
                language = language ?: tr.detectedLanguage
            } catch (e: Exception) {
                Log.w("Engine", "transcription failed", e)
                notes.add("A transcrição falhou (${e.message?.take(80)}). Clipes saíram sem legenda e escolhidos só pelo áudio.")
            }
        } else {
            notes.add("Sem chave do Groq: clipes escolhidos só pelo áudio e sem legendas. Coloque a chave grátis em Configurações.")
        }

        val ranked = Hype.rescore(cands, ex, words, info.durationS, s.maxSeconds)
        var picked: List<Clip>? = null
        if (opt.claudeKey.isNotBlank()) {
            report("Claude escolhendo os melhores", 0.5)
            picked = ClaudePicker.pick(opt.claudeKey.trim(), ranked.take(max(opt.clips * 3, 12)), words, opt.clips, language)
            if (picked == null) notes.add("Claude indisponível; usei o seletor automático.")
            else picked = picked.fold(ArrayList()) { acc, c ->
                if (acc.all { c.end <= it.start || c.start >= it.end }) acc.add(c); acc
            }
        }
        val strong = ranked.count { Hype.isStrong(it, s) }
        val clips = picked ?: Hype.pick(ranked, words, s)
        val failed = ArrayList<String>()
        Hype.fillText(clips, words)

        val stamp = SimpleDateFormat("yyyy-MM-dd HH-mm", Locale.US).format(Date())
        val folder = "Movies/Clipes/" + name.substringBeforeLast('.').take(40).replace(Regex("[^\\w -]"), "") + " " + stamp
        val work = File(ctx.cacheDir, "render").apply { mkdirs() }
        val results = ArrayList<ClipResult>()
        for ((i, c) in clips.withIndex()) {
            coroutineContext.ensureActive()
            val base = 0.5 + 0.5 * i / clips.size
            val span = 0.5 / clips.size
            report("Renderizando clipe ${i + 1} de ${clips.size}", base)
            val events = if (opt.captions) Captions.build(words, c.start, c.end) else emptyList()
            val (cy, hy) = textPositions(layout, info.width, info.height)
            val overlay = if (events.isNotEmpty() || (opt.hook && c.hook.isNotBlank()))
                CaptionOverlay(ctx, events, if (opt.hook) c.hook else "", 3.0, cy, hy) else null
            val out = File(work, "clip-%02d.mp4".format(i + 1))
            try {
                // a busy phone can fail to start the video encoder; try twice more before giving up on this clip
                var attempt = 0
                while (true) {
                    try {
                        Renderer.render(ctx, uri, c, layout, info.width, info.height, overlay, gainFor(loud, c), out) {
                            report("Renderizando clipe ${i + 1} de ${clips.size}", base + span * it)
                        }
                        break
                    } catch (e: androidx.media3.transformer.ExportException) {
                        if (++attempt >= 3) throw e
                        Log.w("Engine", "render attempt $attempt failed, retrying", e)
                        out.delete()
                        kotlinx.coroutines.delay(2000L * attempt)
                    }
                }
                val saved = saveToGallery(out, folder, "clip-%02d-%s.mp4".format(i + 1, slug(c.title)))
                results.add(ClipResult(i + 1, c.title, c.score, c.reason, c.seconds, c.start, saved.toString(), layoutName(layout)))
            } catch (e: Exception) {
                Log.e("Engine", "render failed for clip ${i + 1}", e)
                failed.add("clipe ${i + 1} (${c.title}): ${e.message?.take(100)}")
            } finally {
                overlay?.recycle()
                out.delete()
            }
        }
        notes.add(0, Hype.explain(opt.clips, if (picked == null) strong else null, clips.size,
            if (picked == null) "automático" else "Claude", results.size, failed))
        report("Pronto", 1.0)
        val job = JobResult(name, stamp, results, notes)
        History.add(ctx, job)
        return job
    }

    private fun layoutName(l: Layout) = when (l) {
        is Layout.Pip -> if (l.round) "webcam redonda no canto" else "webcam no canto"
        is Layout.Split -> "webcam + gameplay"
        is Layout.Game -> "gameplay"
        is Layout.Follow -> "rosto"
    }

    /** Caption and hook positions (fractions of the height) for each layout. */
    private fun textPositions(l: Layout, w: Int, h: Int): Pair<Double, Double> = when (l) {
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
        val a = (c.start / hop).toInt().coerceIn(0, loud.size - 1)
        val b = (c.end / hop).toInt().coerceIn(a + 1, loud.size)
        var p = 0.0
        for (i in a until b) p += 10.0.pow(loud[i] / 10.0)
        val db = 10 * log10(p / (b - a) + 1e-12)
        return 10.0.pow((-16.0 - db) / 20).coerceIn(0.5, 4.0).toFloat()
    }

    private fun slug(t: String) = t.lowercase().replace(Regex("[^\\p{L}\\p{N}]+"), "-").trim('-').take(40).ifEmpty { "clipe" }

    private fun saveToGallery(file: File, folder: String, displayName: String): Uri {
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

/** Past jobs, newest first, kept in the app's private storage. */
object History {
    private fun file(ctx: Context) = File(ctx.filesDir, "history.json")

    fun load(ctx: Context): List<JobResult> = try {
        val arr = JSONArray(file(ctx).readText())
        (0 until arr.length()).map { i ->
            val o = arr.getJSONObject(i)
            val cs = o.getJSONArray("clips")
            val ns = o.optJSONArray("notes") ?: JSONArray()
            JobResult(o.getString("name"), o.getString("date"), (0 until cs.length()).map { k ->
                val c = cs.getJSONObject(k)
                ClipResult(c.getInt("rank"), c.getString("title"), c.getInt("score"), c.getString("reason"),
                    c.getDouble("seconds"), c.getDouble("sourceStart"), c.getString("uri"), c.optString("layout"))
            }, (0 until ns.length()).map { ns.getString(it) })
        }
    } catch (e: Exception) {
        emptyList()
    }

    fun add(ctx: Context, job: JobResult) {
        val all = listOf(job) + load(ctx).take(29)
        val arr = JSONArray()
        for (j in all) {
            arr.put(JSONObject().apply {
                put("name", j.name); put("date", j.date)
                put("notes", JSONArray(j.notes))
                put("clips", JSONArray().apply {
                    for (c in j.clips) put(JSONObject().apply {
                        put("rank", c.rank); put("title", c.title); put("score", c.score); put("reason", c.reason)
                        put("seconds", c.seconds); put("sourceStart", c.sourceStart); put("uri", c.uri)
                        put("layout", c.layout)
                    })
                })
            })
        }
        file(ctx).writeText(arr.toString())
    }
}
