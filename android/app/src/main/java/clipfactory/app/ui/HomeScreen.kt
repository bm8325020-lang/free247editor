package clipfactory.app.ui

import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.width
import androidx.compose.material.icons.filled.Search
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.GridItemSpan
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import clipfactory.app.JobState
import clipfactory.app.Project
import clipfactory.app.ProjectStore
import clipfactory.app.ServerSync
import clipfactory.app.Prefs
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

@Composable
fun HomeScreen(onNew: (List<Uri>) -> Unit, onOpen: (Project) -> Unit, onSettings: () -> Unit, onWork: () -> Unit, onDiscover: () -> Unit = {}, onLive: () -> Unit = {},
               onReview: (List<String>) -> Unit = {}) {
    val ctx = LocalContext.current
    val projects by ProjectStore.projects.collectAsState()
    val work by JobState.flow.collectAsState()
    // one or several videos (several go into the queue and are processed one after another)
    val pick = rememberLauncherForActivityResult(ActivityResultContracts.OpenMultipleDocuments()) { uris ->
        uris.forEach { uri ->
            runCatching { ctx.contentResolver.takePersistableUriPermission(uri, android.content.Intent.FLAG_GRANT_READ_URI_PERMISSION) }
        }
        if (uris.isNotEmpty()) onNew(uris)
    }
    // the bot on this phone: what it is doing, and its new clips every minute while the app is open
    var bot by remember { mutableStateOf<org.json.JSONObject?>(null) }
    var botDown by remember { mutableStateOf(false) }
    val configured = remember { ServerSync.configured(ctx) }
    if (configured) androidx.compose.runtime.LaunchedEffect(Unit) {
        var n = 0
        while (true) {
            val st = kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) { ServerSync.status(ctx) }
            bot = st; botDown = st == null
            if (st != null && n % 2 == 0) kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) { ServerSync.sync(ctx) }
            n++
            kotlinx.coroutines.delay(30_000)
        }
    }
    HomeContent(projects, work.phase != "idle", work.stage, work.progress, onPick = { pick.launch(arrayOf("video/*")) },
        onOpen = onOpen, onSettings = onSettings, onWork = onWork, onDelete = { p ->
            // a bot clip is only in the app's list and the gallery: deleting it frees both
            if (p.id.startsWith("srv")) p.clips.forEach { c ->
                if (c.uri.startsWith("content:")) runCatching { ctx.contentResolver.delete(Uri.parse(c.uri), null, null) }
            }
            ProjectStore.delete(ctx, p.id)
        }, onDiscover = onDiscover, onLive = onLive,
        botCard = if (configured) ({ BotCard(bot, botDown) }) else null, weakBar = Prefs.options(ctx).weakBar,
        onReview = onReview)
}

/** The bot at a glance: lives being recorded, clips, its latest decision. */
@Composable
private fun BotCard(st: org.json.JSONObject?, down: Boolean) {
    Column(Modifier.fillMaxWidth().clip(RoundedCornerShape(20.dp)).background(C.surface).padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(6.dp)) {
        if (down) {
            Text("🤖 Bot desligado", style = MaterialTheme.typography.titleMedium, color = C.bad)
            Text("No Termux: bash \$HOME/ligar-clipador.sh", style = MaterialTheme.typography.bodyMedium, color = C.muted)
            return@Column
        }
        if (st == null) { Text("🤖 Procurando o bot…", color = C.muted); return@Column }
        val lives = st.optJSONArray("lives") ?: org.json.JSONArray()
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(if (lives.length() > 0) "🔴 Bot gravando" else "🤖 Bot ligado", style = MaterialTheme.typography.titleMedium,
                color = C.text, modifier = Modifier.weight(1f))
            Text("IA: ${st.optInt("groq_min")}/${st.optInt("groq_max")} min/h", style = MaterialTheme.typography.labelSmall, color = C.muted)
        }
        if (lives.length() == 0) Text("Nenhuma live no ar agora. Ele avisa quando alguém entrar.",
            style = MaterialTheme.typography.bodyMedium, color = C.muted)
        for (i in 0 until lives.length()) {
            val l = lives.getJSONObject(i)
            Text("${if (l.optBoolean("recording")) "●" else "○"} ${l.optString("name")} · ${l.optString("platform")} · " +
                "${l.optInt("minutes")} min · ${l.optInt("clips")} clipe(s)" + if (l.optBoolean("recording")) "" else " · na fila",
                style = MaterialTheme.typography.bodyMedium, color = if (l.optBoolean("recording")) C.text else C.muted)
            l.optString("last").takeIf { it.isNotBlank() }?.let {
                Text(it, style = MaterialTheme.typography.labelSmall, color = C.muted, maxLines = 2, overflow = TextOverflow.Ellipsis)
            }
        }
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
fun HomeContent(projects: List<Project>, busy: Boolean, stage: String, progress: Double, onPick: () -> Unit,
                onOpen: (Project) -> Unit, onSettings: () -> Unit, onWork: () -> Unit, onDelete: (Project) -> Unit,
                onDiscover: () -> Unit = {}, onLive: () -> Unit = {}, botCard: (@Composable () -> Unit)? = null,
                weakBar: Int = WEAK, onReview: (List<String>) -> Unit = {}) {
    var confirm by remember { mutableStateOf<Project?>(null) }
    var confirmWeak by remember { mutableStateOf(false) }
    // the bot's clips: best first, the weak ones (AI grade under 40) out of the way but one tap away
    var botTab by remember { mutableStateOf("bons") }
    val botAll = projects.filter { it.id.startsWith("srv") }
    val grade = { p: Project -> p.clips.firstOrNull()?.score ?: 0 }
    val posted = botAll.filter { it.clips.any { c -> c.posted > 0 } }
        .sortedByDescending { it.clips.maxOf { c -> c.posted } }
    val fresh = botAll - posted.toSet()
    val good = fresh.filter { grade(it) >= weakBar }.sortedWith(compareByDescending<Project> { grade(it) }.thenByDescending { it.createdAt })
    val weak = fresh.filter { grade(it) < weakBar }.sortedByDescending { it.createdAt }
    val mine = projects.filter { !it.id.startsWith("srv") }
    LazyVerticalGrid(GridCells.Fixed(2), Modifier.fillMaxSize(), contentPadding = PaddingValues(16.dp),
        horizontalArrangement = Arrangement.spacedBy(12.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        item(span = { GridItemSpan(2) }) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text("Fábrica", style = MaterialTheme.typography.headlineLarge, color = C.text)
                    Text("de Clipes", style = MaterialTheme.typography.headlineLarge, color = C.accent)
                }
                IconButton(onSettings, Modifier.size(48.dp).clip(CircleShape).background(C.surface)) {
                    Icon(Icons.Filled.Settings, "Configurações", tint = C.text)
                }
            }
        }
        item(span = { GridItemSpan(2) }) {
            // the main action: big and obvious
            Box(Modifier.fillMaxWidth().height(150.dp).clip(RoundedCornerShape(24.dp))
                .background(Brush.linearGradient(listOf(C.accent, Color(0xFFFF9F1C))))
                .combinedClickable(onClick = onPick).padding(20.dp)) {
                Column(Modifier.align(Alignment.BottomStart)) {
                    Box(Modifier.size(44.dp).clip(CircleShape).background(C.onAccent), contentAlignment = Alignment.Center) {
                        Icon(Icons.Filled.Add, null, tint = C.accent)
                    }
                    Spacer(Modifier.height(10.dp))
                    Text("Novo clipe", style = MaterialTheme.typography.headlineSmall, color = C.onAccent)
                    Text("Escolha uma live ou gameplay longo", style = MaterialTheme.typography.bodyMedium, color = C.onAccent.copy(alpha = 0.75f))
                }
            }
        }
        item(span = { GridItemSpan(2) }) {
            Row(Modifier.fillMaxWidth().clip(RoundedCornerShape(20.dp)).background(C.surface)
                .combinedClickable(onClick = onLive).padding(18.dp), verticalAlignment = Alignment.CenterVertically) {
                Text("🔴", style = MaterialTheme.typography.titleLarge)
                Spacer(Modifier.width(14.dp))
                Column {
                    Text("Clipar live", style = MaterialTheme.typography.titleMedium, color = C.text)
                    Text("Assista na Twitch e o app corta os melhores momentos", style = MaterialTheme.typography.bodyMedium, color = C.muted)
                }
            }
        }
        item(span = { GridItemSpan(2) }) {
            Row(Modifier.fillMaxWidth().clip(RoundedCornerShape(20.dp)).background(C.surface)
                .combinedClickable(onClick = onDiscover).padding(18.dp), verticalAlignment = Alignment.CenterVertically) {
                Icon(Icons.Filled.Search, null, tint = C.accent, modifier = Modifier.size(28.dp))
                Spacer(Modifier.width(14.dp))
                Column {
                    Text("Descobrir", style = MaterialTheme.typography.titleMedium, color = C.text)
                    Text("Vídeos em alta e criadores no YouTube", style = MaterialTheme.typography.bodyMedium, color = C.muted)
                }
            }
        }
        if (botCard != null) item(span = { GridItemSpan(2) }) { botCard() }
        if (busy) item(span = { GridItemSpan(2) }) {
            Card(onClick = onWork) {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text("Em andamento", style = MaterialTheme.typography.titleMedium, color = C.text)
                    Text(stage, style = MaterialTheme.typography.bodyMedium, color = C.muted)
                    LinearProgressIndicator({ progress.toFloat() }, Modifier.fillMaxWidth().clip(RoundedCornerShape(4.dp)), color = C.accent, trackColor = C.surface2)
                }
            }
        }
        if (botAll.isNotEmpty()) {
            item(span = { GridItemSpan(2) }) {
                Column(Modifier.padding(top = 8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text("Clipes do bot", style = MaterialTheme.typography.titleMedium, color = C.text)
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                        Chip("Melhores (${good.size})", botTab == "bons") { botTab = "bons" }
                        Chip("Fracos (${weak.size})", botTab == "fracos") { botTab = "fracos" }
                        if (posted.isNotEmpty()) Chip("Postados (${posted.size})", botTab == "postados") { botTab = "postados" }
                        if (botTab == "fracos" && weak.isNotEmpty())
                            TextButton({ confirmWeak = true }) { Text("Apagar fracos", color = C.bad) }
                    }
                    // review mode: the list's clips one after another, swiping, like the feed they will be posted to
                    val list = when (botTab) { "bons" -> good; "postados" -> posted; else -> weak }.filter { it.doneClips.isNotEmpty() }
                    if (list.size > 1) Row(Modifier.fillMaxWidth().clip(RoundedCornerShape(14.dp)).background(C.accent)
                        .combinedClickable(onClick = { onReview(list.map { it.id }) }).padding(12.dp),
                        horizontalArrangement = Arrangement.Center) {
                        Text("▶  Revisar ${list.size} em sequência", color = C.onAccent, style = MaterialTheme.typography.titleSmall)
                    }
                    if (botTab == "bons" && good.isEmpty())
                        Text("Nenhum com nota $weakBar ou mais ainda. Os outros estão em Fracos.", color = C.muted,
                            style = MaterialTheme.typography.bodyMedium)
                }
            }
            items(when (botTab) { "bons" -> good; "postados" -> posted; else -> weak }, key = { it.id }) { p -> BotTile(p, grade(p), { onOpen(p) }, { confirm = p }) }
        }
        item(span = { GridItemSpan(2) }) {
            Text(if (projects.isEmpty()) "Seus clipes aparecem aqui." else if (mine.isEmpty()) "" else "Seus projetos",
                style = MaterialTheme.typography.titleMedium,
                color = if (projects.isEmpty()) C.muted else C.text, modifier = Modifier.padding(top = 8.dp))
        }
        items(mine, key = { it.id }) { p ->
            Column(Modifier.clip(RoundedCornerShape(18.dp)).background(C.surface)
                .combinedClickable(onClick = { onOpen(p) }, onLongClick = { confirm = p })) {
                Box(Modifier.fillMaxWidth().aspectRatio(9f / 12f)) {
                    Thumb(p.doneClips.firstOrNull()?.uri ?: "", Modifier.fillMaxSize(), fallback = p.moments.firstOrNull()?.thumb ?: "")
                    Box(Modifier.align(Alignment.TopStart).padding(8.dp).clip(RoundedCornerShape(8.dp))
                        .background(Color.Black.copy(alpha = 0.6f)).padding(horizontal = 8.dp, vertical = 3.dp)) {
                        Text(statusLabel(p), color = C.text, style = MaterialTheme.typography.labelSmall)
                    }
                }
                Column(Modifier.padding(10.dp)) {
                    Text(p.name, style = MaterialTheme.typography.titleMedium, color = C.text, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    Text(SimpleDateFormat("dd/MM HH:mm", Locale.getDefault()).format(Date(p.createdAt)),
                        style = MaterialTheme.typography.labelSmall, color = C.muted)
                }
            }
        }
    }
    if (confirmWeak) AlertDialog(onDismissRequest = { confirmWeak = false },
        title = { Text("Apagar os ${weak.size} fracos?") },
        text = { Text("Saem da lista e da galeria. Os melhores ficam.") },
        confirmButton = { TextButton({ weak.forEach(onDelete); confirmWeak = false }) { Text("Apagar") } },
        dismissButton = { TextButton({ confirmWeak = false }) { Text("Cancelar") } })
    confirm?.let { p ->
        AlertDialog(onDismissRequest = { confirm = null },
            title = { Text("Apagar projeto?") },
            text = { Text(if (p.id.startsWith("srv")) "Sai da lista e da galeria." else "\"${p.name}\" sai da lista. Os clipes continuam na galeria.") },
            confirmButton = { TextButton({ onDelete(p); confirm = null }) { Text("Apagar") } },
            dismissButton = { TextButton({ confirm = null }) { Text("Cancelar") } })
    }
}

/** AI grade under this = a weak clip: kept, but out of the main list. */
const val WEAK = 40

/** A bot clip: its own title, the grade big, the channel and the time. */
@Composable
private fun BotTile(p: Project, grade: Int, onOpen: () -> Unit, onLong: () -> Unit) {
    val clip = p.clips.firstOrNull()
    Column(Modifier.clip(RoundedCornerShape(18.dp)).background(C.surface)
        .combinedClickable(onClick = onOpen, onLongClick = onLong)) {
        Box(Modifier.fillMaxWidth().aspectRatio(9f / 12f)) {
            Thumb(p.doneClips.firstOrNull()?.uri ?: clip?.uri ?: "", Modifier.fillMaxSize())
            Box(Modifier.align(Alignment.TopStart).padding(8.dp).clip(RoundedCornerShape(10.dp))
                .background(if (grade >= 70) C.accent else if (grade >= WEAK) Color(0xFFFFB020) else Color(0xFF555555))
                .padding(horizontal = 10.dp, vertical = 4.dp)) {
                Text("$grade", color = Color.Black, style = MaterialTheme.typography.titleMedium)
            }
            if (p.status == "rendering") Box(Modifier.align(Alignment.TopEnd).padding(8.dp).clip(RoundedCornerShape(8.dp))
                .background(Color.Black.copy(alpha = 0.6f)).padding(horizontal = 8.dp, vertical = 3.dp)) {
                Text("Montando", color = C.text, style = MaterialTheme.typography.labelSmall)
            }
        }
        Column(Modifier.padding(10.dp)) {
            Text(clip?.title ?: p.name, style = MaterialTheme.typography.titleSmall, color = C.text, maxLines = 2,
                overflow = TextOverflow.Ellipsis)
            clipfactory.app.categoryOf(clip?.reason ?: "").takeIf { it.isNotEmpty() }?.let {
                Text(it, style = MaterialTheme.typography.labelSmall, color = C.accent, maxLines = 1)
            }
            Text(p.name.removeSuffix(" ao vivo (servidor)") + " · " +
                SimpleDateFormat("dd/MM HH:mm", Locale.getDefault()).format(Date(p.createdAt)),
                style = MaterialTheme.typography.labelSmall, color = C.muted, maxLines = 1)
        }
    }
}

fun statusLabel(p: Project) = when (p.status) {
    "analyzing" -> "Analisando"
    "choosing" -> "Escolha os momentos"
    "rendering" -> "Montando"
    "failed" -> "Falhou"
    "canceled" -> "Cancelado"
    else -> "${p.doneClips.size} clipes"
}
