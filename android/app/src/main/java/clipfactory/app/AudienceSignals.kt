package clipfactory.app

import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONObject
import java.util.concurrent.TimeUnit
import kotlin.math.ln

/**
 * What the audience marked: timestamps viewers wrote in the comments of a YouTube video ("12:34 KKKKK"), weighted by
 * likes. Official YouTube Data API (1 unit per 100 comments). Returns (second, weight, an example comment).
 */
object AudienceSignals {
    data class Mark(val t: Double, val weight: Double, val sample: String)

    private val http = OkHttpClient.Builder().callTimeout(30, TimeUnit.SECONDS).build()
    private val REACTION = Regex("(?i)k{3,}|(ha){2,}|rs{2,}|😂|🤣|💀|😭|mano|kkk|n[aã]o (tankei|aguentei)|morri")
    private val TS = Regex("(?<![\\d:])(?:(\\d{1,2}):)?(\\d{1,2}):(\\d{2})(?![\\d:])")

    /** The video id in a YouTube link (watch, youtu.be, live, shorts), or null. */
    fun youtubeId(link: String): String? {
        val l = link.trim()
        Regex("(?:v=|youtu\\.be/|/live/|/shorts/|/embed/)([\\w-]{11})").find(l)?.let { return it.groupValues[1] }
        return if (Regex("[\\w-]{11}").matches(l)) l else null
    }

    fun youtubeComments(key: String, videoId: String, duration: Double, maxPages: Int = 5): List<Mark> {
        val raw = ArrayList<Mark>()
        var page: String? = null
        for (p in 0 until maxPages) {
            val url = "https://www.googleapis.com/youtube/v3/commentThreads".toHttpUrl().newBuilder()
                .addQueryParameter("part", "snippet").addQueryParameter("videoId", videoId)
                .addQueryParameter("order", "relevance").addQueryParameter("maxResults", "100")
                .addQueryParameter("textFormat", "plainText").addQueryParameter("key", key.trim())
                .apply { page?.let { addQueryParameter("pageToken", it) } }.build()
            val js = http.newCall(Request.Builder().url(url).build()).execute().use { r ->
                if (!r.isSuccessful) return raw.takeIf { it.isNotEmpty() }?.let { cluster(it) } ?: emptyList()
                JSONObject(r.body?.string().orEmpty())
            }
            val items = js.optJSONArray("items") ?: break
            for (i in 0 until items.length()) {
                val sn = items.getJSONObject(i).getJSONObject("snippet").getJSONObject("topLevelComment").getJSONObject("snippet")
                val text = sn.optString("textOriginal")
                val likes = sn.optInt("likeCount", 0)
                // a reaction ("kkkk", 😂, "mano") means a moment; times in a thank-you or schedule are clock hours
                val reacts = REACTION.containsMatchIn(text)
                val stamps = TS.findAll(text).toList()
                for (m in stamps) {
                    if (text.substring(0, m.range.first).trimEnd().endsWith("às", true)) continue
                    val h = m.groupValues[1].toDoubleOrNull() ?: 0.0
                    val t = h * 3600 + m.groupValues[2].toDouble() * 60 + m.groupValues[3].toDouble()
                    val w = (1 + ln(1.0 + likes)) * (if (reacts) 1.5 else 0.6) / (if (stamps.size > 4) 3 else 1)
                    if (t in 1.0..duration) raw.add(Mark(t, w, text.take(120)))
                }
            }
            page = js.optString("nextPageToken").ifBlank { null } ?: break
        }
        return cluster(raw)
    }

    /** Timestamps within 15 s of each other are the same moment. */
    private fun cluster(marks: List<Mark>): List<Mark> {
        val out = ArrayList<Mark>()
        for (m in marks.sortedBy { it.t }) {
            val last = out.lastOrNull()
            if (last != null && m.t - last.t <= 15) {
                out[out.size - 1] = Mark(last.t, last.weight + m.weight, if (m.weight > last.weight) m.sample else last.sample)
            } else out.add(m)
        }
        return out.sortedByDescending { it.weight }
    }
}
