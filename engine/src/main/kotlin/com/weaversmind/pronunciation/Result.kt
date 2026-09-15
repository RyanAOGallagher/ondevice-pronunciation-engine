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

/** Stress placement for one word: the target (from the table) and what the learner did (from the audio). */
data class StressCheck(
    val word: String,
    val syllables: List<String>,      // e.g. ["W.AW", "DD.ER"]
    val target: Int?,                 // stressed syllable index per the table (null = monosyllable)
    val heard: Int?,                  // most prominent syllable the learner produced
    val correct: Boolean?,            // heard == target
    val score: Int?,                  // 0-100 margin of the target syllable over its strongest rival
    val sylCorrect: List<Boolean>?,   // per syllable: matched its expected role (take-wide z)
    val sylScores: List<Int>?,
    val skipped: String?,             // why it wasn't scored ("monosyllable", "not aligned", …)
)

data class WordScore(
    val text: String,
    val score: Int,                   // per Method
    val scores: Scores,
    val startS: Double?,
    val endS: Double?,
    val phones: List<PhoneCell>,
    val respell: WordRespell?,        // from the table, no audio
    val stress: StressCheck?,         // from the audio; null if the word couldn't be respelled
)

/** A word's span in ms on its graph's timeline. Bar of a time: `t * 100 / spanMs`. */
data class GraphWord(val text: String, val startMs: Int, val endMs: Int)

/** Native graph attached to a table row (see `tools/add_tutor_graphs.py`). */
internal class TutorGraph(val graph: IntArray, val accent: IntArray?, val words: List<GraphWord>?, val spanMs: Int?)

data class PitchFrame(val tMs: Double, val f0: Double?, val energy: Double) // f0 null = unvoiced, energy 0..1

/** Whole-take stress result: one [StressCheck] per scored word (parallel to [Result.words]) plus tallies. */
data class StressResult(
    val words: List<StressCheck?>,    // null where the word couldn't be respelled
    val correct: Int, val scored: Int,          // word mode: right syllable / words with a verdict
    val sylCorrect: Int, val sylScored: Int,    // syllable mode: syllables in the right role / judged
)

data class Result(
    val overall: Int,                 // per Method
    val grade: String,
    /** Bad / OK / Good / Excellent from the method-A score, calibrated on rated learner takes. See [ratingOf]. */
    val rating: Rating,
    val scores: Scores,
    val freeIpa: String,              // unconstrained ZIPA transcription of the take
    val words: List<WordScore>,
    val pitch: List<PitchFrame>,
    // ---- graphs: learner and tutor in the same shape ----------------------------------------
    // graph: 100 ints 0..100 in the app's `graph_value` format, tallest bar = 100, spanning
    // 0..spanMs. words: ms on that timeline, so bar = startMs * 100 / spanMs on either side.
    /** Computed from this take over the spoken words (80 ms before the first, 80 ms after the last),
     *  so leading silence never flattens it. [userWords] are on that timeline; [userGraphStartMs] is
     *  where it starts on the take (add it to get back to [WordScore.startS] times). See [userGraph]. */
    val userGraph: IntArray,
    val userWords: List<GraphWord>,
    val userSpanMs: Int,
    val userGraphStartMs: Int,
    /** Copied from the table (the product's hand-tuned native graph); null when the row has none. */
    val tutorGraph: IntArray?,
    val tutorAccent: IntArray?,          // bar indices
    val tutorWords: List<GraphWord>?,
    val tutorSpanMs: Int?,
    val durS: Double,                 // of the trimmed take — all times above are on that timeline
    /** Audio dropped before the take's first voiced frame (minus a 250 ms pad). Add it to any time
     *  here to get back to the caller's original recording. */
    val trimStartMs: Int,
    val wpm: Double?,
    val stress: StressResult,
    val timingsMs: Map<String, Long>,
)
