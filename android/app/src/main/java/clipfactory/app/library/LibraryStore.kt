package clipfactory.app.library

import android.content.Context
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import org.json.JSONArray
import org.json.JSONObject
import java.io.File

data class LibScene(
    val idx: Int, val start: Double, val end: Double, val thumb: String,
    val desc: String = "", val speech: String = "",
)

data class LibVideo(
    val id: String, val title: String, val channel: String, val url: String, val durationS: Double,
    val thumbUrl: String = "",
    val status: String = "queued", // queued | downloading | scanning | describing | ready | failed | canceled
    val progress: Double = 0.0, val error: String = "", val file: String = "",
    val scenes: List<LibScene> = emptyList(), val addedAt: Long = System.currentTimeMillis(),
)

/** The app's download folder and scene index. Everything lives in the app's own storage. */
object LibraryStore {
    private val state = MutableStateFlow<List<LibVideo>>(emptyList())
    val videos: StateFlow<List<LibVideo>> get() = state
    private var loaded = false

    fun dir(ctx: Context): File = File(ctx.getExternalFilesDir(null) ?: ctx.filesDir, "biblioteca").apply { mkdirs() }
    private fun file(ctx: Context) = File(dir(ctx), "library.json")

    @Synchronized
    fun load(ctx: Context) {
        if (loaded) return
        loaded = true
        state.value = try {
            val arr = JSONArray(file(ctx).readText())
            (0 until arr.length()).map { fromJson(arr.getJSONObject(it)) }.map {
                // anything that was running when the app was closed goes back to the queue
                if (it.status in setOf("downloading", "scanning", "describing")) it.copy(status = "queued", progress = 0.0) else it
            }
        } catch (e: Exception) {
            emptyList()
        }
    }

    @Synchronized
    fun update(ctx: Context, id: String, f: (LibVideo) -> LibVideo) {
        state.value = state.value.map { if (it.id == id) f(it) else it }
        save(ctx)
    }

    @Synchronized
    fun add(ctx: Context, v: List<LibVideo>) {
        val have = state.value.map { it.id }.toSet()
        state.value = state.value + v.filter { it.id !in have }
        save(ctx)
    }

    @Synchronized
    fun remove(ctx: Context, id: String) {
        state.value.firstOrNull { it.id == id }?.let { v ->
            if (v.file.isNotEmpty()) File(v.file).delete()
            File(dir(ctx), "thumbs/$id").deleteRecursively()
        }
        state.value = state.value.filter { it.id != id }
        save(ctx)
    }

    fun get(id: String) = state.value.firstOrNull { it.id == id }

    private fun save(ctx: Context) {
        val arr = JSONArray()
        state.value.forEach { arr.put(toJson(it)) }
        val tmp = File(file(ctx).path + ".tmp")
        tmp.writeText(arr.toString())
        tmp.renameTo(file(ctx))
    }

    private fun toJson(v: LibVideo) = JSONObject().apply {
        put("id", v.id); put("title", v.title); put("channel", v.channel); put("url", v.url)
        put("durationS", v.durationS); put("thumbUrl", v.thumbUrl); put("status", v.status)
        put("progress", v.progress); put("error", v.error); put("file", v.file); put("addedAt", v.addedAt)
        put("scenes", JSONArray().apply {
            v.scenes.forEach { s ->
                put(JSONObject().apply {
                    put("idx", s.idx); put("start", s.start); put("end", s.end); put("thumb", s.thumb)
                    put("desc", s.desc); put("speech", s.speech)
                })
            }
        })
    }

    private fun fromJson(o: JSONObject): LibVideo {
        val sc = o.optJSONArray("scenes") ?: JSONArray()
        return LibVideo(
            o.getString("id"), o.optString("title"), o.optString("channel"), o.optString("url"),
            o.optDouble("durationS", 0.0), o.optString("thumbUrl"), o.optString("status", "queued"),
            o.optDouble("progress", 0.0), o.optString("error"), o.optString("file"),
            (0 until sc.length()).map {
                val s = sc.getJSONObject(it)
                LibScene(s.getInt("idx"), s.getDouble("start"), s.getDouble("end"), s.optString("thumb"),
                    s.optString("desc"), s.optString("speech"))
            },
            o.optLong("addedAt", System.currentTimeMillis()),
        )
    }
}
