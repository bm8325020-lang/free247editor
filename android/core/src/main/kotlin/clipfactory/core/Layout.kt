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

    /** Webcam panel height (fraction of the output height): a fixed, small share of the frame (default 28%). */
    fun panelFraction(cam: Facecam, srcW: Int, srcH: Int, share: Double = 0.28): Double =
        even(OUT_H * share.coerceIn(0.18, 0.40)).toDouble() / OUT_H

    /** Crop for the webcam panel: tight on the face, never outside the webcam (so no gameplay gets in). */
    fun camBox(cam: Facecam, srcW: Int, srcH: Int, panel: Double): Box {
        val W = srcW.toDouble()
        val H = srcH.toDouble()
        val aspect = OUT_W / (panel * OUT_H)
        val fx0 = cam.x0 * W
        val fx1 = cam.x1 * W
        val fy0 = cam.y0 * H
        val fy1 = cam.y1 * H
        val fcx = (fx0 + fx1) / 2
        val fcy = (fy0 + fy1) / 2
        val fh = cam.fh * H
        val ax0: Double
        val ay0: Double
        val ax1: Double
        val ay1: Double
        val r = cam.rect
        if (r != null) { // the webcam's own border, pulled in 2% so its frame line stays out too
            val mx = r.w * W * 0.02
            val my = r.h * H * 0.02
            ax0 = r.x * W + mx; ay0 = r.y * H + my; ax1 = (r.x + r.w) * W - mx; ay1 = (r.y + r.h) * H - my
        } else { // no border found: stay close around the face, where only the webcam can be
            val uw = max(fx1 - fx0, fh)
            ax0 = max(0.0, fcx - uw); ax1 = min(W, fcx + uw)
            ay0 = max(0.0, fy0 - fh * 0.3); ay1 = min(H, fy1 + fh * 0.3)
        }
        var bh = max(fh * 1.5, (fy1 - fy0) * 1.1)
        var bw = bh * aspect
        if (bw > ax1 - ax0) { bw = ax1 - ax0; bh = bw / aspect }
        if (bh > ay1 - ay0) { bh = ay1 - ay0; bw = bh * aspect }
        val x0 = min(max(fcx - bw / 2, ax0), ax1 - bw)
        val y0 = min(max(fcy - bh * 0.48, ay0), ay1 - bh) // face a touch above the middle
        return Box(x0 / W, y0 / H, bw / W, bh / H)
    }

    /** The whole webcam overlay, to keep out of the gameplay panel: its border if found, else the face crop. */
    fun webcamArea(cam: Facecam, box: Box): Box = cam.rect ?: box

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
            val m = W * 0.01 // the detected border sits a pixel or two inside the real one
            if (cx1 - gx < W * 0.12 && cx1 + m + gw <= W) gx = cx1 + m
            else if (gx + gw - cx0 < W * 0.12 && cx0 - m - gw >= 0) gx = cx0 - m - gw
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
            return Layout.Split(box, panel, gameBox(webcamArea(cam, box), srcW, srcH, panel))
        }
        if (force == "follow" || isBigFace(det)) return Layout.Follow(followCrop(followFace, srcW, srcH))
        return Layout.Game(1.3)
    }
}
