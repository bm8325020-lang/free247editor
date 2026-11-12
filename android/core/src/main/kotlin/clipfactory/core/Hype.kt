package clipfactory.core

import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt

data class Word(val text: String, val start: Double, val end: Double)

data class Clip(
    val peak: Double,
    var start: Double,
    var end: Double,
    val audio: Double,
    var audioMean: Double = 0.0,
    var hype: Double = 0.0,
    var burst: Double = 0.0,
    var words: Int = 0,
    var raw: Double = 0.0,
    var title: String = "",
    var hook: String = "",
    var reason: String = "",
    var score: Int = 0,
    var picker: String = "auto",
    var text: String = "",
) {
    val seconds get() = end - start
}

data class Settings(
    val clips: Int = 8,
    val minSeconds: Double = 15.0,
    val maxSeconds: Double = 50.0,
    val targetSeconds: Double = 28.0,
)

/**
 * Hype-moment finder for streams and gameplay; a port of tools/clipper/gaming.py.
 *  1. audio pass: where it gets suddenly louder than its own recent baseline;
 *  2. only candidate windows get transcribed (by the caller);
 *  3. rescore with what was said: hype words, laughter, repeated words, fast talk.
 */
object Hype {
    val PHRASES: Map<String, Double> = mapOf(
        // pt-BR
        "meu deus" to 2.0, "não acredito" to 2.5, "nao acredito" to 2.5, "que isso" to 1.5, "olha isso" to 1.5,
        "caralho" to 2.0, "porra" to 1.5, "pqp" to 2.0, "puta" to 1.0, "socorro" to 2.0, "morri" to 1.5,
        "matei" to 1.5, "ganhamos" to 2.0, "ganhei" to 1.5, "perdemos" to 1.5, "perdi" to 1.0, "bora" to 1.0,
        "vamo" to 1.0, "vamos" to 0.8, "eita" to 1.5, "nossa" to 1.0, "rapaz" to 1.0, "jesus" to 1.2,
        "que absurdo" to 2.0, "absurdo" to 1.2, "inacreditável" to 2.0, "insano" to 1.5, "mds" to 1.5,
        "corre" to 1.2, "ajuda" to 1.0, "cuidado" to 1.0, "toma" to 1.2, "tomaaa" to 2.0, "chora" to 1.5,
        "lixo" to 1.0, "como assim" to 1.5, "não não não" to 2.5, "nao nao nao" to 2.5, "vai vai" to 1.5,
        "sim sim" to 1.5, "mano" to 0.5, "foi de base" to 2.0, "amassou" to 1.5, "rushou" to 1.0, "clipa" to 2.5,
        "clipa isso" to 3.0, "clip it" to 3.0,
        // en
        "let's go" to 2.0, "lets go" to 2.0, "no way" to 2.5, "oh my god" to 2.0, "oh my gosh" to 1.5,
        "what the" to 1.5, "holy" to 1.5, "clutch" to 2.0, "ace" to 1.0, "headshot" to 1.5, "one tap" to 1.5,
        "oh no" to 1.5, "no no no" to 2.5, "yes yes" to 1.5, "wtf" to 2.0, "omg" to 1.5, "insane" to 1.5,
        "crazy" to 1.0, "dude" to 0.6, "bro" to 0.6, "run" to 0.8, "help" to 0.8, "are you kidding" to 2.0,
        "you're kidding" to 1.5, "gg" to 1.0, "wow" to 1.0, "dead" to 0.8, "killed" to 0.8,
        // es
        "dios mío" to 2.0, "no puede ser" to 2.5, "qué es eso" to 1.5, "increíble" to 1.5,
    )
    private const val W = "[\\p{L}\\p{N}_]"
    private val PHRASE_RX = PHRASES.mapValues { (p, _) -> Regex("(?<!$W)" + Regex.escape(p) + "(?!$W)") }
    private val LAUGH = Regex(
        "(?<!$W)(k{3,}|(ha){2,}|(ah){3,}|(he){3,}|(ja){2,}|lol|lmao|risos|laughs?|laughter)(?!$W)",
        RegexOption.IGNORE_CASE,
    )
    private val TOKEN = Regex("[\\p{L}\\p{N}_']+")

    /** Per-second excitement: how much louder than the last minute or so. loud = dB every `hop` seconds. */
    fun audioExcitement(loud: FloatArray, hop: Double): DoubleArray {
        val per = (1 / hop).roundToInt()
        val n = loud.size / per
        if (n == 0) return DoubleArray(1)
        val level = DoubleArray(n)
        val peak = DoubleArray(n)
        for (i in 0 until n) {
            var s = 0.0
            var m = -1e9
            for (j in 0 until per) {
                val v = loud[i * per + j].toDouble()
                s += v
                if (v > m) m = v
            }
            level[i] = s / per
            peak[i] = m
        }
        val win = min(91, max(3, n or 1))
        val base = Stats.rollingMedian(level, win)
        val a = Stats.smooth(DoubleArray(n) { level[it] - base[it] }, 3)
        val b = Stats.smooth(DoubleArray(n) { peak[it] - base[it] }, 2)
        val rise = DoubleArray(n) { a[it] + 0.5 * b[it] }
        val p10 = Stats.percentile(level, 10.0)
        for (i in 0 until n) if (level[i] < p10) rise[i] = 0.0
        val sd = Stats.std(rise) + 1e-6
        val med = Stats.median(rise)
        val z = DoubleArray(n) { (rise[it] - med) / sd }
        // intros and outros ("hi chat", "bye chat") are almost never the highlight
        val edge = min(15, n / 6)
        if (edge > 0) {
            for (i in 0 until edge) {
                val r = if (edge == 1) 0.3 else 0.3 + 0.7 * i / (edge - 1)
                z[i] = min(z[i], z[i] * r)
                val j = n - 1 - i
                z[j] = min(z[j], z[j] * r)
            }
        }
        return z
    }

    /** Strongest local peaks (in seconds), at least ~one clip length apart. */
    fun candidatePeaks(ex: DoubleArray, s: Settings, k: Int): List<Int> {
        val sep = (s.maxSeconds * 0.8).toInt()
        val order = ex.indices.sortedByDescending { ex[it] }
        val taken = ArrayList<Int>()
        for (i in order) {
            if (ex[i] <= 0.5 && taken.size >= s.clips) break
            if (taken.all { kotlin.math.abs(i - it) >= sep }) taken.add(i)
            if (taken.size >= k) break
        }
        return taken.sorted()
    }

    /** Build-up before the peak matters more than after: ~70% before, ~30% after. */
    fun windowFor(peak: Double, duration: Double, s: Settings): Pair<Double, Double> {
        val t = s.targetSeconds
        var a = max(0.0, peak - t * 0.7)
        var b = min(duration, peak + t * 0.3 + 1.0)
        if (b - a < s.minSeconds) {
            a = max(0.0, b - s.minSeconds)
            b = min(duration, a + s.minSeconds)
        }
        return a to b
    }

    fun merge(ranges: List<Pair<Double, Double>>, pad: Double = 1.5): List<Pair<Double, Double>> {
        val out = ArrayList<DoubleArray>()
        for ((a0, b0) in ranges.sortedBy { it.first }) {
            val a = max(0.0, a0 - pad)
            val b = b0 + pad
            if (out.isNotEmpty() && a <= out.last()[1]) out.last()[1] = max(out.last()[1], b)
            else out.add(doubleArrayOf(a, b))
        }
        return out.map { it[0] to it[1] }
    }

    private fun phraseHits(low: String): Double =
        PHRASES.entries.sumOf { (p, wt) -> wt * min(PHRASE_RX.getValue(p).findAll(low).count(), 3) }

    /** (hype score, words per second around the peak, word count) */
    fun hypeScore(words: List<Word>, a: Double, b: Double, peak: Double): Triple<Double, Double, Int> {
        val inside = words.filter { it.start >= a && it.start < b }
        if (inside.isEmpty()) return Triple(0.0, 0.0, 0)
        val low = inside.joinToString(" ") { it.text.lowercase() }
        var score = phraseHits(low)
        score += 1.5 * min(3, LAUGH.findAll(low).count())
        score += 0.5 * min(4, low.count { it == '!' })
        val toks = TOKEN.findAll(low).map { it.value }.toList()
        for (i in 0 until toks.size - 2) {
            if (toks[i] == toks[i + 1] && toks[i + 1] == toks[i + 2]) {
                score += 1.5
                break
            }
        }
        val near = inside.count { it.start >= peak - 3 && it.start <= peak + 3 }
        return Triple(score, near / 6.0, inside.size)
    }

    /** Never start or end in the middle of a sentence. */
    fun snap(words: List<Word>, a0: Double, b0: Double, duration: Double): Pair<Double, Double> {
        var a = a0
        var b = b0
        val inside = words.filter { it.end > a - 4 && it.start < b + 6 }
        for (w in inside) if (w.start < a && a < w.end) a = w.start - 0.15
        val prev = inside.filter { it.end <= a }
        if (prev.isNotEmpty() && a - prev.last().end < 0.3) {
            for (i in prev.size - 1 downTo 1) {
                if (prev[i].start - prev[i - 1].end > 0.35 || a - prev[i].start > 3) {
                    a = prev[i].start - 0.15
                    break
                }
            }
        }
        val next = inside.filter { it.start >= b - 0.05 || (it.start < b && b < it.end) }
        val limit = b + 5
        for (w in next) {
            if (w.start > b + 0.4 || w.end > limit) break
            b = w.end + 0.3
        }
        return max(0.0, a) to min(duration, b)
    }

    /** The words shouted at the peak, as an on-screen hook (max 6 words), or "". */
    fun hookFrom(words: List<Word>, peak: Double): String {
        val near = words.filter { it.start >= peak - 2.5 && it.start <= peak + 2.5 }
        var best: String? = null
        var bestS = 0.0
        for (i in near.indices) {
            for (j in i until min(near.size, i + 6)) {
                val seg = near.subList(i, j + 1).joinToString(" ") { it.text }
                val low = seg.lowercase()
                var s = PHRASES.entries.sumOf { (p, wt) -> if (PHRASE_RX.getValue(p).containsMatchIn(low)) wt else 0.0 }
                if ('!' in seg || '?' in seg) s += 1.0
                s -= 0.1 * (j - i)
                if (s > bestS) {
                    best = seg
                    bestS = s
                }
            }
        }
        return if (bestS >= 1.5) (best ?: "").trim(' ', ',', '.', ';', ':') else ""
    }

    /** Candidate windows from the audio alone (before any transcription). */
    fun audioCandidates(ex: DoubleArray, duration: Double, s: Settings): List<Clip> {
        val peaks = candidatePeaks(ex, s, max(12, s.clips * 3))
        return peaks.map { p ->
            val (a, b) = windowFor(p + 0.5, duration, s)
            Clip(peak = p + 0.5, start = a, end = b, audio = ex[p])
        }
    }

    /** Ranges to transcribe for these candidates (merged, with the room `snap` may need). */
    fun transcriptionRanges(cands: List<Clip>, duration: Double): List<Pair<Double, Double>> =
        merge(cands.map { it.start to min(duration, it.end + 6) }).map { (a, b) -> a to min(duration, b) }

    /** Rescore with the transcript and order best first. */
    fun rescore(cands: List<Clip>, ex: DoubleArray, words: List<Word>, duration: Double): List<Clip> {
        if (cands.isEmpty()) return cands
        for (c in cands) {
            val (a, b) = snap(words, c.start, c.end, duration)
            c.start = a
            c.end = b
            val (h, burst, n) = hypeScore(words, a, b, c.peak)
            c.hype = h
            c.burst = burst
            c.words = n
            val lo = a.toInt().coerceIn(0, ex.size - 1)
            val hi = max(lo + 1, b.toInt()).coerceAtMost(ex.size)
            c.audioMean = Stats.mean(ex, lo, hi)
        }
        val cols = arrayOf(
            DoubleArray(cands.size) { cands[it].audio }, DoubleArray(cands.size) { cands[it].audioMean },
            DoubleArray(cands.size) { cands[it].hype }, DoubleArray(cands.size) { cands[it].burst },
        )
        val weights = doubleArrayOf(1.0, 0.4, 1.2, 0.5)
        for ((k, col) in cols.withIndex()) {
            val m = Stats.mean(col)
            val sd = Stats.std(col) + 1e-6
            for (i in cands.indices) cands[i].raw += (col[i] - m) / sd * weights[k]
        }
        return cands.sortedByDescending { it.raw }
    }

    /** Built-in pick: best first, no overlaps; fills title, hook, reason, score. */
    fun pick(sorted: List<Clip>, words: List<Word>, s: Settings): List<Clip> {
        val picked = ArrayList<Clip>()
        for (c in sorted) {
            if (picked.all { c.end <= it.start || c.start >= it.end }) picked.add(c)
            if (picked.size >= s.clips) break
        }
        if (picked.isEmpty()) return picked
        val lo = picked.minOf { it.raw }
        val hi = picked.maxOf { it.raw }
        for (c in picked) {
            c.hook = hookFrom(words, c.peak)
            val m = (c.peak / 60).toInt()
            val sec = (c.peak % 60).toInt()
            c.title = c.hook.ifEmpty { "Momento %d:%02d".format(m, sec) }
            c.reason = "Pico de áudio %.1fσ".format(c.audio) +
                (if (c.hype > 0) ", palavras de hype %.0f".format(c.hype) else "") + " (seletor automático)."
            c.picker = "auto"
            c.score = (60 + 35 * (if (hi > lo) (c.raw - lo) / (hi - lo) else 1.0)).roundToInt()
        }
        return picked
    }

    fun fillText(clips: List<Clip>, words: List<Word>) {
        for (c in clips) c.text = words.filter { it.start >= c.start && it.start < c.end }.joinToString(" ") { it.text }
    }
}
