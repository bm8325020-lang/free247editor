package clipfactory.core

import java.text.Normalizer
import kotlin.math.max

data class SceneCut(val start: Double, val end: Double)

/**
 * Splits a video into scenes from a per-frame signal: how different each frame is from the previous one (mean
 * absolute difference of a small luma thumbnail, 0..255) and how bright it is. Scene packs are usually hard cuts,
 * sometimes with black frames between clips; both are handled.
 */
object SceneDetect {
    fun cuts(times: DoubleArray, diffs: DoubleArray, luma: DoubleArray, duration: Double,
             minLen: Double = 0.5, black: Double = 18.0): List<SceneCut> {
        val n = times.size
        if (n == 0) return emptyList()
        val out = ArrayList<SceneCut>()
        var start: Double? = if (luma[0] < black) null else times[0]
        val recent = ArrayDeque<Double>()
        for (i in 1 until n) {
            val t = times[i]
            val isBlack = luma[i] < black
            // adaptive threshold: a cut is a jump well above the recent frame-to-frame change
            val med = if (recent.isEmpty()) 0.0 else recent.sorted()[recent.size / 2]
            val isCut = diffs[i] > max(28.0, 3.0 * med + 6.0)
            if (start == null) {
                if (!isBlack) start = t
            } else if (isBlack || isCut) {
                if (t - start >= minLen) out.add(SceneCut(start, t))
                start = if (isBlack) null else t
            }
            if (!isCut) { recent.addLast(diffs[i]); if (recent.size > 15) recent.removeFirst() }
        }
        val s = start
        if (s != null && duration - s >= minLen) out.add(SceneCut(s, duration))
        return out
    }
}

/** Fast offline scene search over the descriptions and speech of each scene (Portuguese and English). */
object SceneSearch {
    private val STOP = setOf(
        "o", "a", "os", "as", "de", "do", "da", "dos", "das", "e", "que", "um", "uma", "em", "no", "na", "com", "pra",
        "para", "por", "se", "the", "of", "and", "a", "an", "in", "on", "with", "to", "is", "cena", "scene",
    )

    fun normalize(s: String): String =
        Normalizer.normalize(s.lowercase(), Normalizer.Form.NFD).replace(Regex("\\p{M}+"), "")

    fun tokens(s: String): List<String> =
        normalize(s).split(Regex("[^a-z0-9]+")).filter { it.length > 1 && it !in STOP }

    /** Ranked keys of documents matching the query; whole-word matches count double, word starts count once. */
    fun search(query: String, docs: Map<String, String>, limit: Int = 60): List<String> {
        val q = tokens(query).distinct()
        if (q.isEmpty()) return emptyList()
        val scored = docs.mapNotNull { (key, text) ->
            val words = tokens(text).toSet()
            var score = 0.0
            var matched = 0
            for (t in q) {
                when {
                    t in words -> { score += 2.0; matched++ }
                    t.length >= 4 && words.any { it.startsWith(t) || (it.length >= 4 && t.startsWith(it)) } -> { score += 1.0; matched++ }
                }
            }
            // most of what was typed must match: one common word ("parada", "cena") is not a match
            val needed = if (q.size <= 2) 1 else (q.size + 1) / 2
            if (matched < needed) null else key to (score + matched.toDouble() / q.size * 3)
        }
        return scored.sortedByDescending { it.second }.take(limit).map { it.first }
    }
}
