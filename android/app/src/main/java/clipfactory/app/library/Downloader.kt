package clipfactory.app.library

import android.content.Context
import com.yausername.ffmpeg.FFmpeg
import com.yausername.youtubedl_android.YoutubeDL
import com.yausername.youtubedl_android.YoutubeDLRequest
import org.json.JSONObject
import java.io.File

data class SearchHit(val id: String, val title: String, val channel: String, val durationS: Double,
                     val url: String, val thumbUrl: String)

/** yt-dlp inside the app: search and download in the best quality the phone can play. */
object Downloader {
    @Volatile private var ready = false

    @Synchronized
    fun init(ctx: Context) {
        if (ready) return
        YoutubeDL.init(ctx.applicationContext)
        FFmpeg.init(ctx.applicationContext)
        ready = true
    }

    fun search(ctx: Context, query: String, count: Int = 25): List<SearchHit> {
        init(ctx)
        val req = YoutubeDLRequest("ytsearch$count:$query")
        req.addOption("--flat-playlist")
        req.addOption("--dump-json")
        req.addOption("--no-warnings")
        val resp = YoutubeDL.execute(req, null, null)
        return resp.out.lines().filter { it.startsWith("{") }.mapNotNull { line ->
            runCatching {
                val o = JSONObject(line)
                val id = o.getString("id")
                val thumbs = o.optJSONArray("thumbnails")
                val thumb = if (thumbs != null && thumbs.length() > 0) thumbs.getJSONObject(thumbs.length() - 1).optString("url") else ""
                SearchHit(id, o.optString("title"), o.optString("channel", o.optString("uploader")),
                    o.optDouble("duration", 0.0), o.optString("url").ifBlank { "https://www.youtube.com/watch?v=$id" }, thumb)
            }.getOrNull()
        }
    }

    /**
     * Best quality up to 4K, avoiding AV1 (many phones cannot decode it), merged into an MP4.
     * Returns the downloaded file.
     */
    fun download(ctx: Context, url: String, id: String, processId: String, onProgress: (Double) -> Unit): File {
        init(ctx)
        val dir = File(LibraryStore.dir(ctx), "videos").apply { mkdirs() }
        val req = YoutubeDLRequest(url)
        req.addOption("--no-playlist")
        req.addOption("-f", "bv*[vcodec!^=av01][height<=2160]+ba/b[vcodec!^=av01][height<=2160]/bv*+ba/b")
        req.addOption("--merge-output-format", "mp4")
        req.addOption("-o", File(dir, "$id.%(ext)s").absolutePath)
        req.addOption("--no-mtime")
        YoutubeDL.execute(req, processId) { pct, _, _ -> if (pct >= 0) onProgress(pct / 100.0) }
        return dir.listFiles { f -> f.name.startsWith("$id.") && !f.name.endsWith(".part") && !f.name.contains(".f") }
            ?.maxByOrNull { it.length() } ?: throw IllegalStateException("O download terminou mas o arquivo não apareceu.")
    }

    fun cancel(processId: String) = runCatching { YoutubeDL.destroyProcessById(processId) }

    fun update(ctx: Context): String {
        init(ctx)
        val status = YoutubeDL.updateYoutubeDL(ctx, YoutubeDL.UpdateChannel.STABLE)
        return "${status?.name ?: "ok"} (${YoutubeDL.version(ctx) ?: "?"})"
    }
}
