package com.weaversmind.pronunciation

import kotlin.math.min
import kotlin.math.roundToInt
import kotlin.math.sqrt

// Method A — per-word pronunciation scoring from two IPA sources: the learner's
// free IPA (ZIPA greedy) against the per-word target phones, blended with the
// forced aligner's per-slot top phone. Port of mini-coach `scoring/pronounce.dart`
// (itself pronounce.js). Expression order in phoneCost is kept byte-identical —
// the NW backtrace compares doubles with ==.

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
    return 1.0
}

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

private class Aligned(val phone: Phone?, val cost: Double)

private class TargetPhone(val display: String, val key: String, val word: Int, val top: String?, val conf: Double?)

// Global (Needleman–Wunsch) alignment of target phones vs learner phones, gap
// cost 1; returns per target phone the aligned learner phone (null = missing).
private fun alignToTarget(target: List<TargetPhone>, user: List<Phone>): Array<Aligned> {
    val m = target.size
    val n = user.size
    val dist = Array(m + 1) { DoubleArray(n + 1) }
    for (i in 0..m) dist[i][0] = i.toDouble()
    for (j in 0..n) dist[0][j] = j.toDouble()
    for (i in 1..m) {
        for (j in 1..n) {
            val sub = dist[i - 1][j - 1] + phoneCost(target[i - 1].key, user[j - 1].key)
            dist[i][j] = min(sub, min(dist[i - 1][j] + 1, dist[i][j - 1] + 1))
        }
    }
    val res = Array(m) { Aligned(null, 1.0) }
    var i = m
    var j = n
    while (i > 0) {
        if (j > 0) {
            val c = phoneCost(target[i - 1].key, user[j - 1].key)
            if (dist[i][j] == dist[i - 1][j - 1] + c) {
                res[i - 1] = Aligned(user[j - 1], c)
                i--; j--
                continue
            }
            if (dist[i][j] == dist[i][j - 1] + 1) {
                j-- // extra learner phone
                continue
            }
        }
        res[i - 1] = Aligned(null, 1.0) // deletion → missing
        i--
    }
    return res
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
internal class PhoneMeta(val ipa: String, val top: String?, val conf: Double?, val startS: Double?, val endS: Double?)

internal class TargetWord(val text: String, val phoneMeta: List<PhoneMeta>, val startS: Double?, val endS: Double?)

/** Method-A result for one word, before B/C/respell are attached. */
internal class ScoredWord(val text: String, val score: Int, val cells: List<PhoneCell>, val startS: Double?, val endS: Double?)

/** Each phone's score blends two independent signals when both exist: the free
 *  transcription comparison (edit alignment) and the aligner's slot verification. */
internal fun analyzeWords(userIpa: String, words: List<TargetWord>): List<ScoredWord> {
    val target = ArrayList<TargetPhone>()
    for ((wi, w) in words.withIndex()) {
        for (m in w.phoneMeta) {
            // expand each target token so every expanded phone carries its slot's
            // top/conf (ɚ → ə,ɹ both tagged with ɚ's slot)
            for (p in toPhones(m.ipa)) target.add(TargetPhone(p.display, p.key, wi, m.top, m.conf))
        }
    }
    val user = toPhones(userIpa)
    val aligned = alignToTarget(target, user)

    return words.indices.map { wi ->
        val cells = ArrayList<PhoneCell>()
        for (idx in target.indices) {
            val tp = target[idx]
            if (tp.word != wi) continue
            val a = aligned[idx]
            val missing = a.phone == null
            val heardScore = if (missing) 0 else (100 * (1 - a.cost)).roundToInt()
            val verif = verifyScore(tp.key, tp.top)
            val score = if (verif == null) heardScore else ((heardScore + verif) / 2.0).roundToInt()
            val status = if (missing) "missing" else if (score >= OK_THRESHOLD) "ok" else "sub"
            cells.add(PhoneCell(tp.display, a.phone?.display ?: "", tp.top, tp.conf, score, status))
        }
        val wordScore = if (cells.isEmpty()) 0 else (cells.sumOf { it.score }.toDouble() / cells.size).roundToInt()
        ScoredWord(words[wi].text, wordScore, cells, words[wi].startS, words[wi].endS)
    }
}

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
