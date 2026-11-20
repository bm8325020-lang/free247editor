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
    private val style: String = "amarelo",
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

    private fun drawCaption(c: Canvas, e: CaptionEvent) =
        CaptionStyles.draw(c, w, (h * captionY).toFloat(), e.words, e.active, style, fill, stroke)

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

/** The caption looks the user can pick. Used for the video and for the samples in the app. */
object CaptionStyles {
    val all = listOf("amarelo" to "Amarelo TikTok", "branco" to "Branco forte", "karaoke" to "Karaokê", "minimal" to "Minimalista")
    private val yellow = Color.rgb(255, 212, 0)
    private val purple = Color.rgb(124, 77, 255)

    fun draw(c: Canvas, w: Int, centerY: Float, words: List<String>, active: Int, style: String, fill: TextPaint, stroke: TextPaint) {
        val minimal = style == "minimal"
        val shown = if (minimal) words.map { it.lowercase() } else words
        val base = if (minimal) 72f else 96f
        val big = if (style == "karaoke" || minimal) base else base * 1.12f
        fill.textSize = base
        val space = fill.measureText(" ")
        val widths = shown.mapIndexed { i, word -> fill.textSize = if (i == active) big else base; fill.measureText(word) }
        var total = widths.sum() + space * (shown.size - 1)
        // safe area: TikTok, Reels and Shorts put their buttons on the right edge, over the lower half
        val maxW = w * 0.74f
        val scale = if (total > maxW) maxW / total else 1f
        total *= scale
        var x = (w - total) / 2 - w * 0.03f
        val y = centerY + base * scale * 0.35f
        val pill = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = purple }
        for ((i, word) in shown.withIndex()) {
            val on = i == active
            val size = (if (on) big else base) * scale
            fill.textSize = size; stroke.textSize = size
            stroke.strokeWidth = (if (style == "branco") 18f else 14f) * scale
            when (style) {
                "branco" -> fill.color = Color.WHITE
                "karaoke" -> {
                    fill.color = Color.WHITE
                    if (on) {
                        val pad = 14f * scale
                        c.drawRoundRect(RectF(x - pad, y - size * 0.85f, x + widths[i] * scale + pad, y + size * 0.25f),
                            18f * scale, 18f * scale, pill)
                    }
                }
                "minimal" -> fill.color = if (on) Color.WHITE else Color.argb(170, 255, 255, 255)
                else -> fill.color = if (on) yellow else Color.WHITE
            }
            if (!minimal && !(style == "karaoke" && on)) c.drawText(word, x, y, stroke)
            c.drawText(word, x, y, fill)
            x += widths[i] * scale + space * scale
        }
        fill.color = Color.WHITE
        fill.textSize = base
    }

    /** A small sample of a style, for the style picker. */
    fun sample(context: Context, style: String, width: Int = 540, height: Int = 200): Bitmap {
        val bmp = Bitmap.createBitmap(LayoutMath.OUT_W, 380, Bitmap.Config.ARGB_8888)
        val c = Canvas(bmp)
        c.drawColor(Color.rgb(30, 34, 44))
        val font = Typeface.createFromAsset(context.assets, "fonts/Montserrat-Black.ttf")
        val fill = TextPaint(Paint.ANTI_ALIAS_FLAG).apply { typeface = font; color = Color.WHITE
            setShadowLayer(10f, 0f, 4f, Color.argb(160, 0, 0, 0)) }
        val stroke = TextPaint(Paint.ANTI_ALIAS_FLAG).apply { typeface = font; color = Color.BLACK
            this.style = Paint.Style.STROKE; strokeJoin = Paint.Join.ROUND }
        draw(c, LayoutMath.OUT_W, 190f, listOf("ISSO", "É", "INSANO"), 2, style, fill, stroke)
        return Bitmap.createScaledBitmap(bmp, width, width * 380 / LayoutMath.OUT_W, true).also { if (it !== bmp) bmp.recycle() }
    }
}
