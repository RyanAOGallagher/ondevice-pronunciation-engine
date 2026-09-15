package com.weaversmind.pronunciation

import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt
import kotlin.math.sqrt

/** Peak-normalise to 0.9 and trim to the voiced span (10 ms RMS > 0.02) with a 250 ms pad each side.
 *  Port of mini-coach `dsp.dart#preprocess`; quiet phone-mic audio otherwise decodes to nothing and
 *  a long silence before speech squashes the graph. Returns the samples and how many were dropped
 *  at the start. Idempotent: a second pass leaves a trimmed take unchanged. */
internal fun preprocess(x: FloatArray, rate: Int = 16000): Pair<FloatArray, Int> {
    var peak = 1e-9
    for (v in x) peak = max(peak, abs(v).toDouble())
    val g = if (peak < 0.9) 0.9 / peak else 1.0
    val hop = (rate * 0.01).roundToInt()
    var first = -1; var last = -1; var start = 0
    while (start + hop <= x.size) {
        var sq = 0.0
        for (i in start until start + hop) sq += x[i] * g * x[i] * g
        if (sqrt(sq / hop) > 0.02) { if (first < 0) first = start; last = start + hop }
        start += hop
    }
    if (first < 0) return FloatArray(x.size) { (x[it] * g).toFloat() } to 0
    val pad = (rate * 0.25).roundToInt()
    val lo = max(0, first - pad); val hi = min(x.size, last + pad)
    return FloatArray(hi - lo) { (x[lo + it] * g).toFloat() } to lo
}
