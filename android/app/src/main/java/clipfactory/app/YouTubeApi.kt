package clipfactory.app

import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONObject
import java.time.Duration
import java.time.Instant
import java.util.concurrent.TimeUnit

/**
 * YouTube Data API v3 (official, read-only metadata: channels, videos, view counts). It does not give access to the
 * video files. Default quota: 10,000 units a day per key; a search costs 100 units, everything else here costs 1.
 */
object YouTubeApi {
    private const val BASE = "https://www.googleapis.com/youtube/v3/"
    private val http = OkHttpClient.Builder().callTimeout(30, TimeUnit.SECONDS).build()

    data class Channel(val id: String, val title: String, val thumb: String, val subs: Long?, val views: Long?,
                       val videos: Long?, val uploads: String)

    data class Video(val id: String, val title: String, val channelId: String, val channelTitle: String, val thumb: String,
                     val publishedAt: Long, val views: Long?, val likes: Long?, val comments: Long?, val seconds: Long,
                     val live: String, // none | live | upcoming
                     val lang: String) { // spoken language the creator declared ("" when not declared)
        /** Portuguese, or not declared (kept: many Brazilian videos declare nothing). */
        val portuguese get() = lang.isEmpty() || lang.startsWith("pt")
    }

    class ApiError(msg: String) : Exception(msg)

    private fun get(key: String, path: String, vararg params: Pair<String, String>): JSONObject {
        val url = (BASE + path).toHttpUrl().newBuilder().apply {
            params.forEach { (k, v) -> addQueryParameter(k, v) }
            addQueryParameter("key", key.trim())
        }.build()
        http.newCall(Request.Builder().url(url).build()).execute().use { r ->
            val body = JSONObject(r.body?.string().orEmpty().ifBlank { "{}" })
            if (!r.isSuccessful) {
                val e = body.optJSONObject("error")
                val reason = e?.optJSONArray("errors")?.optJSONObject(0)?.optString("reason").orEmpty()
                throw ApiError(when {
                    reason == "quotaExceeded" -> "A cota grátis do YouTube de hoje acabou. Volta amanhã (zera de madrugada)."
                    r.code == 400 && reason.contains("key", true) -> "Chave do YouTube inválida. Confira em Configurações."
                    r.code == 403 -> "O YouTube recusou (${reason.ifBlank { "403" }}). Confira se a YouTube Data API v3 está ativada."
                    else -> "Erro do YouTube ${r.code}: ${e?.optString("message")?.take(120)}"
                })
            }
            return body
        }
    }

    /** 100 units. */
    fun searchChannels(key: String, q: String): List<Channel> {
        val ids = get(key, "search", "part" to "snippet", "type" to "channel", "q" to q, "regionCode" to "BR",
            "relevanceLanguage" to "pt", "maxResults" to "10").getJSONArray("items").let { a ->
            (0 until a.length()).map { a.getJSONObject(it).getJSONObject("snippet").getString("channelId") }
        }
        return channels(key, ids)
    }

    /** 1 unit. */
    fun channels(key: String, ids: List<String>): List<Channel> {
        if (ids.isEmpty()) return emptyList()
        val a = get(key, "channels", "part" to "snippet,statistics,contentDetails", "id" to ids.joinToString(","))
            .optJSONArray("items") ?: return emptyList()
        val byId = (0 until a.length()).map { a.getJSONObject(it) }.associateBy { it.getString("id") }
        return ids.mapNotNull { byId[it] }.map { o ->
            val sn = o.getJSONObject("snippet")
            val st = o.optJSONObject("statistics")
            Channel(o.getString("id"), sn.getString("title"), thumb(sn),
                if (st?.optBoolean("hiddenSubscriberCount") == true) null else st?.optString("subscriberCount")?.toLongOrNull(),
                st?.optString("viewCount")?.toLongOrNull(), st?.optString("videoCount")?.toLongOrNull(),
                o.optJSONObject("contentDetails")?.optJSONObject("relatedPlaylists")?.optString("uploads").orEmpty())
        }
    }

    /** 100 units. order: date | viewCount | relevance. */
    fun searchVideos(key: String, q: String, order: String, publishedAfter: Instant?, channelId: String? = null): List<Video> {
        val params = arrayListOf("part" to "snippet", "type" to "video", "q" to q, "order" to order, "regionCode" to "BR",
            "relevanceLanguage" to "pt", "maxResults" to "50") // same 100 units as 25
        publishedAfter?.let { params.add("publishedAfter" to it.toString()) }
        channelId?.let { params.add("channelId" to it) }
        val a = get(key, "search", *params.toTypedArray()).getJSONArray("items")
        return videos(key, (0 until a.length()).map { a.getJSONObject(it).getJSONObject("id").getString("videoId") })
    }

    /** Latest uploads of a channel: 2 units (playlist + details) instead of a 100-unit search. */
    fun latest(key: String, uploads: String, max: Int = 15): List<Video> {
        if (uploads.isBlank()) return emptyList()
        val a = get(key, "playlistItems", "part" to "contentDetails", "playlistId" to uploads, "maxResults" to "$max")
            .getJSONArray("items")
        return videos(key, (0 until a.length()).map { a.getJSONObject(it).getJSONObject("contentDetails").getString("videoId") })
    }

    /** 1 unit for up to 50 videos. */
    fun videos(key: String, ids: List<String>): List<Video> {
        if (ids.isEmpty()) return emptyList()
        val a = get(key, "videos", "part" to "snippet,statistics,contentDetails", "id" to ids.take(50).joinToString(","))
            .optJSONArray("items") ?: return emptyList()
        return (0 until a.length()).map { a.getJSONObject(it) }.map { o ->
            val sn = o.getJSONObject("snippet")
            val st = o.optJSONObject("statistics")
            Video(o.getString("id"), sn.getString("title"), sn.getString("channelId"), sn.optString("channelTitle"), thumb(sn),
                Instant.parse(sn.getString("publishedAt")).toEpochMilli(), st?.optString("viewCount")?.toLongOrNull(),
                st?.optString("likeCount")?.toLongOrNull(), st?.optString("commentCount")?.toLongOrNull(),
                runCatching { Duration.parse(o.getJSONObject("contentDetails").getString("duration")).seconds }.getOrDefault(0),
                sn.optString("liveBroadcastContent", "none"),
                sn.optString("defaultAudioLanguage").ifBlank { sn.optString("defaultLanguage") }.lowercase())
        }
    }

    private fun thumb(sn: JSONObject): String {
        val t = sn.optJSONObject("thumbnails") ?: return ""
        for (k in listOf("high", "medium", "default")) t.optJSONObject(k)?.optString("url")?.let { if (it.isNotBlank()) return it }
        return ""
    }
}
