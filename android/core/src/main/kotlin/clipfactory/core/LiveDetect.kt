package clipfactory.core

import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sqrt

/**
 * When is a live moment worth a clip? A port of tools/clipper/live.py (the PC/server bot): the chat compared with
 * that channel's own normal (bursts of messages, KEKW, kkkk, "clipa"...) and the audio compared with the live's own
 * recent level. Both are robust z-scores (median / MAD), so a loud streamer or a fast chat does not fire all the time.
 */
object LiveDetect {
    val EMOTES = mapOf(
        "kekw" to 1.5, "lul" to 1.0, "lol" to 1.0, "omegalul" to 2.0, "lmao" to 1.5, "icant" to 1.5, "pog" to 1.0,
        "pogchamp" to 1.0, "pogu" to 1.0, "poggers" to 1.0, "monkas" to 1.0, "monkaw" to 1.5, "w" to 0.5, "wwww" to 1.0,
        "clip" to 3.0, "clipa" to 3.0, "clipped" to 2.0, "kkkk" to 1.5, "kkkkk" to 1.5, "kkkkkk" to 2.0, "omg" to 1.0,
        "wtf" to 1.0, "nooo" to 1.0, "????" to 1.0, "aaaa" to 1.0, "sadge" to 0.5, "pepelaugh" to 1.5, "kappa" to 0.3,
        "wasted" to 2.0, "busted" to 2.0, "gg" to 0.5,
    )
    private val TOKEN = Regex("[\\p{L}\\p{N}_?!]+")
    private val LAUGH = Regex("k{4,}|(ha){3,}|\\?{3,}|w{4,}")

    /** Chat bots: their bursts (giveaways, sub alerts) are not moments. */
    val BOTS = setOf("nightbot", "streamelements", "streamlabs", "moobot", "fossabot", "wizebot", "soundalerts",
        "botisimo", "sery_bot", "kofistreambot", "pokemoncommunitygame", "blerp", "own3d", "streamstickers", "tangiabot")

    /** How much a message says "something just happened": reactions count, plain talk barely does, commands and bots not at all. */
    fun messageWeight(text: String, nick: String = ""): Double {
        if (nick.lowercase() in BOTS || text.trimStart().startsWith("!")) return 0.0
        var w = 0.25 // a big chat is always busy: the burst that matters is of reactions
        for (t in TOKEN.findAll(text.lowercase()).map { it.value }) {
            val e = EMOTES[t]
            if (e != null) w += e else if (LAUGH.matches(t)) w += 1.5
        }
        return min(w, 6.0)
    }

    /** How far the last `recent` values sit above the preceding window; never trusts a spread below Poisson noise. */
    fun robustZ(x: DoubleArray, window: Int, recent: Int = 3): Double {
        if (x.size < 30 + recent) return 0.0
        val base = if (x.size > window + recent) x.copyOfRange(x.size - window - recent, x.size - recent)
                   else x.copyOfRange(0, x.size - recent)
        val med = Stats.median(base)
        val mad = Stats.median(base.map { abs(it - med) }.toDoubleArray()) * 1.4826
        val scale = max(max(mad, sqrt(max(med, 0.0))), 1.0)
        return ((x.size - recent until x.size).sumOf { x[it] } / recent - med) / scale
    }

    /** Seconds ago that the current chat burst began (the chat types after the scene). */
    fun chatOnset(x: DoubleArray, window: Int = 240, maxBack: Int = 20): Int {
        if (x.size < 30) return 0
        val base = if (x.size > window + 15) x.copyOfRange(x.size - window - 15, x.size - 15) else x.copyOfRange(0, x.size - 15)
        val med = Stats.median(base)
        val scale = max(max(Stats.median(base.map { abs(it - med) }.toDoubleArray()) * 1.4826, sqrt(max(med, 0.0))), 1.0)
        var onset = 0
        var dips = 0
        for (back in 1..min(maxBack, x.size)) {
            if (x[x.size - back] > med + scale) { onset = back - 1; dips = 0 } else if (++dips > 1) break
        }
        return onset
    }

    /** Audio: loudness (dB) per second, oldest first. z of the loudest of the last `recent` seconds (3-s smoothing). */
    fun audioZ(secs: DoubleArray, recent: Int = 3): Pair<Double, Int> {
        if (secs.size < 120 + recent) return 0.0 to 0
        val sm = DoubleArray(secs.size) { i -> (max(0, i - 1)..min(secs.size - 1, i + 1)).map { secs[it] }.average() }
        val base = secs.copyOfRange(max(0, secs.size - recent - 120), secs.size - recent)
        val med = Stats.median(base)
        val scale = max(Stats.median(base.map { abs(it - med) }.toDoubleArray()) * 1.4826, 1.0)
        // the exact second from the raw loudness (smoothing shifts a peak at the edge); the strength from the smoothed
        val ago = (0 until recent).maxByOrNull { secs[secs.size - 1 - it] } ?: 0
        return (sm[secs.size - 1 - ago] - med) / scale to ago
    }

    /** The same rule as the server bot: weighted score over the threshold, or one signal very strong on its own. */
    fun fires(chatZ: Double, audioZ: Double, threshold: Double): Boolean =
        0.6 * max(chatZ, 0.0) + 0.4 * max(audioZ, 0.0) >= threshold || chatZ >= 6 || audioZ >= 5
}
