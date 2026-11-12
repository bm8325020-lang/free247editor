package clipfactory.core

import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min

/** A rectangle in 0..1 image coordinates, origin top-left. */
data class Box(val x: Double, val y: Double, val w: Double, val h: Double)

/** A detected face in 0..1 image coordinates (centre and size). */
data class Face(val cx: Double, val cy: Double, val w: Double, val h: Double)

/** Where the streamer's face moves inside the webcam overlay, plus the overlay border when found. */
data class Facecam(
    val x0: Double, val x1: Double, val y0: Double, val y1: Double, val fh: Double,
    val cx0: Double, val cx1: Double, val cy0: Double, val cy1: Double, val fw: Double,
    val rect: Box? = null,
)

sealed class Layout {
    /** Webcam panel on top, gameplay below. */
    data class Split(val cam: Box, val panel: Double, val game: Box) : Layout()

    /** Gameplay only: zoomed band in the middle over a blurred copy. */
    data class Game(val zoom: Double) : Layout()

    /** Big face (just chatting): a 9:16 crop that follows the face. */
    data class Follow(val crop: Box) : Layout()
}

/** Port of tools/clipper/layout.py: facecam detection and the vertical-frame geometry. */
object LayoutMath {
    const val OUT_W = 1080
    const val OUT_H = 1920

    private fun pct(v: List<Double>, q: Double) = Stats.percentile(v.toDoubleArray(), q)

    /**
     * samples: faces found in each sampled frame. edgeFreq: per-pixel fraction of samples where that pixel was an
     * edge (w x h, row-major). Returns a Facecam, "BIG" via [isBigFace], or null.
     */
    fun detectFacecam(samples: List<List<Face>>, edgeFreq: FloatArray?, ew: Int, eh: Int): Any? {
        val n = samples.size
        val hits = samples.flatten()
        if (n == 0 || hits.isEmpty()) return null
        val bins = hits.groupingBy { (it.cx / 0.2).toInt() to (it.cy / 0.2).toInt() }.eachCount()
        val (bx, by) = bins.maxBy { it.value }.key
        val seed = hits.filter { (it.cx / 0.2).toInt() == bx && (it.cy / 0.2).toInt() == by }
        val mx = Stats.median(seed.map { it.cx }.toDoubleArray())
        val my = Stats.median(seed.map { it.cy }.toDoubleArray())
        val group = hits.filter { abs(it.cx - mx) < 0.15 && abs(it.cy - my) < 0.2 }
        if (group.size < 0.35 * n) return null
        val fh = Stats.median(group.map { it.h }.toDoubleArray())
        if (fh > 0.33) return BIG
        var cam = Facecam(
            x0 = pct(group.map { it.cx - it.w / 2 }, 10.0), x1 = pct(group.map { it.cx + it.w / 2 }, 90.0),
            y0 = pct(group.map { it.cy - it.h / 2 }, 10.0), y1 = pct(group.map { it.cy + it.h / 2 }, 90.0),
            fh = fh,
            cx0 = pct(group.map { it.cx }, 10.0), cx1 = pct(group.map { it.cx }, 90.0),
            cy0 = pct(group.map { it.cy }, 10.0), cy1 = pct(group.map { it.cy }, 90.0),
            fw = Stats.median(group.map { it.w }.toDoubleArray()),
        )
        if (edgeFreq != null) overlayRect(edgeFreq, ew, eh, cam)?.let { cam = cam.copy(rect = it) }
        return cam
    }

    val BIG = Any()

    fun isBigFace(r: Any?) = r === BIG

    /** The webcam overlay's border: straight edges that stay put while the game changes around them. */
    fun overlayRect(freq: FloatArray, w: Int, h: Int, cam: Facecam): Box? {
        val persist = BooleanArray(freq.size) { freq[it] > 0.45f }
        val fx0 = ((cam.cx0 - cam.fw * 0.2) * w).toInt()
        val fx1 = ((cam.cx1 + cam.fw * 0.2) * w).toInt()
        val fy0 = ((cam.cy0 - cam.fh * 0.2) * h).toInt()
        val fy1 = ((cam.cy1 + cam.fh * 0.2) * h).toInt()
        val ys = max(0, fy0 - h / 10)
        val ye = min(h, fy1 + h / 10)
        val xs = max(0, fx0 - w / 10)
        val xe = min(w, fx1 + w / 10)
        val col = DoubleArray(w) { x ->
            var c = 0
            for (y in ys until ye) if (persist[y * w + x]) c++
            if (ye > ys) c.toDouble() / (ye - ys) else 0.0
        }
        val row = DoubleArray(h) { y ->
            var c = 0
            for (x in xs until xe) if (persist[y * w + x]) c++
            if (xe > xs) c.toDouble() / (xe - xs) else 0.0
        }

        fun line(lo0: Int, hi0: Int, profile: DoubleArray, faceOnHighSide: Boolean): Int? {
            val lo = max(0, lo0)
            val hi = min(profile.size - 1, hi0)
            if (hi <= lo) return null
            val idx = if (faceOnHighSide) (hi downTo lo) else (lo..hi)
            for (i in idx) if (profile[i] >= 0.5) return i
            return null
        }

        val left = line(fx0 - w / 3, fx0 - 2, col, true) ?: if (fx0 < w * 0.12) 0 else null
        val right = line(fx1 + 2, fx1 + w / 3, col, false) ?: if (fx1 > w * 0.88) w - 1 else null
        val top = line(fy0 - h / 3, fy0 - 2, row, true) ?: if (fy0 < h * 0.15) 0 else null
        val bottom = line(fy1 + 2, fy1 + h / 3, row, false) ?: if (fy1 > h * 0.85) h - 1 else null
        if (left == null || right == null || top == null || bottom == null) return null
        if (right - left < w * 0.08 || bottom - top < h * 0.1) return null
        return Box((left + 1.0) / w, (top + 1.0) / h, (right - left - 1.0) / w, (bottom - top - 1.0) / h)
    }

    private fun even(x: Double) = max(2, (Math.round(x / 2) * 2).toInt())

    /** Facecam panel height (fraction of the output height). */
    fun panelFraction(cam: Facecam, srcW: Int, srcH: Int, default: Double = 0.36): Double {
        val r = cam.rect ?: return even(OUT_H * default).toDouble() / OUT_H
        val aspect = (r.w * srcW) / (r.h * srcH)
        val frac = OUT_W / aspect / OUT_H
        return even(OUT_H * frac.coerceIn(0.28, 0.45)).toDouble() / OUT_H
    }

    /** Box to show in the webcam panel, with the panel's aspect ratio. */
    fun camBox(cam: Facecam, srcW: Int, srcH: Int, panel: Double): Box {
        val aspect = OUT_W / (panel * OUT_H)
        val W = srcW.toDouble()
        val H = srcH.toDouble()
        cam.rect?.let { r0 ->
            var x = r0.x
            var y = r0.y
            var rw = r0.w
            var rh = r0.h
            val bw = rw * W
            val bh = rh * H
            if (bw / bh > aspect) {
                val nbw = bh * aspect
                x += (bw - nbw) / 2 / W
                rw = nbw / W
            } else {
                val nbh = bw / aspect
                val fy = (cam.y0 + cam.y1) / 2 * H
                val y0 = min(max(fy - nbh / 2, y * H), y * H + bh - nbh)
                y = y0 / H
                rh = nbh / H
            }
            return Box(x, y, rw, rh)
        }
        val fh = cam.fh * H
        val cx = (cam.x0 + cam.x1) / 2 * W
        val cy = (cam.y0 + cam.y1) / 2 * H + fh * 0.1
        var bh = max(fh * 2.2, (cam.y1 - cam.y0) * H * 1.2)
        var bw = max(bh * aspect, (cam.x1 - cam.x0) * W * 1.25)
        bh = bw / aspect
        if (bw > W * 0.6) {
            bw = W * 0.6
            bh = bw / aspect
        }
        if (bh > H) {
            bh = H
            bw = bh * aspect
        }
        val x0 = min(max(cx - bw / 2, 0.0), W - bw)
        val y0 = min(max(cy - bh / 2, 0.0), H - bh)
        return Box(x0 / W, y0 / H, bw / W, bh / H)
    }

    /** Gameplay area for the lower panel, nudged so the webcam stays out of it when a small shift is enough. */
    fun gameBox(cam: Box, srcW: Int, srcH: Int, panel: Double, zoom: Double = 1.0): Box {
        val W = srcW.toDouble()
        val H = srcH.toDouble()
        val ga = OUT_W / ((1 - panel) * OUT_H)
        var gh = H / zoom
        var gw = gh * ga
        if (gw > W) {
            gw = W
            gh = W / ga
        }
        var gx = (W - gw) / 2
        val gy = (H - gh) / 2
        val cx0 = cam.x * W
        val cx1 = (cam.x + cam.w) * W
        if (cx1 > gx && cx0 < gx + gw && cam.y * H < gy + gh) {
            if (cx1 - gx < W * 0.12 && cx1 + gw <= W) gx = cx1
            else if (gx + gw - cx0 < W * 0.12 && cx0 - gw >= 0) gx = cx0 - gw
        }
        return Box(gx / W, gy / H, gw / W, gh / H)
    }

    /** 9:16 crop around a face (follow layout). */
    fun followCrop(face: Face?, srcW: Int, srcH: Int): Box {
        val W = srcW.toDouble()
        val H = srcH.toDouble()
        var ch = H
        var cw = ch * OUT_W / OUT_H
        if (cw > W) {
            cw = W
            ch = cw * OUT_H / OUT_W
        }
        val cx = (face?.cx ?: 0.5) * W
        val cy = (face?.cy ?: 0.4) * H
        val x0 = min(max(cx - cw / 2, 0.0), W - cw)
        val y0 = min(max(cy - ch * 0.4, 0.0), H - ch)
        return Box(x0 / W, y0 / H, cw / W, ch / H)
    }

    fun choose(det: Any?, srcW: Int, srcH: Int, force: String = "auto", followFace: Face? = null): Layout {
        if (srcW.toDouble() / srcH <= 0.75) return Layout.Follow(followCrop(followFace, srcW, srcH))
        if (force == "game") return Layout.Game(1.3)
        val cam = det as? Facecam
        if (cam != null && force != "follow") {
            val panel = panelFraction(cam, srcW, srcH)
            val box = camBox(cam, srcW, srcH, panel)
            return Layout.Split(box, panel, gameBox(box, srcW, srcH, panel))
        }
        if (force == "follow" || isBigFace(det)) return Layout.Follow(followCrop(followFace, srcW, srcH))
        return Layout.Game(1.3)
    }
}
