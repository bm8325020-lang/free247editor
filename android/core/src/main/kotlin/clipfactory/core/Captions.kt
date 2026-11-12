package clipfactory.core

/** One caption state on screen: a group of words with one of them highlighted. */
data class CaptionEvent(val start: Double, val end: Double, val words: List<String>, val active: Int, val pop: Boolean)

/** Word-by-word caption timing; a port of tools/clipper/captions.py. Times are relative to the clip start. */
object Captions {
    private val STRIP_LEAD = Regex("^[\"'“”‘’(\\[]+")
    private val STRIP_TRAIL = Regex("[\"'“”‘’)\\],.;:…]+$")
    private val BREAK = Regex("[.!?,;:…]$")

    fun clean(text: String, upper: Boolean = true): String {
        val t = STRIP_TRAIL.replace(STRIP_LEAD.replace(text.replace(Regex("[{}\\\\]"), ""), ""), "")
        return if (upper) t.uppercase() else t
    }

    fun build(
        words: List<Word>, clipStart: Double, clipEnd: Double,
        maxWords: Int = 3, maxChars: Int = 16, upper: Boolean = true,
    ): List<CaptionEvent> {
        data class Item(val t: String, val s: Double, val e: Double, val brk: Boolean)

        val items = words.filter { it.start >= clipStart - 0.05 && it.start < clipEnd }.mapNotNull { w ->
            val t = clean(w.text, upper)
            if (t.isEmpty()) null
            else Item(t, (w.start - clipStart).coerceAtLeast(0.0), (w.end - clipStart).coerceAtMost(clipEnd - clipStart),
                BREAK.containsMatchIn(w.text))
        }
        val groups = ArrayList<List<Item>>()
        var cur = ArrayList<Item>()
        for (it in items) {
            if (cur.isNotEmpty()) {
                val chars = cur.sumOf { x -> x.t.length + 1 } + it.t.length
                val gap = it.s - cur.last().e
                if (cur.size >= maxWords || chars > maxChars || gap > 0.35 || cur.last().brk) {
                    groups.add(cur)
                    cur = ArrayList()
                }
            }
            cur.add(it)
        }
        if (cur.isNotEmpty()) groups.add(cur)
        val out = ArrayList<CaptionEvent>()
        for ((gi, g) in groups.withIndex()) {
            var gEnd = g.last().e + 0.2
            if (gi + 1 < groups.size) gEnd = minOf(gEnd, groups[gi + 1][0].s)
            for (k in g.indices) {
                val s = if (k == 0) g[0].s else g[k].s
                val e = if (k + 1 < g.size) g[k + 1].s else gEnd
                if (e - s < 0.02) continue
                out.add(CaptionEvent(s, e, g.map { it.t }, k, k == 0))
            }
        }
        return out
    }

    /** Index of the event showing at time t (seconds from clip start), or -1. Events are sorted by start. */
    fun at(events: List<CaptionEvent>, t: Double): Int {
        var lo = 0
        var hi = events.size - 1
        var ans = -1
        while (lo <= hi) {
            val mid = (lo + hi) / 2
            if (events[mid].start <= t) {
                ans = mid
                lo = mid + 1
            } else hi = mid - 1
        }
        return if (ans >= 0 && t < events[ans].end) ans else -1
    }
}
