package clipfactory.app

import android.content.Context
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import kotlin.math.log10
import kotlin.math.max
import kotlin.math.roundToInt

/** A creator the user follows. `authorized` = the user says they may clip this creator (clipping program, deal). */
data class Creator(
    val id: String, val title: String, val thumb: String, val subs: Long?, val uploads: String,
    val authorized: Boolean = false, val authNote: String = "", val favorite: Boolean = true,
)

/** One reason behind an opportunity score. observed = measured from the API; otherwise it is an estimate. */
data class Factor(val label: String, val detail: String, val points: Int, val observed: Boolean)

data class Opportunity(val score: Int, val factors: List<Factor>)

/**
 * Discovery data kept on the phone: followed creators and view-count observations (to measure real growth, a
 * video needs at least two observations some time apart; one lookup alone says nothing about growth).
 */
object DiscoveryStore {
    private val creatorsState = MutableStateFlow<List<Creator>>(emptyList())
    val creators: StateFlow<List<Creator>> get() = creatorsState
    private val snaps = HashMap<String, MutableList<Pair<Long, Long>>>() // video id -> (time ms, views)
    private var loaded = false

    private fun dir(ctx: Context) = File(ctx.filesDir, "discovery").apply { mkdirs() }

    @Synchronized
    fun load(ctx: Context) {
        if (loaded) return
        loaded = true
        runCatching {
            val a = JSONArray(File(dir(ctx), "creators.json").readText())
            creatorsState.value = (0 until a.length()).map { a.getJSONObject(it) }.map {
                Creator(it.getString("id"), it.optString("title"), it.optString("thumb"),
                    if (it.has("subs") && !it.isNull("subs")) it.getLong("subs") else null, it.optString("uploads"),
                    it.optBoolean("authorized"), it.optString("authNote"), it.optBoolean("favorite", true))
            }
        }
        runCatching {
            val o = JSONObject(File(dir(ctx), "snapshots.json").readText())
            for (k in o.keys()) {
                val a = o.getJSONArray(k)
                snaps[k] = (0 until a.length()).map { val p = a.getJSONArray(it); p.getLong(0) to p.getLong(1) }.toMutableList()
            }
        }
    }

    @Synchronized
    fun saveCreator(ctx: Context, c: Creator) {
        creatorsState.value = listOf(c) + creatorsState.value.filter { it.id != c.id }
        writeCreators(ctx)
    }

    @Synchronized
    fun removeCreator(ctx: Context, id: String) {
        creatorsState.value = creatorsState.value.filter { it.id != id }
        writeCreators(ctx)
    }

    fun creator(id: String) = creatorsState.value.firstOrNull { it.id == id }

    private fun writeCreators(ctx: Context) {
        val a = JSONArray()
        creatorsState.value.forEach { c ->
            a.put(JSONObject().put("id", c.id).put("title", c.title).put("thumb", c.thumb).put("subs", c.subs ?: JSONObject.NULL)
                .put("uploads", c.uploads).put("authorized", c.authorized).put("authNote", c.authNote).put("favorite", c.favorite))
        }
        File(dir(ctx), "creators.json").writeText(a.toString())
    }

    /** Record what the API just said about these videos. */
    @Synchronized
    fun observe(ctx: Context, videos: List<YouTubeApi.Video>) {
        val now = System.currentTimeMillis()
        for (v in videos) {
            val views = v.views ?: continue
            val list = snaps.getOrPut(v.id) { ArrayList() }
            if (list.isEmpty() || now - list.last().first > 10 * 60_000) list.add(now to views) // at most one every 10 min
            while (list.size > 30) list.removeAt(0)
        }
        // forget videos not seen for 30 days
        snaps.entries.removeIf { now - it.value.last().first > 30L * 86_400_000 }
        val o = JSONObject()
        snaps.forEach { (k, l) -> o.put(k, JSONArray().apply { l.forEach { put(JSONArray().put(it.first).put(it.second)) } }) }
        File(dir(ctx), "snapshots.json").writeText(o.toString())
    }

    fun observations(id: String): List<Pair<Long, Long>> = snaps[id].orEmpty()

    /** Opportunity 0-100 with the reasons; growth is used only when it was actually observed. */
    fun score(v: YouTubeApi.Video, subs: Long?, now: Long = System.currentTimeMillis()): Opportunity {
        val f = ArrayList<Factor>()
        val hours = max(1.0, (now - v.publishedAt) / 3_600_000.0)
        val views = v.views ?: 0
        val vph = views / hours
        val speed = (log10(vph + 1) / 4).coerceIn(0.0, 1.0) // 10k views/hour = full
        f += Factor("Velocidade", "${fmt(vph.toLong())} views/hora desde que saiu", (speed * 35).roundToInt(), true)

        if (subs != null && subs > 0) {
            val ratio = views.toDouble() / subs
            val rel = (ratio / 0.5).coerceIn(0.0, 1.0) // views = half the subscribers = full
            f += Factor("Acima do canal", "views = ${"%.0f".format(ratio * 100)}% dos inscritos", (rel * 25).roundToInt(), true)
        } else {
            f += Factor("Acima do canal", "o canal esconde os inscritos", 0, false)
        }

        val obs = observations(v.id)
        val growth = if (obs.size >= 2 && obs.last().first - obs.first().first >= 30 * 60_000) {
            val (t0, v0) = obs[obs.size - 2].let { if (obs.last().first - it.first < 30 * 60_000) obs.first() else it }
            val (t1, v1) = obs.last()
            (v1 - v0) / max(0.5, (t1 - t0) / 3_600_000.0)
        } else null
        if (growth != null) {
            val g = (growth / max(1.0, vph)).coerceIn(0.0, 2.0) / 2 // growing now at least 2x its average = full
            f += Factor("Crescendo agora", "+${fmt(growth.toLong())} views/hora entre as suas consultas", (g * 15).roundToInt(), true)
        } else {
            f += Factor("Crescendo agora", "precisa de outra consulta daqui a 30 min para medir", 0, false)
        }

        val days = hours / 24
        val recency = when { days <= 2 -> 1.0; days >= 30 -> 0.0; else -> 1 - (days - 2) / 28 }
        f += Factor("Recente", if (days < 1) "saiu há ${hours.toInt()} h" else "saiu há ${days.toInt()} dia(s)", (recency * 15).roundToInt(), true)

        val potential = (v.seconds / 3600.0).coerceIn(0.2, 1.0) // long videos and lives have more moments to cut
        f += Factor("Material para cortes", "${v.seconds / 60} min de vídeo (estimativa: mais longo = mais momentos)",
            (potential * 10).roundToInt(), false)
        return Opportunity(f.sumOf { it.points }.coerceIn(0, 100), f)
    }

    fun fmt(n: Long): String = when {
        n >= 1_000_000 -> "%.1f mi".format(n / 1e6)
        n >= 1_000 -> "%.1f mil".format(n / 1e3)
        else -> "$n"
    }
}
