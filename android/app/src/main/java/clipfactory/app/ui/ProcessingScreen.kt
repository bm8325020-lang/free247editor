package clipfactory.app.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Check
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import clipfactory.app.ClipService
import clipfactory.app.JobState
import clipfactory.app.Moment
import clipfactory.app.Project
import clipfactory.app.ProjectStore
import clipfactory.app.Work

@Composable
fun ProcessingScreen(projectId: String?, onBack: () -> Unit) {
    val ctx = LocalContext.current
    val work by JobState.flow.collectAsState()
    val projects by ProjectStore.projects.collectAsState()
    val p = projects.firstOrNull { it.id == (projectId ?: work.projectId) }
    ProcessingContent(p, work, onBack = onBack, onCancel = { ClipService.cancel(ctx) })
}

@Composable
fun ProcessingContent(p: Project?, work: Work, onBack: () -> Unit, onCancel: () -> Unit) {
    val steps = listOf("Ouvir o vídeo", "Achar os momentos", "Montar os clipes")
    // this screen can be opened for a project that is not being worked on right now: say so instead of "0%"
    val running = work.phase != "idle" && (p == null || work.projectId == p.id)
    val ownError = work.error?.takeIf { p == null || work.projectId == p.id }
    val finished = p != null && p.doneClips.isNotEmpty() && !running
    val current = when {
        !running -> if (finished) 3 else if (p?.moments.isNullOrEmpty()) 0 else 2
        work.phase == "rendering" -> 2
        work.stage.startsWith("Ouvindo o vídeo") || work.stage.startsWith("Procurando") || work.stage.startsWith("Abrindo") -> 0
        else -> 1
    }
    // overall progress: analysis is the first half, rendering the second
    val overall = if (work.phase == "rendering") {
        val total = (p?.clips?.size ?: 1).coerceAtLeast(1)
        val done = p?.clips?.count { it.status == "done" || it.status == "failed" } ?: 0
        0.5 + 0.5 * ((done + work.progress) / total).coerceIn(0.0, 1.0)
    } else if (!running) (if (finished) 1.0 else 0.0) else 0.5 * work.progress
    Column(Modifier.fillMaxSize()) {
        TopBar(p?.name ?: "Preparando", onBack)
        Column(Modifier.weight(1f).padding(horizontal = 20.dp), horizontalAlignment = Alignment.CenterHorizontally) {
            Spacer(Modifier.height(24.dp))
            Box(contentAlignment = Alignment.Center) {
                CircularProgressIndicator({ overall.toFloat() }, Modifier.size(180.dp), color = C.accent, strokeWidth = 12.dp,
                    trackColor = C.surface2)
                Text("${(overall * 100).toInt()}%", style = MaterialTheme.typography.headlineLarge, color = C.text)
            }
            Spacer(Modifier.height(20.dp))
            val label = when {
                ownError != null && !running -> "Parou: $ownError"
                running -> work.stage.ifBlank { "Começando…" }
                finished -> "Pronto: ${p!!.doneClips.size} clipe${if (p.doneClips.size > 1) "s" else ""}"
                p?.status == "choosing" -> "Esperando você escolher os momentos"
                else -> "Parado. Nada está rodando agora."
            }
            Text(label, style = MaterialTheme.typography.titleMedium, color = if (ownError != null && !running) C.bad else C.text,
                textAlign = TextAlign.Center)
            Spacer(Modifier.height(24.dp))
            Card {
                Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    steps.forEachIndexed { i, label ->
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Box(Modifier.size(26.dp).clip(CircleShape).background(if (i < current) C.good else if (i == current) C.accent else C.surface2),
                                contentAlignment = Alignment.Center) {
                                if (i < current) Icon(Icons.Filled.Check, null, tint = C.onAccent, modifier = Modifier.size(16.dp))
                                else Text("${i + 1}", color = if (i == current) C.onAccent else C.muted, style = MaterialTheme.typography.labelSmall)
                            }
                            Spacer(Modifier.width(12.dp))
                            Text(label, color = if (i <= current) C.text else C.muted, style = MaterialTheme.typography.bodyMedium)
                        }
                    }
                }
            }
            val moments = p?.moments.orEmpty()
            if (moments.isNotEmpty()) {
                Spacer(Modifier.height(20.dp))
                Text("${moments.size} momentos achados", style = MaterialTheme.typography.titleMedium, color = C.text,
                    modifier = Modifier.fillMaxWidth())
                Spacer(Modifier.height(8.dp))
                LazyRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    items(moments) { m -> MomentChip(m, p?.clips?.firstOrNull { it.momentId == m.id }?.status) }
                }
            }
        }
        if (running) SoftButton("Cancelar", Modifier.fillMaxWidth().padding(16.dp), onClick = onCancel)
        else SoftButton("Voltar", Modifier.fillMaxWidth().padding(16.dp), onClick = onBack)
    }
}

@Composable
private fun MomentChip(m: Moment, clipStatus: String?) {
    Box(Modifier.width(120.dp).aspectRatio(16f / 9f).clip(RoundedCornerShape(12.dp))) {
        Thumb(m.thumb, Modifier.fillMaxSize())
        Box(Modifier.align(Alignment.BottomStart).padding(6.dp)) { ScoreBadge(m.score) }
        if (clipStatus == "done") Box(Modifier.align(Alignment.TopEnd).padding(6.dp).size(22.dp).clip(CircleShape).background(C.good),
            contentAlignment = Alignment.Center) { Icon(Icons.Filled.Check, null, tint = C.onAccent, modifier = Modifier.size(14.dp)) }
    }
}
