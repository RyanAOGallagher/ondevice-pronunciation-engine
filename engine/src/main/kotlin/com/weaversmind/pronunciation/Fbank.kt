package com.weaversmind.pronunciation

import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.ln
import kotlin.math.max
import kotlin.math.min
import kotlin.math.pow
import kotlin.math.sin

/**
 * kaldi-compatible 80-dim log-mel fbank for ZIPA (zipformer2), matching what
 * sherpa-onnx computes internally: ±1 float wave, dither 0, remove-DC, preemph
 * 0.97, povey window, 25 ms / 10 ms, 512-pt FFT, snip_edges=false (reflect
 * padding), mel 20–(nyquist−400) Hz, log with 1.19e-7 floor.
 * Port of mini-coach `audio/fbank.dart`; validated against `fbank_vectors.json`.
 */
internal object Fbank {
    const val BINS = 80
    private const val SR = 16000
    private const val FRAME_LEN = 400 // 25 ms
    private const val FRAME_SHIFT = 160 // 10 ms
    private const val NFFT = 512
    private const val PREEMPH = 0.97
    private const val LOW_FREQ = 20.0
    private const val HIGH_FREQ = -400.0 // relative to nyquist
    private const val LOG_FLOOR = 1.1920928955078125e-07

    private val window = DoubleArray(FRAME_LEN) { n ->
        (0.5 - 0.5 * cos(2 * PI * n / (FRAME_LEN - 1))).pow(0.85)
    }
    private val banks: Array<DoubleArray> // (bins, nfft/2+1)
    private val cosTab = DoubleArray(NFFT / 2) { cos(-2 * PI * it / NFFT) }
    private val sinTab = DoubleArray(NFFT / 2) { sin(-2 * PI * it / NFFT) }

    init {
        val hi = if (HIGH_FREQ > 0) HIGH_FREQ else SR / 2 + HIGH_FREQ
        fun mel(f: Double) = 1127.0 * ln(1.0 + f / 700.0)
        val melLo = mel(LOW_FREQ)
        val melHi = mel(hi)
        val melPts = DoubleArray(BINS + 2) { i -> melLo + (melHi - melLo) * i / (BINS + 1) }
        val nBins = NFFT / 2 + 1
        banks = Array(BINS) { DoubleArray(nBins) }
        for (k in 0 until nBins) {
            val fm = mel((k * SR).toDouble() / NFFT)
            for (b in 0 until BINS) {
                val l = melPts[b]
                val c = melPts[b + 1]
                val r = melPts[b + 2]
                val up = (fm - l) / (c - l)
                val down = (r - fm) / (r - c)
                val v = min(up, down)
                if (v > 0) banks[b][k] = v
            }
        }
    }

    /** [wave]: 16 kHz mono, ±1 floats. Returns row-major (numFrames, 80) and numFrames. */
    fun compute(wave: FloatArray): Pair<FloatArray, Int> {
        val n = wave.size
        val numFrames = (n + FRAME_SHIFT / 2) / FRAME_SHIFT
        val out = FloatArray(numFrames * BINS)
        val re = DoubleArray(NFFT)
        val im = DoubleArray(NFFT)
        val frame = DoubleArray(FRAME_LEN)

        for (t in 0 until numFrames) {
            val start = t * FRAME_SHIFT + FRAME_SHIFT / 2 - FRAME_LEN / 2
            var mean = 0.0
            for (i in 0 until FRAME_LEN) {
                var m = start + i
                if (m < 0) m = -m - 1 // reflect (snip_edges=false)
                if (m >= n) m = 2 * n - m - 1
                frame[i] = wave[m].toDouble()
                mean += frame[i]
            }
            mean /= FRAME_LEN
            for (i in 0 until FRAME_LEN) frame[i] -= mean // remove_dc_offset
            // pre-emphasis (kaldi: frame[0] -= coeff * frame[0]), then window
            for (i in FRAME_LEN - 1 downTo 1) {
                frame[i] = (frame[i] - PREEMPH * frame[i - 1]) * window[i]
            }
            frame[0] = frame[0] * (1 - PREEMPH) * window[0]

            re.fill(0.0)
            im.fill(0.0)
            System.arraycopy(frame, 0, re, 0, FRAME_LEN)
            fft(re, im)

            for (b in 0 until BINS) {
                var e = 0.0
                val bank = banks[b]
                for (k in 0..NFFT / 2) {
                    val w = bank[k]
                    if (w == 0.0) continue
                    e += w * (re[k] * re[k] + im[k] * im[k])
                }
                out[t * BINS + b] = ln(max(e, LOG_FLOOR)).toFloat()
            }
        }
        return out to numFrames
    }

    // in-place iterative radix-2 complex FFT, size NFFT
    private fun fft(re: DoubleArray, im: DoubleArray) {
        val n = NFFT
        var j = 0
        for (i in 1 until n) {
            var bit = n shr 1
            while (j and bit != 0) {
                j = j xor bit
                bit = bit shr 1
            }
            j = j or bit
            if (i < j) {
                var tr = re[i]; re[i] = re[j]; re[j] = tr
                tr = im[i]; im[i] = im[j]; im[j] = tr
            }
        }
        var len = 2
        while (len <= n) {
            val step = n / len
            var i = 0
            while (i < n) {
                for (k in 0 until len / 2) {
                    val wRe = cosTab[k * step]
                    val wIm = sinTab[k * step]
                    val a = i + k
                    val b = i + k + len / 2
                    val tRe = re[b] * wRe - im[b] * wIm
                    val tIm = re[b] * wIm + im[b] * wRe
                    re[b] = re[a] - tRe
                    im[b] = im[a] - tIm
                    re[a] += tRe
                    im[a] += tIm
                }
                i += len
            }
            len = len shl 1
        }
    }
}
