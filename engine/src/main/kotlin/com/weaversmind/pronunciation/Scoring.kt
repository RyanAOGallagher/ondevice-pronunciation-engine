package com.weaversmind.pronunciation

import kotlin.math.min
import kotlin.math.roundToInt
import kotlin.math.sqrt

// Method A — per-word pronunciation scoring from two IPA sources: the learner's
// free IPA (ZIPA greedy) against the per-word target phones, blended with the
// forced aligner's per-slot top phone. Port of mini-coach `scoring/pronounce.dart`
// (itself pronounce.js), with three changes measured on the 200 rated maxai takes
// (see ~/Desktop/selvas/SCORING-METHODS.md, "Method A variants", 2026-09-14):
//  1. shortest-window alignment: leading/trailing learner phones are free, an extra
//     learner phone inside the window costs INS_EPS, so a repeated sentence aligns to
//     one attempt;
//  2. span penalty: unmatched learner phones between the first and last real match
//     scale every word by N/(N+extras) — off-script speech is no longer free;
//  3. consonant distance: a wrong consonant costs CONS_K × its PFER feature distance
//     (capped at 1) instead of a flat 1, so t↔d is a near miss and Korean chatter
//     still is not;
//  4. top-3 check: the aligner's second opinion is the closest of the slot's three most
//     likely tokens (unweighted), blended at NEAR_W instead of the top token at ½.
// Expression order in phoneCost is kept byte-identical — the NW backtrace compares
// doubles with ==.

// Multi-char equivalences expanded before per-character tokenizing.
private val PHONE_EXPAND = linkedMapOf(
    "ʧ" to "tʃ", "ʤ" to "dʒ", "ʦ" to "ts", "ʣ" to "dz", "ɚ" to "əɹ", "ɝ" to "əɹ",
)

// Per-character fold to a coarse comparison key, so notation-only differences
// (ɹ/r/ɾ, lax/tense vowels) compare equal while real errors still cost.
private val PHONE_KEY = mapOf(
    "ɫ" to "l", "ɭ" to "l", "ɹ" to "r", "ɾ" to "r", "ɽ" to "r", "ɻ" to "r", "r" to "r", "ɡ" to "g",
    "ɜ" to "ə", "ɐ" to "ə", "ʌ" to "ə",
    "ɪ" to "i", "ʊ" to "u", "ɛ" to "e", "æ" to "e", "ɔ" to "o", "ɒ" to "o", "ɑ" to "a", "ä" to "a",
)

// [height 0..1, backness 0..1, rounded 0/1]
private val VOWEL_FEAT = mapOf(
    "i" to doubleArrayOf(0.0, 0.0, 0.0), "e" to doubleArrayOf(0.5, 0.0, 0.0), "a" to doubleArrayOf(1.0, 0.5, 0.0),
    "o" to doubleArrayOf(0.5, 1.0, 1.0), "u" to doubleArrayOf(0.0, 1.0, 1.0), "ə" to doubleArrayOf(0.5, 0.5, 0.0),
)
private const val MAX_VOWEL_COST = 0.6
internal const val OK_THRESHOLD = 85

private val STRIP_RE = Regex("[\\sˈˌːˑ͡‿|0-9ʰʲʷˠˤʼˀ.\\-/\\[\\]()]")
private val COMBINING_RE = Regex("[̀-ͯ]")

internal fun phoneCost(x: String, y: String): Double {
    if (x == y) return 0.0
    val fx = VOWEL_FEAT[x]
    val fy = VOWEL_FEAT[y]
    if (fx != null && fy != null) {
        val d = sqrt(Math.pow(fx[0] - fy[0], 2.0) +
                Math.pow(fx[1] - fy[1], 2.0) +
                0.5 * Math.pow(fx[2] - fy[2], 2.0)) /
            sqrt(2.5)
        return MAX_VOWEL_COST * min(1.0, d)
    }
    return min(1.0, CONS_K * featureDistance(x, y))
}
private const val CONS_K = 2.0   // ×1 and ×4 tried: AUC/correlation rise with K, Bad takes rise too; ×2 keeps the gap

internal class Phone(val display: String, val key: String)

/** IPA string → phones: real glyph for the UI, folded key for comparison. */
internal fun toPhones(raw: String?): List<Phone> {
    var s = (raw ?: "").lowercase()
    s = s.replace(COMBINING_RE, "")
    s = s.replace("˞", "ɹ") // rhotic hook → ɹ phone
    for ((k, v) in PHONE_EXPAND) s = s.replace(k, v)
    s = s.replace(STRIP_RE, "")
    return s.codePoints().toArray().map { cp ->
        val ch = String(Character.toChars(cp))
        Phone(ch, PHONE_KEY[ch] ?: ch)
    }
}

private class Aligned(val phone: Phone?, val cost: Double, val userIndex: Int = -1)

private class TargetPhone(val display: String, val key: String, val word: Int, val top: String?, val conf: Double?, val nearSim: Double = -1.0)

// Shortest-window alignment of target phones vs learner phones: leading and trailing
// learner phones are free, an inserted learner phone inside the window costs INS_EPS
// (small: keep every match, but never jump to a second attempt to gain one phone).
// Returns per target phone the aligned learner phone (null = missing) and its index.
private const val INS_EPS = 0.05
private fun alignToTarget(target: List<TargetPhone>, user: List<Phone>): Array<Aligned> {
    val m = target.size
    val n = user.size
    val dist = Array(m + 1) { DoubleArray(n + 1) }
    for (i in 0..m) dist[i][0] = i.toDouble()
    for (j in 0..n) dist[0][j] = 0.0
    for (i in 1..m) {
        for (j in 1..n) {
            val sub = dist[i - 1][j - 1] + phoneCost(target[i - 1].key, user[j - 1].key)
            dist[i][j] = min(sub, min(dist[i - 1][j] + 1, dist[i][j - 1] + INS_EPS))
        }
    }
    val res = Array(m) { Aligned(null, 1.0) }
    var i = m
    var j = 0
    for (k in 0..n) if (dist[m][k] < dist[m][j]) j = k   // free trailing learner phones
    while (i > 0) {
        if (j > 0) {
            val c = phoneCost(target[i - 1].key, user[j - 1].key)
            if (dist[i][j] == dist[i - 1][j - 1] + c) {
                res[i - 1] = Aligned(user[j - 1], c, j - 1)
                i--; j--
                continue
            }
            if (dist[i][j] == dist[i][j - 1] + INS_EPS) {
                j-- // extra learner phone inside the window
                continue
            }
        }
        res[i - 1] = Aligned(null, 1.0) // deletion → missing
        i--
    }
    return res
}

/** Frame range of the sentence inside the clip: first to last heard phone that really matches a target phone
 *  (cost ≤ 0.5) under the shortest-window alignment; null if fewer than two matches. The forced aligner is run
 *  on this range only, so extra speech and a repeated attempt cannot stretch its slots. */
internal fun sentenceWindow(targetIpa: List<String>, heard: List<Pair<String, Int>>): IntRange? {
    val target = ArrayList<TargetPhone>()
    for (ipa in targetIpa) for (p in toPhones(ipa)) target.add(TargetPhone(p.display, p.key, 0, null, null))
    val user = ArrayList<Phone>(); val frameOf = ArrayList<Int>()
    for ((tok, f) in heard) for (p in toPhones(tok)) { user.add(p); frameOf.add(f) }
    if (target.isEmpty() || user.isEmpty()) return null
    val hits = alignToTarget(target, user).filter { it.phone != null && it.cost <= 0.5 }.map { frameOf[it.userIndex] }
    if (hits.size < 2) return null
    return hits.min()..hits.max()
}

/** Span penalty factor N/(N+extras): extras = learner phones between the first and last
 *  real match (cost ≤ 0.5) that matched nothing. 1.0 when there is nothing to penalise.
 *  ponytail: a repeated sentence under heavy static can still be charged (its second
 *  attempt is inside the window); a noise gate upstream is the fix, not this rule. */
private const val SPAN_ALLOW = 0.125 // share of N forgiven before the penalty starts: 0 charged a native TIMIT read 8 points, ¼ loosens Bad detection
private fun spanFactor(aligned: Array<Aligned>): Double {
    val hits = aligned.filter { it.phone != null && it.cost <= 0.5 }.map { it.userIndex }
    if (hits.size < 2) return 1.0
    val n = aligned.size
    val extra = maxOf(0.0, ((hits.max() - hits.min() + 1) - hits.size) - SPAN_ALLOW * n)
    return n / (n + extra)
}

// GOP-style verification: how close the aligner's top phone (what the acoustic
// model heard at this slot) is to the expected phone. 100 = heard exactly it.
internal fun verifyScore(expectedKey: String, topIpa: String?): Int? {
    if (topIpa == null) return null
    val topKeys = toPhones(topIpa).map { it.key }
    if (topKeys.isEmpty()) return null
    val cost = topKeys.minOf { phoneCost(expectedKey, it) }
    return (100 * (1 - cost)).roundToInt()
}

/** One target phone slot's acoustic metadata from the forced aligner. */
internal class PhoneMeta(val ipa: String, val top: String?, val conf: Double?, val startS: Double?, val endS: Double?, val nearSim: Double = -1.0)

internal class TargetWord(val text: String, val phoneMeta: List<PhoneMeta>, val startS: Double?, val endS: Double?)

/** Method-A result for one word, before B/C/respell are attached. */
internal class ScoredWord(val text: String, val score: Int, val cells: List<PhoneCell>, val startS: Double?, val endS: Double?)

/** Each phone's score blends two independent signals when both exist: the free
 *  transcription comparison (edit alignment) and the aligner's slot verification —
 *  the best PFER similarity among the slot's TOP_K tokens, weighted NEAR_W. */
private const val NEAR_W = 0.35   // ½ and top-1 (the original) tried: ½ loosens Bad detection, top-1 leaves misheard clean takes low
internal fun analyzeWords(userIpa: String, words: List<TargetWord>): List<ScoredWord> {
    val target = ArrayList<TargetPhone>()
    for ((wi, w) in words.withIndex()) {
        for (m in w.phoneMeta) {
            // expand each target token so every expanded phone carries its slot's
            // top/conf (ɚ → ə,ɹ both tagged with ɚ's slot)
            for (p in toPhones(m.ipa)) target.add(TargetPhone(p.display, p.key, wi, m.top, m.conf, m.nearSim))
        }
    }
    val user = toPhones(userIpa)
    val aligned = alignToTarget(target, user)
    val span = spanFactor(aligned)

    return words.indices.map { wi ->
        val cells = ArrayList<PhoneCell>()
        for (idx in target.indices) {
            val tp = target[idx]
            if (tp.word != wi) continue
            val a = aligned[idx]
            val missing = a.phone == null
            val heardScore = if (missing) 0 else (100 * (1 - a.cost)).roundToInt()
            val verif = if (tp.nearSim >= 0) (100 * tp.nearSim).roundToInt() else verifyScore(tp.key, tp.top)
            val score = if (verif == null) heardScore else ((1 - NEAR_W) * heardScore + NEAR_W * verif).roundToInt()
            val status = if (missing) "missing" else if (score >= OK_THRESHOLD) "ok" else "sub"
            cells.add(PhoneCell(tp.display, a.phone?.display ?: "", tp.top, tp.conf, score, status))
        }
        val wordScore = if (cells.isEmpty()) 0 else (span * cells.sumOf { it.score }.toDouble() / cells.size).roundToInt()
        ScoredWord(words[wi].text, wordScore, cells, words[wi].startS, words[wi].endS)
    }
}

/** Method-A score below which a take reads as Bad on the 200 rated maxai takes (held-out cutoff 59–63 on
 *  every split; 0.96 binary accuracy). The isotonic band cutoffs on the same set are 62 / 64 / 75. */
const val BAD_CUTOFF = 62

internal fun gradeOf(score: Int): String = when {
    score >= 90 -> "A"
    score >= 80 -> "B"
    score >= 70 -> "C"
    score >= 55 -> "D"
    else -> "F"
}

/** Unweighted mean of [scores], rounded; 0 when empty. Word → overall aggregation. */
internal fun meanScore(scores: List<Int>): Int =
    if (scores.isEmpty()) 0 else (scores.sum().toDouble() / scores.size).roundToInt()
