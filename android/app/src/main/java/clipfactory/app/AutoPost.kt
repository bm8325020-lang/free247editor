package clipfactory.app

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.net.Uri
import androidx.work.CoroutineWorker
import androidx.work.ExistingWorkPolicy
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONObject
import java.util.concurrent.TimeUnit

/**
 * Posts the best bot clips to the owner's Instagram by itself, through Meta's official Content Publishing API
 * (Instagram professional account linked to a Facebook Page; the device uploads the video directly, resumable
 * upload). The owner's rhythm: every [Config.interval] minutes up to [Config.perRound] clips, graded [Config.minScore]+
 * (or under it when the AI said the clip is worth posting), never more than [Config.dailyCap] a day (Meta allows
 * 50-100 API posts per 24 h), with [VETO_MIN] minutes to cancel from a notification. TikTok stays one tap (its API
 * only posts privately for unaudited apps).
 */
object AutoPost {
    data class Config(
        val enabled: Boolean, val minScore: Int, val dailyCap: Int, val interval: Int, val perRound: Int,
        val username: String, val connected: Boolean,
    )

    private const val GRAPH = "https://graph.facebook.com"
    private const val VETO_MIN = 5
    private val http = OkHttpClient.Builder().connectTimeout(20, TimeUnit.SECONDS).readTimeout(5, TimeUnit.MINUTES)
        .writeTimeout(10, TimeUnit.MINUTES).build()

    private fun sp(ctx: Context) = ctx.getSharedPreferences("autopost", Context.MODE_PRIVATE)

    fun config(ctx: Context): Config {
        val p = sp(ctx)
        return Config(p.getBoolean("enabled", false), p.getInt("minScore2", 50), p.getInt("dailyCap", 50),
            p.getInt("interval", 15), p.getInt("perRound", 2), p.getString("username", "") ?: "",
            (p.getString("token_enc", "") ?: "").isNotEmpty())
    }

    fun save(ctx: Context, c: Config) {
        sp(ctx).edit().putBoolean("enabled", c.enabled).putInt("minScore2", c.minScore).putInt("dailyCap", c.dailyCap)
            .putInt("interval", c.interval).putInt("perRound", c.perRound).apply()
        if (c.enabled) start(ctx) else WorkManager.getInstance(ctx).cancelUniqueWork("autopost-round")
    }

    /**
     * Connect with a user token made in Meta's Graph API Explorer (permissions instagram_basic,
     * instagram_content_publish, pages_show_list, pages_read_engagement), ideally extended to 60 days in the Access
     * Token Debugger. The Page token derived from a long-lived user token does not expire, so nothing to renew.
     */
    fun connect(ctx: Context, userToken: String): String {
        val url = "$GRAPH/me/accounts?fields=id,name,access_token,instagram_business_account{id,username}" +
            "&access_token=${Uri.encode(userToken.trim())}"
        val body = http.newCall(Request.Builder().url(url).build()).execute().use { r ->
            val b = r.body?.string().orEmpty()
            if (!r.isSuccessful) return "A Meta recusou a chave: ${errorOf(b)}"
            b
        }
        val pages = JSONObject(body).optJSONArray("data") ?: return "Nenhuma Página do Facebook nessa conta."
        for (i in 0 until pages.length()) {
            val pg = pages.getJSONObject(i)
            val ig = pg.optJSONObject("instagram_business_account") ?: continue
            sp(ctx).edit().putString("token_enc", SecureKeys.encrypt(pg.getString("access_token")))
                .putString("igId", ig.getString("id")).putString("username", ig.optString("username"))
                .putString("page", pg.optString("name")).apply()
            return "Conectado: @${ig.optString("username")} (Página ${pg.optString("name")})"
        }
        return "Nenhuma Página com Instagram profissional ligado. Ligue o Instagram à Página nas configurações dele."
    }

    fun disconnect(ctx: Context) = sp(ctx).edit().remove("token_enc").remove("igId").remove("username").apply()

    private fun errorOf(body: String) = runCatching { JSONObject(body).getJSONObject("error").optString("message") }
        .getOrNull()?.take(160) ?: body.take(160)

    /** Publish one Reel. Returns the post's link, or throws with Meta's message. */
    fun publish(ctx: Context, video: Uri, caption: String, onStep: (String) -> Unit = {}): String {
        val token = SecureKeys.decrypt(sp(ctx).getString("token_enc", "") ?: "")
        val ig = sp(ctx).getString("igId", "") ?: ""
        require(token.isNotEmpty() && ig.isNotEmpty()) { "Instagram não conectado" }
        val bytes = ctx.contentResolver.openInputStream(video)!!.use { it.readBytes() }
        onStep("Criando o Reels")
        val form = okhttp3.FormBody.Builder().add("media_type", "REELS").add("upload_type", "resumable")
            .add("caption", caption.take(2100)).add("share_to_feed", "true").add("access_token", token).build()
        val created = http.newCall(Request.Builder().url("$GRAPH/$ig/media").post(form).build()).execute().use { r ->
            val b = r.body?.string().orEmpty()
            check(r.isSuccessful) { "criar: ${errorOf(b)}" }
            JSONObject(b)
        }
        val container = created.getString("id")
        val upload = created.optString("uri").ifBlank { "https://rupload.facebook.com/ig-api-upload/$container" }
        onStep("Enviando o vídeo (${bytes.size / 1_000_000} MB)")
        http.newCall(Request.Builder().url(upload)
            .header("Authorization", "OAuth $token").header("offset", "0").header("file_size", bytes.size.toString())
            .post(bytes.toRequestBody("application/octet-stream".toMediaType())).build()).execute().use { r ->
            val b = r.body?.string().orEmpty()
            check(r.isSuccessful) { "enviar: ${errorOf(b)}" }
        }
        onStep("O Instagram está processando")
        var status = ""
        for (i in 0 until 20) { // up to ~7 minutes (a background job has 10)
            Thread.sleep(20_000)
            status = http.newCall(Request.Builder().url("$GRAPH/$container?fields=status_code,status&access_token=${Uri.encode(token)}").build())
                .execute().use { r -> JSONObject(r.body?.string().orEmpty()).optString("status_code") }
            if (status == "FINISHED") break
            check(status != "ERROR" && status != "EXPIRED") { "o Instagram não aceitou o vídeo ($status)" }
        }
        check(status == "FINISHED") { "o Instagram demorou demais para processar" }
        onStep("Publicando")
        val media = http.newCall(Request.Builder().url("$GRAPH/$ig/media_publish")
            .post(okhttp3.FormBody.Builder().add("creation_id", container).add("access_token", token).build()).build())
            .execute().use { r ->
                val b = r.body?.string().orEmpty()
                check(r.isSuccessful) { "publicar: ${errorOf(b)}" }
                JSONObject(b).getString("id")
            }
        return runCatching {
            http.newCall(Request.Builder().url("$GRAPH/$media?fields=permalink&access_token=${Uri.encode(token)}").build())
                .execute().use { r -> JSONObject(r.body?.string().orEmpty()).optString("permalink") }
        }.getOrDefault("").ifBlank { "https://www.instagram.com/" }
    }

    // ---------- the queue ----------

    private fun today(): String = java.text.SimpleDateFormat("yyyy-MM-dd", java.util.Locale.US).format(java.util.Date())

    private fun postedToday(ctx: Context): Int {
        val p = sp(ctx)
        return if (p.getString("day", "") == today()) p.getInt("count", 0) else 0
    }

    private fun countPost(ctx: Context) {
        val n = postedToday(ctx) + 1
        sp(ctx).edit().putString("day", today()).putInt("count", n).apply()
    }

    /** Clips to post now: not posted, not vetoed, rendered by the app, last 24 h; graded minScore+ or, under it,
     *  marked worth posting by the AI. Best first. */
    private fun candidates(ctx: Context, cfg: Config): List<Project> {
        val skipped = sp(ctx).getStringSet("skipped", emptySet())!!
        val queued = (sp(ctx).getString("queue", "") ?: "").split(",").filter { it.isNotEmpty() }.toSet()
        val since = System.currentTimeMillis() - 86_400_000L
        return ProjectStore.projects.value.filter { p ->
            val c = p.clips.firstOrNull()
            p.id.startsWith("srv") && p.createdAt > since && p.id !in skipped && p.id !in queued && p.status == "done" &&
                c != null && p.doneClips.isNotEmpty() && p.clips.none { it.posted > 0 } && c.layoutName != "do servidor" &&
                (c.score >= cfg.minScore || p.notes.any { it == "IA: vale postar = sim" })
        }.sortedByDescending { it.clips.first().score }
    }

    /** Keep the round chain alive (called every 15 minutes and when the app opens). */
    fun tick(ctx: Context) {
        val cfg = config(ctx)
        if (cfg.enabled && cfg.connected) start(ctx)
    }

    private fun start(ctx: Context) {
        WorkManager.getInstance(ctx).enqueueUniqueWork("autopost-round", ExistingWorkPolicy.KEEP,
            OneTimeWorkRequestBuilder<RoundWorker>().build())
    }

    /** One round: pick up to perRound clips, warn the owner (VETO_MIN minutes to cancel), schedule the next round. */
    private fun round(ctx: Context) {
        val cfg = config(ctx)
        if (!cfg.enabled || !cfg.connected) return
        ProjectStore.load(ctx)
        val p = sp(ctx)
        val room = cfg.dailyCap - postedToday(ctx)
        val queuedNow = (p.getString("queue", "") ?: "").split(",").filter { it.isNotEmpty() }
        if (room > 0 && queuedNow.isEmpty()) {
            val pick = candidates(ctx, cfg).take(minOf(cfg.perRound, room))
            if (pick.isNotEmpty()) {
                p.edit().putString("queue", pick.joinToString(",") { it.id }).apply()
                notify(ctx, 40, "📅 Vou postar ${pick.size} clipe(s) no Instagram em $VETO_MIN min",
                    pick.joinToString("\n") { "• ${it.clips.first().title} (nota ${it.clips.first().score})" } +
                        "\nToque em Cancelar para não postar.", actions = true)
                WorkManager.getInstance(ctx).enqueueUniqueWork("autopost-now", ExistingWorkPolicy.REPLACE,
                    OneTimeWorkRequestBuilder<PostWorker>().setInitialDelay(VETO_MIN.toLong(), TimeUnit.MINUTES).build())
            }
        }
        // the next round
        WorkManager.getInstance(ctx).enqueueUniqueWork("autopost-round", ExistingWorkPolicy.REPLACE,
            OneTimeWorkRequestBuilder<RoundWorker>().setInitialDelay(cfg.interval.toLong(), TimeUnit.MINUTES).build())
    }

    fun cancelScheduled(ctx: Context) {
        val p = sp(ctx)
        val queued = (p.getString("queue", "") ?: "").split(",").filter { it.isNotEmpty() }
        p.edit().putStringSet("skipped", p.getStringSet("skipped", emptySet())!! + queued).remove("queue").apply()
        WorkManager.getInstance(ctx).cancelUniqueWork("autopost-now")
        ctx.getSystemService(NotificationManager::class.java).cancel(40)
    }

    /** Post the next queued clip; one per job (a background job has 10 minutes), the next job takes the next one. */
    fun postNow(ctx: Context) {
        val p = sp(ctx)
        val queued = (p.getString("queue", "") ?: "").split(",").filter { it.isNotEmpty() }
        val pid = queued.firstOrNull() ?: return
        p.edit().putString("queue", queued.drop(1).joinToString(",")).apply()
        if (queued.size > 1) WorkManager.getInstance(ctx).enqueueUniqueWork("autopost-next", ExistingWorkPolicy.APPEND,
            OneTimeWorkRequestBuilder<PostWorker>().build())
        if (postedToday(ctx) >= config(ctx).dailyCap) return
        ProjectStore.load(ctx)
        val pr = ProjectStore.get(pid) ?: return
        val c = pr.doneClips.firstOrNull() ?: return
        if (c.posted > 0) return
        val caption = pr.notes.firstOrNull { it.startsWith("Legenda sugerida pelo servidor:") }?.substringAfter("\n")?.trim()
            ?: c.title
        val nid = 50 + (pid.hashCode() and 0xff)
        try {
            notify(ctx, nid, "⏫ Postando no Instagram", c.title)
            val link = publish(ctx, Uri.parse(c.uri), caption) { step -> notify(ctx, nid, "⏫ Postando no Instagram", "$step · ${c.title}") }
            countPost(ctx)
            ProjectStore.update(ctx, pid) { x ->
                x.copy(clips = x.clips.map { if (it.uri == c.uri) it.copy(posted = System.currentTimeMillis()) else it },
                    notes = x.notes + "Postado no Instagram: $link")
            }
            notify(ctx, nid, "✅ Postado no Instagram", "${c.title} · $link", link = link)
        } catch (e: Exception) {
            p.edit().putStringSet("skipped", p.getStringSet("skipped", emptySet())!! + pid).apply()
            notify(ctx, nid, "❌ Não consegui postar no Instagram", e.message?.take(200) ?: e.toString())
        }
    }

    private fun notify(ctx: Context, id: Int, title: String, text: String, actions: Boolean = false, link: String = "") {
        val nm = ctx.getSystemService(NotificationManager::class.java)
        nm.createNotificationChannel(NotificationChannel("autopost", "Postagem automática", NotificationManager.IMPORTANCE_HIGH))
        val open = if (link.isNotEmpty()) PendingIntent.getActivity(ctx, 41, Intent(Intent.ACTION_VIEW, Uri.parse(link)), PendingIntent.FLAG_IMMUTABLE)
            else PendingIntent.getActivity(ctx, 42, Intent(ctx, MainActivity::class.java), PendingIntent.FLAG_IMMUTABLE)
        val b = android.app.Notification.Builder(ctx, "autopost").setSmallIcon(R.drawable.ic_notify)
            .setContentTitle(title).setContentText(text).setStyle(android.app.Notification.BigTextStyle().bigText(text))
            .setContentIntent(open).setAutoCancel(!actions)
        if (actions) {
            fun action(label: String, what: String, code: Int) = android.app.Notification.Action.Builder(null, label,
                PendingIntent.getBroadcast(ctx, code, Intent(ctx, Receiver::class.java).setAction(what), PendingIntent.FLAG_IMMUTABLE)).build()
            b.addAction(action("Cancelar", "cancel", 43)).addAction(action("Postar agora", "now", 44))
        }
        nm.notify(id, b.build())
    }

    class Receiver : BroadcastReceiver() {
        override fun onReceive(ctx: Context, intent: Intent) {
            when (intent.action) {
                "cancel" -> cancelScheduled(ctx)
                "now" -> WorkManager.getInstance(ctx).enqueueUniqueWork("autopost-now", ExistingWorkPolicy.REPLACE,
                    OneTimeWorkRequestBuilder<PostWorker>().build())
            }
        }
    }

    class RoundWorker(ctx: Context, params: WorkerParameters) : CoroutineWorker(ctx, params) {
        override suspend fun doWork(): androidx.work.ListenableWorker.Result {
            round(applicationContext)
            return androidx.work.ListenableWorker.Result.success()
        }
    }

    class PostWorker(ctx: Context, params: WorkerParameters) : CoroutineWorker(ctx, params) {
        override suspend fun doWork(): androidx.work.ListenableWorker.Result {
            postNow(applicationContext)
            return androidx.work.ListenableWorker.Result.success()
        }
    }
}
