package com.weaversmind.pronunciation

import kotlin.math.exp

private const val NEG = -1e30

/** ZIPA `tokens.txt`: one `<token> <id>` per line; every token is a single codepoint
 *  except `<blk>`/`<sos/eos>`/`<unk>`. */
internal class Tokens(text: String) {
    val tokToId = HashMap<String, Int>()
    val idToTok: Map<Int, String>
    val blank = 0

    init {
        for (l in text.split('\n')) {
            val i = l.lastIndexOf(' ')
            if (i <= 0) continue
            tokToId[l.substring(0, i)] = l.substring(i + 1).trim().toInt()
        }
        idToTok = tokToId.entries.associate { it.value to it.key }
    }

    /** Target phone → ZIPA token ids: per-codepoint fold + single-char lookup.
     *  Codepoints with no token are dropped (no `<unk>`), so a phone may map to 0..n ids. */
    fun mapPhone(phone: String): IntArray {
        val ids = ArrayList<Int>(4)
        phone.codePoints().forEach { r ->
            val ch = String(Character.toChars(r))
            val folded = CHAR_FOLD[ch] ?: ch
            folded.codePoints().forEach { r2 ->
                tokToId[String(Character.toChars(r2))]?.let { ids.add(it) }
            }
        }
        return ids.toIntArray()
    }

    companion object {
        // gruut/espeak chars with no ZIPA token → nearest ZIPA char sequence
        private val CHAR_FOLD = mapOf(
            "ɡ" to "g", // U+0261 vs ZIPA ASCII g
            "ɚ" to "ə˞",
            "ɝ" to "ɜ˞",
            "ᵻ" to "ɨ",
            "ɫ" to "l",
            "͡" to "", // tie bar
        )
    }
}

/** One forced-aligned target phone. [topIpa]/[topConf]: the model's own most
 *  confident non-blank token anywhere in the span, and its probability. */
data class AlignedPhone(
    val ipa: String,
    val wordIndex: Int,
    val startS: Double,
    val endS: Double,
    val topIpa: String,
    val topConf: Double,
)

/**
 * Offline CTC Viterbi forced alignment of a target token sequence onto frame
 * log-probs. [lp] is row-major (T, V) log-probs (already log-softmax). [ids] are
 * vocab ids, one per target slot; [wordOf] maps each slot to its group.
 * Port of mini-coach `align/viterbi.dart`.
 */
internal fun forcedAlign(
    lp: FloatArray, T: Int, V: Int,
    toks: List<String>, ids: IntArray, wordOf: IntArray,
    blank: Int, idToTok: Map<Int, String>, frameSec: Double,
): List<AlignedPhone> {
    val n = ids.size
    if (n == 0 || T < 2) return emptyList()

    // CTC state chain: blank, c1, blank, c2, blank, ...
    val states = IntArray(2 * n + 1)
    states[0] = blank
    for (i in 0 until n) {
        states[2 * i + 1] = ids[i]
        states[2 * i + 2] = blank
    }
    val S = states.size
    val skipOk = BooleanArray(S)
    for (s in 2 until S) if (states[s] != blank && states[s] != states[s - 2]) skipOk[s] = true

    val dp = DoubleArray(T * S) { NEG }
    val bp = IntArray(T * S)
    dp[0] = lp[states[0]].toDouble()
    if (S > 1) dp[1] = lp[states[1]].toDouble()

    for (t in 1 until T) {
        val prevRow = (t - 1) * S
        val row = t * S
        for (s in 0 until S) {
            var best = dp[prevRow + s]
            var from = s
            if (s >= 1 && dp[prevRow + s - 1] > best) {
                best = dp[prevRow + s - 1]; from = s - 1
            }
            if (s >= 2 && skipOk[s] && dp[prevRow + s - 2] > best) {
                best = dp[prevRow + s - 2]; from = s - 2
            }
            dp[row + s] = best + lp[t * V + states[s]]
            bp[row + s] = from
        }
    }

    // Final pass: anchor at whichever of the last two states scores best.
    val f = T - 1
    var s = if (dp[f * S + (S - 1)] >= dp[f * S + (S - 2)]) S - 1 else S - 2
    val ts = IntArray(n) { -1 }
    val te = IntArray(n) { -1 }
    for (tt in f downTo 0) {
        if (states[s] != blank) {
            val ti = (s - 1) / 2
            if (te[ti] < 0) te[ti] = tt
            ts[ti] = tt
        }
        s = bp[tt * S + s]
    }

    val out = ArrayList<AlignedPhone>(n)
    var prevEnd = -1
    for (i in 0 until n) {
        var end = te[i]
        var beg = ts[i]
        if (end < 0) {
            end = if (prevEnd >= 0) prevEnd else 0
            beg = end
        }
        if (beg < 0) beg = end

        // The model's OWN most-confident token over this slot's frames (free
        // argmax, not the forced target) + its probability.
        var topIpa = toks[i]
        var topConf = 0.0
        val lo = beg
        val hi = if (end >= beg) end else beg
        var bestVal = NEG
        var t = lo
        while (t <= hi && t < T) {
            for (v in 0 until V) {
                if (v == blank) continue
                val value = lp[t * V + v].toDouble()
                if (value > bestVal) {
                    bestVal = value
                    topIpa = idToTok[v] ?: toks[i]
                }
            }
            t++
        }
        if (bestVal > NEG) topConf = exp(bestVal)

        out.add(AlignedPhone(toks[i], wordOf[i], beg * frameSec, end * frameSec, topIpa, topConf))
        prevEnd = end
    }
    return out
}

/**
 * Align target [phones] (each may expand to several ZIPA tokens: aɪ → a,ɪ · iː → i,ː)
 * and merge token spans back to one [AlignedPhone] per phone.
 * Port of mini-coach `models/zipa_aligner.dart#align`.
 */
internal fun alignPhones(
    lp: FloatArray, T: Int, V: Int, tokens: Tokens,
    phones: List<String>, wordOfPhone: IntArray, frameSec: Double,
): List<AlignedPhone> {
    val tokIds = ArrayList<Int>()
    val tokStr = ArrayList<String>()
    val phoneOfTok = ArrayList<Int>()
    for (pi in phones.indices) {
        for (id in tokens.mapPhone(phones[pi])) {
            tokIds.add(id)
            tokStr.add(tokens.idToTok.getValue(id))
            phoneOfTok.add(pi)
        }
    }
    if (tokIds.isEmpty()) return emptyList()

    val tokAligned = forcedAlign(
        lp, T, V, tokStr, tokIds.toIntArray(), phoneOfTok.toIntArray(),
        tokens.blank, tokens.idToTok, frameSec,
    )

    val out = ArrayList<AlignedPhone>(phones.size)
    var i = 0
    while (i < tokAligned.size) {
        val pi = tokAligned[i].wordIndex // phone index (see wordOf above)
        var j = i
        var best = tokAligned[i]
        while (j < tokAligned.size && tokAligned[j].wordIndex == pi) {
            if (tokAligned[j].topConf > best.topConf) best = tokAligned[j]
            j++
        }
        out.add(AlignedPhone(phones[pi], wordOfPhone[pi], tokAligned[i].startS, tokAligned[j - 1].endS,
            best.topIpa, best.topConf))
        i = j
    }
    return out
}

/** Unconstrained greedy CTC decode over frames [tFrom, tTo): per-frame argmax,
 *  collapse repeats, drop blank/sos/unk, `▁` → space. Replaces sherpa's decoder. */
internal fun greedyIpa(lp: FloatArray, V: Int, tokens: Tokens, tFrom: Int, tTo: Int): String {
    val sb = StringBuilder()
    var prev = -1
    for (t in tFrom until tTo) {
        val base = t * V
        var bestId = 0
        var bestVal = lp[base]
        for (c in 1 until V) {
            val v = lp[base + c]
            if (v > bestVal) { bestVal = v; bestId = c }
        }
        if (bestId != prev) {
            if (bestId > 2) sb.append(tokens.idToTok[bestId] ?: "")
            prev = bestId
        }
    }
    return sb.toString().replace('▁', ' ').trim()
}
