package com.weaversmind.pronunciation

/** One word of a table sentence. [phones] keep stress marks (`ˈ`/`ˌ`). */
data class WordIpa(val word: String, val wordIndex: Int, val phones: List<String>)

class SentenceNotFoundException(val sentence: String) : Exception("not in table: $sentence")

/** Which scorer fills [Result.overall], [Result.grade] and [WordScore.score].
 *  All three are always present in [Scores]. */
enum class Method {
    /** pronounce.js: IPA edit alignment + aligner verification (mini-coach default). */
    A,
    /** PFER per slot: 20-feature distance of the aligner's top phone vs target, 1:1. */
    PFER_SLOT,
    /** PFER sequence, best-window: feature-weighted edit distance vs the free recognition. */
    PFER_SEQ,
}

data class Scores(val a: Int, val pferSlot: Int, val pferSeq: Int) {
    operator fun get(m: Method): Int = when (m) {
        Method.A -> a
        Method.PFER_SLOT -> pferSlot
        Method.PFER_SEQ -> pferSeq
    }
}

data class PhoneCell(
    val expected: String,   // display glyph
    val actual: String,     // what edit alignment matched from the learner's free IPA ("" = missing)
    val top: String?,       // aligner's most confident phone at this slot
    val conf: Double?,      // its probability
    val score: Int,
    val status: String,     // "ok" | "sub" | "missing"
)

/** Respelling of one word from the table alone (see [PronunciationEngine.respell]). */
data class WordRespell(val word: String, val syllables: List<String>, val stress: Int?) {
    /** `"_W.AW_ | DD.ER"` — syllables joined by ` | `, the stressed one underscored; the raw
     *  word if it couldn't be syllabified. */
    val text: String
        get() = if (syllables.isEmpty()) word
        else syllables.mapIndexed { i, s -> if (i == stress) "_${s}_" else s }.joinToString(" | ")
}

/** BoldVoice-style respelling of one word plus the learner's stress-placement verdict. */
data class Respell(
    val syllables: List<String>,      // e.g. ["W.AW", "DD.ER"]
    val stress: Int?,                 // target stressed-syllable index (null = monosyllable)
    val heard: Int?,                  // most prominent syllable the learner produced
    val correct: Boolean?,            // heard == stress
    val stressScore: Int?,            // 0-100 margin of target over strongest rival
    val sylCorrect: List<Boolean>?,   // per syllable: matched its expected role (take-wide z)
    val sylScores: List<Int>?,
    val skipped: String?,             // why the learner side wasn't scored
)

data class WordScore(
    val text: String,
    val score: Int,                   // per Method
    val scores: Scores,
    val startS: Double?,
    val endS: Double?,
    val phones: List<PhoneCell>,
    val respell: Respell?,
)

data class PitchFrame(val tMs: Double, val f0: Double?, val energy: Double) // f0 null = unvoiced, energy 0..1

data class StressSummary(val correct: Int, val scored: Int, val sylCorrect: Int, val sylScored: Int)

data class Result(
    val overall: Int,                 // per Method
    val grade: String,
    val scores: Scores,
    val freeIpa: String,              // unconstrained ZIPA transcription of the take
    val words: List<WordScore>,
    val pitch: List<PitchFrame>,
    val durS: Double,
    val wpm: Double?,
    val stress: StressSummary,
    val timingsMs: Map<String, Long>,
)
