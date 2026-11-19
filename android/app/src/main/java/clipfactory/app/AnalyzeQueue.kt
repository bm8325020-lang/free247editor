package clipfactory.app

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject
import java.io.File

/** Videos waiting to be analysed, one after another (kept on disk so a restart does not lose them). */
object AnalyzeQueue {
    private fun file(ctx: Context) = File(ctx.filesDir, "queue.json")

    @Synchronized
    private fun read(ctx: Context): MutableList<Pair<String, String>> = runCatching {
        val a = JSONArray(file(ctx).readText())
        (0 until a.length()).map { a.getJSONObject(it).let { o -> o.getString("uri") to o.optString("name", "video") } }.toMutableList()
    }.getOrDefault(ArrayList())

    @Synchronized
    private fun write(ctx: Context, l: List<Pair<String, String>>) {
        file(ctx).writeText(JSONArray().apply { l.forEach { put(JSONObject().put("uri", it.first).put("name", it.second)) } }.toString())
    }

    @Synchronized
    fun add(ctx: Context, uri: String, name: String): Int {
        val l = read(ctx)
        if (l.none { it.first == uri }) l.add(uri to name)
        write(ctx, l)
        return l.size
    }

    @Synchronized
    fun pop(ctx: Context): Pair<String, String>? {
        val l = read(ctx)
        val first = l.firstOrNull() ?: return null
        write(ctx, l.drop(1))
        return first
    }

    @Synchronized fun size(ctx: Context) = read(ctx).size

    @Synchronized fun clear(ctx: Context) = write(ctx, emptyList())
}
