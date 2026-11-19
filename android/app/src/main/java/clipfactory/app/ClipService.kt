package clipfactory.app

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.net.Uri
import android.os.Build
import android.os.IBinder
import android.os.PowerManager
import android.util.Log
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch

/** What the background work is doing right now (the Processing screen shows this). */
data class Work(
    val projectId: String? = null,
    val phase: String = "idle", // idle | analyzing | rendering
    val stage: String = "",
    val progress: Double = 0.0,
    val clipIndex: Int = -1,
    val error: String? = null,
)

object JobState {
    val work = MutableStateFlow(Work())
    val flow: StateFlow<Work> get() = work
}

/**
 * Runs the work in the foreground so Android does not stop it during a long stream:
 *  analyze -> (the user picks moments, when that option is on) -> render each clip; also re-renders an edited clip.
 */
class ClipService : Service() {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private var job: Job? = null
    private var wake: PowerManager.WakeLock? = null

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_CANCEL -> { job?.cancel(); return START_NOT_STICKY }
            null -> return START_NOT_STICKY
        }
        // Android requires startForeground after startForegroundService, even when this request is turned down
        startForegroundCompat(notification("Preparando…", 0))
        if (job?.isActive == true) {
            // a render request waits in its project and is picked up when the current work ends
            if (intent.action != ACTION_RENDER || intent.getStringExtra(EXTRA_PROJECT)?.startsWith("live") != true)
                android.widget.Toast.makeText(this, "Espere o trabalho atual terminar.", android.widget.Toast.LENGTH_LONG).show()
            return START_NOT_STICKY
        }
        wake = (getSystemService(Context.POWER_SERVICE) as PowerManager)
            .newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "clipfactory:job").apply { acquire(6 * 60 * 60 * 1000L) }
        ProjectStore.load(this)
        val opt = Prefs.options(this)
        val action = intent.action!!
        job = scope.launch {
            var projectId = intent.getStringExtra(EXTRA_PROJECT)
            try {
                when (action) {
                    ACTION_ANALYZE -> {
                        val uri = intent.data ?: return@launch
                        projectId = "p" + System.currentTimeMillis()
                        // debug builds only: a ready transcript (TSV start, end, word) to test captions without a key
                        val testWords = if (BuildConfig.DEBUG) intent.getStringExtra("test_words")?.let { path ->
                            java.io.File(path).readLines().filter { it.isNotBlank() }.map {
                                val p = it.split('\t'); clipfactory.core.Word(p[2], p[0].toDouble(), p[1].toDouble())
                            }
                        } else null
                        val pid = projectId!!
                        set(Work(pid, "analyzing", "Abrindo o vídeo", 0.0))
                        val p = Engine.analyze(this@ClipService, pid, uri, intent.getStringExtra(EXTRA_NAME) ?: "video", opt, testWords) { st, pr ->
                            set(Work(pid, "analyzing", st, pr))
                        }
                        if (opt.chooseMoments) {
                            set(Work(pid, "idle"))
                            nm().notify(ID_DONE, done("Escolha os momentos", "Achei ${p.moments.size} momentos. Toque para escolher quais viram clipe."))
                            return@launch
                        }
                        ProjectStore.update(this@ClipService, pid) {
                            it.copy(clips = Engine.clipsFor(it, it.moments.filter { m -> m.preselected }.map { m -> m.id }, opt))
                        }
                        renderPending(pid, opt)
                    }
                    ACTION_RENDER -> {
                        val pid = projectId ?: return@launch
                        val ids = intent.getIntArrayExtra(EXTRA_MOMENTS)?.toList() ?: emptyList()
                        ProjectStore.update(this@ClipService, pid) { p ->
                            val have = p.clips.map { it.momentId }.toSet()
                            p.copy(status = "rendering", clips = p.clips + Engine.clipsFor(p, ids.filter { it !in have }, opt))
                        }
                        renderPending(pid, opt)
                        // clips that arrived while this one rendered (e.g. live clips) wait in their projects
                        while (true) {
                            val next = ProjectStore.projects.value.firstOrNull { p ->
                                p.status == "rendering" && p.clips.any { it.status == "pending" }
                            } ?: break
                            projectId = next.id
                            renderPending(next.id, opt)
                        }
                    }
                    ACTION_RERENDER -> {
                        val pid = projectId ?: return@launch
                        val idx = intent.getIntExtra(EXTRA_INDEX, -1)
                        ProjectStore.update(this@ClipService, pid) { p ->
                            p.copy(clips = p.clips.mapIndexed { i, c -> if (i == idx) c.copy(status = "pending") else c })
                        }
                        renderPending(pid, opt)
                    }
                }
            } catch (e: CancellationException) {
                projectId?.let { id -> ProjectStore.update(this@ClipService, id) {
                    it.copy(status = if (it.doneClips.isEmpty()) "canceled" else "done",
                        clips = it.clips.map { c -> if (c.status == "rendering") c.copy(status = "pending") else c })
                } }
                set(Work(projectId, "idle", error = "Cancelado."))
            } catch (e: Throwable) {
                Log.e("ClipService", "job failed", e)
                projectId?.let { id -> ProjectStore.update(this@ClipService, id) { it.copy(status = if (it.doneClips.isEmpty()) "failed" else "done",
                    notes = listOf("Erro: ${e.message ?: e}") + it.notes) } }
                set(Work(projectId, "idle", error = e.message ?: e.toString()))
                nm().notify(ID_DONE, done("Falhou", e.message ?: e.toString()))
            } finally {
                wake?.let { if (it.isHeld) it.release() }
                stopForeground(STOP_FOREGROUND_REMOVE)
                stopSelf()
            }
        }
        return START_NOT_STICKY
    }

    private suspend fun renderPending(pid: String, opt: Options) {
        val failed = ArrayList<String>()
        val started = System.currentTimeMillis()
        var renderedCount = 0
        while (true) {
            val p = ProjectStore.get(pid) ?: return
            val i = p.clips.indexOfFirst { it.status == "pending" }
            if (i < 0) break
            val todo = p.clips.count { it.status == "pending" }
            val total = p.clips.size
            val n = total - todo + 1
            ProjectStore.update(this, pid) { it.copy(status = "rendering", clips = it.clips.mapIndexed { k, c -> if (k == i) c.copy(status = "rendering") else c }) }
            set(Work(pid, "rendering", "Montando o clipe $n de $total", 0.0, i))
            try {
                val made = Engine.render(this, ProjectStore.get(pid)!!, i, opt) { pr ->
                    set(Work(pid, "rendering", "Montando o clipe $n de $total", pr, i))
                }
                ProjectStore.update(this, pid) { it.copy(clips = it.clips.mapIndexed { k, c -> if (k == i) made else c }) }
                renderedCount++
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                Log.e("ClipService", "clip $i failed", e)
                failed.add(e.message?.take(100) ?: e.toString())
                ProjectStore.update(this, pid) { it.copy(clips = it.clips.mapIndexed { k, c ->
                    if (k == i) c.copy(status = "failed", error = e.message?.take(160) ?: e.toString()) else c }) }
            }
        }
        val p = ProjectStore.get(pid) ?: return
        val took = (System.currentTimeMillis() - started) / 60000.0
        ProjectStore.update(this, pid) { p ->
            p.copy(status = if (p.doneClips.isEmpty()) "failed" else "done",
                notes = p.notes.filterNot { it.startsWith("Tempo da montagem") } + (if (renderedCount > 0)
                    listOf("Tempo da montagem: %.1f min para %d clipe(s) (%.1f min cada).".format(took, renderedCount, took / renderedCount))
                    else emptyList()))
        }
        set(Work(pid, "idle"))
        nm().notify(ID_DONE, if (p.doneClips.isEmpty()) done("Nenhum clipe saiu", failed.firstOrNull() ?: "Abra o app para ver o motivo.")
            else done("${p.doneClips.size} clipes prontos", "Toque para assistir e postar."))
    }

    private fun set(w: Work) {
        JobState.work.value = w
        if (w.phase != "idle") nm().notify(ID, notification(w.stage, (w.progress * 100).toInt()))
    }

    override fun onDestroy() {
        scope.coroutineContext[Job]?.cancel()
        super.onDestroy()
    }

    private fun nm() = getSystemService(NotificationManager::class.java)

    private fun channel() {
        nm().createNotificationChannel(NotificationChannel(CHANNEL, "Criação de clipes", NotificationManager.IMPORTANCE_LOW))
    }

    private fun openApp() = PendingIntent.getActivity(
        this, 0, Intent(this, MainActivity::class.java), PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
    )

    private fun notification(text: String, pct: Int): Notification {
        channel()
        val cancel = PendingIntent.getService(
            this, 1, Intent(this, ClipService::class.java).setAction(ACTION_CANCEL), PendingIntent.FLAG_IMMUTABLE,
        )
        return Notification.Builder(this, CHANNEL)
            .setSmallIcon(R.drawable.ic_notify)
            .setContentTitle("Criando clipes")
            .setContentText(text)
            .setProgress(100, pct, false)
            .setOngoing(true)
            .setContentIntent(openApp())
            .addAction(Notification.Action.Builder(null, "Cancelar", cancel).build())
            .build()
    }

    private fun done(title: String, text: String): Notification {
        channel()
        return Notification.Builder(this, CHANNEL)
            .setSmallIcon(R.drawable.ic_notify)
            .setContentTitle(title)
            .setContentText(text)
            .setAutoCancel(true)
            .setContentIntent(openApp())
            .build()
    }

    private fun startForegroundCompat(n: Notification) {
        when {
            Build.VERSION.SDK_INT >= 35 -> startForeground(ID, n, ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PROCESSING)
            Build.VERSION.SDK_INT >= 29 -> startForeground(ID, n, ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC)
            else -> startForeground(ID, n)
        }
    }

    companion object {
        private const val CHANNEL = "clips"
        private const val ID = 1
        private const val ID_DONE = 2
        const val EXTRA_NAME = "name"
        const val EXTRA_PROJECT = "project"
        const val EXTRA_MOMENTS = "moments"
        const val EXTRA_INDEX = "index"
        const val ACTION_ANALYZE = "analyze"
        const val ACTION_RENDER = "render"
        const val ACTION_RERENDER = "rerender"
        const val ACTION_CANCEL = "cancel"

        fun analyze(ctx: Context, uri: Uri, name: String, testWords: String? = null) = ctx.startForegroundService(
            Intent(ctx, ClipService::class.java).setAction(ACTION_ANALYZE).setData(uri).putExtra(EXTRA_NAME, name)
                .putExtra("test_words", testWords).addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION))

        fun render(ctx: Context, projectId: String, momentIds: List<Int>) = ctx.startForegroundService(
            Intent(ctx, ClipService::class.java).setAction(ACTION_RENDER).putExtra(EXTRA_PROJECT, projectId)
                .putExtra(EXTRA_MOMENTS, momentIds.toIntArray()))

        fun rerender(ctx: Context, projectId: String, index: Int) = ctx.startForegroundService(
            Intent(ctx, ClipService::class.java).setAction(ACTION_RERENDER).putExtra(EXTRA_PROJECT, projectId)
                .putExtra(EXTRA_INDEX, index))

        fun cancel(ctx: Context) = ctx.startService(Intent(ctx, ClipService::class.java).setAction(ACTION_CANCEL))
    }
}
