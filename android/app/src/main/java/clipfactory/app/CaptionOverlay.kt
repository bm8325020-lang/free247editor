package clipfactory.app

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.PorterDuff
import android.graphics.RectF
import android.graphics.Typeface
import android.text.Layout
import android.text.StaticLayout
import android.text.TextPaint
import androidx.annotation.OptIn
import androidx.media3.common.util.UnstableApi
import androidx.media3.effect.BitmapOverlay
import clipfactory.core.CaptionEvent
import clipfactory.core.Captions
import clipfactory.core.LayoutMath

/**
 * Word-by-word captions (current word highlighted) and a hook title box, drawn into a full-frame transparent
 * bitmap. Two bitmaps take turns so Media3 sees a new bitmap (and re-uploads it) only when the text changes.
 */
@OptIn(UnstableApi::class)
class CaptionOverlay(
    context: Context,
    private val events: List<CaptionEvent>,
    private val hook: String,
    private val hookSeconds: Double,
    private val captionY: Double,
    private val hookY: Double,
) : BitmapOverlay() {
    private val w = LayoutMath.OUT_W
    private val h = LayoutMath.OUT_H
    private val buffers = arrayOf(
        Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888), Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888),
    )
    private var current = 0
    private var lastKey = Int.MIN_VALUE
    private var baseUs = Long.MIN_VALUE
    private val font: Typeface = Typeface.createFromAsset(context.assets, "fonts/Montserrat-Black.ttf")

    private val fill = TextPaint(Paint.ANTI_ALIAS_FLAG).apply {
        typeface = font; textSize = 96f; color = Color.WHITE
        setShadowLayer(10f, 0f, 4f, Color.argb(160, 0, 0, 0))
    }
    private val stroke = TextPaint(Paint.ANTI_ALIAS_FLAG).apply {
        typeface = font; textSize = 96f; color = Color.BLACK
        style = Paint.Style.STROKE; strokeWidth = 14f; strokeJoin = Paint.Join.ROUND
    }
    private val hookPaint = TextPaint(Paint.ANTI_ALIAS_FLAG).apply {
        typeface = font; textSize = 72f; color = Color.rgb(16, 16, 16)
    }
    private val box = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.WHITE }
    private val highlight = Color.rgb(255, 212, 0)

    override fun getBitmap(presentationTimeUs: Long): Bitmap {
        if (baseUs == Long.MIN_VALUE) baseUs = presentationTimeUs
        val t = (presentationTimeUs - baseUs) / 1e6
        val ev = Captions.at(events, t)
        val showHook = hook.isNotBlank() && t < hookSeconds
        val key = ev * 2 + if (showHook) 1 else 0
        if (key != lastKey) {
            lastKey = key
            current = 1 - current
            val bmp = buffers[current]
            bmp.eraseColor(Color.TRANSPARENT)
            val c = Canvas(bmp)
            if (showHook) drawHook(c)
            if (ev >= 0) drawCaption(c, events[ev])
        }
        return buffers[current]
    }

    private fun drawCaption(c: Canvas, e: CaptionEvent) {
        val base = 96f
        val big = base * 1.12f
        val space = fill.measureText(" ")
        val widths = e.words.mapIndexed { i, word ->
            fill.textSize = if (i == e.active) big else base
            fill.measureText(word)
        }
        var total = widths.sum() + space * (e.words.size - 1)
        val scale = if (total > w - 80) (w - 80) / total else 1f
        total *= scale
        var x = (w - total) / 2
        val y = (h * captionY).toFloat() + base * scale * 0.35f
        for ((i, word) in e.words.withIndex()) {
            val size = (if (i == e.active) big else base) * scale
            fill.textSize = size
            stroke.textSize = size
            stroke.strokeWidth = 14f * scale
            fill.color = if (i == e.active) highlight else Color.WHITE
            c.drawText(word, x, y, stroke)
            c.drawText(word, x, y, fill)
            x += widths[i] * scale + space * scale
        }
        fill.color = Color.WHITE
        fill.textSize = base
    }

    private fun drawHook(c: Canvas) {
        val maxW = (w * 0.78).toInt()
        val text = Captions.clean(hook, true)
        val layout = StaticLayout.Builder.obtain(text, 0, text.length, hookPaint, maxW)
            .setAlignment(Layout.Alignment.ALIGN_CENTER).setMaxLines(3).build()
        val lw = (0 until layout.lineCount).maxOf { layout.getLineWidth(it) }
        val pad = 28f
        val top = (h * hookY).toFloat()
        val rect = RectF((w - lw) / 2 - pad, top, (w + lw) / 2 + pad, top + layout.height + pad * 2)
        c.drawRoundRect(rect, 18f, 18f, box)
        c.save()
        c.translate((w - maxW) / 2f, top + pad)
        layout.draw(c)
        c.restore()
    }

    fun recycle() {
        buffers.forEach { if (!it.isRecycled) it.recycle() }
    }

    @Suppress("unused")
    private fun clear(c: Canvas) = c.drawColor(Color.TRANSPARENT, PorterDuff.Mode.CLEAR)
}
