package clipfactory.app.ui

import android.content.Intent
import android.graphics.BitmapFactory
import android.net.Uri
import android.util.LruCache
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import clipfactory.app.Creator
import clipfactory.app.DiscoveryStore
import clipfactory.app.Opportunity
import clipfactory.app.Prefs
import clipfactory.app.YouTubeApi
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import java.time.Instant
import java.time.temporal.ChronoUnit

private data class Found(val video: YouTubeApi.Video, val opp: Opportunity)

private val MODES = listOf("recentes" to "Recentes (7 dias)", "populares" to "Mais vistos (30 dias)", "canais" to "Canais")

/** Find YouTube videos and creators worth clipping (official API: metadata only, never the video file). */
@OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class, ExperimentalFoundationApi::class)
@Composable
fun DiscoverScreen(onBack: () -> Unit, onSettings: () -> Unit) {
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()
    val key = remember { Prefs.options(ctx).youtubeKey }
    LaunchedEffect(Unit) { DiscoveryStore.load(ctx) }
    val creators by DiscoveryStore.creators.collectAsState()
    var query by remember { mutableStateOf("") }
    var mode by remember { mutableStateOf("recentes") }
    var busy by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }
    var heading by remember { mutableStateOf("") }
    var found by remember { mutableStateOf<List<Found>>(emptyList()) }
    var channels by remember { mutableStateOf<List<YouTubeApi.Channel>>(emptyList()) }
    var subsById by remember { mutableStateOf<Map<String, Long?>>(emptyMap()) }
    var open by remember { mutableStateOf<Found?>(null) }
    var editCreator by remember { mutableStateOf<Creator?>(null) }
    var updatedAt by remember { mutableStateOf(0L) }

    var onlyPt by remember { mutableStateOf(true) }

    fun run(label: String, filterLang: Boolean = true, block: suspend () -> List<YouTubeApi.Video>) {
        if (busy) return
        busy = true; error = null
        scope.launch {
            try {
                val all = withContext(Dispatchers.IO) { block() }
                // regionCode/relevanceLanguage do not filter the language: the search returns Russian, Turkish... too
                val vids = if (filterLang && onlyPt) all.filter { it.portuguese } else all
                val subs = withContext(Dispatchers.IO) {
                    val ids = vids.map { it.channelId }.distinct().filter { it !in subsById }
                    subsById + YouTubeApi.channels(key, ids.take(50)).associate { it.id to it.subs }
                }
                withContext(Dispatchers.IO) { DiscoveryStore.observe(ctx, vids) }
                subsById = subs
                found = vids.filter { it.live != "upcoming" }.map { Found(it, DiscoveryStore.score(it, subs[it.channelId])) }
                    .sortedByDescending { it.opp.score }
                channels = emptyList(); updatedAt = System.currentTimeMillis()
                heading = label + if (all.size != vids.size) " · ${all.size - vids.size} em outros idiomas escondidos" else ""
            } catch (e: Exception) {
                error = e.message ?: e.toString()
            } finally {
                busy = false
            }
        }
    }

    fun search() {
        val q = query.trim()
        if (q.isEmpty()) return
        when (mode) {
            "canais" -> {
                busy = true; error = null
                scope.launch {
                    try {
                        channels = withContext(Dispatchers.IO) { YouTubeApi.searchChannels(key, q) }
                        found = emptyList(); heading = "Canais para \"$q\""; updatedAt = System.currentTimeMillis()
                    } catch (e: Exception) { error = e.message } finally { busy = false }
                }
            }
            "populares" -> run("Mais vistos em 30 dias: \"$q\"") {
                YouTubeApi.searchVideos(key, q, "viewCount", Instant.now().minus(30, ChronoUnit.DAYS))
            }
            else -> run("Recentes: \"$q\"") { YouTubeApi.searchVideos(key, q, "date", Instant.now().minus(7, ChronoUnit.DAYS)) }
        }
    }

    Column(Modifier.fillMaxSize()) {
        TopBar("Descobrir", onBack) {
            if (found.isNotEmpty()) IconButton(onClick = {
                // re-read the same videos (1 unit): a second observation is what makes growth measurable
                val ids = found.map { it.video.id }
                run(heading.substringBefore(" · "), filterLang = false) { YouTubeApi.videos(key, ids) }
            }) { Icon(Icons.Filled.Refresh, "Atualizar", tint = C.text) }
        }
        if (key.isBlank()) {
            Column(Modifier.padding(20.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Text("Falta a chave do YouTube", style = MaterialTheme.typography.titleLarge, color = C.text)
                Text("Cole a chave da YouTube Data API v3 (começa com AIza) em Configurações. Ela é grátis.",
                    style = MaterialTheme.typography.bodyMedium, color = C.muted)
                BigButton("Abrir Configurações", icon = Icons.Filled.Settings, onClick = onSettings)
            }
            return@Column
        }
        LazyColumn(Modifier.weight(1f), contentPadding = PaddingValues(horizontal = 16.dp, vertical = 4.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp)) {
            item {
                OutlinedTextField(query, { query = it }, Modifier.fillMaxWidth(), singleLine = true,
                    placeholder = { Text("Criador, jogo ou assunto (ex.: GTA RP, Alanzoka)", color = C.muted) },
                    trailingIcon = { IconButton(onClick = ::search) { Icon(Icons.Filled.Search, "Buscar", tint = C.accent) } },
                    keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
                    keyboardActions = KeyboardActions(onSearch = { search() }),
                    colors = OutlinedTextFieldDefaults.colors(focusedBorderColor = C.accent, unfocusedBorderColor = C.line,
                        focusedTextColor = C.text, unfocusedTextColor = C.text, cursorColor = C.accent))
                Spacer(Modifier.size(8.dp))
                FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    MODES.forEach { (k, l) -> Chip(l, mode == k) { mode = k } }
                    Chip(if (onlyPt) "Só português" else "Todos os idiomas", onlyPt) { onlyPt = !onlyPt }
                }
                Text("Cada busca gasta 100 da cota grátis (10.000 por dia ≈ 100 buscas). Abrir um criador seguido gasta 2.",
                    style = MaterialTheme.typography.labelSmall, color = C.muted, modifier = Modifier.padding(top = 6.dp))
            }
            if (creators.isNotEmpty()) item {
                SectionTitle("Seus criadores", "Toque para ver os vídeos novos. Segure para editar.")
                Spacer(Modifier.size(6.dp))
                LazyRow(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    items(creators, key = { it.id }) { c ->
                        Column(horizontalAlignment = Alignment.CenterHorizontally, modifier = Modifier.width(76.dp)
                            .combinedClickable(onClick = { run("Novos de ${c.title}", filterLang = false) { YouTubeApi.latest(key, c.uploads) } },
                                onLongClick = { editCreator = c })) {
                            NetImage(c.thumb, Modifier.size(60.dp).clip(CircleShape))
                            Text(c.title, style = MaterialTheme.typography.labelSmall, color = C.text, maxLines = 1,
                                overflow = TextOverflow.Ellipsis)
                            Text(if (c.authorized) "autorizado" else "sem autorização", style = MaterialTheme.typography.labelSmall,
                                color = if (c.authorized) C.good else C.bad, maxLines = 1)
                        }
                    }
                }
            }
            if (busy) item { Text("Buscando no YouTube…", color = C.accent, style = MaterialTheme.typography.titleMedium) }
            error?.let { e -> item { Text(e, color = C.bad, style = MaterialTheme.typography.bodyMedium) } }
            if (heading.isNotEmpty() && !busy) item {
                Text(heading, style = MaterialTheme.typography.titleMedium, color = C.text)
                Text("Atualizado às ${java.text.SimpleDateFormat("HH:mm").format(java.util.Date(updatedAt))}" +
                    if (found.isNotEmpty()) " · toque em ⟳ daqui a 30 min para medir quem está crescendo" else "",
                    style = MaterialTheme.typography.labelSmall, color = C.muted)
            }
            items(channels, key = { "c" + it.id }) { ch ->
                val followed = creators.any { it.id == ch.id }
                Row(Modifier.fillMaxWidth().clip(RoundedCornerShape(16.dp)).background(C.surface).padding(10.dp),
                    verticalAlignment = Alignment.CenterVertically) {
                    NetImage(ch.thumb, Modifier.size(52.dp).clip(CircleShape))
                    Spacer(Modifier.width(10.dp))
                    Column(Modifier.weight(1f)) {
                        Text(ch.title, style = MaterialTheme.typography.titleMedium, color = C.text, maxLines = 1, overflow = TextOverflow.Ellipsis)
                        Text((ch.subs?.let { "${DiscoveryStore.fmt(it)} inscritos" } ?: "inscritos ocultos") +
                            (ch.videos?.let { " · $it vídeos" } ?: ""), style = MaterialTheme.typography.labelSmall, color = C.muted)
                    }
                    Chip(if (followed) "Seguindo" else "Seguir", followed) {
                        if (!followed) DiscoveryStore.saveCreator(ctx, Creator(ch.id, ch.title, ch.thumb, ch.subs, ch.uploads))
                        editCreator = DiscoveryStore.creator(ch.id)
                    }
                }
            }
            items(found, key = { "v" + it.video.id }) { f -> VideoCard(f, creators.firstOrNull { it.id == f.video.channelId }) { open = f } }
        }
    }

    open?.let { f ->
        val v = f.video
        val cr = creators.firstOrNull { it.id == v.channelId }
        ModalBottomSheet(onDismissRequest = { open = null }, containerColor = C.surface) {
            Column(Modifier.navigationBarsPadding().padding(horizontal = 20.dp).padding(bottom = 24.dp)
                .verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Text(v.title, style = MaterialTheme.typography.titleLarge, color = C.text)
                Text("Nota de oportunidade: ${f.opp.score}/100", style = MaterialTheme.typography.titleMedium, color = C.accent)
                f.opp.factors.forEach { fa ->
                    Row {
                        Text("+${fa.points}", color = C.accent, style = MaterialTheme.typography.titleMedium, modifier = Modifier.width(44.dp))
                        Column {
                            Text("${fa.label} · ${if (fa.observed) "dado do YouTube" else "estimativa"}", color = C.text,
                                style = MaterialTheme.typography.bodyMedium)
                            Text(fa.detail, color = C.muted, style = MaterialTheme.typography.labelSmall)
                        }
                    }
                }
                Text(if (cr?.authorized == true) "✅ Você marcou ${cr.title} como autorizado${if (cr.authNote.isNotBlank()) " (${cr.authNote})" else ""}."
                    else "⚠️ Você não marcou este criador como autorizado. Ser famoso não dá permissão: sem programa de " +
                        "clipping ou acordo, postar cortes pode dar strike.",
                    color = if (cr?.authorized == true) C.good else C.bad, style = MaterialTheme.typography.bodyMedium)
                Text("Para clipar: baixe o vídeo e use Compartilhar → Fábrica de Clipes (Vídeo ou Live gravada).",
                    color = C.muted, style = MaterialTheme.typography.bodyMedium)
                BigButton("Abrir no YouTube") {
                    // remembered for "Novo clipe": the comments of this video tell which moments viewers loved
                    ctx.getSharedPreferences("clip", 0).edit().putString("lastLink", "https://www.youtube.com/watch?v=${v.id}")
                        .putString("lastTitle", v.title).apply()
                    runCatching { ctx.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse("https://www.youtube.com/watch?v=${v.id}"))) }
                }
                if (cr == null) SoftButton("Seguir ${v.channelTitle}", Modifier.fillMaxWidth()) {
                    scope.launch {
                        val ch = withContext(Dispatchers.IO) { runCatching { YouTubeApi.channels(key, listOf(v.channelId)).firstOrNull() }.getOrNull() }
                        if (ch != null) { DiscoveryStore.saveCreator(ctx, Creator(ch.id, ch.title, ch.thumb, ch.subs, ch.uploads)); editCreator = DiscoveryStore.creator(ch.id) }
                    }
                }
            }
        }
    }

    editCreator?.let { c ->
        var note by remember(c.id) { mutableStateOf(c.authNote) }
        var auth by remember(c.id) { mutableStateOf(c.authorized) }
        ModalBottomSheet(onDismissRequest = { DiscoveryStore.saveCreator(ctx, c.copy(authorized = auth, authNote = note.trim())); editCreator = null },
            containerColor = C.surface) {
            Column(Modifier.navigationBarsPadding().padding(horizontal = 20.dp).padding(bottom = 24.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Text(c.title, style = MaterialTheme.typography.titleLarge, color = C.text)
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f)) {
                        Text("Tenho autorização para clipar", color = C.text, style = MaterialTheme.typography.titleMedium)
                        Text("Programa de clipping, regra publicada pelo criador ou acordo.", color = C.muted, style = MaterialTheme.typography.labelSmall)
                    }
                    Switch(auth, { auth = it }, colors = SwitchDefaults.colors(checkedTrackColor = C.accent, checkedThumbColor = C.onAccent))
                }
                OutlinedTextField(note, { note = it.take(120) }, Modifier.fillMaxWidth(), label = { Text("De onde vem a autorização") },
                    colors = OutlinedTextFieldDefaults.colors(focusedBorderColor = C.accent, unfocusedBorderColor = C.line,
                        focusedTextColor = C.text, unfocusedTextColor = C.text, focusedLabelColor = C.accent, unfocusedLabelColor = C.muted))
                BigButton("Salvar") { DiscoveryStore.saveCreator(ctx, c.copy(authorized = auth, authNote = note.trim())); editCreator = null }
                SoftButton("Deixar de seguir", Modifier.fillMaxWidth()) { DiscoveryStore.removeCreator(ctx, c.id); editCreator = null }
            }
        }
    }
}

@Composable
private fun VideoCard(f: Found, creator: Creator?, onClick: () -> Unit) {
    val v = f.video
    val hours = ((System.currentTimeMillis() - v.publishedAt) / 3_600_000).coerceAtLeast(0)
    val age = if (hours < 48) "há $hours h" else "há ${hours / 24} dias"
    Column(Modifier.fillMaxWidth().clip(RoundedCornerShape(16.dp)).background(C.surface).clickable(onClick = onClick)) {
        Box(Modifier.fillMaxWidth().aspectRatio(16f / 9f)) {
            NetImage(v.thumb, Modifier.fillMaxSize())
            Box(Modifier.align(Alignment.TopStart).padding(8.dp)) { ScoreBadge(f.opp.score) }
            if (v.live == "live") Text("AO VIVO", color = C.text, style = MaterialTheme.typography.labelSmall,
                modifier = Modifier.align(Alignment.TopEnd).padding(8.dp).clip(RoundedCornerShape(6.dp)).background(C.bad).padding(horizontal = 6.dp, vertical = 2.dp))
        }
        Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(3.dp)) {
            Text(v.title, style = MaterialTheme.typography.titleMedium, color = C.text, maxLines = 2, overflow = TextOverflow.Ellipsis)
            Text("${v.channelTitle} · $age · ${v.views?.let { DiscoveryStore.fmt(it) + " views" } ?: "views ocultas"} · ${v.seconds / 60} min",
                style = MaterialTheme.typography.labelSmall, color = C.muted, maxLines = 1, overflow = TextOverflow.Ellipsis)
            Text(if (creator?.authorized == true) "✅ criador autorizado" else "⚠️ sem autorização marcada",
                style = MaterialTheme.typography.labelSmall, color = if (creator?.authorized == true) C.good else C.bad)
        }
    }
}

private val imageCache = LruCache<String, ImageBitmap>(80)
private val imageHttp = OkHttpClient()

/** A picture from the internet (YouTube thumbnails), kept in memory while the app is open. */
@Composable
fun NetImage(url: String, modifier: Modifier = Modifier) {
    val bmp by produceState(imageCache.get(url), url) {
        if (value == null && url.isNotBlank()) value = withContext(Dispatchers.IO) {
            runCatching {
                imageHttp.newCall(Request.Builder().url(url).build()).execute().use { r ->
                    BitmapFactory.decodeStream(r.body?.byteStream())?.asImageBitmap()?.also { imageCache.put(url, it) }
                }
            }.getOrNull()
        }
    }
    Box(modifier.background(C.surface2)) {
        bmp?.let { Image(it, null, Modifier.matchParentSize(), contentScale = ContentScale.Crop) }
    }
}
