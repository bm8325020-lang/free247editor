package clipfactory.app.ui

import android.media.MediaMetadataRetriever
import android.net.Uri
import androidx.annotation.OptIn
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Check
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.RangeSlider
import androidx.compose.material3.SliderDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.media3.common.MediaItem
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.ui.PlayerView
import clipfactory.app.CaptionStyles
import clipfactory.app.ClipService
import clipfactory.app.JobState
import clipfactory.app.ProjectStore
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.withContext
import kotlin.math.max
import kotlin.math.min

/** Change one clip: where it starts and ends, the title, caption look and layout. Only this clip is made again. */
@OptIn(UnstableApi::class)
@Composable
fun EditorScreen(projectId: String, index: Int, onBack: () -> Unit, onApplied: () -> Unit) {
    val ctx = LocalContext.current
    val projects by ProjectStore.projects.collectAsState()
    val p = projects.firstOrNull { it.id == projectId } ?: return
    val clip = p.clips.getOrNull(index) ?: return
    val m = p.moments.firstOrNull { it.id == clip.momentId }
    val dur = if (p.durationS > 0) p.durationS else clip.edit.end + 30
    val lo = max(0.0, min(clip.edit.start, m?.start ?: clip.edit.start) - 15)
    val hi = min(dur, max(clip.edit.end, m?.end ?: clip.edit.end) + 15)

    var range by remember { mutableStateOf(clip.edit.start.toFloat()..clip.edit.end.toFloat()) }
    var hook by remember { mutableStateOf(clip.edit.hook) }
    var style by remember { mutableStateOf(clip.edit.style) }
    var layout by remember { mutableStateOf(clip.edit.layout) }
    val samples = rememberStyleSamples()
    val work by JobState.flow.collectAsState()
    val busy = work.phase != "idle"

    // preview of the source, looping inside the chosen range
    val player = remember { ExoPlayer.Builder(ctx).build().apply { volume = 1f } }
    DisposableEffect(Unit) {
        player.setMediaItem(MediaItem.fromUri(Uri.parse(p.source))); player.prepare()
        player.seekTo((range.start * 1000).toLong()); player.play()
        onDispose { player.release() }
    }
    LaunchedEffect(range.start) { player.seekTo((range.start * 1000).toLong()) }
    LaunchedEffect(Unit) {
        while (isActive) {
            delay(150)
            if (player.currentPosition / 1000.0 > range.endInclusive || player.currentPosition / 1000.0 < range.start - 0.5)
                player.seekTo((range.start * 1000).toLong())
        }
    }
    // filmstrip of the editable window
    var strip by remember { mutableStateOf<List<ImageBitmap>>(emptyList()) }
    LaunchedEffect(p.source, lo, hi) {
        strip = withContext(Dispatchers.IO) {
            runCatching {
                val r = MediaMetadataRetriever()
                try {
                    r.setDataSource(ctx, Uri.parse(p.source))
                    (0 until 8).mapNotNull { i ->
                        val t = lo + (hi - lo) * (i + 0.5) / 8
                        r.getScaledFrameAtTime((t * 1e6).toLong(), MediaMetadataRetriever.OPTION_CLOSEST_SYNC, 160, 90)
                            ?.asImageBitmap()
                    }
                } finally { r.release() }
            }.getOrDefault(emptyList())
        }
    }

    Column(Modifier.fillMaxSize()) {
        TopBar("Editar clipe", onBack)
        Column(Modifier.weight(1f).verticalScroll(rememberScrollState()).padding(horizontal = 16.dp),
            verticalArrangement = Arrangement.spacedBy(14.dp)) {
            Box(Modifier.fillMaxWidth().aspectRatio(16f / 9f).clip(RoundedCornerShape(16.dp)).background(Color.Black)) {
                AndroidView({ PlayerView(it).apply { useController = false } }, Modifier.fillMaxSize(), update = { it.player = player })
            }
            SectionTitle("Corte", "${mmss(range.start.toDouble())} → ${mmss(range.endInclusive.toDouble())} · ${(range.endInclusive - range.start).toInt()} s")
            Box(Modifier.fillMaxWidth().height(54.dp)) {
                Row(Modifier.fillMaxSize().padding(horizontal = 10.dp).clip(RoundedCornerShape(8.dp)).background(C.surface2)) {
                    strip.forEach { Image(it, null, Modifier.weight(1f).fillMaxHeight(), contentScale = ContentScale.Crop) }
                }
                RangeSlider(value = range, valueRange = lo.toFloat()..hi.toFloat(), modifier = Modifier.fillMaxSize(),
                    onValueChange = { v ->
                        // a clip is 3 s to 90 s long
                        val len = v.endInclusive - v.start
                        range = when {
                            len < 3f -> if (v.start != range.start) (v.endInclusive - 3f)..v.endInclusive else v.start..(v.start + 3f)
                            len > 90f -> if (v.start != range.start) v.start..(v.start + 90f) else (v.endInclusive - 90f)..v.endInclusive
                            else -> v
                        }
                    },
                    colors = SliderDefaults.colors(thumbColor = C.accent, activeTrackColor = C.accent.copy(alpha = 0.35f),
                        inactiveTrackColor = Color(0x88000000)))
            }
            SectionTitle("Título no começo", "Deixe vazio para não mostrar")
            OutlinedTextField(hook, { hook = it.take(80) }, Modifier.fillMaxWidth(), singleLine = false, maxLines = 3,
                colors = OutlinedTextFieldDefaults.colors(focusedBorderColor = C.accent, unfocusedBorderColor = C.line,
                    focusedTextColor = C.text, unfocusedTextColor = C.text, cursorColor = C.accent))
            SectionTitle("Legenda")
            StylePicker(style, samples) { style = it }
            SectionTitle("Layout")
            FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                LAYOUTS.forEach { (k, label) -> Chip(label, layout == k) { layout = k } }
            }
        }
        BigButton(if (busy) "Espere o trabalho atual terminar" else "Aplicar e montar de novo", Modifier.padding(16.dp),
            enabled = !busy, icon = Icons.Filled.Check) {
            ProjectStore.update(ctx, projectId) { pr ->
                pr.copy(clips = pr.clips.mapIndexed { i, c ->
                    if (i == index) c.copy(title = if (hook.isNotBlank()) hook else c.title,
                        edit = c.edit.copy(start = range.start.toDouble(), end = range.endInclusive.toDouble(), hook = hook, style = style, layout = layout))
                    else c
                })
            }
            ClipService.rerender(ctx, projectId, index)
            onApplied()
        }
    }
}
