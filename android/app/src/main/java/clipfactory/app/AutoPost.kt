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
import java.util.Calendar
import java.util.concurrent.TimeUnit

/**
 * Posts the best bot clips to the owner's Instagram by itself, through Meta's official Content Publishing API
 * (Instagram professional account linked to a Facebook Page; the device uploads the video directly, resumable
 * upload). Rules: only clips graded [minScore]+, at most [perDay] a day, at the chosen times, and the owner gets
 * [VETO_MIN] minutes to cancel each one from a notification. TikTok stays one tap (its API only posts privately
 * for unaudited apps).
 */
object AutoPost {
    data class Config(
        val enabled: Boolean, val minScore: Int, val perDay: Int, val slots: List<Int>, // minutes of the day
        val username: String, val connected: Boolean,
    )

    private const val GRAPH = "https://graph.facebook.com"
    private const val VETO_MIN = 30
    private val http = OkHttpClient.Builder().connectTimeout(20, TimeUnit.SECONDS).readTimeout(5, TimeUnit.MINUTES)
        .writeTimeout(10, TimeUnit.MINUTES).build()

    private fun sp(ctx: Context) = ctx.getSharedPreferences("autopost", Context.MODE_PRIVATE)

    fun config(ctx: Context): Config {
        val p = sp(ctx)
        return Config(p.getBoolean("enabled", false), p.getInt("minScore", 80), p.getInt("perDay", 4),
            parseSlots(p.getString("slots", "12:30,18:30,21:30") ?: ""), p.getString("username", "") ?: "",
            (p.getString("token_enc", "") ?: "").isNotEmpty())
    }

    fun slotsText(ctx: Context) = sp(ctx).getString("slots", "12:30,18:30,21:30") ?: ""

    fun save(ctx: Context, enabled: Boolean, minScore: Int, perDay: Int, slots: String) {
        sp(ctx).edit().putBoolean("enabled", enabled).putInt("minScore", minScore).putInt("perDay", perDay)
            .putString("slots", slots).apply()
    }

    fun parseSlots(s: String): List<Int> = s.split(",", " ", ";").mapNotNull { t ->
        val m = Regex("^(\\d{1,2})(?::(\\d{2}))?$").find(t.trim()) ?: return@mapNotNull null
        val h = m.groupValues[1].toInt(); val mi = m.groupValues[2].ifEmpty { "0" }.toInt()
        if (h in 0..23 && mi in 0..59) h * 60 + mi else null
    }.distinct().sorted()

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

    /** The best bot clip not posted, not vetoed, from the last 24 h, graded at least minScore. */
    private fun candidate(ctx: Context, cfg: Config): Project? {
        val skipped = sp(ctx).getStringSet("skipped", emptySet())!!
        val since = System.currentTimeMillis() - 86_400_000L
        return ProjectStore.projects.value.filter { p ->
            p.id.startsWith("srv") && p.createdAt > since && p.id !in skipped && p.status == "done" &&
                p.doneClips.isNotEmpty() && p.clips.none { it.posted > 0 } &&
                (p.clips.first().score) >= cfg.minScore && p.clips.first().layoutName != "do servidor"
        }.maxByOrNull { it.clips.first().score }
    }

    /**
     * Called every 15 minutes (with the bot sync) and when the app opens: schedules the next post when a time slot
     * is coming (and warns the owner), and posts it when its time comes unless it was vetoed.
     */
    fun tick(ctx: Context) {
        val cfg = config(ctx)
        if (!cfg.enabled || !cfg.connected || cfg.slots.isEmpty()) return
        ProjectStore.load(ctx)
        val p = sp(ctx)
        val now = Calendar.getInstance()
        val minute = now.get(Calendar.HOUR_OF_DAY) * 60 + now.get(Calendar.MINUTE)
        val pending = p.getString("scheduledPid", "") ?: ""
        val at = p.getLong("scheduledAt", 0)
        if (pending.isNotEmpty()) {
            if (System.currentTimeMillis() >= at) postNow(ctx)
            return
        }
        if (postedToday(ctx) >= cfg.perDay) return
        // the next slot within the veto window
        val slot = cfg.slots.firstOrNull { it - minute in 0..VETO_MIN + 15 } ?: return
        if (p.getString("lastSlot", "") == "${today()}-$slot") return
        val best = candidate(ctx, cfg) ?: return
        val time = Calendar.getInstance().apply {
            set(Calendar.HOUR_OF_DAY, slot / 60); set(Calendar.MINUTE, slot % 60); set(Calendar.SECOND, 0)
        }.timeInMillis
        p.edit().putString("scheduledPid", best.id).putLong("scheduledAt", time).putString("lastSlot", "${today()}-$slot").apply()
        notify(ctx, 40, "📅 Vou postar no Instagram às %02d:%02d".format(slot / 60, slot % 60),
            "\"${best.clips.first().title}\" (nota ${best.clips.first().score}). Toque em Cancelar para não postar.", actions = true)
        // the post itself runs at its time even if the 15-minute check comes late
        WorkManager.getInstance(ctx).enqueueUniqueWork("autopost-now", ExistingWorkPolicy.REPLACE,
            OneTimeWorkRequestBuilder<PostWorker>().setInitialDelay(maxOf(0, time - System.currentTimeMillis()), TimeUnit.MILLISECONDS).build())
    }

    fun cancelScheduled(ctx: Context) {
        val p = sp(ctx)
        val pid = p.getString("scheduledPid", "") ?: ""
        if (pid.isNotEmpty()) p.edit().putStringSet("skipped", p.getStringSet("skipped", emptySet())!! + pid).apply()
        p.edit().remove("scheduledPid").remove("scheduledAt").apply()
        WorkManager.getInstance(ctx).cancelUniqueWork("autopost-now")
        ctx.getSystemService(NotificationManager::class.java).cancel(40)
    }

    /** Post the scheduled clip now (its time came, or the owner tapped "Postar agora"). */
    fun postNow(ctx: Context) {
        val p = sp(ctx)
        val pid = p.getString("scheduledPid", "") ?: ""
        if (pid.isEmpty()) return
        p.edit().remove("scheduledPid").remove("scheduledAt").apply()
        ProjectStore.load(ctx)
        val pr = ProjectStore.get(pid) ?: return
        val c = pr.doneClips.firstOrNull() ?: return
        val caption = pr.notes.firstOrNull { it.startsWith("Legenda sugerida pelo servidor:") }?.substringAfter("\n")?.trim()
            ?: c.title
        try {
            notify(ctx, 40, "⏫ Postando no Instagram", c.title)
            val link = publish(ctx, Uri.parse(c.uri), caption) { step -> notify(ctx, 40, "⏫ Postando no Instagram", "$step · ${c.title}") }
            countPost(ctx)
            ProjectStore.update(ctx, pid) { x ->
                x.copy(clips = x.clips.map { if (it.uri == c.uri) it.copy(posted = System.currentTimeMillis()) else it },
                    notes = x.notes + "Postado no Instagram: $link")
            }
            notify(ctx, 40, "✅ Postado no Instagram", "${c.title} · $link", link = link)
        } catch (e: Exception) {
            p.edit().putStringSet("skipped", p.getStringSet("skipped", emptySet())!! + pid).apply()
            notify(ctx, 40, "❌ Não consegui postar no Instagram", e.message?.take(200) ?: e.toString())
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

    class PostWorker(ctx: Context, params: WorkerParameters) : CoroutineWorker(ctx, params) {
        override suspend fun doWork(): androidx.work.ListenableWorker.Result {
            postNow(applicationContext)
            return androidx.work.ListenableWorker.Result.success()
        }
    }
}
