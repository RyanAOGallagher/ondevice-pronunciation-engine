package com.weaversmind.pronunciation

import kotlin.math.ceil
import kotlin.math.floor
import kotlin.math.max
import kotlin.math.roundToInt
import kotlin.math.abs
import kotlin.math.min
import kotlin.math.sqrt

/** Autocorrelation pitch track: one frame every 10 ms (30 ms window, 60–400 Hz).
 *  Port of mini-coach `audio/dsp.dart#pitchTrack`. Energy is normalised to the take's max. */
internal fun pitchTrack(samples: FloatArray, rate: Int): List<PitchFrame> {
    val hop = (rate * 0.01).roundToInt()
    val win = (rate * 0.03).roundToInt()
    val minLag = floor(rate / 400.0).toInt()
    val maxLag = ceil(rate / 60.0).toInt()
    var maxEnergy = 1e-9
    val tMs = ArrayList<Double>()
    val f0 = ArrayList<Double?>()
    val energies = ArrayList<Double>()

    var start = 0
    while (start + win < samples.size) {
        var sq = 0.0
        for (i in start until start + win) sq += samples[i].toDouble() * samples[i]
        val energy = sqrt(sq / win)
        maxEnergy = max(maxEnergy, energy)

        var best = 0.0
        var bestLag = -1
        var lag = minLag
        while (lag <= maxLag && start + win + lag < samples.size) {
            var corr = 0.0
            var norm = 0.0
            for (i in start until start + win) {
                corr += samples[i].toDouble() * samples[i + lag]
                norm += samples[i].toDouble() * samples[i]
            }
            val r = if (norm > 0) corr / norm else 0.0
            if (r > best) { best = r; bestLag = lag }
            lag++
        }
        val voiced = energy > 0.01 && best > 0.5 && bestLag > 0
        tMs.add(start.toDouble() / rate * 1000)
        f0.add(if (voiced) rate.toDouble() / bestLag else null)
        energies.add(energy)
        start += hop
    }
    return tMs.indices.map { PitchFrame(tMs[it], f0[it], energies[it] / maxEnergy) }
}

/** Peak-normalise to 0.9 and trim to the voiced span (250 ms pad). Required before the
 *  models — quiet phone-mic audio otherwise decodes to nothing. Port of `dsp.dart#preprocess`. */
internal fun preprocess(x: FloatArray, rate: Int): FloatArray {
    var peak = 1e-9
    for (v in x) peak = max(peak, abs(v).toDouble())
    val g = if (peak < 0.9) 0.9 / peak else 1.0

    val hop = (rate * 0.01).roundToInt()
    var first = -1
    var last = -1
    var start = 0
    while (start + hop <= x.size) {
        var sq = 0.0
        for (i in start until start + hop) sq += x[i] * g * x[i] * g
        if (sqrt(sq / hop) > 0.02) {
            if (first < 0) first = start
            last = start + hop
        }
        start += hop
    }
    if (first < 0) return FloatArray(x.size) { (x[it] * g).toFloat() }
    val pad = (rate * 0.25).roundToInt()
    val lo = max(0, first - pad)
    val hi = min(x.size, last + pad)
    return FloatArray(hi - lo) { (x[lo + it] * g).toFloat() }
}
