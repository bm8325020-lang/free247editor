package clipfactory.app

import android.Manifest
import android.app.Activity
import android.content.Intent
import android.content.pm.PackageManager
import android.content.res.ColorStateList
import android.graphics.Bitmap
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.OpenableColumns
import android.text.InputType
import android.util.Size
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.Button
import android.widget.CompoundButton
import android.widget.EditText
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ProgressBar
import android.widget.RadioButton
import android.widget.RadioGroup
import android.widget.ScrollView
import android.widget.SeekBar
import android.widget.Switch
import android.widget.TextView
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class MainActivity : Activity() {
    private val ui = CoroutineScope(SupervisorJob() + Dispatchers.Main)
    private var watch: Job? = null
    private var selected: Uri? = null
    private var selectedName = "video"

    private val bg = Color.rgb(14, 15, 18)
    private val card = Color.rgb(23, 25, 30)
    private val fg = Color.rgb(242, 242, 242)
    private val muted = Color.rgb(154, 160, 170)
    private val accent = Color.rgb(255, 212, 0)

    private lateinit var videoLabel: TextView
    private lateinit var go: Button
    private lateinit var cancel: Button
    private lateinit var progress: ProgressBar
    private lateinit var stage: TextView
    private lateinit var results: LinearLayout
    private lateinit var opts: Options

    private fun dp(v: Int) = (v * resources.displayMetrics.density).toInt()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        opts = Prefs.options(this)
        setContentView(build())
        if (Build.VERSION.SDK_INT >= 33 &&
            checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED
        ) requestPermissions(arrayOf(Manifest.permission.POST_NOTIFICATIONS), 2)
        handleIncoming(intent)
        renderHistory()
        watch = ui.launch { JobState.flow.collect { show(it) } }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        handleIncoming(intent)
    }

    override fun onDestroy() {
        ui.cancel()
        super.onDestroy()
    }

    private fun handleIncoming(i: Intent?) {
        val uri = when (i?.action) {
            Intent.ACTION_SEND -> if (Build.VERSION.SDK_INT >= 33) i.getParcelableExtra(Intent.EXTRA_STREAM, Uri::class.java)
            else @Suppress("DEPRECATION") i.getParcelableExtra(Intent.EXTRA_STREAM)
            Intent.ACTION_VIEW -> i.data
            else -> null
        } ?: return
        choose(uri)
        // debug builds only: start right away with a ready transcript (used by the emulator test)
        if (BuildConfig.DEBUG && i?.getBooleanExtra("autostart", false) == true) {
            opts = opts.copy(clips = i.getIntExtra("clips", opts.clips), maxSeconds = i.getIntExtra("max", opts.maxSeconds)); save()
            startForegroundService(Intent(this, ClipService::class.java).setData(uri)
                .putExtra(ClipService.EXTRA_NAME, i.getStringExtra("name") ?: "test")
                .putExtra("test_words", i.getStringExtra("test_words")))
        }
    }

    // ---------- UI ----------

    private fun text(s: String, size: Float = 15f, color: Int = fg, bold: Boolean = false) = TextView(this).apply {
        text = s; textSize = size; setTextColor(color)
        if (bold) typeface = Typeface.DEFAULT_BOLD
    }

    private fun box(): LinearLayout = LinearLayout(this).apply {
        orientation = LinearLayout.VERTICAL
        setPadding(dp(16), dp(14), dp(16), dp(16))
        background = GradientDrawable().apply { setColor(card); cornerRadius = dp(14).toFloat() }
        layoutParams = LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(12) }
    }

    private fun button(label: String, primary: Boolean = false, onClick: () -> Unit) = Button(this).apply {
        text = label; isAllCaps = false; textSize = 16f
        setTextColor(if (primary) Color.BLACK else fg)
        background = GradientDrawable().apply {
            setColor(if (primary) accent else Color.rgb(40, 43, 50)); cornerRadius = dp(12).toFloat()
        }
        setPadding(dp(14), dp(12), dp(14), dp(12))
        layoutParams = LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(10) }
        setOnClickListener { onClick() }
    }

    private fun field(hint: String, value: String, secret: Boolean, onChange: (String) -> Unit) = EditText(this).apply {
        this.hint = hint; setText(value); setTextColor(fg); setHintTextColor(muted); textSize = 15f
        inputType = if (secret) InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_PASSWORD
        else InputType.TYPE_CLASS_TEXT
        backgroundTintList = ColorStateList.valueOf(muted)
        setOnFocusChangeListener { _, has -> if (!has) onChange(getText().toString()) }
        addTextChangedListener(object : android.text.TextWatcher {
            override fun afterTextChanged(e: android.text.Editable?) = onChange(e.toString())
            override fun beforeTextChanged(s: CharSequence?, a: Int, b: Int, c: Int) {}
            override fun onTextChanged(s: CharSequence?, a: Int, b: Int, c: Int) {}
        })
    }

    private fun slider(label: (Int) -> String, min: Int, max: Int, step: Int, value: Int, onChange: (Int) -> Unit): View {
        val wrap = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        val t = text(label(value), 15f)
        val sb = SeekBar(this).apply {
            this.max = (max - min) / step
            progress = (value - min) / step
            progressTintList = ColorStateList.valueOf(accent); thumbTintList = ColorStateList.valueOf(accent)
            setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
                override fun onProgressChanged(s: SeekBar?, p: Int, fromUser: Boolean) {
                    val v = min + p * step
                    t.text = label(v); onChange(v)
                }
                override fun onStartTrackingTouch(s: SeekBar?) {}
                override fun onStopTrackingTouch(s: SeekBar?) {}
            })
        }
        wrap.addView(t); wrap.addView(sb)
        wrap.setPadding(0, dp(8), 0, 0)
        return wrap
    }

    private fun switch(label: String, value: Boolean, onChange: (Boolean) -> Unit) = Switch(this).apply {
        text = label; isChecked = value; setTextColor(fg); textSize = 15f
        thumbTintList = ColorStateList.valueOf(accent)
        setPadding(0, dp(8), 0, dp(8))
        setOnCheckedChangeListener { _: CompoundButton, b: Boolean -> onChange(b) }
    }

    private fun save() = Prefs.save(this, opts)

    private fun build(): View {
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(16), dp(24), dp(16), dp(32))
        }
        root.addView(text("Fábrica de Clipes", 28f, fg, true))
        root.addView(text("Live ou gameplay longo → clipes verticais prontos para TikTok, Reels e Shorts.", 14f, muted))

        val v = box()
        v.addView(text("1. Vídeo", 17f, fg, true))
        videoLabel = text("Nenhum vídeo escolhido. Dica: em qualquer app, use Compartilhar → Fábrica de Clipes.", 14f, muted)
        v.addView(videoLabel)
        v.addView(button("Escolher vídeo") { pick() })
        root.addView(v)

        val o = box()
        o.addView(text("2. Opções", 17f, fg, true))
        o.addView(slider({ "Quantidade de clipes: $it" }, 3, 15, 1, opts.clips) { opts = opts.copy(clips = it); save() })
        o.addView(slider({ "Duração máxima: $it s" }, 20, 60, 5, opts.maxSeconds) { opts = opts.copy(maxSeconds = it); save() })
        o.addView(text("Layout", 15f, fg).apply { setPadding(0, dp(8), 0, 0) })
        val layouts = listOf(
            "auto" to "Automático (recomendado): webcam em cima + gameplay embaixo; sem webcam, só gameplay",
            "split" to "Webcam em cima + gameplay embaixo (TikTok/Shorts)",
            "pip" to "Webcam pequena no canto",
            "game" to "Só gameplay (nunca mostrar webcam)",
            "follow" to "Seguir o rosto (just chatting)")
        val rg = RadioGroup(this)
        layouts.forEachIndexed { i, (key, label) ->
            rg.addView(RadioButton(this).apply {
                id = 1000 + i; text = label; setTextColor(fg); buttonTintList = ColorStateList.valueOf(accent)
                isChecked = opts.layout == key
            })
        }
        rg.setOnCheckedChangeListener { _, id -> opts = opts.copy(layout = layouts[id - 1000].first); save() }
        o.addView(rg)
        o.addView(switch("Legendas palavra por palavra", opts.captions) { opts = opts.copy(captions = it); save() })
        o.addView(switch("Título de gancho nos primeiros 3 s", opts.hook) { opts = opts.copy(hook = it); save() })
        root.addView(o)

        val k = box()
        k.addView(text("3. Chaves (opcional, mas melhora muito)", 17f, fg, true))
        k.addView(text("Groq (grátis): transcrição para legendas e palavras de hype. Sem ela, os clipes saem sem legenda e escolhidos só pelo áudio.", 13f, muted))
        k.addView(field("Chave do Groq (gsk_...)", opts.groqKey, true) { opts = opts.copy(groqKey = it); save() })
        val groqStatus = text("", 13f, muted)
        k.addView(button("Testar chave do Groq") {
            groqStatus.setTextColor(muted); groqStatus.text = "Testando…"
            ui.launch {
                val r = withContext(Dispatchers.IO) { KeyCheck.groq(opts.groqKey) }
                groqStatus.setTextColor(if (r.ok) Color.rgb(90, 220, 120) else Color.rgb(255, 120, 110))
                groqStatus.text = r.message
            }
        })
        k.addView(groqStatus)
        k.addView(button("Pegar chave grátis do Groq") {
            startActivity(Intent(Intent.ACTION_VIEW, Uri.parse("https://console.groq.com/keys")))
        })
        k.addView(text("Anthropic (pago por uso, centavos por live): o Claude escolhe os momentos e escreve os títulos.", 13f, muted).apply { setPadding(0, dp(12), 0, 0) })
        k.addView(field("Chave da Anthropic (sk-ant-...)", opts.claudeKey, true) { opts = opts.copy(claudeKey = it); save() })
        val claudeStatus = text("", 13f, muted)
        k.addView(button("Testar chave da Anthropic") {
            claudeStatus.setTextColor(muted); claudeStatus.text = "Testando…"
            ui.launch {
                val r = withContext(Dispatchers.IO) { KeyCheck.anthropic(opts.claudeKey) }
                claudeStatus.setTextColor(if (r.ok) Color.rgb(90, 220, 120) else Color.rgb(255, 120, 110))
                claudeStatus.text = r.message
            }
        })
        k.addView(claudeStatus)
        k.addView(text("As chaves ficam criptografadas no aparelho (Android Keystore) e só são enviadas ao próprio provedor.", 12f, muted).apply { setPadding(0, dp(8), 0, 0) })
        k.addView(field("Idioma da live (ex.: pt). Vazio = detectar", opts.language, false) { opts = opts.copy(language = it); save() })
        root.addView(k)

        go = button("CRIAR CLIPES", primary = true) { start() }.apply { textSize = 18f; typeface = Typeface.DEFAULT_BOLD }
        root.addView(go)
        progress = ProgressBar(this, null, android.R.attr.progressBarStyleHorizontal).apply {
            max = 1000; visibility = View.GONE
            progressTintList = ColorStateList.valueOf(accent)
            layoutParams = LinearLayout.LayoutParams(-1, dp(10)).apply { topMargin = dp(14) }
        }
        root.addView(progress)
        stage = text("", 14f, muted).apply { setPadding(0, dp(6), 0, 0) }
        root.addView(stage)
        cancel = button("Cancelar") { ClipService.cancel(this) }.apply { visibility = View.GONE }
        root.addView(cancel)

        results = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        root.addView(results)

        return ScrollView(this).apply { setBackgroundColor(bg); addView(root) }
    }

    // ---------- actions ----------

    private fun pick() {
        startActivityForResult(Intent(Intent.ACTION_OPEN_DOCUMENT).apply {
            addCategory(Intent.CATEGORY_OPENABLE); type = "video/*"
        }, 1)
    }

    @Deprecated("Deprecated in Java")
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (requestCode == 1 && resultCode == RESULT_OK) data?.data?.let {
            try {
                contentResolver.takePersistableUriPermission(it, Intent.FLAG_GRANT_READ_URI_PERMISSION)
            } catch (_: SecurityException) {
            }
            choose(it)
        }
    }

    private fun choose(uri: Uri) {
        selected = uri
        selectedName = contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use {
            if (it.moveToFirst()) it.getString(0) else null
        } ?: "video"
        videoLabel.text = selectedName
        videoLabel.setTextColor(fg)
        ui.launch {
            val info = withContext(Dispatchers.IO) { runCatching { FrameAnalyzer(this@MainActivity, uri).info() }.getOrNull() }
            if (info != null) {
                val m = (info.durationS / 60).toInt()
                videoLabel.text = "$selectedName\n${info.width}x${info.height} · ${m / 60}h${"%02d".format(m % 60)}min"
            }
        }
    }

    private fun start() {
        val uri = selected ?: return pick()
        save()
        ClipService.start(this, uri, selectedName)
    }

    private fun show(s: JobStatus) {
        when (s) {
            is JobStatus.Running -> {
                go.isEnabled = false; go.alpha = 0.5f
                progress.visibility = View.VISIBLE; cancel.visibility = View.VISIBLE
                progress.progress = (s.progress * 1000).toInt()
                stage.text = "${s.stage}… ${(s.progress * 100).toInt()}%"
            }
            is JobStatus.Done -> {
                idle()
                stage.text = if (s.result.clips.isEmpty()) "Nenhum clipe saiu. Veja o motivo abaixo."
                else "Pronto: ${s.result.clips.size} clipes salvos na galeria (Movies/Clipes)."
                renderHistory()
            }
            is JobStatus.Failed -> { idle(); stage.text = "Erro: ${s.message}" }
            JobStatus.Idle -> idle()
        }
    }

    private fun idle() {
        go.isEnabled = true; go.alpha = 1f
        progress.visibility = View.GONE; cancel.visibility = View.GONE
    }

    private fun renderHistory() {
        results.removeAllViews()
        val jobs = History.load(this)
        for (job in jobs.take(5)) {
            val b = box()
            b.addView(text(job.name, 16f, fg, true))
            b.addView(text("${job.date} · ${job.clips.size} clipes", 13f, muted))
            for (n in job.notes) b.addView(text("• $n", 13f, accent).apply { setPadding(0, dp(4), 0, 0) })
            for (c in job.clips) b.addView(clipRow(c))
            results.addView(b)
        }
    }

    private fun clipRow(c: ClipResult): View {
        val uri = Uri.parse(c.uri)
        val row = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            setPadding(0, dp(12), 0, 0)
            gravity = Gravity.CENTER_VERTICAL
        }
        val thumb = ImageView(this).apply {
            layoutParams = LinearLayout.LayoutParams(dp(72), dp(128))
            scaleType = ImageView.ScaleType.CENTER_CROP
            setBackgroundColor(Color.BLACK)
        }
        ui.launch {
            val bmp: Bitmap? = withContext(Dispatchers.IO) {
                runCatching { contentResolver.loadThumbnail(uri, Size(216, 384), null) }.getOrNull()
            }
            if (bmp != null) thumb.setImageBitmap(bmp)
        }
        val col = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(12), 0, 0, 0)
            layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
        }
        val at = c.sourceStart.toInt()
        col.addView(text("#${c.rank}  ${c.title}", 15f, fg, true))
        col.addView(text("nota ${c.score} · ${c.seconds.toInt()} s · aos %d:%02d:%02d · %s".format(at / 3600, at % 3600 / 60, at % 60, c.layout), 12f, muted))
        val actions = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        fun small(label: String, f: () -> Unit) = Button(this).apply {
            text = label; isAllCaps = false; textSize = 13f; setTextColor(Color.BLACK)
            background = GradientDrawable().apply { setColor(accent); cornerRadius = dp(10).toFloat() }
            layoutParams = LinearLayout.LayoutParams(0, dp(40), 1f).apply { marginEnd = dp(6); topMargin = dp(6) }
            setOnClickListener { f() }
        }
        actions.addView(small("Assistir") {
            runCatching {
                startActivity(Intent(Intent.ACTION_VIEW).setDataAndType(uri, "video/mp4").addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION))
            }
        })
        actions.addView(small("Compartilhar") {
            startActivity(Intent.createChooser(Intent(Intent.ACTION_SEND).apply {
                type = "video/mp4"; putExtra(Intent.EXTRA_STREAM, uri); putExtra(Intent.EXTRA_TEXT, c.title)
                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            }, "Postar clipe"))
        })
        col.addView(actions)
        row.addView(thumb)
        row.addView(col)
        return row
    }
}
