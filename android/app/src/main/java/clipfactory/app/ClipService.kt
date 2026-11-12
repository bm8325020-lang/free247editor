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
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch

sealed class JobStatus {
    data object Idle : JobStatus()
    data class Running(val stage: String, val progress: Double) : JobStatus()
    data class Done(val result: JobResult) : JobStatus()
    data class Failed(val message: String) : JobStatus()
}

object JobState {
    val status = MutableStateFlow<JobStatus>(JobStatus.Idle)
    val flow: StateFlow<JobStatus> get() = status
}

/** Runs the job in the foreground so Android does not kill it during a long stream. */
class ClipService : Service() {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private var job: Job? = null
    private var wake: PowerManager.WakeLock? = null

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_CANCEL) {
            job?.cancel()
            return START_NOT_STICKY
        }
        val uri = intent?.data ?: return START_NOT_STICKY
        if (job?.isActive == true) return START_NOT_STICKY
        val name = intent.getStringExtra(EXTRA_NAME) ?: "video"
        val opt = Prefs.options(this)
        // debug builds only: a ready transcript (TSV start, end, word) so captions can be tested without a Groq key
        val testWords = if (BuildConfig.DEBUG) intent.getStringExtra("test_words")?.let { path ->
            java.io.File(path).readLines().filter { it.isNotBlank() }.map {
                val p = it.split('\t'); clipfactory.core.Word(p[2], p[0].toDouble(), p[1].toDouble())
            }
        } else null
        startForegroundCompat(notification("Preparando…", 0))
        wake = (getSystemService(Context.POWER_SERVICE) as PowerManager)
            .newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "clipfactory:job").apply { acquire(6 * 60 * 60 * 1000L) }
        JobState.status.value = JobStatus.Running("Preparando", 0.0)
        job = scope.launch {
            var lastPct = -1
            try {
                val result = Engine(this@ClipService, uri, name, opt, testWords) { stage, p ->
                    JobState.status.value = JobStatus.Running(stage, p)
                    val pct = (p * 100).toInt()
                    if (pct != lastPct) {
                        lastPct = pct
                        nm().notify(ID, notification(stage, pct))
                    }
                }.run()
                JobState.status.value = JobStatus.Done(result)
                if (result.clips.isEmpty()) {
                    nm().notify(ID_DONE, done("Nenhum clipe saiu", result.notes.firstOrNull() ?: "Abra o app para ver o motivo."))
                } else {
                    nm().notify(ID_DONE, done("${result.clips.size} clipes prontos", "Salvos na galeria, pasta Movies/Clipes."))
                }
            } catch (e: CancellationException) {
                JobState.status.value = JobStatus.Failed("Cancelado.")
            } catch (e: Throwable) {
                JobState.status.value = JobStatus.Failed(e.message ?: e.toString())
                nm().notify(ID_DONE, done("Falhou", e.message ?: e.toString()))
            } finally {
                wake?.let { if (it.isHeld) it.release() }
                stopForeground(STOP_FOREGROUND_REMOVE)
                stopSelf()
            }
        }
        return START_NOT_STICKY
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
        const val ACTION_CANCEL = "cancel"

        fun start(ctx: Context, uri: Uri, name: String) {
            ctx.startForegroundService(
                Intent(ctx, ClipService::class.java).setData(uri).putExtra(EXTRA_NAME, name)
                    .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            )
        }

        fun cancel(ctx: Context) {
            ctx.startService(Intent(ctx, ClipService::class.java).setAction(ACTION_CANCEL))
        }
    }
}
