package com.weaversmind.pronunciation

import kotlin.math.ceil
import kotlin.math.floor
import kotlin.math.max
import kotlin.math.roundToInt
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
