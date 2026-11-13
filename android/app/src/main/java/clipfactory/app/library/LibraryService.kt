package clipfactory.app.library

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import android.os.PowerManager
import android.util.Log
import clipfactory.app.AudioAnalyzer
import clipfactory.app.MainActivity
import clipfactory.app.Prefs
import clipfactory.app.R
import clipfactory.app.Transcriber
import clipfactory.core.Word
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import java.io.File

/**
 * Works through the library queue one video at a time, in the foreground so Android does not stop it:
 * download (yt-dlp) -> find scenes -> thumbnails -> describe scenes (Claude, if a key is set) -> speech (Groq, if set).
 */
class LibraryService : Service() {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private var job: Job? = null
    private var currentProcess: String? = null
    private var wake: PowerManager.WakeLock? = null

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_CANCEL) {
            val id = intent.getStringExtra("id")
            if (id != null) {
                LibraryStore.update(this, id) { it.copy(status = "canceled", progress = 0.0) }
                if (currentId == id) currentProcess?.let { Downloader.cancel(it) }
            }
            return START_NOT_STICKY
        }
        startForegroundCompat(notification("Preparando a biblioteca…", 0))
        if (job?.isActive != true) {
            wake = (getSystemService(Context.POWER_SERVICE) as PowerManager)
                .newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "clipfactory:library").apply { acquire(6 * 60 * 60 * 1000L) }
            job = scope.launch { runQueue() }
        }
        return START_NOT_STICKY
    }

    @Volatile private var currentId: String? = null

    private suspend fun runQueue() {
        LibraryStore.load(this)
        try {
            while (isActiveScope()) {
                val next = LibraryStore.videos.value.firstOrNull { it.status == "queued" } ?: break
                currentId = next.id
                process(next)
            }
        } finally {
            currentId = null
            wake?.let { if (it.isHeld) it.release() }
            stopForeground(STOP_FOREGROUND_REMOVE)
            stopSelf()
        }
    }

    private fun isActiveScope() = scope.isActive

    private fun set(id: String, status: String, progress: Double, text: String) {
        LibraryStore.update(this, id) { it.copy(status = status, progress = progress, error = "") }
        nm().notify(ID, notification(text, (progress * 100).toInt()))
    }

    private fun process(v: LibVideo) {
        val id = v.id
        val short = v.title.take(40)
        try {
            // 1. download (skipped if the file is already there)
            var file = v.file.takeIf { it.isNotEmpty() && File(it).exists() }?.let { File(it) }
            if (file == null) {
                set(id, "downloading", 0.0, "Baixando: $short")
                if (v.url.startsWith("content://")) {
                    file = importFile(android.net.Uri.parse(v.url), id) { p ->
                        LibraryStore.update(this, id) { it.copy(progress = p) }
                    }
                } else {
                    val pid = "dl-$id"
                    currentProcess = pid
                    file = Downloader.download(this, v.url, id, pid) { p ->
                        LibraryStore.update(this, id) { it.copy(progress = p) }
                        nm().notify(ID, notification("Baixando: $short", (p * 100).toInt()))
                    }
                    currentProcess = null
                }
                LibraryStore.update(this, id) { it.copy(file = file.path) }
            }
            if (LibraryStore.get(id)?.status == "canceled") return
            // 2. scenes
            set(id, "scanning", 0.0, "Separando cenas: $short")
            val scan = SceneScanner.scan(file.path) { p ->
                LibraryStore.update(this, id) { it.copy(progress = p * 0.8) }
            }
            val cuts = scan.cuts
            LibraryStore.update(this, id) { it.copy(info = "${scan.frames} quadros analisados") }
            val thumbs = SceneScanner.thumbnails(file.path, cuts, File(LibraryStore.dir(this), "thumbs/$id"))
            var scenes = cuts.mapIndexed { i, c -> LibScene(i, c.start, c.end, thumbs[i]) }
            LibraryStore.update(this, id) { it.copy(scenes = scenes, progress = 0.9) }
            // 3. speech per scene (optional, Groq)
            val opt = Prefs.options(this)
            if (opt.groqKey.isNotBlank()) {
                set(id, "describing", 0.9, "Transcrevendo falas: $short")
                val words = runCatching { transcribe(file, opt.groqKey, opt.language) }.getOrElse {
                    Log.w("Library", "speech failed", it); emptyList()
                }
                scenes = scenes.map { s ->
                    s.copy(speech = words.filter { it.start >= s.start && it.start < s.end }.joinToString(" ") { it.text })
                }
                LibraryStore.update(this, id) { it.copy(scenes = scenes) }
            }
            // 4. what happens in each scene (optional, Claude): 16 scenes per request
            if (opt.claudeKey.isNotBlank()) {
                val batches = scenes.chunked(16)
                for ((b, batch) in batches.withIndex()) {
                    if (LibraryStore.get(id)?.status == "canceled") return
                    set(id, "describing", b.toDouble() / batches.size, "Descrevendo cenas ${b * 16 + 1}–${b * 16 + batch.size} de ${scenes.size}: $short")
                    val desc = runCatching {
                        SceneAI.describe(opt.claudeKey, v.title, batch.filter { File(it.thumb).exists() }.map { it.idx to File(it.thumb) })
                    }.getOrElse { Log.w("Library", "describe failed", it); emptyMap() }
                    scenes = scenes.map { s -> desc[s.idx]?.let { s.copy(desc = it) } ?: s }
                    LibraryStore.update(this, id) { it.copy(scenes = scenes) }
                }
            }
            LibraryStore.update(this, id) { it.copy(status = "ready", progress = 1.0, error = "") }
        } catch (e: Throwable) {
            Log.e("Library", "failed: ${v.url}", e)
            val canceled = LibraryStore.get(id)?.status == "canceled"
            LibraryStore.update(this, id) {
                it.copy(status = if (canceled) "canceled" else "failed", error = Downloader.explain(e))
            }
        } finally {
            currentProcess = null
        }
    }

    /** Copies a video picked from the phone into the library folder (so it stays even if the original is moved). */
    private fun importFile(uri: android.net.Uri, id: String, onProgress: (Double) -> Unit): File {
        val dir = File(LibraryStore.dir(this), "videos").apply { mkdirs() }
        val out = File(dir, "$id.mp4")
        val total = contentResolver.openFileDescriptor(uri, "r")?.use { it.statSize }?.takeIf { it > 0 } ?: -1L
        contentResolver.openInputStream(uri)!!.use { inp ->
            out.outputStream().use { o ->
                val buf = ByteArray(1 shl 20)
                var done = 0L
                while (true) {
                    val n = inp.read(buf)
                    if (n < 0) break
                    o.write(buf, 0, n)
                    done += n
                    if (total > 0) onProgress(done.toDouble() / total)
                }
            }
        }
        return out
    }

    private fun transcribe(file: File, key: String, language: String): List<Word> {
        val audio = AudioAnalyzer(this, android.net.Uri.fromFile(file))
        val tr = Transcriber(key, language.ifBlank { null })
        val dur = LibraryStore.videos.value.firstOrNull { it.file == file.path }?.durationS?.takeIf { it > 0 } ?: 3600.0
        val out = ArrayList<Word>()
        var a = 0.0
        while (a < dur) {
            val b = minOf(dur, a + 300)
            val part = runCatching { tr.words(audio.wav16k(a, b), a) }.getOrElse { break }
            out += part
            a = b
        }
        return out
    }

    private fun nm() = getSystemService(NotificationManager::class.java)

    private fun notification(text: String, pct: Int): Notification {
        nm().createNotificationChannel(NotificationChannel("library", "Biblioteca", NotificationManager.IMPORTANCE_LOW))
        val open = PendingIntent.getActivity(this, 3, Intent(this, MainActivity::class.java).putExtra("tab", "library"),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
        return Notification.Builder(this, "library").setSmallIcon(R.drawable.ic_notify)
            .setContentTitle("Biblioteca de scenepacks").setContentText(text)
            .setProgress(100, pct.coerceIn(0, 100), pct < 0).setOngoing(true).setContentIntent(open).build()
    }

    private fun startForegroundCompat(n: Notification) {
        when {
            Build.VERSION.SDK_INT >= 35 -> startForeground(ID, n, ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC)
            Build.VERSION.SDK_INT >= 29 -> startForeground(ID, n, ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC)
            else -> startForeground(ID, n)
        }
    }

    override fun onDestroy() {
        currentProcess?.let { Downloader.cancel(it) }
        scope.coroutineContext[Job]?.cancel()
        super.onDestroy()
    }

    companion object {
        private const val ID = 7
        const val ACTION_CANCEL = "cancel"
        fun kick(ctx: Context) = ctx.startForegroundService(Intent(ctx, LibraryService::class.java))
        fun cancel(ctx: Context, id: String) =
            ctx.startService(Intent(ctx, LibraryService::class.java).setAction(ACTION_CANCEL).putExtra("id", id))
    }
}
