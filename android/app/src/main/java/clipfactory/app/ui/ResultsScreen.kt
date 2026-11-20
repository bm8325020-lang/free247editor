package clipfactory.app.ui

import android.app.Activity
import android.net.Uri
import androidx.annotation.OptIn
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.pager.VerticalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.Send
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
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
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.media3.common.MediaItem
import androidx.media3.common.Player
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.ui.AspectRatioFrameLayout
import androidx.media3.ui.PlayerView
import clipfactory.app.PClip
import clipfactory.app.Poster
import clipfactory.app.Prefs
import clipfactory.app.ProjectStore
import clipfactory.core.PostText

/** The clips as a full-screen vertical feed, like the app they will be posted to. */
@OptIn(UnstableApi::class)
@kotlin.OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ResultsScreen(projectId: String, start: Int, onBack: () -> Unit, onEdit: (String, Int) -> Unit, openPost: Boolean = false,
                  group: List<String> = listOf(projectId)) {
    val ctx = LocalContext.current
    val projects by ProjectStore.projects.collectAsState()
    val p = projects.firstOrNull { it.id == projectId } ?: return
    // one project's clips, or (review mode) the clips of several projects one after another
    val entries = group.mapNotNull { id -> projects.firstOrNull { it.id == id } }.flatMap { pr -> pr.doneClips.map { pr to it } }
    val clips = entries.map { it.second }
    if (clips.isEmpty()) {
        Column(Modifier.fillMaxSize()) {
            TopBar(p.name, onBack)
            Column(Modifier.padding(24.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Text("Nenhum clipe pronto.", style = MaterialTheme.typography.titleLarge, color = C.text)
                (p.notes + p.clips.filter { it.status == "failed" }.map { "Falhou: ${it.error}" }).forEach {
                    Text(it, style = MaterialTheme.typography.bodyMedium, color = C.muted)
                }
            }
        }
        return
    }
    val pager = rememberPagerState(initialPage = start.coerceIn(0, clips.size - 1)) { clips.size }
    val player = remember { ExoPlayer.Builder(ctx).build().apply { repeatMode = Player.REPEAT_MODE_ONE } }
    DisposableEffect(Unit) { onDispose { player.release() } }
    val current = clips.getOrNull(pager.currentPage)
    LaunchedEffect(current?.uri) {
        current ?: return@LaunchedEffect
        player.setMediaItem(MediaItem.fromUri(Uri.parse(current.uri))); player.prepare(); player.play()
    }
    var posting by remember { mutableStateOf(if (openPost) entries.getOrNull(start) else null) }
    var deleting by remember { mutableStateOf<Pair<clipfactory.app.Project, PClip>?>(null) }

    Box(Modifier.fillMaxSize().background(Color.Black)) {
        VerticalPager(pager, Modifier.fillMaxSize(), key = { clips[it].uri }) { page ->
            val (pp, c) = entries[page]
            Box(Modifier.fillMaxSize()) {
                if (page == pager.currentPage) {
                    AndroidView({ PlayerView(it).apply { useController = false; resizeMode = AspectRatioFrameLayout.RESIZE_MODE_FIT } },
                        Modifier.fillMaxSize().clickable { if (player.isPlaying) player.pause() else player.play() },
                        update = { it.player = player })
                }
                // bottom info
                Column(Modifier.align(Alignment.BottomStart).fillMaxWidth()
                    .background(Brush.verticalGradient(listOf(Color.Transparent, Color(0xCC000000))))
                    .padding(start = 16.dp, end = 88.dp, top = 40.dp, bottom = 24.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        ScoreBadge(c.score)
                        Text("${page + 1}/${clips.size} · ${c.seconds.toInt()} s · ${c.layoutName}", style = MaterialTheme.typography.labelSmall, color = C.muted)
                    }
                    Text(c.title, style = MaterialTheme.typography.titleLarge.copy(fontWeight = FontWeight.Black), color = Color.White,
                        maxLines = 3, overflow = TextOverflow.Ellipsis)
                    if (page == 0 || group.size > 1) pp.notes.firstOrNull()?.let {
                        Text(it, style = MaterialTheme.typography.labelSmall, color = C.accent, maxLines = 3, overflow = TextOverflow.Ellipsis)
                    }
                    // the AI's reason in full on a tap (two lines cut it in the middle)
                    var open by remember(c.uri) { mutableStateOf(false) }
                    if (c.reason.isNotBlank()) Text(c.reason, style = MaterialTheme.typography.bodyMedium, color = C.muted,
                        maxLines = if (open) 12 else 2, overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.clickable { open = !open })
                }
                // side buttons
                Column(Modifier.align(Alignment.BottomEnd).padding(end = 12.dp, bottom = 32.dp), verticalArrangement = Arrangement.spacedBy(18.dp),
                    horizontalAlignment = Alignment.CenterHorizontally) {
                    SideButton(Icons.Filled.Send, "Postar", accent = true) { posting = pp to c }
                    SideButton(Icons.Filled.Edit, "Editar") {
                        // a clip from the bot is already vertical: re-laying it out would ruin it
                        if (c.layoutName == "do servidor") android.widget.Toast.makeText(ctx,
                            "Este clipe veio pronto do bot. Para cortar diferente, use o vídeo original em Novo clipe.",
                            android.widget.Toast.LENGTH_LONG).show()
                        else onEdit(pp.id, pp.clips.indexOf(c))
                    }
                    SideButton(Icons.Filled.Delete, "Apagar") { deleting = pp to c }
                }
            }
        }
        Row(Modifier.padding(8.dp).clip(CircleShape).background(Color(0x66000000)).clickable(onClick = onBack).padding(10.dp)) {
            Icon(Icons.AutoMirrored.Filled.ArrowBack, "Voltar", tint = Color.White)
        }
    }

    posting?.let { (pp, c) ->
        ModalBottomSheet(onDismissRequest = { posting = null }, containerColor = C.surface) {
            Column(Modifier.navigationBarsPadding().padding(horizontal = 20.dp).padding(bottom = 28.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Text("Postar em", style = MaterialTheme.typography.titleLarge, color = C.text)
                Text("A legenda (título, créditos e hashtags) é copiada e o app abre com o clipe. Você cola e publica.",
                    style = MaterialTheme.typography.bodyMedium, color = C.muted)
                PostText.Platform.entries.forEach { pl ->
                    Row(Modifier.fillMaxWidth().clip(RoundedCornerShape(14.dp)).background(C.surface2)
                        .clickable {
                            posting = null
                            val ready = pp.notes.firstOrNull { it.startsWith("Legenda sugerida pelo servidor:") }
                                ?.substringAfter("\n")?.trim()
                            Poster.post(ctx as Activity, Uri.parse(c.uri), c.title, Prefs.options(ctx), pl, ready)
                            // marked as posted: the home screen keeps posted clips in their own tab
                            ProjectStore.update(ctx, pp.id) { pr ->
                                pr.copy(clips = pr.clips.map { x -> if (x.uri == c.uri) x.copy(posted = System.currentTimeMillis()) else x })
                            }
                        }
                        .padding(16.dp)) { Text(pl.label, style = MaterialTheme.typography.titleMedium, color = C.text) }
                }
            }
        }
    }
    deleting?.let { (pp, c) ->
        AlertDialog(onDismissRequest = { deleting = null }, containerColor = C.surface,
            title = { Text("Apagar este clipe?", color = C.text) },
            text = { Text("Ele sai da galeria também.", color = C.muted) },
            confirmButton = { TextButton({
                runCatching { ctx.contentResolver.delete(Uri.parse(c.uri), null, null) }
                ProjectStore.update(ctx, pp.id) { pr -> pr.copy(clips = pr.clips.filter { it !== c && it.uri != c.uri }) }
                deleting = null
            }) { Text("Apagar", color = C.bad) } },
            dismissButton = { TextButton({ deleting = null }) { Text("Cancelar", color = C.text) } })
    }
}

@Composable
private fun SideButton(icon: ImageVector, label: String, accent: Boolean = false, onClick: () -> Unit) {
    Column(horizontalAlignment = Alignment.CenterHorizontally, modifier = Modifier.clickable(onClick = onClick)) {
        Box(Modifier.size(54.dp).clip(CircleShape).background(if (accent) C.accent else Color(0x88000000)), contentAlignment = Alignment.Center) {
            Icon(icon, label, tint = if (accent) C.onAccent else Color.White)
        }
        Spacer(Modifier.height(4.dp))
        Text(label, style = MaterialTheme.typography.labelSmall, color = Color.White)
    }
}
