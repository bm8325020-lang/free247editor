package clipfactory.app.ui

import android.graphics.Bitmap
import android.media.MediaMetadataRetriever
import android.net.Uri
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.AutoAwesome
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Slider
import androidx.compose.material3.SliderDefaults
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import clipfactory.app.CaptionStyles
import clipfactory.app.ClipService
import clipfactory.app.Options
import clipfactory.app.Prefs
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/** Before starting: a few choices, prefilled with the user's defaults (and saved as the new defaults). */
@Composable
fun NewClipScreen(uri: Uri, name: String, onBack: () -> Unit, onStarted: () -> Unit) {
    val ctx = LocalContext.current
    // keep access to the video after the app closes, when the app that shared it allows that
    remember(uri) { runCatching { ctx.contentResolver.takePersistableUriPermission(uri, android.content.Intent.FLAG_GRANT_READ_URI_PERMISSION) } }
    var opt by remember { mutableStateOf(Prefs.options(ctx)) }
    val frame by produceState<Pair<ImageBitmap?, Double>>(null to 0.0, uri) {
        value = withContext(Dispatchers.IO) {
            val r = MediaMetadataRetriever()
            runCatching {
                r.setDataSource(ctx, uri)
                val d = (r.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION)?.toLongOrNull() ?: 0L) / 1000.0
                r.getScaledFrameAtTime((d * 0.3 * 1e6).toLong(), MediaMetadataRetriever.OPTION_CLOSEST_SYNC, 640, 360)?.asImageBitmap() to d
            }.getOrDefault(null to 0.0).also { r.release() }
        }
    }
    val samples = rememberStyleSamples()
    NewClipContent(name, frame.first, frame.second, opt, samples, onChange = { opt = it; Prefs.save(ctx, it) }, onBack = onBack) {
        ClipService.analyze(ctx, uri, name)
        onStarted()
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
fun NewClipContent(name: String, frame: ImageBitmap?, durationS: Double, opt: Options, samples: Map<String, ImageBitmap>,
                   onChange: (Options) -> Unit, onBack: () -> Unit, onStart: () -> Unit) {
    Column(Modifier.fillMaxSize()) {
        TopBar("Novo clipe", onBack)
        Column(Modifier.weight(1f).verticalScroll(rememberScrollState()).padding(horizontal = 16.dp),
            verticalArrangement = Arrangement.spacedBy(14.dp)) {
            Box(Modifier.fillMaxWidth().aspectRatio(16f / 9f).clip(RoundedCornerShape(20.dp)).background(C.surface2)) {
                frame?.let { Image(it, null, Modifier.fillMaxSize(), contentScale = ContentScale.Crop) }
                Column(Modifier.align(Alignment.BottomStart).fillMaxWidth()
                    .background(androidx.compose.ui.graphics.Brush.verticalGradient(listOf(androidx.compose.ui.graphics.Color.Transparent, C.bg.copy(alpha = 0.9f))))
                    .padding(14.dp)) {
                    Text(name, style = MaterialTheme.typography.titleMedium, color = C.text, maxLines = 1)
                    if (durationS > 0) Text("Duração ${mmss(durationS)}", style = MaterialTheme.typography.labelSmall, color = C.muted)
                }
            }
            Card {
                Column {
                    SectionTitle("Quantos clipes", "Até ${opt.clips}: momentos fracos não viram clipe só para completar.")
                    Slider(opt.clips.toFloat(), { onChange(opt.copy(clips = it.toInt())) }, valueRange = 1f..15f, steps = 13,
                        colors = SliderDefaults.colors(thumbColor = C.accent, activeTrackColor = C.accent))
                    SectionTitle("Duração máxima: ${opt.maxSeconds} s")
                    Slider(opt.maxSeconds.toFloat(), { onChange(opt.copy(maxSeconds = (it / 5).toInt() * 5)) }, valueRange = 20f..60f,
                        colors = SliderDefaults.colors(thumbColor = C.accent, activeTrackColor = C.accent))
                }
            }
            Card {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f)) {
                        Text("Escolher os momentos antes de montar", style = MaterialTheme.typography.titleMedium, color = C.text)
                        Text(if (opt.chooseMoments) "Você vê os momentos achados e marca quais viram clipe."
                             else "Automático: ele escolhe e monta sozinho.", style = MaterialTheme.typography.bodyMedium, color = C.muted)
                    }
                    Switch(opt.chooseMoments, { onChange(opt.copy(chooseMoments = it)) },
                        colors = SwitchDefaults.colors(checkedTrackColor = C.accent, checkedThumbColor = C.onAccent))
                }
            }
            Card {
                Column {
                    SectionTitle("Legenda")
                    StylePicker(opt.captionStyle, samples) { onChange(opt.copy(captionStyle = it)) }
                }
            }
            Card {
                Column {
                    SectionTitle("Layout")
                    FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        LAYOUTS.forEach { (k, label) -> Chip(label, opt.layout == k) { onChange(opt.copy(layout = k)) } }
                    }
                }
            }
            Spacer(Modifier.height(4.dp))
        }
        BigButton("Criar clipes", Modifier.padding(16.dp), icon = Icons.Filled.AutoAwesome, onClick = onStart)
    }
}

val LAYOUTS = listOf(
    "auto" to "Automático", "stack" to "Webcam em cima", "pip" to "Webcam no canto",
    "split" to "Webcam em faixa", "game" to "Só gameplay",
)

@Composable
fun StylePicker(selected: String, samples: Map<String, ImageBitmap>, onPick: (String) -> Unit) {
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        CaptionStyles.all.chunked(2).forEach { row ->
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                row.forEach { (k, label) ->
                    val on = k == selected
                    Column(Modifier.weight(1f).clip(RoundedCornerShape(14.dp)).background(if (on) C.accent else C.surface2)
                        .clickable { onPick(k) }.padding(3.dp)) {
                        Box(Modifier.fillMaxWidth().aspectRatio(1080f / 380f).clip(RoundedCornerShape(11.dp)).background(C.bg)) {
                            samples[k]?.let { Image(it, label, Modifier.fillMaxSize(), contentScale = ContentScale.Crop) }
                        }
                        Text(label, style = MaterialTheme.typography.labelSmall, color = if (on) C.onAccent else C.text,
                            modifier = Modifier.padding(horizontal = 8.dp, vertical = 6.dp))
                    }
                }
            }
        }
    }
}
