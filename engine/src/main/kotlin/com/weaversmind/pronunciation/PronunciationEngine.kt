package com.weaversmind.pronunciation

import ai.onnxruntime.OnnxTensor
import ai.onnxruntime.OrtEnvironment
import ai.onnxruntime.OrtSession
import ai.onnxruntime.TensorInfo
import android.content.Context
import org.json.JSONException
import org.json.JSONObject
import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.FloatBuffer
import java.nio.LongBuffer
import kotlin.math.min
import kotlin.math.roundToInt

/**
 * Sentence + audio → pronunciation score, fully on device.
 *
 * ```
 * val engine = PronunciationEngine.load(context, tableJson)   // once; ~67 MB model
 * val result = engine.evaluate("I read a book.", wavFile)     // blocking, ~150-250 ms
 * ```
 * `evaluate` is blocking and synchronized per engine — call it off the main thread
 * (e.g. `withContext(Dispatchers.Default)`).
 */
class PronunciationEngine private constructor(
    private val table: Map<String, List<WordIpa>>,
    private val tokens: Tokens,
    private val env: OrtEnvironment,
    private var session: OrtSession?,
) : AutoCloseable {

    companion object {
        private const val ASSET_DIR = "pronunciation_engine/zipa"

        /**
         * Parses [tableJson] (`{ "sentence": [ {word, wordIndex, ipa}, … ], … }`), extracts
         * the ZIPA model to `filesDir` on first run, and opens the ONNX session.
         * @throws IllegalArgumentException if the table is not valid
         */
        @JvmStatic
        fun load(context: Context, tableJson: String): PronunciationEngine {
            val table = parseTable(tableJson)
            val tokens = Tokens(context.assets.open("$ASSET_DIR/tokens.txt").bufferedReader().use { it.readText() })
            val modelPath = ensureAssetFile(context, "$ASSET_DIR/model.int8.onnx")
            val env = OrtEnvironment.getEnvironment()
            val opts = OrtSession.SessionOptions().apply {
                setIntraOpNumThreads(min(4, Runtime.getRuntime().availableProcessors()))
                setInterOpNumThreads(1)
            }
            return PronunciationEngine(table, tokens, env, env.createSession(modelPath, opts))
        }

        internal fun parseTable(json: String): Map<String, List<WordIpa>> {
            val root = try { JSONObject(json) } catch (e: JSONException) {
                throw IllegalArgumentException("invalid table: ${e.message}")
            }
            val table = HashMap<String, List<WordIpa>>(root.length() * 2)
            for (k in root.keys()) {
                val arr = root.optJSONArray(k) ?: throw IllegalArgumentException("invalid table: \"$k\" is not an array")
                val words = List(arr.length()) { i ->
                    val o = arr.optJSONObject(i) ?: throw IllegalArgumentException("invalid table: \"$k\"[$i] is not an object")
                    if (!o.has("ipa")) throw IllegalArgumentException("invalid table: \"$k\"[$i] has no \"ipa\"")
                    WordIpa(o.optString("word", ""), o.optInt("wordIndex", i),
                        o.getString("ipa").trim().split(WS).filter { it.isNotEmpty() })
                }
                table[normKey(k)] = words
            }
            return table
        }

        private val WS = Regex("\\s+")
        private val STRESS = Regex("[ˈˌ]")

        // Same key normalisation the table was built with ({} markers stripped, trimmed),
        // plus curly→straight apostrophe so "I’ve" and "I've" hit the same row.
        internal fun normKey(s: String): String =
            s.replace("{", "").replace("}", "").replace('’', '\'').trim().replace(WS, " ")
    }

    /** Per-word target phones for [sentence], or null if it isn't in the table. */
    fun lookup(sentence: String): List<WordIpa>? = table[normKey(sentence)]

    /** Whole-sentence respelling as one string, no audio needed — words separated by ` / `,
     *  syllables by ` | `, the stressed syllable `_underscored_`:
     *  `"W.AW.SH / _W.AW_ | DD.ER / AW.L"`. Null if [sentence] isn't in the table. */
    fun respell(sentence: String): String? = respellWords(sentence)?.joinToString(" / ") { it.text }

    /** Per-word respelling (no audio needed), or null if [sentence] isn't in the table.
     *  A word whose IPA can't be syllabified gets empty [WordRespell.syllables]. */
    fun respellWords(sentence: String): List<WordRespell>? = lookup(sentence)?.map { w ->
        val r = respellWord(w.phones)
        WordRespell(w.word, r?.syllables ?: emptyList(), r?.stress)
    }

    /**
     * Scores [wav] (16 kHz mono 16-bit PCM) against [sentence].
     * @throws SentenceNotFoundException [sentence] is not in the table
     * @throws IllegalArgumentException the WAV isn't 16 kHz mono PCM16, or is shorter than 0.5 s
     */
    @JvmOverloads
    fun evaluate(sentence: String, wav: File, method: Method = Method.A): Result {
        val (samples, rate) = decodeWavPcm16(wav.readBytes())
        require(rate == 16000) { "expected 16 kHz WAV, got $rate Hz" }
        return evaluate(sentence, samples, method)
    }

    /** As above for raw samples: 16 kHz mono, ±1 floats. */
    @JvmOverloads
    fun evaluate(sentence: String, samples16k: FloatArray, method: Method = Method.A): Result {
        val words = lookup(sentence) ?: throw SentenceNotFoundException(sentence)
        require(samples16k.size >= 8000) { "audio shorter than 0.5 s" }
        val timings = LinkedHashMap<String, Long>()
        val samples = preprocess(samples16k, 16000) // peak-normalise + trim, as mini-coach does
        val (lp, T, V) = logProbs(samples, timings)
        return score(lp, T, V, tokens, samples, words, method, timings)
    }

    // The only ORT touchpoint: fbank → zipformer2 CTC → (T, V) log-probs.
    // ponytail: one lock per engine; pool sessions if concurrent evaluates ever matter.
    private fun logProbs(samples: FloatArray, timings: MutableMap<String, Long>): Triple<FloatArray, Int, Int> =
        synchronized(this) {
            val s = session ?: throw IllegalStateException("engine closed")
            var t0 = System.nanoTime()
            val (feats, T) = Fbank.compute(samples)
            timings["fbank"] = (System.nanoTime() - t0) / 1_000_000
            t0 = System.nanoTime()
            val x = OnnxTensor.createTensor(env, FloatBuffer.wrap(feats), longArrayOf(1, T.toLong(), Fbank.BINS.toLong()))
            val xLens = OnnxTensor.createTensor(env, LongBuffer.wrap(longArrayOf(T.toLong())), longArrayOf(1))
            try {
                s.run(mapOf("x" to x, "x_lens" to xLens)).use { r ->
                    val out = r.get("log_probs").get() as OnnxTensor
                    val shape = (out.info as TensorInfo).shape // [1, outT, 127]
                    val buf = out.floatBuffer
                    val flat = FloatArray(buf.remaining())
                    buf.get(flat)
                    timings["ort"] = (System.nanoTime() - t0) / 1_000_000
                    Triple(flat, shape[1].toInt(), shape[2].toInt())
                }
            } finally {
                x.close(); xLens.close()
            }
        }

    override fun close() {
        synchronized(this) { session?.close(); session = null }
    }
}

private val STRESS = Regex("[ˈˌ]")

/**
 * Everything after ORT — pure, so it can be unit-tested against a dumped log-prob
 * fixture. Port of the tail of mini-coach `pipeline.dart`, plus methods B/C, the
 * respelling and the stress check.
 */
internal fun score(
    lp: FloatArray, T: Int, V: Int, tokens: Tokens, samples: FloatArray,
    words: List<WordIpa>, method: Method, timings: MutableMap<String, Long>,
): Result {
    val durS = samples.size / 16000.0
    val frameSec = durS / T

    // target phones, stress stripped (not ZIPA tokens); wordOf = position in `words`
    val phones = ArrayList<String>()
    val wordOf = ArrayList<Int>()
    for ((wi, w) in words.withIndex()) {
        for (p in w.phones) {
            val s = p.replace(STRESS, "")
            if (s.isNotEmpty()) { phones.add(s); wordOf.add(wi) }
        }
    }

    var t0 = System.nanoTime()
    val aligned = alignPhones(lp, T, V, tokens, phones, wordOf.toIntArray(), frameSec)
    if (aligned.isEmpty()) throw IllegalStateException("no alignable phones for \"${words.joinToString(" ") { it.word }}\"")
    val freeIpa = greedyIpa(lp, V, tokens, 0, T)
    timings["viterbi"] = (System.nanoTime() - t0) / 1_000_000

    // group aligned phones by word; words with no surviving phones are dropped
    t0 = System.nanoTime()
    val groups = List(words.size) { wi -> aligned.filter { it.wordIndex == wi } }
    val kept = words.indices.filter { groups[it].isNotEmpty() }
    val targetWords = kept.map { wi ->
        val g = groups[wi]
        TargetWord(words[wi].word, g.map { PhoneMeta(it.ipa, it.topIpa, it.topConf, it.startS, it.endS) },
            g.first().startS, g.last().endS)
    }

    // A — pronounce.js
    val a = analyzeWords(freeIpa, targetWords)
    // B — PFER per slot: aligner's top phone vs target, 1:1
    val b = targetWords.map { tw -> meanScore(tw.phoneMeta.map { scorePhoneme(it.ipa, it.top, false).score }) }
    // C — PFER best-window: per word against the greedy tokens inside the word's frame span;
    //     overall against the whole free recognition.
    //     ponytail: per-word C is unvalidated (bench measured utterance-level only).
    val c = kept.mapIndexed { i, wi ->
        val g = groups[wi]
        val f0 = (g.first().startS / frameSec).roundToInt()
        val f1 = min(T, (g.last().endS / frameSec).roundToInt() + 1)
        pferSequenceWindow(targetWords[i].phoneMeta.map { it.ipa }, tokenizeIpa(greedyIpa(lp, V, tokens, f0, f1))).score
    }
    val scores = Scores(meanScore(a.map { it.score }), meanScore(b), pferSequenceWindow(phones, tokenizeIpa(freeIpa)).score)
    timings["score"] = (System.nanoTime() - t0) / 1_000_000

    // prosody + fluency
    t0 = System.nanoTime()
    val pitch = pitchTrack(samples, 16000)
    timings["pitch"] = (System.nanoTime() - t0) / 1_000_000
    var wpm: Double? = null
    if (targetWords.isNotEmpty()) {
        val speechS = (targetWords.last().endS ?: durS) - (targetWords.first().startS ?: 0.0)
        if (speechS > 0.2) wpm = targetWords.size / speechS * 60
    }

    // respelling (target side, stress marks intact) + stress placement (learner side)
    t0 = System.nanoTime()
    val rTargets = kept.map { wi -> respellWord(words[wi].phones) }
    val (respells, stress) = scoreStress(rTargets, kept.map { groups[it] }, aligned, pitch)
    timings["stress"] = (System.nanoTime() - t0) / 1_000_000

    val wordScores = a.mapIndexed { i, sw ->
        val sc = Scores(sw.score, b[i], c[i])
        WordScore(sw.text, sc[method], sc, sw.startS, sw.endS, sw.cells, respells[i])
    }
    val overall = scores[method]
    return Result(overall, gradeOf(overall), scores, freeIpa, wordScores, pitch, durS, wpm, stress, timings)
}

/** Copies a bundled asset to filesDir (ORT opens file paths). Skips the copy if a
 *  same-size file already exists. Returns the absolute path. */
internal fun ensureAssetFile(ctx: Context, assetPath: String): String {
    val name = assetPath.substringAfterLast('/')
    val subDir = assetPath.substringBeforeLast('/', "")
    val outDir = if (subDir.isEmpty()) ctx.filesDir else File(ctx.filesDir, subDir).apply { mkdirs() }
    val out = File(outDir, name)
    // openFd works only for uncompressed assets (.onnx via noCompress); for compressed
    // ones it throws — then trust that a non-empty existing copy is current.
    if (out.exists() && out.length() > 0) {
        val expected = try { ctx.assets.openFd(assetPath).use { it.length } } catch (_: Exception) { -1L }
        if (expected < 0 || out.length() == expected) return out.absolutePath
    }
    ctx.assets.open(assetPath).use { input -> out.outputStream().use { input.copyTo(it) } }
    return out.absolutePath
}

/** Decodes a 16-bit PCM WAV into mono float32 [-1,1] and its sample rate. Walks the
 *  RIFF chunk list (a LIST chunk may precede `data`). Downmixes N channels. */
internal fun decodeWavPcm16(bytes: ByteArray): Pair<FloatArray, Int> {
    val bb = ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN)
    fun tag(off: Int) = String(bytes, off, 4, Charsets.US_ASCII)
    require(bytes.size >= 12 && tag(0) == "RIFF" && tag(8) == "WAVE") { "not a RIFF/WAVE file" }

    var channels = 1
    var bits = 16
    var audioFormat = 1
    var sampleRate = 0
    var dataOff = -1
    var dataLen = 0
    var pos = 12
    while (pos + 8 <= bytes.size) {
        val id = tag(pos)
        val size = bb.getInt(pos + 4)
        val body = pos + 8
        when (id) {
            "fmt " -> {
                audioFormat = bb.getShort(body).toInt() and 0xffff
                channels = bb.getShort(body + 2).toInt() and 0xffff
                sampleRate = bb.getInt(body + 4)
                bits = bb.getShort(body + 14).toInt() and 0xffff
            }
            "data" -> {
                dataOff = body
                dataLen = if (size <= bytes.size - body) size else bytes.size - body
            }
        }
        pos = body + size + (size and 1) // chunks are word-aligned
    }
    require(dataOff >= 0 && audioFormat == 1 && bits == 16) {
        "only 16-bit PCM WAV supported (format=$audioFormat, bits=$bits)"
    }
    val frames = dataLen / 2 / channels
    val out = FloatArray(frames)
    var p = dataOff
    for (i in 0 until frames) {
        var acc = 0
        for (c in 0 until channels) {
            acc += bb.getShort(p).toInt()
            p += 2
        }
        out[i] = (acc.toFloat() / channels) / 32768f
    }
    return out to sampleRate
}
