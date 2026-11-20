package clipfactory.app

import android.content.Context
import clipfactory.core.Word
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import org.json.JSONArray
import org.json.JSONObject
import java.io.File

/** What the user can change in one clip with the editor. */
data class ClipEdit(val start: Double, val end: Double, val hook: String, val style: String, val layout: String)

/** A moment the analysis found (a candidate clip). */
data class Moment(
    val id: Int, val start: Double, val end: Double, val peak: Double, val title: String, val hook: String,
    val reason: String, val score: Int, val text: String, val thumb: String, val preselected: Boolean,
)

/** A clip being made (or made) from a moment. */
data class PClip(
    val momentId: Int, val title: String, val score: Int, val reason: String, val edit: ClipEdit,
    val uri: String = "", val status: String = "pending", // pending | rendering | done | failed
    val error: String = "", val layoutName: String = "",
    val posted: Long = 0, // when the user posted it (0 = not yet)
) {
    val seconds get() = edit.end - edit.start
}

data class Project(
    val id: String, val name: String, val source: String, val createdAt: Long,
    val width: Int = 0, val height: Int = 0, val durationS: Double = 0.0, val language: String? = null,
    val words: List<Word> = emptyList(), val moments: List<Moment> = emptyList(), val clips: List<PClip> = emptyList(),
    val notes: List<String> = emptyList(), val folder: String = "",
    val status: String = "analyzing", // analyzing | choosing | rendering | done | failed | canceled
) {
    val doneClips get() = clips.filter { it.status == "done" && it.uri.isNotEmpty() }
}

/** Projects live in the app's private storage, one folder each (project.json + moment thumbnails). */
object ProjectStore {
    private val state = MutableStateFlow<List<Project>>(emptyList())
    val projects: StateFlow<List<Project>> get() = state
    private var loaded = false

    fun dir(ctx: Context, id: String) = File(File(ctx.filesDir, "projects"), id).apply { mkdirs() }

    @Synchronized
    fun load(ctx: Context) {
        if (loaded) return
        loaded = true
        val root = File(ctx.filesDir, "projects")
        state.value = (root.listFiles() ?: emptyArray()).mapNotNull { d ->
            runCatching { fromJson(JSONObject(File(d, "project.json").readText())) }.getOrNull()
        }.map {
            // work that was running when the app was closed cannot resume mid-way
            if (it.status in setOf("analyzing", "rendering")) it.copy(status = if (it.doneClips.isEmpty()) "failed" else "done",
                clips = it.clips.map { c -> if (c.status == "rendering") c.copy(status = "pending") else c })
            else it
        }.sortedByDescending { it.createdAt }
    }

    fun get(id: String) = state.value.firstOrNull { it.id == id }

    @Synchronized
    fun put(ctx: Context, p: Project) {
        state.value = (listOf(p) + state.value.filter { it.id != p.id }).sortedByDescending { it.createdAt }
        val f = File(dir(ctx, p.id), "project.json")
        val tmp = File(f.path + ".tmp")
        tmp.writeText(toJson(p).toString())
        tmp.renameTo(f)
    }

    @Synchronized
    fun update(ctx: Context, id: String, f: (Project) -> Project) {
        get(id)?.let { put(ctx, f(it)) }
    }

    @Synchronized
    fun delete(ctx: Context, id: String) {
        // a bot clip's raw cut lives in the app's files: it goes with the project
        get(id)?.source?.let { src ->
            if (src.startsWith("file:") && "/raw/" in src) runCatching { java.io.File(android.net.Uri.parse(src).path!!).delete() }
        }
        state.value = state.value.filter { it.id != id }
        dir(ctx, id).deleteRecursively()
    }

    private fun toJson(p: Project) = JSONObject().apply {
        put("id", p.id); put("name", p.name); put("source", p.source); put("createdAt", p.createdAt)
        put("width", p.width); put("height", p.height); put("durationS", p.durationS); put("language", p.language ?: "")
        put("folder", p.folder); put("status", p.status); put("notes", JSONArray(p.notes))
        put("words", JSONArray().apply { p.words.forEach { put(JSONArray(listOf(it.text, it.start, it.end))) } })
        put("moments", JSONArray().apply {
            p.moments.forEach { m ->
                put(JSONObject().apply {
                    put("id", m.id); put("start", m.start); put("end", m.end); put("peak", m.peak); put("title", m.title)
                    put("hook", m.hook); put("reason", m.reason); put("score", m.score); put("text", m.text)
                    put("thumb", m.thumb); put("preselected", m.preselected)
                })
            }
        })
        put("clips", JSONArray().apply {
            p.clips.forEach { c ->
                put(JSONObject().apply {
                    put("momentId", c.momentId); put("title", c.title); put("score", c.score); put("reason", c.reason)
                    put("uri", c.uri); put("status", c.status); put("error", c.error); put("layoutName", c.layoutName)
                    put("posted", c.posted)
                    put("edit", JSONObject().apply {
                        put("start", c.edit.start); put("end", c.edit.end); put("hook", c.edit.hook)
                        put("style", c.edit.style); put("layout", c.edit.layout)
                    })
                })
            }
        })
    }

    private fun fromJson(o: JSONObject): Project {
        val ws = o.optJSONArray("words") ?: JSONArray()
        val ms = o.optJSONArray("moments") ?: JSONArray()
        val cs = o.optJSONArray("clips") ?: JSONArray()
        val ns = o.optJSONArray("notes") ?: JSONArray()
        return Project(
            o.getString("id"), o.optString("name"), o.optString("source"), o.optLong("createdAt"),
            o.optInt("width"), o.optInt("height"), o.optDouble("durationS", 0.0), o.optString("language").ifBlank { null },
            (0 until ws.length()).map { val w = ws.getJSONArray(it); Word(w.getString(0), w.getDouble(1), w.getDouble(2)) },
            (0 until ms.length()).map {
                val m = ms.getJSONObject(it)
                Moment(m.getInt("id"), m.getDouble("start"), m.getDouble("end"), m.getDouble("peak"), m.optString("title"),
                    m.optString("hook"), m.optString("reason"), m.optInt("score"), m.optString("text"), m.optString("thumb"),
                    m.optBoolean("preselected"))
            },
            (0 until cs.length()).map {
                val c = cs.getJSONObject(it)
                val e = c.getJSONObject("edit")
                PClip(c.getInt("momentId"), c.optString("title"), c.optInt("score"), c.optString("reason"),
                    ClipEdit(e.getDouble("start"), e.getDouble("end"), e.optString("hook"), e.optString("style", "amarelo"),
                        e.optString("layout", "auto")),
                    c.optString("uri"), c.optString("status", "pending"), c.optString("error"), c.optString("layoutName"),
                    c.optLong("posted", 0))
            },
            (0 until ns.length()).map { ns.getString(it) },
            o.optString("folder"), o.optString("status", "done"),
        )
    }
}
