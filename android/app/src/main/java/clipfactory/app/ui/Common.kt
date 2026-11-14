package clipfactory.app.ui

import android.graphics.BitmapFactory
import android.net.Uri
import android.util.Size
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.produceState
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/** Thumbnail of a gallery video (content://) or of an image file path. */
@Composable
fun Thumb(src: String, modifier: Modifier = Modifier, w: Int = 360, h: Int = 640, fallback: String = "") {
    val ctx = LocalContext.current
    val bmp by produceState<ImageBitmap?>(null, src, fallback) {
        value = withContext(Dispatchers.IO) {
            listOf(src, fallback).firstNotNullOfOrNull { s ->
                runCatching {
                    when {
                        s.isBlank() -> null
                        s.startsWith("content://") -> ctx.contentResolver.loadThumbnail(Uri.parse(s), Size(w, h), null)
                        s.startsWith("file://") || s.endsWith(".mp4") -> android.media.MediaMetadataRetriever().run {
                            try { setDataSource(s.removePrefix("file://")); getScaledFrameAtTime(1_000_000, 2, w, h) } finally { release() }
                        }
                        else -> BitmapFactory.decodeFile(s)
                    }?.asImageBitmap()
                }.getOrNull()
            }
        }
    }
    Box(modifier.background(C.surface2)) {
        bmp?.let { Image(it, null, Modifier.matchParentSize(), contentScale = ContentScale.Crop) }
    }
}

@Composable
fun BigButton(text: String, modifier: Modifier = Modifier, enabled: Boolean = true, icon: ImageVector? = null, onClick: () -> Unit) {
    Button(onClick, modifier.fillMaxWidth().height(56.dp), enabled = enabled, shape = RoundedCornerShape(16.dp),
        colors = ButtonDefaults.buttonColors(containerColor = C.accent, contentColor = C.onAccent)) {
        if (icon != null) { Icon(icon, null); Spacer(Modifier.width(8.dp)) }
        Text(text, style = MaterialTheme.typography.labelLarge)
    }
}

@Composable
fun SoftButton(text: String, modifier: Modifier = Modifier, icon: ImageVector? = null, onClick: () -> Unit) {
    Button(onClick, modifier.height(48.dp), shape = RoundedCornerShape(14.dp), contentPadding = PaddingValues(horizontal = 16.dp),
        colors = ButtonDefaults.buttonColors(containerColor = C.surface2, contentColor = C.text)) {
        if (icon != null) { Icon(icon, null, Modifier.size(18.dp)); Spacer(Modifier.width(6.dp)) }
        Text(text, style = MaterialTheme.typography.labelLarge, maxLines = 1)
    }
}

@Composable
fun Card(modifier: Modifier = Modifier, onClick: (() -> Unit)? = null, content: @Composable () -> Unit) {
    Box(modifier.fillMaxWidth().clip(RoundedCornerShape(20.dp)).background(C.surface)
        .then(if (onClick != null) Modifier.clickable { onClick() } else Modifier).padding(16.dp)) { content() }
}

@Composable
fun TopBar(title: String, onBack: (() -> Unit)?, actions: @Composable () -> Unit = {}) {
    Row(Modifier.fillMaxWidth().padding(horizontal = 8.dp, vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
        if (onBack != null) IconButton(onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, "Voltar", tint = C.text) }
        else Spacer(Modifier.width(8.dp))
        Text(title, style = MaterialTheme.typography.headlineSmall, color = C.text, modifier = Modifier.weight(1f),
            maxLines = 1, overflow = TextOverflow.Ellipsis)
        actions()
    }
}

@Composable
fun ScoreBadge(score: Int, modifier: Modifier = Modifier) {
    Box(modifier.clip(RoundedCornerShape(8.dp)).background(C.accent).padding(horizontal = 8.dp, vertical = 3.dp)) {
        Text("$score", color = C.onAccent, style = MaterialTheme.typography.labelSmall)
    }
}

@Composable
fun Chip(text: String, selected: Boolean, onClick: () -> Unit) {
    Box(Modifier.clip(RoundedCornerShape(12.dp)).background(if (selected) C.accent else C.surface2)
        .clickable { onClick() }.padding(horizontal = 14.dp, vertical = 9.dp)) {
        Text(text, color = if (selected) C.onAccent else C.text, style = MaterialTheme.typography.labelSmall)
    }
}

@Composable
fun SectionTitle(text: String, hint: String? = null) {
    Column(Modifier.padding(bottom = 10.dp), verticalArrangement = Arrangement.spacedBy(2.dp)) {
        Text(text, style = MaterialTheme.typography.titleMedium, color = C.text)
        if (hint != null) Text(hint, style = MaterialTheme.typography.bodyMedium, color = C.muted)
    }
}

fun mmss(s: Double): String = "%d:%02d".format((s / 60).toInt(), (s % 60).toInt())

/** Small pictures of each caption style, drawn once in the background and kept for the whole app. */
private var styleSamples: Map<String, ImageBitmap>? = null

@Composable
fun rememberStyleSamples(): Map<String, ImageBitmap> {
    val ctx = LocalContext.current
    val v by produceState(styleSamples ?: emptyMap()) {
        if (styleSamples == null) styleSamples = withContext(Dispatchers.Default) {
            clipfactory.app.CaptionStyles.all.associate { (k, _) -> k to clipfactory.app.CaptionStyles.sample(ctx, k).asImageBitmap() }
        }
        value = styleSamples!!
    }
    return v
}
