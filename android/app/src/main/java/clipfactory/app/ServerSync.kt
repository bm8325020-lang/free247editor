package clipfactory.app

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.net.Uri
import androidx.work.Constraints
import androidx.work.CoroutineWorker
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.NetworkType
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONArray
import java.io.File
import java.util.concurrent.TimeUnit

/**
 * Brings the clips made by the live bot on the server into the app: lists them, downloads the new ones to the
 * gallery and adds each as a ready project (Results → Postar). Runs every 15 minutes, and on "Buscar agora".
 */
object ServerSync {
    data class Result(val ok: Boolean, val message: String, val newClips: Int = 0)

    private val http = OkHttpClient.Builder().connectTimeout(15, TimeUnit.SECONDS).readTimeout(5, TimeUnit.MINUTES).build()
    private const val WORK = "server-sync"

    /** "123.45.67.89:8080", "http://host:8080/" or "host" -> "http://host:8080" */
    fun base(addr: String): String? {
        var a = addr.trim().removeSuffix("/")
        if (a.isEmpty()) return null
        if (!a.startsWith("http://") && !a.startsWith("https://")) a = "http://$a"
        val hostPart = a.substringAfter("://")
        if (!hostPart.contains(":")) a = "$a:8080"
        return a
    }

    private fun get(url: String, code: String) = http.newCall(Request.Builder().url(url).header("X-Code", code).build()).execute()

    /** What the bot is doing now (lives, clips, latest decision); null when it does not answer. */
    fun status(ctx: Context): org.json.JSONObject? {
        val opt = Prefs.options(ctx)
        val base = base(opt.serverAddr) ?: return null
        if (opt.serverCode.length < 8) return null
        return runCatching {
            quick.newCall(Request.Builder().url("$base/api/status").header("X-Code", opt.serverCode).build()).execute().use { r ->
                if (r.isSuccessful) org.json.JSONObject(r.body?.string().orEmpty()) else null
            }
        }.getOrNull()
    }

    private val quick = OkHttpClient.Builder().connectTimeout(4, TimeUnit.SECONDS).readTimeout(8, TimeUnit.SECONDS).build()

    fun configured(ctx: Context) = Prefs.options(ctx).let { base(it.serverAddr) != null && it.serverCode.length >= 8 }

    fun sync(ctx: Context): Result {
        val opt = Prefs.options(ctx)
        val base = base(opt.serverAddr) ?: return Result(false, "Sem endereço do servidor.")
        if (opt.serverCode.length < 8) return Result(false, "Código do servidor curto ou vazio (mínimo 8).")
        ProjectStore.load(ctx)
        val sp = ctx.getSharedPreferences("server", Context.MODE_PRIVATE)
        val seen = sp.getStringSet("seen", emptySet())!!.toMutableSet()
        val list = try {
            get("$base/api/clips", opt.serverCode).use { r ->
                when {
                    r.code == 401 -> return Result(false, "O servidor recusou o código. Confira o APP_CODE do script.")
                    !r.isSuccessful -> return Result(false, "O servidor respondeu com erro ${r.code}.")
                    else -> JSONArray(r.body?.string().orEmpty())
                }
            }
        } catch (e: Exception) {
            return Result(false, "Não consegui falar com o servidor (${e.javaClass.simpleName}). Ele está ligado? A porta está aberta?")
        }
        fillKeys(ctx, base, opt)
        var added = 0
        val toRender = ArrayList<String>()
        for (i in 0 until list.length()) {
            val c = list.getJSONObject(i)
            val id = c.getString("id")
            if (id in seen) continue
            try {
                val tmp = File(ctx.cacheDir, "srv-$id.mp4")
                get("$base/api/clip/$id", opt.serverCode).use { r ->
                    if (!r.isSuccessful) throw IllegalStateException("HTTP ${r.code}")
                    tmp.outputStream().use { out -> r.body!!.byteStream().copyTo(out) }
                }
                val channel = c.optString("channel")
                val title = c.optString("title").ifBlank { "@$channel" }
                val uri = Engine.saveToGallery(ctx, tmp, "Movies/Clipes/Servidor $channel", "clip-$id.mp4")
                tmp.delete()
                val dur = runCatching {
                    android.media.MediaMetadataRetriever().run {
                        try { setDataSource(ctx, uri); extractMetadata(android.media.MediaMetadataRetriever.METADATA_KEY_DURATION)!!.toDouble() / 1000 }
                        finally { release() }
                    }
                }.getOrDefault(30.0)
                // the bot makes every clip; its AI only grades them (0-100) so the best can be posted first
                val score = if (c.has("score") && !c.isNull("score")) c.optInt("score", 60).coerceIn(0, 99) else 60
                val why = c.optString("why").trim()
                val reason = "Cortado pelo bot às ${c.optString("created").substringAfter('T').take(5)}." +
                    (if (why.isNotEmpty()) " Nota da IA $score: $why" else "")
                val pid = "srv$id"
                // the raw cut around the clip (original quality, with room on both sides): Editar can then move the
                // start and the end, and render with the app's own layout. Kept in the app's files, not the gallery.
                val raw = if (c.optBoolean("raw")) fetchRaw(ctx, base, opt.serverCode, id) else null
                val rawDur = raw?.let { f -> runCatching {
                    android.media.MediaMetadataRetriever().run {
                        try { setDataSource(f.path); extractMetadata(android.media.MediaMetadataRetriever.METADATA_KEY_DURATION)!!.toDouble() / 1000 }
                        finally { release() }
                    }
                }.getOrNull() }
                val useRaw = raw != null && rawDur != null
                val src = if (useRaw) Uri.fromFile(raw).toString() else uri.toString()
                val a = if (useRaw) c.optDouble("raw_start", 0.0).coerceIn(0.0, rawDur!!) else 0.0
                val b = if (useRaw) c.optDouble("raw_end", rawDur!!).coerceIn(a + 1, rawDur) else dur
                ProjectStore.put(ctx, Project(pid, "@$channel ao vivo (servidor)", src, System.currentTimeMillis(),
                    durationS = if (useRaw) rawDur!! else dur,
                    moments = listOf(Moment(0, a, b, (a + b) / 2, title, "", reason, score, "", "", true)),
                    // with the raw cut the app renders the clip again with its own layout (webcam found by the face
                    // detector, a tall game band, sharper scaling); the bot's version shows meanwhile and is replaced
                    clips = listOf(PClip(0, title, score, reason,
                        ClipEdit(a, b, if (opt.hook && useRaw && c.has("score") && " às " !in title) title else "", opt.captionStyle,
                            if (useRaw) "pip" else "auto"),
                        uri = uri.toString(), status = if (useRaw) "pending" else "done", layoutName = "do servidor")),
                    notes = listOf("Legenda sugerida pelo servidor:\n" + c.optString("caption")),
                    status = if (useRaw) "rendering" else "done"))
                if (useRaw) toRender.add(pid)
                seen.add(id)
                added++
            } catch (e: Exception) {
                // the next run tries again
                android.util.Log.w("ServerSync", "clip $id failed", e)
            }
        }
        sp.edit().putStringSet("seen", seen).apply()
        // the render service picks up every project waiting to render; from the background Android may refuse to
        // start it, and then the app does it the next time it opens
        if (toRender.isNotEmpty()) runCatching { ClipService.render(ctx, toRender.first(), emptyList()) }
        if (added > 0) notifyNew(ctx, added)
        return Result(true, if (added > 0) "$added clipe(s) novo(s) do servidor." else "Conectado. Nenhum clipe novo.", added)
    }

    /**
     * Bot on this same phone (Termux): the Groq and Coarena keys typed into its installer also go into the app, so
     * they are typed once. Only fills keys the app does not have yet.
     */
    private fun fillKeys(ctx: Context, base: String, opt: Options) {
        val host = base.substringAfter("://").substringBefore(":")
        if (host != "127.0.0.1" && host != "localhost") return
        if (opt.groqKey.isNotBlank() && opt.coarenaKey.isNotBlank()) return
        runCatching {
            get("$base/api/keys", opt.serverCode).use { r ->
                if (!r.isSuccessful) return
                val k = org.json.JSONObject(r.body?.string().orEmpty())
                val groq = opt.groqKey.ifBlank { k.optString("groq").trim() }
                val coarena = opt.coarenaKey.ifBlank { k.optString("coarena").trim() }
                if (groq != opt.groqKey || coarena != opt.coarenaKey) {
                    Prefs.save(ctx, Prefs.options(ctx).copy(groqKey = groq, coarenaKey = coarena))
                    android.util.Log.i("ServerSync", "keys filled from the bot on this phone")
                }
            }
        }
    }

    /** Downloads the raw cut of a clip into the app's files (the bot frees its copy once sent); null if it fails. */
    private fun fetchRaw(ctx: Context, base: String, code: String, id: String): File? {
        val dir = File(ctx.filesDir, "raw").apply { mkdirs() }
        // raw cuts are big (~1 MB per second): the ones older than 3 days go
        dir.listFiles()?.filter { System.currentTimeMillis() - it.lastModified() > 3 * 86_400_000L }?.forEach { it.delete() }
        val f = File(dir, "srv-$id.mp4")
        return try {
            get("$base/api/raw/$id", code).use { r ->
                if (!r.isSuccessful) return null
                val tmp = File(dir, "srv-$id.part")
                tmp.outputStream().use { out -> r.body!!.byteStream().copyTo(out) }
                if (!tmp.renameTo(f)) return null
            }
            f
        } catch (e: Exception) {
            android.util.Log.w("ServerSync", "raw $id failed", e)
            null
        }
    }

    /** Start (or stop, without an address) the check every 15 minutes. */
    fun schedule(ctx: Context) {
        val opt = Prefs.options(ctx)
        val wm = WorkManager.getInstance(ctx)
        if (base(opt.serverAddr) == null || opt.serverCode.length < 8) { wm.cancelUniqueWork(WORK); return }
        val req = PeriodicWorkRequestBuilder<Worker>(15, TimeUnit.MINUTES)
            .setConstraints(Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build()).build()
        wm.enqueueUniquePeriodicWork(WORK, ExistingPeriodicWorkPolicy.KEEP, req)
    }

    private fun notifyNew(ctx: Context, n: Int) {
        val nm = ctx.getSystemService(NotificationManager::class.java)
        nm.createNotificationChannel(NotificationChannel("server", "Clipes do servidor", NotificationManager.IMPORTANCE_DEFAULT))
        nm.notify(30, android.app.Notification.Builder(ctx, "server").setSmallIcon(R.drawable.ic_notify)
            .setContentTitle("$n clipe(s) novo(s) da live").setContentText("Toque para assistir e postar.")
            .setAutoCancel(true)
            .setContentIntent(PendingIntent.getActivity(ctx, 3, Intent(ctx, MainActivity::class.java), PendingIntent.FLAG_IMMUTABLE))
            .build())
    }

    class Worker(ctx: Context, params: WorkerParameters) : CoroutineWorker(ctx, params) {
        override suspend fun doWork(): androidx.work.ListenableWorker.Result {
            sync(applicationContext)
            return androidx.work.ListenableWorker.Result.success()
        }
    }
}
