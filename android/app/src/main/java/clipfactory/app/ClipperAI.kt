package clipfactory.app

import android.util.Log
import clipfactory.core.Word
import org.json.JSONObject
import kotlin.math.max
import kotlin.math.min

/**
 * Reads the whole transcript the way a clipper watches a stream: in 10-minute blocks, it marks what would go viral
 * on its own (funny, absurd, epic, rage, scare, wholesome), even when nobody screams. Uses the free Coarena models.
 */
object ClipperAI {
    data class Pick(val start: Double, val end: Double, val title: String, val why: String, val score: Int, val kind: String)

    private fun mmss(t: Double) = "%d:%02d".format((t / 60).toInt(), (t % 60).toInt())

    private fun parseTime(s: String): Double? {
        val p = s.trim().split(":").map { it.toDoubleOrNull() ?: return null }
        return when (p.size) { 1 -> p[0]; 2 -> p[0] * 60 + p[1]; 3 -> p[0] * 3600 + p[1] * 60 + p[2]; else -> null }
    }

    /** Transcript lines of ~8 s each: "[12:34] what was said". */
    private fun lines(words: List<Word>, from: Double, to: Double): String {
        val sb = StringBuilder()
        var lineStart = -1.0
        val line = StringBuilder()
        for (w in words) {
            if (w.start < from || w.start >= to) continue
            if (lineStart < 0) lineStart = w.start
            line.append(w.text).append(' ')
            if (w.start - lineStart >= 8 || w.text.endsWith(".") || w.text.endsWith("?") || w.text.endsWith("!")) {
                sb.append("[").append(mmss(lineStart)).append("] ").append(line.toString().trim()).append('\n')
                line.setLength(0); lineStart = -1.0
            }
        }
        if (line.isNotEmpty()) sb.append("[").append(mmss(lineStart)).append("] ").append(line.toString().trim()).append('\n')
        return sb.toString()
    }

    /**
     * All the moments the AI marks, across the whole video. [hints]: audio spikes and audience timestamps the AI should
     * look at closely (it still decides). [report] gets 0..1.
     */
    fun find(key: String, words: List<Word>, duration: Double, language: String?, hints: List<Pair<Double, String>>,
             report: (Double) -> Unit): List<Pick> {
        if (key.isBlank() || words.isEmpty()) return emptyList()
        val out = ArrayList<Pick>()
        val block = 600.0
        val blocks = generateSequence(0.0) { it + block }.takeWhile { it < duration }.toList()
        for ((i, from) in blocks.withIndex()) {
            report(i.toDouble() / blocks.size)
            val to = min(duration, from + block + 45) // a little overlap so a moment at the edge is not cut in two
            val text = lines(words, from, to)
            if (text.length < 80) continue // nothing said in this block
            val near = hints.filter { it.first in from..to }.joinToString("\n") { "- ${mmss(it.first)}: ${it.second}" }
            val prompt = """You are a top Brazilian clipper of gaming lives and videos (GTA RP, GTA 6, Minecraft, etc.).
Below is the transcript of minutes ${mmss(from)} to ${mmss(to)} of a stream, with timestamps.
Find the moments a good clipper would cut for TikTok/Reels/Shorts: things that would go viral ON THEIR OWN in
15 to 60 seconds. Funny lines and jokes (even said calmly), absurd or chaotic situations, epic plays, fails, rage,
scares, savage replies, wholesome moments, a short story with a punchline. Include the setup before the punchline
and the reaction after. Skip ads, sub/donation thanks, dead talk, and things that only make sense with context.
Be selective: at most 4 moments in this block, and none if nothing is good. Score honestly (90+ = sure to go viral).
${if (near.isNotEmpty()) "Signals worth checking (audio spikes, viewers' timestamps):\n$near\n" else ""}
For each moment give: start and end (mm:ss, from the transcript times), a punchy title (max 6 words, in
${language ?: "the language of the transcript"}, no hashtags, no emoji), why (one short sentence), score 1-100,
kind (engraçado, absurdo, foda, raiva, susto, emocionante or outro).
Answer with ONLY a JSON object: {"moments":[{"start":"12:30","end":"13:05","title":"...","why":"...","score":85,"kind":"engraçado"}]}

Transcript:
$text"""
            val picks = ask(key, prompt) ?: continue
            for (p in picks) {
                val a = p.start.coerceIn(from - 30, to)
                val b = p.end.coerceIn(a + 5, min(duration, a + 90))
                if (b - a >= 5) out.add(p.copy(start = max(0.0, a), end = b))
            }
        }
        report(1.0)
        // the overlap can mark the same moment twice: keep the better one
        val kept = ArrayList<Pick>()
        for (p in out.sortedByDescending { it.score }) {
            if (kept.none { min(it.end, p.end) - max(it.start, p.start) > 0.5 * min(it.end - it.start, p.end - p.start) }) kept.add(p)
        }
        return kept.sortedBy { it.start }
    }

    private fun ask(key: String, prompt: String): List<Pick>? {
        // DeepSeek found more of the moments in a test (the quiet joke and the epic one); then the others
        for (model in listOf("deepseek-v4-1-flash-1017", "gpt-6-luna", "gemini-3-8-flash")) {
            try {
                val text = CoarenaPicker.complete(key.trim(), model, prompt)
                val json = Regex("\\{.*\\}", RegexOption.DOT_MATCHES_ALL).find(text)?.value ?: continue
                val arr = JSONObject(json).getJSONArray("moments")
                return (0 until arr.length()).mapNotNull { i ->
                    val o = arr.getJSONObject(i)
                    val a = parseTime(o.optString("start")) ?: return@mapNotNull null
                    val b = parseTime(o.optString("end")) ?: return@mapNotNull null
                    if (b <= a) return@mapNotNull null
                    Pick(a, b, o.optString("title").trim().trim('"'), o.optString("why").trim(),
                        o.optInt("score", 60).coerceIn(1, 100), o.optString("kind", "outro"))
                }
            } catch (e: Exception) {
                Log.w("ClipperAI", "$model failed: ${e.javaClass.simpleName}")
            }
        }
        return null
    }
}
