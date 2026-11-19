package clipfactory.app

import android.Manifest
import android.annotation.SuppressLint
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.content.pm.ServiceInfo
import android.graphics.Color
import android.graphics.PixelFormat
import android.graphics.drawable.GradientDrawable
import android.hardware.display.DisplayManager
import android.hardware.display.VirtualDisplay
import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioPlaybackCaptureConfiguration
import android.media.AudioRecord
import android.media.MediaCodec
import android.media.MediaCodecInfo
import android.media.MediaFormat
import android.media.projection.MediaProjection
import android.media.projection.MediaProjectionManager
import android.net.Uri
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.os.PowerManager
import android.provider.Settings
import android.util.Log
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.WindowManager
import android.widget.LinearLayout
import android.widget.TextView
import clipfactory.core.Hype
import clipfactory.core.LiveDetect
import clipfactory.core.Word
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.TimeUnit
import kotlin.math.log10
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sqrt
import kotlin.random.Random

data class LiveState(
    val running: Boolean = false, val channel: String = "", val startedAt: Long = 0, val msgsPerMin: Int = 0,
    val chatZ: Double = 0.0, val audioZ: Double = 0.0, val clips: Int = 0, val warming: Boolean = true,
    val status: String = "", val warning: String? = null,
)

/**
 * Clips a live the user is watching in the Twitch app: records the screen and the playback sound (both with the
 * user's consent, through Android's screen-capture prompt) into a rolling 3-minute buffer, reads the live's public
 * chat (anonymous, read-only), and cuts a clip when the chat or the audio spikes, or when the user taps ✂.
 * Each clip becomes a project rendered by ClipService like any other video.
 */
class LiveCaptureService : Service() {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private val main = Handler(Looper.getMainLooper())
    private var projection: MediaProjection? = null
    private var display: VirtualDisplay? = null
    private var venc: MediaCodec? = null
    private var aenc: MediaCodec? = null
    private var rec: AudioRecord? = null
    private var writer: SegmentWriter? = null
    private var ws: WebSocket? = null
    private var wake: PowerManager.WakeLock? = null
    private var bubble: View? = null
    private var bubbleText: TextView? = null
    @Volatile private var running = false
    @Volatile private var manual = false
    private var channel = ""
    private var startUs = 0L
    private var lastClipUs = Long.MIN_VALUE / 2
    private val chat = ConcurrentHashMap<Long, Double>() // second (monotonic) -> weighted messages
    private val chatCount = ConcurrentHashMap<Long, Int>()
    private val loud = ConcurrentHashMap<Long, DoubleArray>() // second -> [sum dB, count]
    private val pending = ArrayList<Cut>()
    private val cutLock = Mutex()
    private val http = OkHttpClient.Builder().pingInterval(60, TimeUnit.SECONDS).build()

    private data class Cut(val fromUs: Long, val toUs: Long, val sceneUs: Long, val chatZ: Double, val audioZ: Double,
                           val manual: Boolean, val wall: Long)

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_CLIP -> { manual = true; return START_NOT_STICKY }
            ACTION_STOP -> { stopAll(); return START_NOT_STICKY }
            ACTION_START -> {}
            else -> return START_NOT_STICKY
        }
        // Android requires the foreground notification before the screen capture starts
        startForeground(ID, notification("Preparando…"), ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PROJECTION)
        if (running) return START_NOT_STICKY
        channel = intent.getStringExtra(EXTRA_CHANNEL).orEmpty().trim().removePrefix("@").lowercase()
        val code = intent.getIntExtra(EXTRA_CODE, 0)
        @Suppress("DEPRECATION") val data: Intent? = if (Build.VERSION.SDK_INT >= 33)
            intent.getParcelableExtra(EXTRA_DATA, Intent::class.java) else intent.getParcelableExtra(EXTRA_DATA)
        try {
            startCapture(code, data ?: throw IllegalStateException("sem permissão de gravar a tela"))
        } catch (e: Exception) {
            Log.e(TAG, "capture failed", e)
            state.value = LiveState(warning = "Não consegui começar a gravar: ${e.message}")
            stopAll()
        }
        return START_NOT_STICKY
    }

    @SuppressLint("MissingPermission")
    private fun startCapture(code: Int, data: Intent) {
        val mpm = getSystemService(MediaProjectionManager::class.java)
        val proj = mpm.getMediaProjection(code, data) ?: throw IllegalStateException("o Android negou a gravação")
        projection = proj
        proj.registerCallback(object : MediaProjection.Callback() {
            override fun onStop() { main.post { stopAll() } }
        }, main)
        running = true
        startUs = nowUs()
        ProjectStore.load(this)
        wake = (getSystemService(Context.POWER_SERVICE) as PowerManager)
            .newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "clipfactory:live").apply { acquire(8 * 60 * 60 * 1000L) }
        val w = SegmentWriter(File(cacheDir, "live-buffer"))
        writer = w

        // video: the screen, landscape (the live is watched full screen with the phone on its side), ~720p
        val dm = resources.displayMetrics
        val long = max(dm.widthPixels, dm.heightPixels).toDouble()
        val short = min(dm.widthPixels, dm.heightPixels).toDouble()
        val vw = 1280
        val vh = ((1280 * short / long).toInt() / 2) * 2
        val vf = MediaFormat.createVideoFormat(MediaFormat.MIMETYPE_VIDEO_AVC, vw, vh).apply {
            setInteger(MediaFormat.KEY_COLOR_FORMAT, MediaCodecInfo.CodecCapabilities.COLOR_FormatSurface)
            setInteger(MediaFormat.KEY_BIT_RATE, 4_000_000)
            setInteger(MediaFormat.KEY_FRAME_RATE, 30)
            setInteger(MediaFormat.KEY_I_FRAME_INTERVAL, 1)
            // Android sends no frame while the screen does not change (paused live, still image): repeat the last one,
            // or the buffer would stop advancing and nothing could be cut
            setLong(MediaFormat.KEY_REPEAT_PREVIOUS_FRAME_AFTER, 100_000L)
        }
        val v = MediaCodec.createEncoderByType(MediaFormat.MIMETYPE_VIDEO_AVC)
        v.configure(vf, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE)
        val surface = v.createInputSurface()
        v.start()
        venc = v
        display = proj.createVirtualDisplay("clipfactory-live", vw, vh, dm.densityDpi,
            DisplayManager.VIRTUAL_DISPLAY_FLAG_AUTO_MIRROR, surface, null, null)
        Thread({ drain(v, true) }, "live-video").start()

        // audio: what the phone is playing (the Twitch app), not the microphone
        val canAudio = checkSelfPermission(Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED
        if (canAudio) runCatching {
            val cfg = AudioPlaybackCaptureConfiguration.Builder(proj)
                .addMatchingUsage(AudioAttributes.USAGE_MEDIA).addMatchingUsage(AudioAttributes.USAGE_GAME)
                .addMatchingUsage(AudioAttributes.USAGE_UNKNOWN).build()
            val fmt = AudioFormat.Builder().setEncoding(AudioFormat.ENCODING_PCM_16BIT).setSampleRate(RATE)
                .setChannelMask(AudioFormat.CHANNEL_IN_STEREO).build()
            val r = AudioRecord.Builder().setAudioFormat(fmt).setAudioPlaybackCaptureConfig(cfg)
                .setBufferSizeInBytes(max(AudioRecord.getMinBufferSize(RATE, AudioFormat.CHANNEL_IN_STEREO,
                    AudioFormat.ENCODING_PCM_16BIT) * 4, 32768)).build()
            val af = MediaFormat.createAudioFormat(MediaFormat.MIMETYPE_AUDIO_AAC, RATE, 2).apply {
                setInteger(MediaFormat.KEY_AAC_PROFILE, MediaCodecInfo.CodecProfileLevel.AACObjectLC)
                setInteger(MediaFormat.KEY_BIT_RATE, 128_000)
                setInteger(MediaFormat.KEY_MAX_INPUT_SIZE, 16384)
            }
            val a = MediaCodec.createEncoderByType(MediaFormat.MIMETYPE_AUDIO_AAC)
            a.configure(af, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE)
            a.start()
            r.startRecording()
            rec = r; aenc = a
            Thread({ audioLoop(r, a) }, "live-audio").start()
        }.onFailure { Log.w(TAG, "no playback audio", it) }
        if (rec == null) {
            w.audioEnabled = false
            warn("Sem som: permita o acesso ao áudio para os clipes saírem com o som da live.")
        }

        connectChat()
        showBubble()
        state.value = LiveState(true, channel, System.currentTimeMillis(), status = "Conhecendo o ritmo da live (2 min)…")
        scope.launch { ticker() }
    }

    // ---------- encoders ----------
    private fun drain(codec: MediaCodec, video: Boolean) {
        val info = MediaCodec.BufferInfo()
        while (running) {
            // video: its own thread, waits for frames; audio: called between reads, takes what is ready and returns
            val idx = try { codec.dequeueOutputBuffer(info, if (video) 20_000 else 0) } catch (e: IllegalStateException) { break }
            if (!video && idx == MediaCodec.INFO_TRY_AGAIN_LATER) break
            when {
                idx == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED ->
                    if (video) writer?.setVideoFormat(codec.outputFormat) else writer?.setAudioFormat(codec.outputFormat)
                idx >= 0 -> {
                    val buf = codec.getOutputBuffer(idx)
                    if (buf != null) runCatching { if (video) writer?.writeVideo(buf, info) else writer?.writeAudio(buf, info) }
                    codec.releaseOutputBuffer(idx, false)
                }
            }
        }
    }

    private fun audioLoop(r: AudioRecord, a: MediaCodec) {
        val chunk = ByteArray(8192) // ~46 ms of 44.1 kHz stereo 16-bit
        var lastPts = 0L
        while (running) {
            val n = r.read(chunk, 0, chunk.size)
            if (n <= 0) continue
            val durUs = n / 4 * 1_000_000L / RATE
            val pts = max(lastPts + 1, nowUs() - durUs)
            lastPts = pts
            // loudness for the detector
            var sum = 0.0
            var i = 0
            while (i + 1 < n) { val s = (chunk[i].toInt() and 0xff) or (chunk[i + 1].toInt() shl 8); sum += s.toDouble() * s; i += 2 }
            val db = 20 * log10(sqrt(sum / max(1, n / 2)) / 32768 + 1e-9)
            loud.compute(pts / 1_000_000) { _, v -> (v ?: DoubleArray(2)).also { it[0] += db; it[1] += 1 } }
            try {
                val inIdx = a.dequeueInputBuffer(10_000)
                if (inIdx >= 0) {
                    val ib = a.getInputBuffer(inIdx)!!
                    ib.clear(); ib.put(chunk, 0, min(n, ib.remaining()))
                    a.queueInputBuffer(inIdx, 0, min(n, ib.capacity()), pts, 0)
                }
                drain(a, false)
            } catch (e: IllegalStateException) { break }
        }
    }

    // ---------- chat (public, anonymous, read-only) ----------
    private fun connectChat() {
        if (!running || channel.isBlank()) return
        ws = http.newWebSocket(Request.Builder().url("wss://irc-ws.chat.twitch.tv:443").build(), object : WebSocketListener() {
            override fun onOpen(webSocket: WebSocket, response: Response) {
                webSocket.send("NICK justinfan${Random.nextInt(10000, 99999)}")
                webSocket.send("JOIN #$channel")
            }

            override fun onMessage(webSocket: WebSocket, text: String) {
                for (line in text.split("\r\n")) {
                    if (line.startsWith("PING")) { webSocket.send("PONG :tmi.twitch.tv"); continue }
                    val i = line.indexOf(" PRIVMSG #")
                    if (i < 0) continue
                    val j = line.indexOf(" :", i)
                    if (j < 0) continue
                    val sec = nowUs() / 1_000_000
                    chat.merge(sec, LiveDetect.messageWeight(line.substring(j + 2)), Double::plus)
                    chatCount.merge(sec, 1, Int::plus)
                }
            }

            override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) = retry()
            override fun onClosed(webSocket: WebSocket, code: Int, reason: String) = retry()
            private fun retry() { if (running) scope.launch { delay(10_000); connectChat() } }
        })
    }

    // ---------- detection ----------
    private suspend fun ticker() {
        val opt = Prefs.options(this)
        val threshold = doubleArrayOf(2.5, 3.0, 3.8)[opt.liveLevel.coerceIn(0, 2)]
        var silentSince = 0
        while (running) {
            delay(1000)
            val now = nowUs()
            val nowSec = now / 1_000_000
            val first = max(startUs / 1_000_000, nowSec - 600)
            val secs = (first until nowSec)
            val chatSeries = secs.map { chat[it] ?: 0.0 }.toDoubleArray()
            var prev = -90.0
            val loudSeries = secs.map { s -> loud[s]?.let { prev = it[0] / it[1]; prev } ?: prev }.toDoubleArray()
            // keep memory small
            chat.keys.removeIf { it < first }; chatCount.keys.removeIf { it < first }; loud.keys.removeIf { it < first }
            val warm = nowSec - startUs / 1_000_000 >= 120
            val cz = if (warm) LiveDetect.robustZ(chatSeries, 240) else 0.0
            val (az, ago) = if (warm && rec != null) LiveDetect.audioZ(loudSeries) else (0.0 to 0)
            val msgs = (nowSec - 60 until nowSec).sumOf { chatCount[it] ?: 0 }
            if (rec != null && loudSeries.isNotEmpty() && loudSeries.last() < -80) silentSince++ else silentSince = 0
            if (silentSince == 45) warn("O som está mudo há 45 s. Se a live não está muda, o app da Twitch pode não deixar " +
                "gravar o som: os clipes vão sair sem áudio.")

            // the sound never arrived (the phone or the app does not allow capturing it): record without it
            val w = writer
            if (w != null && w.audioEnabled && !w.hasAudioFormat() && now - startUs > 8_000_000L) {
                w.audioEnabled = false
                warn("O som da live não chegou: gravando só a imagem. Os clipes vão sair sem áudio.")
            }

            val cooled = now - lastClipUs >= 90_000_000L
            val wantManual = manual.also { manual = false }
            if (wantManual || (warm && cooled && LiveDetect.fires(cz, az, threshold))) {
                val scene = when {
                    wantManual -> now - 20_000_000L // the user taps right after the moment
                    az >= cz -> now - ago * 1_000_000L
                    else -> now - (LiveDetect.chatOnset(chatSeries) + 3) * 1_000_000L
                }
                lastClipUs = now
                synchronized(pending) {
                    pending.add(Cut(scene - 20_000_000L, now + (if (wantManual) 5 else 10) * 1_000_000L, scene, cz, az,
                        wantManual, System.currentTimeMillis()))
                }
                vibrateText(if (wantManual) "✂ Cortando…" else "🔥 Momento! Cortando…")
            }
            // cut what is fully recorded
            val newest = writer?.closed()?.lastOrNull()?.endUs ?: 0
            val ready = synchronized(pending) { pending.filter { it.toUs <= newest }.also { pending.removeAll(it.toSet()) } }
            ready.forEach { c -> scope.launch { cutLock.withLock { cut(c) } } }

            val st = state.value.copy(msgsPerMin = msgs, chatZ = cz, audioZ = az, warming = !warm,
                status = if (!warm) "Conhecendo o ritmo da live (${120 - (nowSec - startUs / 1_000_000)} s)…"
                         else "Vigiando: chat ${"%+.1f".format(cz)}σ · som ${"%+.1f".format(az)}σ")
            state.value = st
            main.post { bubbleText?.text = "● @$channel · ${st.clips} clipes\n${if (st.warming) "aquecendo" else "$msgs msg/min"}" }
        }
    }

    private fun cut(c: Cut) {
        val w = writer ?: return
        try {
            val pid = "live" + System.currentTimeMillis()
            val dir = ProjectStore.dir(this, pid)
            val src = File(dir, "source.mp4")
            val first = w.join(c.fromUs, c.toUs, src) ?: return warn("Perdi um corte: o vídeo já tinha saído do buffer.")
            val uri = Uri.fromFile(src)
            val dur = (w.closed().last().endUs - first) / 1e6
            val a = max(0.0, (c.fromUs - first) / 1e6)
            val b = min(dur, (c.toUs - first) / 1e6)
            val peak = ((c.sceneUs - first) / 1e6).coerceIn(a, b)
            val opt = Prefs.options(this)
            val words: List<Word> = if (opt.groqKey.isNotBlank() && rec != null) runCatching {
                Transcriber(opt.groqKey.trim(), opt.language.ifBlank { null }).words(AudioAnalyzer(this, uri).wav16k(a, b), a)
            }.getOrElse { Log.w(TAG, "transcription failed", it); emptyList() } else emptyList()
            val hook = Hype.hookFrom(words, peak)
            val clock = SimpleDateFormat("HH:mm", Locale.US).format(Date(c.wall))
            val title = hook.ifBlank { "@$channel às $clock" }
            val reason = if (c.manual) "Você tocou em ✂ às $clock." else
                "Chat ${"%.1f".format(c.chatZ)}σ e som ${"%.1f".format(c.audioZ)}σ acima do normal da live, às $clock."
            val score = if (c.manual) 80 else (50 + 8 * max(c.chatZ, c.audioZ)).toInt().coerceIn(1, 99)
            val layout = if (opt.layout == "auto") "pip" else opt.layout // live clips: webcam in the corner
            val m = Moment(0, a, b, peak, title, hook, reason, score, words.joinToString(" ") { it.text }, "", true)
            val clip = PClip(0, title, score, reason, ClipEdit(a, b, if (opt.hook) hook else "", opt.captionStyle, layout))
            ProjectStore.put(this, Project(pid, "@$channel ao vivo $clock", uri.toString(), System.currentTimeMillis(),
                durationS = dur, words = words, moments = listOf(m), clips = listOf(clip),
                folder = "Movies/Clipes/Live $channel " + SimpleDateFormat("yyyy-MM-dd", Locale.US).format(Date()),
                status = "rendering"))
            state.value = state.value.copy(clips = state.value.clips + 1)
            ClipService.render(this, pid, listOf(0))
        } catch (e: Exception) {
            Log.e(TAG, "cut failed", e)
            warn("Um corte falhou: ${e.message?.take(120)}")
        }
    }

    // ---------- bubble over other apps ----------
    @SuppressLint("ClickableViewAccessibility", "SetTextI18n")
    private fun showBubble() {
        if (!Settings.canDrawOverlays(this)) return
        val wm = getSystemService(WindowManager::class.java)
        val dp = resources.displayMetrics.density
        fun round(color: Int, r: Float) = GradientDrawable().apply { setColor(color); cornerRadius = r * dp }
        val text = TextView(this).apply {
            setTextColor(Color.WHITE); textSize = 12f; text = "● @$channel\naquecendo"
            setPadding((12 * dp).toInt(), (6 * dp).toInt(), (8 * dp).toInt(), (6 * dp).toInt())
        }
        fun button(label: String, bg: Int, fg: Int, onTap: () -> Unit) = TextView(this).apply {
            this.text = label; textSize = 18f; setTextColor(fg); gravity = Gravity.CENTER; background = round(bg, 20f)
            layoutParams = LinearLayout.LayoutParams((40 * dp).toInt(), (40 * dp).toInt()).apply { marginEnd = (6 * dp).toInt() }
            setOnClickListener { onTap() }
        }
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL
            background = round(Color.argb(200, 11, 12, 16), 26f)
            setPadding((4 * dp).toInt(), (4 * dp).toInt(), (2 * dp).toInt(), (4 * dp).toInt())
            addView(text)
            addView(button("✂", Color.rgb(255, 212, 0), Color.BLACK) { manual = true })
            addView(button("■", Color.argb(255, 60, 62, 70), Color.WHITE) { stopAll() })
        }
        val lp = WindowManager.LayoutParams(WindowManager.LayoutParams.WRAP_CONTENT, WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY, WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE,
            PixelFormat.TRANSLUCENT).apply { gravity = Gravity.TOP or Gravity.START; x = (12 * dp).toInt(); y = (80 * dp).toInt() }
        // drag the bubble by its text
        var sx = 0f; var sy = 0f; var ox = 0; var oy = 0
        text.setOnTouchListener { _, e ->
            when (e.action) {
                MotionEvent.ACTION_DOWN -> { sx = e.rawX; sy = e.rawY; ox = lp.x; oy = lp.y }
                MotionEvent.ACTION_MOVE -> { lp.x = ox + (e.rawX - sx).toInt(); lp.y = oy + (e.rawY - sy).toInt(); wm.updateViewLayout(root, lp) }
            }
            true
        }
        runCatching { wm.addView(root, lp); bubble = root; bubbleText = text }
    }

    private fun vibrateText(t: String) = main.post { bubbleText?.text = "● @$channel\n$t" }

    private fun warn(msg: String) {
        state.value = state.value.copy(warning = msg)
        getSystemService(NotificationManager::class.java).notify(ID_WARN, Notification.Builder(this, CHANNEL)
            .setSmallIcon(R.drawable.ic_notify).setContentTitle("Clipar live").setContentText(msg)
            .setStyle(Notification.BigTextStyle().bigText(msg)).setAutoCancel(true).build())
    }

    // ---------- stop ----------
    private fun stopAll() {
        if (!running && projection == null) { stopSelf(); return }
        running = false
        runCatching { ws?.close(1000, null) }
        runCatching { rec?.stop(); rec?.release() }
        runCatching { aenc?.stop(); aenc?.release() }
        runCatching { display?.release() }
        runCatching { venc?.signalEndOfInputStream(); venc?.stop(); venc?.release() }
        runCatching { projection?.stop() }
        projection = null
        writer?.finish()
        bubble?.let { b -> runCatching { getSystemService(WindowManager::class.java).removeView(b) } }
        bubble = null
        // cut what was already detected, with what was recorded
        val left = synchronized(pending) { pending.toList().also { pending.clear() } }
        val newest = writer?.closed()?.lastOrNull()?.endUs ?: 0
        val clipsBefore = state.value.clips
        scope.launch {
            left.forEach { c -> cutLock.withLock { cut(c.copy(toUs = min(c.toUs, newest))) } }
            state.value = state.value.copy(running = false, status = "Parado. ${state.value.clips} clipe(s) nesta live.")
            wake?.let { if (it.isHeld) it.release() }
            stopForeground(STOP_FOREGROUND_REMOVE)
            stopSelf()
        }
        if (left.isEmpty() && clipsBefore == 0) state.value = state.value.copy(running = false)
    }

    override fun onDestroy() {
        running = false
        scope.cancel()
        super.onDestroy()
    }

    private fun notification(text: String): Notification {
        getSystemService(NotificationManager::class.java)
            .createNotificationChannel(NotificationChannel(CHANNEL, "Clipar live", NotificationManager.IMPORTANCE_LOW))
        fun action(label: String, act: String, code: Int) = Notification.Action.Builder(null, label,
            PendingIntent.getService(this, code, Intent(this, LiveCaptureService::class.java).setAction(act), PendingIntent.FLAG_IMMUTABLE)).build()
        return Notification.Builder(this, CHANNEL).setSmallIcon(R.drawable.ic_notify).setContentTitle("Clipando a live")
            .setContentText(text).setOngoing(true)
            .setContentIntent(PendingIntent.getActivity(this, 0, Intent(this, MainActivity::class.java), PendingIntent.FLAG_IMMUTABLE))
            .addAction(action("✂ Clipar agora", ACTION_CLIP, 11)).addAction(action("■ Parar", ACTION_STOP, 12)).build()
    }

    companion object {
        private const val TAG = "LiveCapture"
        private const val CHANNEL = "live"
        private const val ID = 7
        private const val ID_WARN = 8
        private const val RATE = 44_100
        const val ACTION_START = "live_start"
        const val ACTION_CLIP = "live_clip"
        const val ACTION_STOP = "live_stop"
        const val EXTRA_CODE = "code"
        const val EXTRA_DATA = "data"
        const val EXTRA_CHANNEL = "channel"
        val state = MutableStateFlow(LiveState())
        val flow: StateFlow<LiveState> get() = state

        private fun nowUs() = System.nanoTime() / 1000

        fun start(ctx: Context, code: Int, data: Intent, channel: String) = ctx.startForegroundService(
            Intent(ctx, LiveCaptureService::class.java).setAction(ACTION_START).putExtra(EXTRA_CODE, code)
                .putExtra(EXTRA_DATA, data).putExtra(EXTRA_CHANNEL, channel))

        fun clip(ctx: Context) = ctx.startService(Intent(ctx, LiveCaptureService::class.java).setAction(ACTION_CLIP))
        fun stop(ctx: Context) = ctx.startService(Intent(ctx, LiveCaptureService::class.java).setAction(ACTION_STOP))
    }
}
