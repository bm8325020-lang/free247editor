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
    /** round webcam: centre x, centre y, radius / width, radius / height (fractions) */
    val circle: DoubleArray? = null,
    val shape: String = "unknown", // round | rect | unknown
)

sealed class Layout {
    /** Webcam panel on top, gameplay below. */
    data class Split(val cam: Box, val panel: Double, val game: Box) : Layout()

    /** Gameplay only: zoomed band in the middle over a blurred copy. */
    data class Game(val zoom: Double) : Layout()

    /**
     * The streamer's whole webcam, in its own shape, small in the top-left corner over full-screen gameplay.
     * src and game are fractions of the source frame; dst is a fraction of the output frame.
     */
    data class Pip(val src: Box, val round: Boolean, val dst: Box, val game: Box) : Layout()

    /**
     * Videos: webcam across the top (cam: source box), a wide gameplay band under it (game: source box), blurred
     * gameplay with the captions below. top, bandY, bandH are fractions of the output height.
     */
    data class Stack(val cam: Box, val top: Double, val game: Box, val bandY: Double, val bandH: Double) : Layout()

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
        if (edgeFreq != null) {
            val c = overlayCircle(edgeFreq, ew, eh, cam)
            cam = if (c != null) {
                cam.copy(circle = c, rect = Box(c[0] - c[2], c[1] - c[3], 2 * c[2], 2 * c[3]), shape = "round")
            } else {
                overlayRect(edgeFreq, ew, eh, cam)?.let { cam.copy(rect = it, shape = "rect") } ?: cam
            }
        }
        // A face that stays put but has no steady border is not proof of a webcam: in games like GTA a cutscene
        // character can hold still. No border, no webcam: the clip shows gameplay only.
        if (cam.shape == "unknown") {
            // no steady border: only a face in the same spot for most of the whole video counts (a borderless
            // webcam); a game character holding still for a while does not
            return if (group.size >= 0.6 * n) cam.copy(shape = "face") else null
        }
        return cam
    }

    val BIG = Any()

    fun isBigFace(r: Any?) = r === BIG

    /**
     * A round webcam: the circle around the face whose outline stays put while the game changes. Same brute-force
     * search as the Python engine (centres near the face, radii 0.75x-2.6x the face, 72 points, 1 px tolerance).
     */
    fun overlayCircle(freq: FloatArray, w: Int, h: Int, cam: Facecam, minScore: Double = 0.6): DoubleArray? {
        val p = BooleanArray(freq.size) { freq[it] > 0.45f }
        val tol = p.copyOf()
        for (y in 0 until h) for (x in 0 until w) {
            val i = y * w + x
            if (p[i]) continue
            if ((y > 0 && p[i - w]) || (y < h - 1 && p[i + w]) || (x > 0 && p[i - 1]) || (x < w - 1 && p[i + 1])) tol[i] = true
        }
        val fcx = (cam.cx0 + cam.cx1) / 2 * w
        val fcy = (cam.cy0 + cam.cy1) / 2 * h
        val fh = cam.fh * h
        val n = 72
        val cos = DoubleArray(n) { Math.cos(it * 2 * Math.PI / n) }
        val sin = DoubleArray(n) { Math.sin(it * 2 * Math.PI / n) }
        val radii = ArrayList<Double>()
        run { var r = 0.75 * fh; while (r < 2.6 * fh) { radii.add(r); r += 1.0 } }
        val span = 0.6 * fh
        var best = doubleArrayOf(0.0, 0.0, 0.0, 0.0)
        var cy = fcy - span
        while (cy <= fcy + span + 1e-9) {
            var cx = fcx - span
            while (cx <= fcx + span + 1e-9) {
                for (r in radii) {
                    var hit = 0
                    var seen = 0
                    for (k in 0 until n) {
                        val px = Math.rint(cx + r * cos[k]).toInt()
                        val py = Math.rint(cy + r * sin[k]).toInt()
                        if (px in 0 until w && py in 0 until h) {
                            seen++
                            if (tol[py * w + px]) hit++
                        }
                    }
                    // a webcam can touch the frame edge: judge only the visible part (at least half of the circle)
                    val sc = if (seen >= n / 2) hit.toDouble() / seen else 0.0
                    if (sc > best[0]) best = doubleArrayOf(sc, cx, cy, r)
                }
                cx += 2.0
            }
            cy += 2.0
        }
        val (score, bx, by, br) = best.toList()
        if (score < minScore) return null
        if (Math.hypot(fcx - bx, fcy - by) + 0.45 * fh > br) return null
        return doubleArrayOf(bx / w, by / h, br / w, br / h)
    }

    /** Picture-in-picture: the whole webcam (its own shape), small in the top-left corner, nothing of the face cut. */
    fun pipLayout(cam: Facecam, srcW: Int, srcH: Int, pipWidth: Double = 0.40, margin: Int = 36, top: Double = 0.06): Layout.Pip {
        val W = srcW.toDouble()
        val H = srcH.toDouble()
        val fx0 = cam.x0 * W
        val fx1 = cam.x1 * W
        val fy0 = cam.y0 * H
        val fy1 = cam.y1 * H
        val fh = cam.fh * H
        val round = cam.shape == "round" && cam.circle != null
        var x0: Double
        var y0: Double
        var bw: Double
        var bh: Double
        val r0 = cam.rect
        if (round) {
            val c = cam.circle!!
            val r = c[2] * W * 0.97
            x0 = c[0] * W - r; y0 = c[1] * H - r; bw = 2 * r; bh = 2 * r
        } else if (r0 != null) {
            x0 = (r0.x + r0.w * 0.015) * W; y0 = (r0.y + r0.h * 0.015) * H; bw = r0.w * W * 0.97; bh = r0.h * H * 0.97
        } else {
            val fcx = (fx0 + fx1) / 2
            val fcy = (fy0 + fy1) / 2
            bw = max(fx1 - fx0, fh) * 1.8
            bh = (fy1 - fy0) + fh * 0.8
            x0 = fcx - bw / 2; y0 = fcy - bh / 2
        }
        var x1 = x0 + bw
        var y1 = y0 + bh
        if (!(round || r0 != null)) { // no border known: contain everywhere the face goes
            x1 = max(x1, fx1); y1 = max(y1, fy1); x0 = min(x0, fx0); y0 = min(y0, fy0)
        }
        x0 = max(0.0, x0); y0 = max(0.0, y0); x1 = min(W, x1); y1 = min(H, y1)
        bw = x1 - x0; bh = y1 - y0
        var pw = OUT_W * pipWidth
        var ph = pw * bh / bw
        if (ph > OUT_H * 0.3) { ph = OUT_H * 0.3; pw = ph * bw / bh }
        val dw = Math.round(pw / 2) * 2.0
        val dh = Math.round(ph / 2) * 2.0
        val dst = Box(margin.toDouble() / OUT_W, Math.floor(OUT_H * top) / OUT_H, dw / OUT_W, dh / OUT_H)
        // gameplay fills the frame, steering clear of the webcam when a small shift does it
        val ga = OUT_W.toDouble() / OUT_H
        var gh = H
        var gw = gh * ga
        if (gw > W) { gw = W; gh = W / ga }
        var gx = (W - gw) / 2
        val gy = (H - gh) / 2
        if (x1 > gx && x0 < gx + gw) {
            val mg = W * 0.01
            if (x1 - gx < W * 0.12 && x1 + mg + gw <= W) gx = x1 + mg
            else if (gx + gw - x0 < W * 0.12 && x0 - mg - gw >= 0) gx = x0 - mg - gw
        }
        return Layout.Pip(Box(x0 / W, y0 / H, bw / W, bh / H), round, dst, Box(gx / W, gy / H, gw / W, gh / H))
    }

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

    /** The webcam's visible area in source pixels (x0, y0, x1, y1), a hair inside its border; round: inscribed square. */
    private fun webcamPx(cam: Facecam, srcW: Int, srcH: Int): DoubleArray {
        val W = srcW.toDouble()
        val H = srcH.toDouble()
        val c = cam.circle
        if (cam.shape == "round" && c != null) {
            val half = c[2] * W * 0.97 / Math.sqrt(2.0)
            return doubleArrayOf(c[0] * W - half, c[1] * H - half, c[0] * W + half, c[1] * H + half)
        }
        if (cam.shape == "face") { // no border seen: a generous box around everywhere the face goes
            val fx0 = cam.x0 * W; val fx1 = cam.x1 * W; val fy0 = cam.y0 * H; val fy1 = cam.y1 * H
            val fh = cam.fh * H
            val bw = max(fx1 - fx0, fh) * 1.6
            val bh = (fy1 - fy0) + fh * 0.6
            val cx = (fx0 + fx1) / 2
            val cy = (fy0 + fy1) / 2
            return doubleArrayOf(max(0.0, cx - bw / 2), max(0.0, cy - bh / 2), min(W, cx + bw / 2), min(H, cy + bh / 2))
        }
        val r = cam.rect!!
        return doubleArrayOf((r.x + r.w * 0.015) * W, (r.y + r.h * 0.015) * H, (r.x + r.w * 0.985) * W, (r.y + r.h * 0.985) * H)
    }

    /** Webcam panel height (fraction of the output): the whole webcam across the full width, within min..max. */
    fun panelFraction(cam: Facecam, srcW: Int, srcH: Int, minShare: Double = 0.30, maxShare: Double = 0.45): Double {
        val p = webcamPx(cam, srcW, srcH)
        val aspect = (p[2] - p[0]) / (p[3] - p[1])
        val frac = OUT_W / aspect / OUT_H
        return even(OUT_H * frac.coerceIn(minShare, maxShare)).toDouble() / OUT_H
    }

    /** The whole webcam filling the top panel edge to edge (small trims keep the face). */
    fun camBox(cam: Facecam, srcW: Int, srcH: Int, panel: Double): Box {
        val W = srcW.toDouble()
        val H = srcH.toDouble()
        val p = webcamPx(cam, srcW, srcH)
        var x0 = p[0]
        var y0 = p[1]
        var bw = p[2] - p[0]
        var bh = p[3] - p[1]
        val aspect = OUT_W / (panel * OUT_H)
        val fcx = (cam.cx0 + cam.cx1) / 2 * W
        val fcy = (cam.cy0 + cam.cy1) / 2 * H
        if (bw / bh > aspect) {
            val nbw = bh * aspect
            x0 = min(max(fcx - nbw / 2, x0), p[2] - nbw)
            bw = nbw
        } else {
            val nbh = bw / aspect
            y0 = min(max(fcy - nbh * 0.45, y0), p[3] - nbh)
            bh = nbh
        }
        return Box(x0 / W, y0 / H, bw / W, bh / H)
    }

    /** Stacked layout for videos (see Layout.Stack); same rules as the Python engine. */
    fun stackLayout(cam: Facecam, srcW: Int, srcH: Int, camMax: Double = 0.36): Layout.Stack {
        val W = srcW.toDouble()
        val H = srcH.toDouble()
        val r = cam.rect
        val a = if (r != null) doubleArrayOf(r.x, r.w) else webcamPx(cam, srcW, srcH).let { doubleArrayOf(it[0] / W, (it[2] - it[0]) / W) }
        val ax = a[0]
        val aw = a[1]
        var gx0 = 0.0
        var gx1 = 1.0
        if (ax < 0.25 && 1 - (ax + aw + 0.01) >= 0.7) gx0 = ax + aw + 0.01
        else if (ax + aw > 0.75 && ax - 0.01 >= 0.7) gx1 = ax - 0.01
        val bandH = even(OUT_W * H / ((gx1 - gx0) * W))
        val panelPx = Math.round(panelFraction(cam, srcW, srcH) * OUT_H).toInt()
        val top = max(2, minOf(panelPx, even(OUT_H * camMax), OUT_H - bandH - 300))
        val box = camBox(cam, srcW, srcH, top.toDouble() / OUT_H)
        return Layout.Stack(box, top.toDouble() / OUT_H, Box(gx0, 0.0, gx1 - gx0, 1.0),
            top.toDouble() / OUT_H, bandH.toDouble() / OUT_H)
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
        if (cam != null && force == "pip") return pipLayout(cam, srcW, srcH)
        if (cam != null && force != "follow" && force != "split") return stackLayout(cam, srcW, srcH)
        if (cam != null && force != "follow") {
            val panel = panelFraction(cam, srcW, srcH)
            val box = camBox(cam, srcW, srcH, panel)
            return Layout.Split(box, panel, gameBox(webcamArea(cam, box), srcW, srcH, panel))
        }
        if (force == "follow" || isBigFace(det)) return Layout.Follow(followCrop(followFace, srcW, srcH))
        return Layout.Game(1.3)
    }
}
