package clipfactory.core

import kotlin.math.sqrt

/** Small numpy-style helpers so the Kotlin port matches the Python engine number for number. */
internal object Stats {
    fun mean(x: DoubleArray, from: Int = 0, to: Int = x.size): Double {
        if (to <= from) return 0.0
        var s = 0.0
        for (i in from until to) s += x[i]
        return s / (to - from)
    }

    fun std(x: DoubleArray): Double {
        if (x.isEmpty()) return 0.0
        val m = mean(x)
        var s = 0.0
        for (v in x) s += (v - m) * (v - m)
        return sqrt(s / x.size)
    }

    /** numpy.percentile with linear interpolation. */
    fun percentile(x: DoubleArray, q: Double): Double {
        if (x.isEmpty()) return 0.0
        val s = x.sortedArray()
        val pos = (s.size - 1) * q / 100.0
        val lo = pos.toInt()
        val hi = minOf(lo + 1, s.size - 1)
        return s[lo] + (s[hi] - s[lo]) * (pos - lo)
    }

    fun median(x: DoubleArray): Double = percentile(x, 50.0)

    private fun padEdge(x: DoubleArray, left: Int, right: Int): DoubleArray {
        val out = DoubleArray(x.size + left + right)
        for (i in out.indices) out[i] = x[(i - left).coerceIn(0, x.size - 1)]
        return out
    }

    /** Moving average of width n with edge padding (same length as x). */
    fun smooth(x: DoubleArray, n: Int): DoubleArray {
        if (n <= 1 || x.isEmpty()) return x.copyOf()
        val p = padEdge(x, n / 2, n - 1 - n / 2)
        val out = DoubleArray(x.size)
        var s = 0.0
        for (i in 0 until n) s += p[i]
        out[0] = s / n
        for (i in 1 until x.size) {
            s += p[i + n - 1] - p[i - 1]
            out[i] = s / n
        }
        return out
    }

    /** Rolling median of width n with edge padding (same length as x). */
    fun rollingMedian(x: DoubleArray, n: Int): DoubleArray {
        if (x.isEmpty()) return x
        val p = padEdge(x, n / 2, n - 1 - n / 2)
        val out = DoubleArray(x.size)
        val win = DoubleArray(n)
        for (i in x.indices) {
            System.arraycopy(p, i, win, 0, n)
            win.sort()
            out[i] = if (n % 2 == 1) win[n / 2] else (win[n / 2 - 1] + win[n / 2]) / 2
        }
        return out
    }
}
