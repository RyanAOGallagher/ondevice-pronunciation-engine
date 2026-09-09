package com.weaversmind.pronunciation

import java.text.Normalizer
import kotlin.math.exp
import kotlin.math.ln
import kotlin.math.max
import kotlin.math.sqrt

// ---------------------------------------------------------------- target side
// IPA → BoldVoice respelling: fold → r-vowel merge → flap rule → syllabify (onset
// maximization) → render. Port of selvas/respell.py (units_from_ipa … assemble).

// Multi-char symbols first (tokenizer is longest-match).
private val IPA_VOWEL_FOLD = linkedMapOf(
    "iː" to "EE", "i" to "EE", "ɪ" to "IH", "ɛ" to "EH", "æ" to "AA",
    "ʌ" to "UH", "ə" to "uh", "ᵻ" to "uh", "ɐ" to "uh",
    "ɜː" to "ER", "ɜ" to "ER", "ɚ" to "ER", "ɝ" to "ER",
    "uː" to "OO", "u" to "OO", "ʊ" to "U",
    "ɔː" to "AW", "ɔ" to "AW", "ɑː" to "AH", "ɑ" to "AH", "ɒ" to "AH",
    "eɪ" to "EY", "aɪ" to "ai", "ɔɪ" to "OY", "oʊ" to "OH", "aʊ" to "OW",
    "oː" to "OH", "o" to "OH", "eː" to "EY", "e" to "EH",
)
private val IPA_CONS_FOLD = linkedMapOf(
    "p" to "P", "b" to "B", "t" to "T", "d" to "D", "k" to "K", "ɡ" to "G", "g" to "G",
    "m" to "M", "n" to "N", "ŋ" to "NG",
    "f" to "F", "v" to "V", "θ" to "th", "ð" to "TH",
    "s" to "S", "z" to "Z", "ʃ" to "SH", "ʒ" to "ZH", "h" to "H",
    "ɹ" to "R", "r" to "R", "j" to "Y", "w" to "W", "l" to "L",
    "tʃ" to "CH", "dʒ" to "J", "ɾ" to "DD", "ʔ" to "T",
)
private val IPA_SYMBOLS = (IPA_VOWEL_FOLD.keys + IPA_CONS_FOLD.keys).sortedByDescending { it.length }

// vowel + coda R -> single r-colored label
private val R_MERGE = mapOf("AH" to "AR", "AW" to "OR", "OH" to "OR", "EH" to "AIR", "IH" to "EAR", "EE" to "EAR")

// legal syllable onsets in BoldVoice label space, for onset maximization
private val ONSET_CLUSTERS: Set<List<String>> = setOf(
    listOf("P", "R"), listOf("B", "R"), listOf("T", "R"), listOf("D", "R"), listOf("K", "R"), listOf("G", "R"),
    listOf("F", "R"), listOf("th", "R"), listOf("SH", "R"),
    listOf("P", "L"), listOf("B", "L"), listOf("K", "L"), listOf("G", "L"), listOf("F", "L"), listOf("S", "L"),
    listOf("S", "P"), listOf("S", "T"), listOf("S", "K"), listOf("S", "M"), listOf("S", "N"), listOf("S", "F"), listOf("S", "W"),
    listOf("S", "P", "R"), listOf("S", "P", "L"), listOf("S", "T", "R"), listOf("S", "K", "R"), listOf("S", "K", "W"),
    listOf("T", "W"), listOf("D", "W"), listOf("K", "W"), listOf("G", "W"), listOf("th", "W"),
    listOf("P", "Y"), listOf("B", "Y"), listOf("T", "Y"), listOf("D", "Y"), listOf("K", "Y"), listOf("G", "Y"),
    listOf("F", "Y"), listOf("V", "Y"), listOf("M", "Y"), listOf("N", "Y"), listOf("L", "Y"), listOf("S", "Y"),
    listOf("Z", "Y"), listOf("th", "Y"), listOf("H", "Y"),
)
private val NON_ONSET_SINGLES = setOf("NG")

/** One phone in BoldVoice label space. [stress] 0/1/2 for vowels, null for consonants. */
internal class Unit(val label: String, val isVowel: Boolean, val stress: Int?)

/** IPA (with stress marks) → units, or null if a symbol isn't in the fold tables. */
internal fun unitsFromIpa(raw: String): List<Unit>? {
    val ipa = Normalizer.normalize(raw, Normalizer.Form.NFC).replace("͡", "")
    val units = ArrayList<Unit>()
    var pendingStress: Int? = null
    var i = 0
    while (i < ipa.length) {
        val ch = ipa[i]
        if (ch == 'ˈ' || ch == 'ˌ') { pendingStress = if (ch == 'ˈ') 1 else 2; i++; continue }
        if (ch == '̩') { // syllabic consonant (n̩ l̩): insert schwa before it
            if (units.isNotEmpty() && !units.last().isVowel) units.add(units.size - 1, Unit("uh", true, 0))
            i++; continue
        }
        if (ch == 'ː' || ch == ' ' || ch == '‿' || ch == '̃' || ch == '͡') { i++; continue }
        val sym = IPA_SYMBOLS.firstOrNull { ipa.startsWith(it, i) } ?: return null
        val vowel = IPA_VOWEL_FOLD[sym]
        if (vowel != null) {
            var st = pendingStress ?: 0
            if (vowel == "uh" && st == 1) st = 0 // stressed schwa (e.g. "the" citation) stays uh
            units.add(Unit(vowel, true, st))
            pendingStress = null
        } else {
            units.add(Unit(IPA_CONS_FOLD.getValue(sym), false, null))
        }
        i += sym.length
    }
    return units
}

private fun applyRules(input: List<Unit>): List<Unit> {
    // 1) vowel + R -> r-colored label, only when R is in the coda
    val out = ArrayList<Unit>()
    var i = 0
    while (i < input.size) {
        val u = input[i]
        val nxt = input.getOrNull(i + 1)
        val nxt2 = input.getOrNull(i + 2)
        val codaR = nxt != null && nxt.label == "R" && (nxt2 == null || !nxt2.isVowel)
        if (u.isVowel && u.label in R_MERGE && codaR) {
            out.add(Unit(R_MERGE.getValue(u.label), true, u.stress)); i += 2
        } else if (u.isVowel && (u.label == "ER" || u.label == "uh") && codaR) {
            out.add(Unit("ER", true, u.stress)); i += 2 // absorb coda r into ER
        } else {
            out.add(u); i += 1
        }
    }
    // 2) flap: t/d between a vowel and an UNSTRESSED vowel -> DD
    val units = out.toMutableList()
    for (k in units.indices) {
        val u = units[k]
        val prv = units.getOrNull(k - 1)
        val nxt = units.getOrNull(k + 1)
        if ((u.label == "T" || u.label == "D") && !u.isVowel &&
            prv != null && prv.isVowel && nxt != null && nxt.isVowel && nxt.stress == 0) {
            units[k] = Unit("DD", false, null)
        }
    }
    return units
}

private fun legalOnset(labels: List<String>): Boolean = when (labels.size) {
    0 -> true
    1 -> labels[0] !in NON_ONSET_SINGLES
    else -> labels in ONSET_CLUSTERS
}

private fun syllabify(units: List<Unit>): List<List<Unit>> {
    val nuclei = units.indices.filter { units[it].isVowel }
    if (nuclei.isEmpty()) return listOf(units)
    val sylls = ArrayList<List<Unit>>()
    var start = 0
    for ((k, n) in nuclei.withIndex()) {
        if (k + 1 == nuclei.size) { sylls.add(units.subList(start, units.size)); break }
        val between = units.subList(n + 1, nuclei[k + 1]).map { it.label }
        var split = 0
        for (take in between.size downTo 0) {
            if (legalOnset(between.subList(between.size - take, between.size))) { split = between.size - take; break }
        }
        sylls.add(units.subList(start, n + 1 + split))
        start = n + 1 + split
    }
    return sylls
}

internal class RespellTarget(val syllables: List<String>, val stress: Int?)

/** units -> syllable strings + stressed syllable index (null for monosyllables). */
internal fun assemble(units: List<Unit>): RespellTarget {
    val sylls = syllabify(applyRules(units))
    val stresses = sylls.map { s -> s.firstOrNull { it.isVowel }?.stress ?: 0 }
    var stressIdx: Int? = null
    if (sylls.size > 1) {
        for (target in intArrayOf(1, 2)) {
            val at = stresses.indexOf(target)
            if (at >= 0) { stressIdx = at; break }
        }
    }
    return RespellTarget(sylls.map { s -> s.joinToString(".") { it.label } }, stressIdx)
}

/** Respelling for one table word (its phones, stress marks intact). Null if any symbol is unknown. */
internal fun respellWord(phones: List<String>): RespellTarget? =
    unitsFromIpa(phones.joinToString(""))?.let(::assemble)

// --------------------------------------------------------------- learner side
// Word-stress placement check. Port of weavers_tts_dashboard/app/selvas/lib/stress.ts.
// Target: the respelling's stressed-syllable index. Learner: each syllable nucleus
// (vowel) located by the forced aligner, prominence measured over its window from
// the pitch track — F0 (semitones), RMS energy, duration. Duration gets the smallest
// weight on purpose: the aligner is CTC-based and emits onset spikes.

private val VOWEL = Regex("[iɪeɛæaɑɒɔoʊuʌəɜɐᵻɨʉɚɝ]")
private const val W_F0 = 0.45
private const val W_ENERGY = 0.35
private const val W_DUR = 0.2

private class RawMeasure(val durMs: Double, val f0St: Double?, val energy: Double)

private fun zScores(xs: DoubleArray): DoubleArray {
    val mean = xs.sum() / xs.size
    val sd = sqrt(xs.sumOf { (it - mean) * (it - mean) } / xs.size)
    return DoubleArray(xs.size) { if (sd > 1e-9) (xs[it] - mean) / sd else 0.0 }
}

// Weighted prominence per nucleus, cues z-scored across the given group.
private fun prominences(raw: List<RawMeasure>): DoubleArray {
    val zDur = zScores(DoubleArray(raw.size) { raw[it].durMs })
    val zEn = zScores(DoubleArray(raw.size) { raw[it].energy })
    val f0Fallback = raw.firstOrNull { it.f0St != null }?.f0St ?: 0.0
    val zF0 = zScores(DoubleArray(raw.size) { raw[it].f0St ?: (f0Fallback - 3) }) // unvoiced ≈ low pitch
    return DoubleArray(raw.size) { W_F0 * zF0[it] + W_ENERGY * zEn[it] + W_DUR * zDur[it] }
}

private fun measureNucleus(p: AlignedPhone, nextStartS: Double, frames: List<PitchFrame>): RawMeasure {
    // CTC onset spikes: a degenerate span gets stretched toward the next phone's start, then padded.
    val startMs = p.startS * 1000 - 15
    val endMs = max(max(p.endS * 1000, nextStartS * 1000), p.startS * 1000 + 40) + 15
    val win = frames.filter { it.tMs >= startMs && it.tMs < endMs }
    val voiced = win.filter { it.f0 != null }
    val f0St = if (voiced.isNotEmpty()) 12 * log2(voiced.sumOf { it.f0!! } / voiced.size / 55) else null
    val energy = if (win.isNotEmpty()) win.sumOf { it.energy } / win.size else 0.0
    return RawMeasure(endMs - startMs - 30, f0St, energy)
}

private fun log2(x: Double) = ln(x) / ln(2.0)

// z-score margin → 0-100, 50 at the boundary; ±2σ ≈ 95/5.
private fun marginScore(m: Double): Int = Math.round(100 / (1 + exp(-1.5 * m))).toInt()

/**
 * Stress verdicts for each word. [targets] and [groups] are parallel (one per word,
 * group = that word's aligned phones in order); [allPhones] is the whole take's
 * phone sequence, used for the "next phone start" window stretch.
 */
internal fun scoreStress(
    wordTexts: List<String>, targets: List<RespellTarget?>, groups: List<List<AlignedPhone>>,
    allPhones: List<AlignedPhone>, frames: List<PitchFrame>,
): StressResult {
    fun nextStart(p: AlignedPhone): Double {
        val i = allPhones.indexOf(p)
        return if (i + 1 < allPhones.size) allPhones[i + 1].startS else p.endS
    }

    // Pass 1: locate and measure each scorable word's nuclei.
    val raws = ArrayList<List<RawMeasure>?>(targets.size)
    val out = ArrayList<StressCheck?>(targets.size)
    for (i in targets.indices) {
        val src = targets[i]
        if (src == null) { raws.add(null); out.add(null); continue }
        fun skip(why: String): StressCheck {
            raws.add(null)
            return StressCheck(wordTexts[i], src.syllables, src.stress, null, null, null, null, null, why)
        }
        if (src.stress == null || src.syllables.size < 2) { out.add(skip("monosyllable")); continue }
        val grp = groups[i]
        if (grp.isEmpty()) { out.add(skip("not aligned")); continue }
        val nuclei = grp.filter { VOWEL.containsMatchIn(it.ipa) }
        if (nuclei.size != src.syllables.size) {
            out.add(skip("nuclei ${nuclei.size} ≠ syllables ${src.syllables.size}")); continue
        }
        raws.add(nuclei.map { measureNucleus(it, nextStart(it), frames) })
        out.add(StressCheck(wordTexts[i], src.syllables, src.stress, null, null, null, null, null, null))
    }

    // Pass 2a: word mode — z within each word, argmax vs target; score = margin of the
    // target syllable over the strongest competitor.
    val heard = IntArray(targets.size) { -1 }
    val wordScore = IntArray(targets.size)
    for (i in targets.indices) {
        val raw = raws[i] ?: continue
        val target = targets[i]!!.stress!!
        val prom = prominences(raw)
        var mi = 0
        for (k in prom.indices) if (prom[k] > prom[mi]) mi = k
        heard[i] = mi
        val rival = prom.indices.filter { it != target }.maxOf { prom[it] }
        wordScore[i] = marginScore(prom[target] - rival)
    }

    // Pass 2b: syllable mode — z across every measured nucleus in the take, then each
    // syllable against its expected role (stressed ↔ above average).
    val sylCorrect = arrayOfNulls<List<Boolean>>(targets.size)
    val sylScores = arrayOfNulls<List<Int>>(targets.size)
    val allRaw = raws.filterNotNull().flatten()
    if (allRaw.size >= 2) {
        val promU = prominences(allRaw)
        var k = 0
        for (i in targets.indices) {
            val raw = raws[i] ?: continue
            val target = targets[i]!!.stress!!
            val ps = promU.copyOfRange(k, k + raw.size)
            k += raw.size
            sylCorrect[i] = ps.indices.map { s -> if (s == target) ps[s] > 0 else ps[s] < 0 }
            sylScores[i] = ps.indices.map { s -> marginScore(if (s == target) ps[s] else -ps[s]) }
        }
    }

    var correct = 0; var scored = 0; var sylOk = 0; var sylN = 0
    val result = targets.indices.map { i ->
        val r = out[i] ?: return@map null
        if (raws[i] == null) return@map r
        val ok = heard[i] == targets[i]!!.stress
        scored++; if (ok) correct++
        sylCorrect[i]?.let { sylN += it.size; sylOk += it.count { c -> c } }
        StressCheck(r.word, r.syllables, r.target, heard[i], ok, wordScore[i], sylCorrect[i], sylScores[i], null)
    }
    return StressResult(result, correct, scored, sylOk, sylN)
}
