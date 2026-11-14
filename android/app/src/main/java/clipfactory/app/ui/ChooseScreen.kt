package clipfactory.app.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Movie
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import clipfactory.app.ClipService
import clipfactory.app.Moment
import clipfactory.app.ProjectStore

@Composable
fun ChooseScreen(projectId: String, onBack: () -> Unit, onStarted: () -> Unit) {
    val ctx = LocalContext.current
    val projects by ProjectStore.projects.collectAsState()
    val p = projects.firstOrNull { it.id == projectId } ?: return
    var chosen by remember(projectId) { mutableStateOf(p.moments.filter { it.preselected }.map { it.id }.toSet()) }
    ChooseContent(p.name, p.moments, p.notes, chosen, onToggle = { id -> chosen = if (id in chosen) chosen - id else chosen + id },
        onBack = onBack) {
        ClipService.render(ctx, projectId, p.moments.map { it.id }.filter { it in chosen })
        onStarted()
    }
}

@Composable
fun ChooseContent(name: String, moments: List<Moment>, notes: List<String>, chosen: Set<Int>, onToggle: (Int) -> Unit,
                  onBack: () -> Unit, onGo: () -> Unit) {
    Column(Modifier.fillMaxSize()) {
        TopBar("Escolha os momentos", onBack)
        LazyColumn(Modifier.weight(1f), contentPadding = PaddingValues(horizontal = 16.dp, vertical = 4.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp)) {
            item {
                Text("Os melhores já vêm marcados. Toque para marcar ou desmarcar.", style = MaterialTheme.typography.bodyMedium,
                    color = C.muted)
                notes.forEach { Text(it, style = MaterialTheme.typography.bodyMedium, color = C.accent, modifier = Modifier.padding(top = 6.dp)) }
            }
            items(moments, key = { it.id }) { m ->
                val on = m.id in chosen
                Row(Modifier.fillMaxWidth().clip(RoundedCornerShape(18.dp)).background(C.surface)
                    .border(2.dp, if (on) C.accent else C.surface, RoundedCornerShape(18.dp))
                    .clickable { onToggle(m.id) }.padding(10.dp), verticalAlignment = Alignment.CenterVertically) {
                    Box(Modifier.width(128.dp).height(72.dp).clip(RoundedCornerShape(12.dp))) {
                        Thumb(m.thumb, Modifier.fillMaxSize())
                        Box(Modifier.align(Alignment.BottomStart).padding(5.dp)) { ScoreBadge(m.score) }
                    }
                    Spacer(Modifier.width(12.dp))
                    Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(3.dp)) {
                        Text(m.title, style = MaterialTheme.typography.titleMedium, color = C.text, maxLines = 2, overflow = TextOverflow.Ellipsis)
                        Text("${mmss(m.start)} · ${(m.end - m.start).toInt()} s", style = MaterialTheme.typography.labelSmall, color = C.muted)
                        if (m.text.isNotBlank()) Text("“${m.text}”", style = MaterialTheme.typography.bodyMedium, color = C.muted,
                            maxLines = 2, overflow = TextOverflow.Ellipsis)
                    }
                    Spacer(Modifier.width(8.dp))
                    Box(Modifier.size(28.dp).clip(CircleShape).background(if (on) C.accent else C.surface2), contentAlignment = Alignment.Center) {
                        if (on) Icon(Icons.Filled.Check, null, tint = C.onAccent, modifier = Modifier.size(18.dp))
                    }
                }
            }
        }
        BigButton(if (chosen.isEmpty()) "Marque pelo menos um" else "Montar ${chosen.size} clipe${if (chosen.size > 1) "s" else ""}",
            Modifier.padding(16.dp), enabled = chosen.isNotEmpty(), icon = Icons.Filled.Movie, onClick = onGo)
    }
}
