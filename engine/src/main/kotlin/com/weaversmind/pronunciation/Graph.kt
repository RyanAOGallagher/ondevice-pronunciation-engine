package com.weaversmind.pronunciation

import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt
import kotlin.math.sqrt

/**
 * The learner's loudness graph in the SpeakingMax `graph_value` format: 100 ints 0..100 over
 * [startS, endS] of the take (the engine passes the whole take), tallest bin = 100. Matches the honest ACD Maker energy curve
 * (`[Energy (EPD size)]` → the product's `arrayToGraphData_buffer`) to ~0.92 corr / ~8 pts
 * per bin on native clips. Envelope: 80 ms RMS, 40 ms box smoothing — plain linear RMS was
 * the best of everything tried (sqrt/peak/log envelopes, 10–160 ms windows, noise floors).
 */
internal fun userGraph(samples: FloatArray, rate: Int, startS: Double, endS: Double): IntArray {
    val a = max(0, (startS * rate).roundToInt())
    val b = min(samples.size, (endS * rate).roundToInt())
    if (b - a < rate / 10) return IntArray(100)
    val sq = DoubleArray(b - a) { val v = samples[a + it].toDouble(); v * v }
    val env = boxMean(boxMean(sq, rate * 80 / 1000).also { for (i in it.indices) it[i] = sqrt(it[i]) }, rate * 40 / 1000)
    return toGraph100(env)
}

/** Centred moving average, edges clamped to the available window. */
private fun boxMean(v: DoubleArray, w: Int): DoubleArray {
    val c = DoubleArray(v.size + 1)
    for (i in v.indices) c[i + 1] = c[i] + v[i]
    return DoubleArray(v.size) { i ->
        val lo = max(0, i - w / 2); val hi = min(v.size, i + w - w / 2)
        (c[hi] - c[lo]) / (hi - lo)
    }
}

/** Port of ProcStudyData.php `arrayToGraphData_buffer`: 100 bins, per bin the mean of the
 *  running max + running min of |x|, then the tallest bin scaled to 100. */
internal fun toGraph100(data: DoubleArray): IntArray {
    val n = 100
    val step = data.size / 100.0
    val g = DoubleArray(n)
    var maxNum = 0.0
    for (i in 0 until n) {
        val s = (i * step).toInt(); val e = min(((i + 1) * step).toInt(), data.size)
        var wmax = Double.NEGATIVE_INFINITY; var wmin = Double.POSITIVE_INFINITY; var sum = 0.0
        for (k in s until e) {
            val v = abs(data[k]); wmax = max(wmax, v); wmin = min(wmin, v); sum += wmax + wmin
        }
        g[i] = if (e > s) sum / (e - s) * 0.02 else 0.0
        maxNum = max(maxNum, g[i])
    }
    if (maxNum == 0.0) return IntArray(n)
    // PHP: <30 → scale up, 30..100 → keep, ≥100 → scale down. Our envelope is ≤1 so it is always
    // the first branch; the middle branch is only reachable for raw int16 input, kept for parity.
    val resize = abs((100 - maxNum) / maxNum)
    return IntArray(n) { i ->
        val v = when {
            maxNum < 30 -> g[i] + g[i] * resize
            maxNum < 100 -> g[i]
            else -> g[i] - g[i] * resize
        }
        v.roundToInt()
    }
}
