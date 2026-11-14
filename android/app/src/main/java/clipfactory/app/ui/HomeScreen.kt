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
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

@Composable
fun HomeScreen(onNew: (Uri) -> Unit, onOpen: (Project) -> Unit, onSettings: () -> Unit, onWork: () -> Unit) {
    val ctx = LocalContext.current
    val projects by ProjectStore.projects.collectAsState()
    val work by JobState.flow.collectAsState()
    val pick = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) {
            runCatching { ctx.contentResolver.takePersistableUriPermission(uri, android.content.Intent.FLAG_GRANT_READ_URI_PERMISSION) }
            onNew(uri)
        }
    }
    HomeContent(projects, work.phase != "idle", work.stage, work.progress, onPick = { pick.launch(arrayOf("video/*")) },
        onOpen = onOpen, onSettings = onSettings, onWork = onWork, onDelete = { ProjectStore.delete(ctx, it.id) })
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
fun HomeContent(projects: List<Project>, busy: Boolean, stage: String, progress: Double, onPick: () -> Unit,
                onOpen: (Project) -> Unit, onSettings: () -> Unit, onWork: () -> Unit, onDelete: (Project) -> Unit) {
    var confirm by remember { mutableStateOf<Project?>(null) }
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
        if (busy) item(span = { GridItemSpan(2) }) {
            Card(onClick = onWork) {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text("Em andamento", style = MaterialTheme.typography.titleMedium, color = C.text)
                    Text(stage, style = MaterialTheme.typography.bodyMedium, color = C.muted)
                    LinearProgressIndicator({ progress.toFloat() }, Modifier.fillMaxWidth().clip(RoundedCornerShape(4.dp)), color = C.accent, trackColor = C.surface2)
                }
            }
        }
        item(span = { GridItemSpan(2) }) {
            Text(if (projects.isEmpty()) "Seus clipes aparecem aqui." else "Seus projetos", style = MaterialTheme.typography.titleMedium,
                color = if (projects.isEmpty()) C.muted else C.text, modifier = Modifier.padding(top = 8.dp))
        }
        items(projects, key = { it.id }) { p ->
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
    confirm?.let { p ->
        AlertDialog(onDismissRequest = { confirm = null },
            title = { Text("Apagar projeto?") },
            text = { Text("\"${p.name}\" sai da lista. Os clipes continuam na galeria.") },
            confirmButton = { TextButton({ onDelete(p); confirm = null }) { Text("Apagar") } },
            dismissButton = { TextButton({ confirm = null }) { Text("Cancelar") } })
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
