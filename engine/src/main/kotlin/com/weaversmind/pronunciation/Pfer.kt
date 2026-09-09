package com.weaversmind.pronunciation

import java.text.Normalizer

// PFER — Phonological Feature Error Rate. Grades a heard phone against its target
// by how many of 20 binary articulatory features differ (+1 present / −1 absent /
// 0 n/a). /p/→/b/ = 1/20, /s/→/ʃ/ = 2/20. Phones outside the table fall back to
// exact match. Port of selvas/bench_scripts/pfer/pfer.mjs (the copy with the
// best-window sequence variant).

private typealias Vec = Map<String, Int>

private val FEATURES = listOf(
    "syllabic", "sonorant", "consonantal", "continuant", "nasal", "voice", "spreadGlottis",
    "lateral", "labial", "round", "labiodental", "coronal", "anterior", "distributed",
    "strident", "dorsal", "high", "low", "back", "tense",
)

// Consonant templates. Unset features default to 0 in the distance calc.
private val OBSTRUENT: Vec = mapOf("syllabic" to -1, "sonorant" to -1, "consonantal" to 1, "lateral" to -1,
    "spreadGlottis" to -1, "nasal" to -1, "high" to 0, "low" to 0, "back" to 0, "tense" to 0)
private val STOP = OBSTRUENT + mapOf("continuant" to -1, "strident" to -1)
private val FRIC = OBSTRUENT + mapOf("continuant" to 1)
private val AFFR = OBSTRUENT + mapOf("continuant" to -1, "strident" to 1) // delayed-release stand-in
private val SONOR: Vec = mapOf("syllabic" to -1, "sonorant" to 1, "consonantal" to 1, "voice" to 1,
    "spreadGlottis" to -1, "strident" to -1, "high" to 0, "low" to 0, "back" to 0, "tense" to 0)
// Place bundles (labial / coronal / dorsal) — mutually exclusive defaults.
private val LAB: Vec = mapOf("labial" to 1, "round" to -1, "labiodental" to -1, "coronal" to -1,
    "anterior" to 0, "distributed" to 0, "dorsal" to -1)
private val LABDENT: Vec = mapOf("labial" to 1, "round" to -1, "labiodental" to 1, "coronal" to -1,
    "anterior" to 0, "distributed" to 0, "dorsal" to -1)
private fun cor(anterior: Int, distributed: Int): Vec = mapOf("labial" to -1, "round" to -1, "labiodental" to -1,
    "coronal" to 1, "anterior" to anterior, "distributed" to distributed, "dorsal" to -1)
private val DORS: Vec = mapOf("labial" to -1, "round" to -1, "labiodental" to -1, "coronal" to -1,
    "anterior" to 0, "distributed" to 0, "dorsal" to 1)
private fun voiced(v: Vec): Vec = v + ("voice" to 1)
// Vowel builder: [dorsal], syllabic sonorant; height / backness / round / tense.
private fun vowel(high: Int, low: Int, back: Int, round: Int, tense: Int): Vec = mapOf(
    "syllabic" to 1, "sonorant" to 1, "consonantal" to -1, "continuant" to 1, "nasal" to -1, "voice" to 1,
    "spreadGlottis" to -1, "lateral" to -1, "labial" to -1, "labiodental" to -1, "coronal" to -1,
    "anterior" to 0, "distributed" to 0, "strident" to -1, "dorsal" to 1, "round" to round,
    "high" to high, "low" to low, "back" to back, "tense" to tense,
)

// IPA → feature vector. Keys are the *normalized* phone (see normalize()).
private val PHONES: Map<String, Vec> = mapOf(
    // stops
    "p" to STOP + LAB + ("voice" to -1), "b" to voiced(STOP + LAB),
    "t" to STOP + cor(1, -1) + ("voice" to -1), "d" to voiced(STOP + cor(1, -1)),
    "k" to STOP + DORS + mapOf("voice" to -1, "high" to 1, "low" to -1, "back" to 1),
    "g" to voiced(STOP + DORS + mapOf("high" to 1, "low" to -1, "back" to 1)),
    "ʔ" to STOP + DORS + mapOf("voice" to -1, "dorsal" to -1),
    // fricatives
    "f" to FRIC + LABDENT + mapOf("strident" to 1, "voice" to -1), "v" to voiced(FRIC + LABDENT + ("strident" to 1)),
    "θ" to FRIC + cor(1, 1) + mapOf("strident" to -1, "voice" to -1), "ð" to voiced(FRIC + cor(1, 1) + ("strident" to -1)),
    "s" to FRIC + cor(1, -1) + mapOf("strident" to 1, "voice" to -1), "z" to voiced(FRIC + cor(1, -1) + ("strident" to 1)),
    "ʃ" to FRIC + cor(-1, 1) + mapOf("strident" to 1, "voice" to -1), "ʒ" to voiced(FRIC + cor(-1, 1) + ("strident" to 1)),
    "h" to FRIC + DORS + mapOf("dorsal" to -1, "spreadGlottis" to 1, "voice" to -1),
    // affricates
    "tʃ" to AFFR + cor(-1, 1) + ("voice" to -1), "dʒ" to voiced(AFFR + cor(-1, 1)),
    // nasals
    "m" to SONOR + LAB + mapOf("continuant" to -1, "nasal" to 1),
    "n" to SONOR + cor(1, -1) + mapOf("continuant" to -1, "nasal" to 1),
    "ŋ" to SONOR + DORS + mapOf("continuant" to -1, "nasal" to 1, "high" to 1, "low" to -1, "back" to 1),
    // liquids / glides
    "l" to SONOR + cor(1, -1) + mapOf("continuant" to 1, "lateral" to 1),
    "r" to SONOR + cor(-1, -1) + ("continuant" to 1),
    "j" to SONOR + DORS + mapOf("consonantal" to -1, "continuant" to 1, "high" to 1, "low" to -1, "back" to -1),
    "w" to SONOR + LAB + mapOf("consonantal" to -1, "continuant" to 1, "round" to 1, "dorsal" to 1, "high" to 1, "low" to -1, "back" to 1),
    // vowels (high, low, back, round, tense)
    "i" to vowel(1, -1, -1, -1, 1), "ɪ" to vowel(1, -1, -1, -1, -1),
    "e" to vowel(-1, -1, -1, -1, 1), "ɛ" to vowel(-1, -1, -1, -1, -1),
    "æ" to vowel(-1, 1, -1, -1, -1),
    "ə" to vowel(-1, -1, 0, -1, -1), "ʌ" to vowel(-1, -1, 1, -1, -1),
    "ɑ" to vowel(-1, 1, 1, -1, 1), "ɔ" to vowel(-1, -1, 1, 1, 1),
    "o" to vowel(-1, -1, 1, 1, 1), "ʊ" to vowel(1, -1, 1, 1, -1), "u" to vowel(1, -1, 1, 1, 1),
    "a" to vowel(-1, 1, -1, -1, 1),
    "ɯ" to vowel(1, -1, 1, -1, 1),
    "ɰ" to SONOR + DORS + mapOf("consonantal" to -1, "continuant" to 1, "high" to 1, "low" to -1, "back" to 1),
    "x" to FRIC + DORS + mapOf("voice" to -1, "high" to 1, "low" to -1, "back" to 1),
    // diphthongs = midpoint of onset+offglide vectors, rounded toward 0
    "aɪ" to vowel(0, 0, -1, -1, 1),
    "eɪ" to vowel(0, -1, -1, -1, 0),
    "oʊ" to vowel(0, -1, 1, 1, 0),
    "aʊ" to vowel(0, 0, 0, 0, 0),
    "ɔɪ" to vowel(0, -1, 0, 0, 0),
)

// Fold common IPA variants / diacritics onto a table key.
private val ALIAS = mapOf(
    "ɡ" to "g", "ʤ" to "dʒ", "ʧ" to "tʃ", "ɹ" to "r", "ɾ" to "r", "ɫ" to "l", "ʍ" to "w", "y" to "j",
    "ɜ" to "ə", "ɝ" to "ə", "ɚ" to "ə", "ᵻ" to "ɪ", "ɐ" to "ʌ", "ɒ" to "ɑ", "ɡ̊" to "g", "g̊" to "g",
    "ɕ" to "ʃ", "ʑ" to "ʒ", "tɕ" to "tʃ", "dʑ" to "dʒ", "ʐ" to "ʒ", "əʊ" to "oʊ",
)

private val NORM_MARKS = Regex("[ˈˌ.ˑ‿͜͡ ]") // stress, syllable dots, ties, spaces
private val NORM_COMBINING = Regex("[̀-ͯ]")
private val NORM_MODIFIERS = Regex("[ʰʷʲˠˤⁿˡ]")
private val NORM_LENGTH = Regex("[ːˑ]")

internal fun pferNormalize(raw: String?): String {
    var p = Normalizer.normalize(raw ?: "", Normalizer.Form.NFC)
        .replace(NORM_MARKS, "")
        .replace(NORM_COMBINING, "")
        .replace(NORM_MODIFIERS, "")
        .replace(NORM_LENGTH, "")
    ALIAS[p]?.let { p = it }
    // Retry alias on the first char if still unknown.
    if (p !in PHONES && p.length > 1) ALIAS[p.substring(0, 1)]?.let { p = it }
    return p
}

internal class Pfer(val distance: Double, val score: Int, val known: Boolean)

/** Feature distance between two *present* phones (no miss handling here). */
internal fun pfer(target: String, heard: String): Pfer {
    val t = pferNormalize(target)
    val h = pferNormalize(heard)
    val tv = PHONES[t]
    val hv = PHONES[h]
    if (tv == null || hv == null) {
        val same = t == h && t.isNotEmpty()
        return Pfer(if (same) 0.0 else 1.0, if (same) 100 else 0, false)
    }
    var differing = 0
    for (f in FEATURES) if ((tv[f] ?: 0) != (hv[f] ?: 0)) differing++
    val distance = differing.toDouble() / FEATURES.size
    return Pfer(distance, Math.round((1 - distance) * 100).toInt(), true)
}

/** One aligned phoneme's outcome (method B). A missed target phone scores 0. */
internal fun scorePhoneme(target: String, heard: String?, missed: Boolean): Pfer =
    if (missed || heard.isNullOrEmpty()) Pfer(1.0, 0, false) else pfer(target, heard)

internal fun featureDistance(a: String, b: String): Double = pfer(a, b).distance

// Multi-character phones to keep whole when splitting an IPA string.
private val MULTI = listOf("t͡ʃ", "d͡ʒ", "t͡ɕ", "d͡ʑ", "tʃ", "dʒ", "tɕ", "dʑ", "aɪ", "eɪ", "ɔɪ", "aʊ", "oʊ", "əʊ", "ɪə", "eə", "ʊə")
private fun isDiacritic(c: Char) = c in '̀'..'ͯ' || c in 'ʰ'..'˿'
private val TOK_STRIP = Regex("[ˈˌ./\\s]")

/** Split a raw IPA string (e.g. ZIPA output "wɛɹɪz…") into phone tokens. */
internal fun tokenizeIpa(s: String?): List<String> {
    val str = Normalizer.normalize(s ?: "", Normalizer.Form.NFC).replace(TOK_STRIP, "")
    val out = ArrayList<String>()
    var i = 0
    while (i < str.length) {
        var tok = ""
        for (m in MULTI) if (str.startsWith(m, i)) { tok = m; break }
        if (tok.isNotEmpty()) i += tok.length else { tok = str[i].toString(); i += 1 }
        // Absorb trailing diacritics (aspiration, nasalization, …) onto the base.
        while (i < str.length && isDiacritic(str[i])) { tok += str[i]; i += 1 }
        out.add(tok)
    }
    return out
}

internal class PferWindow(val pfer: Double, val score: Int, val nTarget: Int, val nHeard: Int)

/**
 * Method C: align the target against the best-matching *substring* of the heard
 * sequence (leading/trailing heard phones skipped for free, insertions inside the
 * attempt still cost 1). Substitution costs the feature distance.
 */
internal fun pferSequenceWindow(target: List<String>, heard: List<String>): PferWindow {
    val t = target.map(::pferNormalize)
    val h = heard.map(::pferNormalize)
    val n = t.size
    val m = h.size
    if (n == 0) return PferWindow(if (m > 0) 1.0 else 0.0, if (m > 0) 0 else 100, n, m)
    var prev = DoubleArray(m + 1) // free skip of any heard prefix
    for (i in 1..n) {
        val cur = DoubleArray(m + 1)
        cur[0] = i.toDouble()
        for (j in 1..m) {
            cur[j] = minOf(
                prev[j - 1] + featureDistance(t[i - 1], h[j - 1]),
                prev[j] + 1,
                cur[j - 1] + 1,
            )
        }
        prev = cur
    }
    val cost = prev.min() // free skip of any heard suffix
    val pferVal = cost / n
    return PferWindow(pferVal, maxOf(0, Math.round((1 - pferVal) * 100).toInt()), n, m)
}
