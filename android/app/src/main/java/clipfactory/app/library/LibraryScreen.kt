package clipfactory.app.library

import android.app.Activity
import android.app.AlertDialog
import android.content.Intent
import android.content.res.ColorStateList
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.net.Uri
import android.os.Handler
import android.os.Looper
import android.text.InputType
import android.util.LruCache
import android.view.Gravity
import android.view.View
import android.view.inputmethod.EditorInfo
import android.widget.Button
import android.widget.CheckBox
import android.widget.EditText
import android.widget.GridLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ProgressBar
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import android.widget.VideoView
import clipfactory.app.Prefs
import clipfactory.core.SceneSearch
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.File

/** The "Biblioteca" tab: search scene packs, download them all in the app, find the exact scene by describing it. */
class LibraryScreen(private val a: Activity, private val ui: CoroutineScope) {
    private val bg = Color.rgb(14, 15, 18)
    private val card = Color.rgb(23, 25, 30)
    private val fg = Color.rgb(242, 242, 242)
    private val muted = Color.rgb(154, 160, 170)
    private val accent = Color.rgb(255, 212, 0)
    private val http = OkHttpClient()
    private val cache = LruCache<String, Bitmap>(120)
    private fun dp(v: Int) = (v * a.resources.displayMetrics.density).toInt()

    private lateinit var packQuery: EditText
    private lateinit var webFind: Button
    private lateinit var hitsBox: LinearLayout
    private lateinit var hitsStatus: TextView
    private lateinit var downloadAll: Button
    private lateinit var sceneStatus: TextView
    private lateinit var sceneGrid: GridLayout
    private lateinit var exportAll: Button
    private lateinit var downloads: LinearLayout
    private val chosen = LinkedHashMap<String, SearchHit>()
    private var lastScenes: List<Pair<LibVideo, LibScene>> = emptyList()

    // ---------- small UI kit ----------
    private fun text(s: String, size: Float = 15f, color: Int = fg, bold: Boolean = false) = TextView(a).apply {
        text = s; textSize = size; setTextColor(color); if (bold) typeface = Typeface.DEFAULT_BOLD
    }

    private fun box() = LinearLayout(a).apply {
        orientation = LinearLayout.VERTICAL
        setPadding(dp(16), dp(14), dp(16), dp(16))
        background = GradientDrawable().apply { setColor(card); cornerRadius = dp(14).toFloat() }
        layoutParams = LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(12) }
    }

    private fun button(label: String, primary: Boolean = false, weight: Boolean = false, f: () -> Unit) = Button(a).apply {
        text = label; isAllCaps = false; textSize = 15f; setTextColor(if (primary) Color.BLACK else fg)
        background = GradientDrawable().apply { setColor(if (primary) accent else Color.rgb(40, 43, 50)); cornerRadius = dp(12).toFloat() }
        layoutParams = (if (weight) LinearLayout.LayoutParams(0, -2, 1f) else LinearLayout.LayoutParams(-1, -2))
            .apply { topMargin = dp(8); if (weight) marginEnd = dp(6) }
        setOnClickListener { f() }
    }

    private fun input(hint: String, onGo: () -> Unit) = EditText(a).apply {
        this.hint = hint; setTextColor(fg); setHintTextColor(muted); textSize = 16f
        inputType = InputType.TYPE_CLASS_TEXT; imeOptions = EditorInfo.IME_ACTION_SEARCH
        backgroundTintList = ColorStateList.valueOf(muted)
        setOnEditorActionListener { _, _, _ -> onGo(); true }
    }

    private fun thumbView(w: Int, h: Int) = ImageView(a).apply {
        layoutParams = LinearLayout.LayoutParams(w, h); scaleType = ImageView.ScaleType.CENTER_CROP; setBackgroundColor(Color.BLACK)
    }

    private fun loadThumb(iv: ImageView, src: String) {
        if (src.isBlank()) return
        cache.get(src)?.let { iv.setImageBitmap(it); return }
        ui.launch {
            val bmp = withContext(Dispatchers.IO) {
                runCatching {
                    if (src.startsWith("http")) http.newCall(Request.Builder().url(src).build()).execute().use { r ->
                        r.body?.bytes()?.let { BitmapFactory.decodeByteArray(it, 0, it.size) }
                    } else BitmapFactory.decodeFile(src, BitmapFactory.Options().apply { inSampleSize = 2 })
                }.getOrNull()
            }
            if (bmp != null) { cache.put(src, bmp); iv.setImageBitmap(bmp) }
        }
    }

    private fun mmss(s: Double) = "%d:%02d".format((s / 60).toInt(), (s % 60).toInt())

    // ---------- screen ----------
    fun build(): View {
        LibraryStore.load(a)
        val root = LinearLayout(a).apply { orientation = LinearLayout.VERTICAL; setPadding(dp(16), dp(8), dp(16), dp(32)) }
        root.addView(text("Biblioteca de scenepacks", 24f, fg, true))
        root.addView(text("Busque, baixe tudo de uma vez e ache a cena exata para a sua edit.", 14f, muted))

        // 1. search scene packs / import
        val s = box()
        s.addView(text("1. Buscar e baixar", 17f, fg, true))
        packQuery = input("ex.: gojo scenepack 4k") { searchPacks(packQuery.text.toString()) }
        s.addView(packQuery)
        s.addView(button("Buscar no YouTube", primary = true) { searchPacks(packQuery.text.toString()) })
        s.addView(button("Importar vídeos do celular") {
            a.startActivityForResult(Intent(Intent.ACTION_OPEN_DOCUMENT).apply {
                addCategory(Intent.CATEGORY_OPENABLE); type = "video/*"; putExtra(Intent.EXTRA_ALLOW_MULTIPLE, true)
            }, IMPORT_REQUEST)
        })
        s.addView(text("Se o YouTube bloquear um download, baixe por outro app e use Importar: a busca de cenas funciona igual.", 12f, muted))
        hitsStatus = text("", 13f, muted); s.addView(hitsStatus)
        hitsBox = LinearLayout(a).apply { orientation = LinearLayout.VERTICAL }; s.addView(hitsBox)
        downloadAll = button("Baixar selecionados", primary = true) { downloadChosen() }.apply { visibility = View.GONE }
        s.addView(downloadAll)
        root.addView(s)

        // 2. find a scene
        val f = box()
        f.addView(text("2. Achar cena nos seus vídeos", 17f, fg, true))
        f.addView(text("Procura dentro do que você já baixou ou importou. Escreva do seu jeito: \"gojo tirando a venda\", \"close nos olhos\", \"fala que é fraco\".", 13f, muted))
        lateinit var sq: EditText
        sq = input("o que acontece na cena?") { findScenes(sq.text.toString(), smart = false) }
        f.addView(sq)
        val row = LinearLayout(a).apply { orientation = LinearLayout.HORIZONTAL }
        row.addView(button("Buscar", primary = true, weight = true) { findScenes(sq.text.toString(), smart = false) })
        row.addView(button("Busca inteligente (Claude)", weight = true) { findScenes(sq.text.toString(), smart = true) })
        f.addView(row)
        sceneStatus = text("", 13f, muted); f.addView(sceneStatus)
        webFind = button("Procurar esse momento na internet") { findOnWeb(sq.text.toString()) }.apply { visibility = View.GONE }
        f.addView(webFind)
        sceneGrid = GridLayout(a).apply { columnCount = 2 }; f.addView(sceneGrid)
        exportAll = button("Salvar todas na galeria") { exportMany(lastScenes) }.apply { visibility = View.GONE }
        f.addView(exportAll)
        root.addView(f)

        // 3. downloads
        val d = box()
        d.addView(text("3. Downloads", 17f, fg, true))
        downloads = LinearLayout(a).apply { orientation = LinearLayout.VERTICAL }; d.addView(downloads)
        d.addView(button("Atualizar o baixador (se o YouTube parar de baixar)") {
            Toast.makeText(a, "Atualizando…", Toast.LENGTH_SHORT).show()
            ui.launch {
                val r = withContext(Dispatchers.IO) { runCatching { Downloader.update(a) } }
                Toast.makeText(a, r.fold({ "Baixador: $it" }, { "Falhou: ${it.message}" }), Toast.LENGTH_LONG).show()
            }
        })
        root.addView(d)

        ui.launch {
            LibraryStore.videos.collect { list ->
                renderDownloads(list)
                oneButtons.forEach { (id, b) -> refreshOne(b, id) } // "Na fila" turns into "Baixado" when done
                delay(500)
            } // StateFlow keeps only the latest: at most 2 redraws per second
        }
        return ScrollView(a).apply { setBackgroundColor(bg); addView(root) }
    }

    private val checks = ArrayList<CheckBox>()
    private val oneButtons = HashMap<String, Button>() // search result id -> its "Baixar" button

    private fun searchPacks(query: String) {
        if (query.isBlank()) return
        hitsStatus.text = "Buscando…"; hitsBox.removeAllViews(); chosen.clear(); checks.clear(); oneButtons.clear()
        downloadAll.visibility = View.GONE
        ui.launch {
            val r = withContext(Dispatchers.IO) { runCatching { Downloader.search(a, query) } }
            r.onFailure { hitsStatus.text = "A busca falhou: ${it.message?.take(120)}. Tente \"Atualizar o baixador\"." }
            r.onSuccess { hits ->
                hitsStatus.text = if (hits.isEmpty()) "Nada encontrado."
                else "${hits.size} vídeos. Toque em Baixar no que quiser, ou marque vários."
                if (hits.isNotEmpty()) hitsBox.addView(Button(a).apply {
                    text = "Marcar todos"; isAllCaps = false; textSize = 13f; setTextColor(fg)
                    background = GradientDrawable().apply { setColor(Color.rgb(40, 43, 50)); cornerRadius = dp(8).toFloat() }
                    layoutParams = LinearLayout.LayoutParams(-2, dp(36)).apply { topMargin = dp(6) }
                    setOnClickListener {
                        val all = checks.any { !it.isChecked }
                        checks.forEach { it.isChecked = all }
                        text = if (all) "Desmarcar todos" else "Marcar todos"
                    }
                })
                for (h in hits) {
                    val rowV = LinearLayout(a).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL; setPadding(0, dp(8), 0, 0) }
                    val cb = CheckBox(a).apply {
                        isChecked = false; buttonTintList = ColorStateList.valueOf(accent)
                        setOnCheckedChangeListener { _, on -> if (on) chosen[h.id] = h else chosen.remove(h.id); updateDownloadButton() }
                    }
                    checks.add(cb)
                    val iv = thumbView(dp(104), dp(58)); loadThumb(iv, h.thumbUrl)
                    val col = LinearLayout(a).apply { orientation = LinearLayout.VERTICAL; setPadding(dp(8), 0, dp(6), 0); layoutParams = LinearLayout.LayoutParams(0, -2, 1f) }
                    col.addView(text(h.title, 14f, fg, true).apply { maxLines = 2 })
                    col.addView(text("${mmss(h.durationS)} · ${h.channel}", 12f, muted))
                    // download just this one
                    val one = Button(a).apply {
                        isAllCaps = false; textSize = 13f; setPadding(dp(10), 0, dp(10), 0)
                        layoutParams = LinearLayout.LayoutParams(-2, dp(38))
                        setOnClickListener { if (isEnabled) { enqueue(listOf(h)); refreshOne(this, h.id) } }
                    }
                    refreshOne(one, h.id)
                    oneButtons[h.id] = one
                    rowV.addView(cb); rowV.addView(iv); rowV.addView(col); rowV.addView(one)
                    hitsBox.addView(rowV)
                }
                updateDownloadButton()
            }
        }
    }

    /** "Baixar" / "Na fila" / "Baixado" for one search result, so the same video is not downloaded twice. */
    private fun refreshOne(b: Button, id: String) {
        val v = LibraryStore.get(id)
        val (label, enabled) = when (v?.status) {
            null, "failed", "canceled" -> "Baixar" to true
            "ready" -> "Baixado" to false
            else -> "Na fila" to false
        }
        b.text = label; b.isEnabled = enabled
        b.setTextColor(if (enabled) Color.BLACK else muted)
        b.background = GradientDrawable().apply { setColor(if (enabled) accent else Color.rgb(40, 43, 50)); cornerRadius = dp(10).toFloat() }
    }

    private fun updateDownloadButton() {
        downloadAll.visibility = if (chosen.isEmpty()) View.GONE else View.VISIBLE
        downloadAll.text = "Baixar selecionados (${chosen.size})"
    }

    private fun enqueue(hits: List<SearchHit>) {
        val retry = hits.mapNotNull { h -> LibraryStore.get(h.id)?.takeIf { it.status in setOf("failed", "canceled") } }
        retry.forEach { v -> LibraryStore.update(a, v.id) { it.copy(status = "queued", error = "") } }
        LibraryStore.add(a, hits.map { LibVideo(it.id, it.title, it.channel, it.url, it.durationS, it.thumbUrl) })
        LibraryService.kick(a)
        Toast.makeText(a, if (hits.size == 1) "Baixando \"${hits[0].title.take(40)}\". O progresso fica em Downloads."
            else "${hits.size} na fila. Pode sair do app: o progresso fica na notificação.", Toast.LENGTH_LONG).show()
    }

    private fun downloadChosen() {
        enqueue(chosen.values.toList())
        checks.forEach { it.isChecked = false }
    }

    private fun renderDownloads(list: List<LibVideo>) {
        downloads.removeAllViews()
        if (list.isEmpty()) { downloads.addView(text("Nada baixado ainda.", 13f, muted)); return }
        for (v in list.sortedByDescending { it.addedAt }) {
            val row = LinearLayout(a).apply { orientation = LinearLayout.HORIZONTAL; setPadding(0, dp(10), 0, 0) }
            val iv = thumbView(dp(96), dp(54)); loadThumb(iv, v.scenes.firstOrNull()?.thumb ?: v.thumbUrl)
            val col = LinearLayout(a).apply { orientation = LinearLayout.VERTICAL; setPadding(dp(10), 0, 0, 0); layoutParams = LinearLayout.LayoutParams(0, -2, 1f) }
            col.addView(text(v.title, 14f, fg, true).apply { maxLines = 2 })
            val described = v.scenes.count { it.desc.isNotEmpty() }
            val st = when (v.status) {
                "queued" -> "Na fila"
                "downloading" -> "Baixando ${(v.progress * 100).toInt()}%"
                "scanning" -> "Separando cenas ${(v.progress * 100).toInt()}%"
                "describing" -> "Descrevendo cenas ${(v.progress * 100).toInt()}%"
                "ready" -> "Pronto · ${v.scenes.size} cenas" + if (described > 0) " · $described descritas" else " · sem descrição (coloque a chave da Anthropic)"
                "canceled" -> "Cancelado"
                else -> "Falhou: ${v.error}"
            }
            col.addView(text(st, 12f, if (v.status == "failed") Color.rgb(255, 120, 110) else muted))
            if (v.status == "ready" && v.info.isNotBlank()) col.addView(text(v.info, 11f, muted))
            if (v.status in setOf("downloading", "scanning", "describing")) {
                col.addView(ProgressBar(a, null, android.R.attr.progressBarStyleHorizontal).apply {
                    max = 100; progress = (v.progress * 100).toInt(); progressTintList = ColorStateList.valueOf(accent)
                })
            }
            val actions = LinearLayout(a).apply { orientation = LinearLayout.HORIZONTAL }
            fun small(label: String, f: () -> Unit) = Button(a).apply {
                text = label; isAllCaps = false; textSize = 12f; setTextColor(fg); setPadding(0, 0, 0, 0)
                background = GradientDrawable().apply { setColor(Color.rgb(40, 43, 50)); cornerRadius = dp(8).toFloat() }
                layoutParams = LinearLayout.LayoutParams(0, dp(34), 1f).apply { marginEnd = dp(6); topMargin = dp(4) }
                setOnClickListener { f() }
            }
            when (v.status) {
                "queued", "downloading", "scanning", "describing" -> actions.addView(small("Cancelar") { LibraryService.cancel(a, v.id) })
                "failed", "canceled" -> actions.addView(small("Tentar de novo") {
                    LibraryStore.update(a, v.id) { it.copy(status = "queued", error = "") }; LibraryService.kick(a)
                })
                "ready" -> actions.addView(small("Ver cenas") { showScenes(v.scenes.map { v to it }, "Todas as cenas de \"${v.title.take(30)}\"") })
            }
            actions.addView(small("Apagar") {
                AlertDialog.Builder(a).setMessage("Apagar \"${v.title}\" e as cenas dele?")
                    .setPositiveButton("Apagar") { _, _ -> if (v.status != "ready") LibraryService.cancel(a, v.id); LibraryStore.remove(a, v.id) }
                    .setNegativeButton("Não", null).show()
            })
            col.addView(actions)
            row.addView(iv); row.addView(col)
            downloads.addView(row)
        }
    }

    private fun allScenes() = LibraryStore.videos.value.filter { it.status == "ready" || it.scenes.isNotEmpty() }
        .flatMap { v -> v.scenes.map { v to it } }

    private fun findScenes(query: String, smart: Boolean) {
        if (query.isBlank()) return
        val scenes = allScenes()
        if (scenes.isEmpty()) {
            sceneStatus.text = "Sua biblioteca está vazia. Procure na internet ou importe vídeos."
            webFind.visibility = View.VISIBLE; return
        }
        val byKey = scenes.associateBy { (v, s) -> "${v.id}#${s.idx}" }
        if (!smart) {
            val docs = byKey.mapValues { (_, p) -> "${p.first.title} ${p.second.desc} ${p.second.speech}" }
            val keys = SceneSearch.search(query, docs)
            showScenes(keys.mapNotNull { byKey[it] },
                if (keys.isEmpty()) "Nada nos seus vídeos. Tente a busca inteligente ou procure na internet." else "${keys.size} cenas")
            return
        }
        val key = Prefs.options(a).claudeKey
        if (key.isBlank()) { sceneStatus.text = "A busca inteligente precisa da chave da Anthropic (aba Clipes → Chaves)."; return }
        // short ids keep the index small; the index is cached by the API between searches
        val short = byKey.keys.withIndex().associate { (i, k) -> "s$i" to k }
        val index = short.mapNotNull { (sid, k) ->
            val (_, sc) = byKey.getValue(k)
            val t = listOf(sc.desc, if (sc.speech.isNotBlank()) "fala: ${sc.speech}" else "").filter { it.isNotBlank() }.joinToString(" | ")
            if (t.isBlank()) null else sid to t
        }
        if (index.isEmpty()) { sceneStatus.text = "As cenas ainda não têm descrição. Coloque a chave da Anthropic e baixe de novo."; return }
        sceneStatus.text = "Claude procurando…"
        ui.launch {
            val ids = withContext(Dispatchers.IO) { SceneAI.search(key, query, index) }
            if (ids == null) { sceneStatus.text = "A busca inteligente falhou; usando a busca normal."; findScenes(query, false); return@launch }
            showScenes(ids.mapNotNull { short[it]?.let(byKey::get) }, "${ids.size} cenas (Claude)")
        }
    }

    private fun showScenes(list: List<Pair<LibVideo, LibScene>>, status: String) {
        lastScenes = list
        webFind.visibility = View.VISIBLE
        sceneStatus.text = status
        sceneGrid.removeAllViews()
        val w = (a.resources.displayMetrics.widthPixels - dp(64)) / 2
        for ((v, s) in list.take(80)) {
            val cell = LinearLayout(a).apply {
                orientation = LinearLayout.VERTICAL; setPadding(dp(3), dp(6), dp(3), 0)
                layoutParams = GridLayout.LayoutParams().apply { width = w }
                setOnClickListener { preview(v, s) }
            }
            val iv = thumbView(w - dp(6), (w - dp(6)) * 9 / 16); loadThumb(iv, s.thumb)
            cell.addView(iv)
            cell.addView(text(s.desc.substringBefore(" | ").ifBlank { "${v.title.take(30)} · ${mmss(s.start)}" }, 11f, muted).apply { maxLines = 2 })
            sceneGrid.addView(cell)
        }
        exportAll.visibility = if (list.size > 1) View.VISIBLE else View.GONE
        exportAll.text = "Salvar todas na galeria (${list.size})"
    }

    private fun preview(v: LibVideo, s: LibScene) {
        val vv = VideoView(a)
        val h = Handler(Looper.getMainLooper())
        val loop = object : Runnable {
            override fun run() {
                if (vv.currentPosition >= (s.end * 1000).toInt()) vv.seekTo((s.start * 1000).toInt())
                h.postDelayed(this, 100)
            }
        }
        vv.setVideoURI(Uri.fromFile(File(v.file)))
        vv.setOnPreparedListener { it.isLooping = false; vv.seekTo((s.start * 1000).toInt()); vv.start(); h.post(loop) }
        val wrap = LinearLayout(a).apply { orientation = LinearLayout.VERTICAL; setPadding(dp(12), dp(12), dp(12), 0) }
        wrap.addView(vv, LinearLayout.LayoutParams(-1, dp(220)))
        wrap.addView(text(s.desc.ifBlank { v.title }, 13f, Color.DKGRAY))
        if (s.speech.isNotBlank()) wrap.addView(text("Fala: ${s.speech}", 13f, Color.GRAY))
        wrap.addView(text("${mmss(s.start)}–${mmss(s.end)} · ${"%.1f".format(s.end - s.start)} s", 12f, Color.GRAY))
        AlertDialog.Builder(a).setView(wrap)
            .setPositiveButton("Salvar na galeria") { _, _ -> exportMany(listOf(v to s)) }
            .setNegativeButton("Fechar", null)
            .setOnDismissListener { h.removeCallbacks(loop); vv.stopPlayback() }
            .show()
    }

    private fun exportMany(list: List<Pair<LibVideo, LibScene>>) {
        if (list.isEmpty()) return
        Toast.makeText(a, "Salvando ${list.size} cena(s) na galeria (Movies/Cenas)…", Toast.LENGTH_SHORT).show()
        ui.launch {
            var ok = 0
            for ((v, s) in list) {
                runCatching { SceneExporter.export(a, v, s) }.onSuccess { ok++ }
                sceneStatus.text = "Salvando… $ok de ${list.size}"
            }
            sceneStatus.text = "$ok de ${list.size} cenas salvas em Movies/Cenas."
            Toast.makeText(a, sceneStatus.text, Toast.LENGTH_LONG).show()
        }
    }

    /** Not in the library: Claude says where the moment is from and searches YouTube for it (section 1). */
    private fun findOnWeb(description: String) {
        if (description.isBlank()) return
        val key = Prefs.options(a).claudeKey
        if (key.isBlank()) { packQuery.setText(description); searchPacks(description); hitsStatus.text = "Buscando o que você escreveu…"; return }
        sceneStatus.text = "Claude identificando de onde é essa cena…"
        ui.launch {
            val r = withContext(Dispatchers.IO) { SceneAI.webQuery(key, description) }
            val query = r?.first ?: description
            sceneStatus.text = r?.second ?: "Não consegui identificar; buscando o que você escreveu."
            packQuery.setText(query)
            searchPacks(query)
            Toast.makeText(a, "Resultados na seção 1 (role para cima).", Toast.LENGTH_LONG).show()
        }
    }

    /** Videos picked from the phone: copied into the library and split into scenes like downloads. */
    fun onImport(uris: List<Uri>) {
        if (uris.isEmpty()) return
        val vids = uris.map { u ->
            runCatching { a.contentResolver.takePersistableUriPermission(u, Intent.FLAG_GRANT_READ_URI_PERMISSION) }
            val name = a.contentResolver.query(u, arrayOf(android.provider.OpenableColumns.DISPLAY_NAME), null, null, null)
                ?.use { if (it.moveToFirst()) it.getString(0) else null } ?: "vídeo"
            val dur = runCatching {
                android.media.MediaMetadataRetriever().use { r -> r.setDataSource(a, u)
                    (r.extractMetadata(android.media.MediaMetadataRetriever.METADATA_KEY_DURATION)?.toLongOrNull() ?: 0L) / 1000.0 }
            }.getOrDefault(0.0)
            LibVideo("imp" + Integer.toHexString(u.toString().hashCode()), name.substringBeforeLast('.'), "importado", u.toString(), dur)
        }
        LibraryStore.add(a, vids)
        vids.forEach { v -> if (LibraryStore.get(v.id)?.status in setOf("failed", "canceled")) LibraryStore.update(a, v.id) { it.copy(status = "queued") } }
        LibraryService.kick(a)
        Toast.makeText(a, "${vids.size} vídeo(s) importado(s); separando as cenas.", Toast.LENGTH_LONG).show()
    }

    companion object {
        const val IMPORT_REQUEST = 7
    }
}
