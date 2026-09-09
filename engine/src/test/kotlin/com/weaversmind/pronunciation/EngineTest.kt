package com.weaversmind.pronunciation

import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.ln

class EngineTest {
    private fun res(name: String) = javaClass.getResourceAsStream("/$name")!!.readBytes()

    // 1. fbank must match the python reference (validated against ZIPA's desktop decode)
    @Test fun fbankMatchesReference() {
        val (wave, rate) = decodeWavPcm16(res("test.wav"))
        assertEquals(16000, rate)
        val vec = JSONObject(String(res("fbank_vectors.json")))
        val (feats, T) = Fbank.compute(wave)
        assertEquals(vec.getInt("numFrames"), T)
        val frames = vec.getJSONObject("frames")
        for (k in frames.keys()) {
            val t = k.toInt()
            val row = frames.getJSONArray(k)
            for (b in 0 until Fbank.BINS) {
                assertEquals("frame $t bin $b", row.getDouble(b), feats[t * Fbank.BINS + b].toDouble(), 1e-3)
            }
        }
    }

    // 2. Viterbi on a synthetic lattice (the July 'ab' case)
    @Test fun viterbiAlignsSyntheticAb() {
        val T = 6; val V = 3 // 0=blank, 1='a', 2='b'; frames a a a b b b (peaked)
        val lp = FloatArray(T * V)
        val lo = ln(0.05).toFloat(); val hi = ln(0.9).toFloat()
        for (t in 0 until T) {
            val hot = if (t < 3) 1 else 2
            for (v in 0 until V) lp[t * V + v] = if (v == hot) hi else lo
        }
        val out = forcedAlign(lp, T, V, listOf("a", "b"), intArrayOf(1, 2), intArrayOf(0, 0), 0,
            mapOf(1 to "a", 2 to "b"), 0.02)
        assertEquals(2, out.size)
        assertEquals("a", out[0].ipa)
        assertEquals("b", out[1].ipa)
        assertTrue(out[0].endS <= out[1].startS + 1e-9)
        assertEquals("a", out[0].topIpa)
        assertEquals(0.9, out[0].topConf, 0.02)
        assertEquals("b", out[1].topIpa)
    }

    @Test fun greedyIpaCollapsesAndDropsBlank() {
        val tokens = Tokens("<blk> 0\n<sos/eos> 1\n<unk> 2\n▁ 3\na 4\nb 5\n")
        val V = 6
        // frames: a a blank b b ▁ a
        val hot = intArrayOf(4, 4, 0, 5, 5, 3, 4)
        val lp = FloatArray(hot.size * V) { -5f }
        for ((t, h) in hot.withIndex()) lp[t * V + h] = -0.1f
        assertEquals("ab a", greedyIpa(lp, V, tokens, 0, hot.size))
        assertEquals("b", greedyIpa(lp, V, tokens, 3, 5))
    }

    @Test fun mapPhoneFoldsAndDecomposes() {
        val tokens = Tokens(String(res("tokens.txt")))
        assertEquals(listOf(4, 63), tokens.mapPhone("aɪ").toList())          // a, ɪ
        assertEquals(listOf(10), tokens.mapPhone("ɡ").toList())              // U+0261 → g
        assertEquals(listOf(50, 112), tokens.mapPhone("ɚ").toList())         // ə, ˞
        assertEquals(listOf(23, 84), tokens.mapPhone("t͡ʃ").toList())        // tie bar dropped
        assertEquals(0, tokens.mapPhone("ˈ").size)                           // stress mark: no token
    }

    // 3. pronounce.js port
    @Test fun toPhonesFoldsNotationAndExpandsAffricates() {
        val phones = toPhones("ʃiːhædʒɚ")
        assertEquals("ʃihedʒər", phones.joinToString("") { it.key })
        assertEquals("ʃihædʒəɹ", phones.joinToString("") { it.display })
    }

    @Test fun vowelConfusionsCostLessThanConsonantErrors() {
        assertTrue(phoneCost("i", "e") < 0.4)
        assertEquals(1.0, phoneCost("t", "k"), 0.0)
        assertEquals(0.0, phoneCost("a", "a"), 0.0)
    }

    @Test fun analyzeWordsPerfectAndMissing() {
        val words = listOf(
            TargetWord("do", listOf(PhoneMeta("d", "d", 0.9, null, null), PhoneMeta("uː", "u", 0.8, null, null)), null, null),
            TargetWord("it", listOf(PhoneMeta("ɪ", "ɪ", 0.7, null, null), PhoneMeta("t", "t", 0.9, null, null)), null, null),
        )
        val perfect = analyzeWords("duɪt", words)
        assertEquals(100, perfect[0].score)
        assertEquals(100, perfect[1].score)
        val bad = analyzeWords("mmm", words)
        assertTrue(bad[0].score < 60)
    }

    @Test fun gradeBoundaries() {
        assertEquals("A", gradeOf(95)); assertEquals("B", gradeOf(85))
        assertEquals("D", gradeOf(55)); assertEquals("F", gradeOf(20))
    }

    // 7 (unit half). PFER worked examples from SCORING-METHODS.md
    @Test fun pferWorkedExamples() {
        assertEquals(95, scorePhoneme("p", "b", false).score)
        assertEquals(90, scorePhoneme("s", "ʃ", false).score)
        assertEquals(85, scorePhoneme("ð", "t", false).score)
        assertEquals(45, scorePhoneme("m", "i", false).score)
        assertEquals(100, scorePhoneme("ɡ", "g", false).score)   // alias fold
        assertEquals(0, scorePhoneme("t", null, true).score)
        assertNull(null)
    }

    // 7. every pair in the node-generated fixture
    @Test fun pferMatchesNodeFixture() {
        val vec = JSONObject(String(res("pfer_vectors.json")))
        val slot = vec.getJSONArray("slot")
        for (i in 0 until slot.length()) {
            val c = slot.getJSONObject(i)
            val heard = if (c.isNull("heard")) null else c.getString("heard")
            val r = scorePhoneme(c.getString("target"), heard, c.getBoolean("missed"))
            assertEquals("slot ${c.getString("target")}→$heard", c.getInt("score"), r.score)
            assertEquals(c.getDouble("distance"), r.distance, 1e-12)
        }
        val seq = vec.getJSONArray("seq")
        for (i in 0 until seq.length()) {
            val c = seq.getJSONObject(i)
            val target = c.getJSONArray("target").let { a -> List(a.length()) { a.getString(it) } }
            val toks = tokenizeIpa(c.getString("heard"))
            assertEquals("tokenize ${c.getString("heard")}", c.getJSONArray("tokens").let { a -> List(a.length()) { a.getString(it) } }, toks)
            val r = pferSequenceWindow(target, toks)
            assertEquals("seq ${c.getString("heard")}", c.getInt("score"), r.score)
            assertEquals(c.getDouble("pfer"), r.pfer, 1e-12)
        }
    }

    // 5. respelling against respell.py
    @Test fun respellMatchesPythonFixture() {
        val vec = org.json.JSONArray(String(res("respell_vectors.json")))
        for (i in 0 until vec.length()) {
            val c = vec.getJSONObject(i)
            val r = respellWord(listOf(c.getString("ipa")))
            assertTrue("${c.getString("word")} parsed", r != null)
            val syl = c.getJSONArray("syllables").let { a -> List(a.length()) { a.getString(it) } }
            assertEquals(c.getString("word"), syl, r!!.syllables)
            assertEquals(c.getString("word") + " stress", if (c.isNull("stress")) null else c.getInt("stress"), r.stress)
        }
    }

    // 6. table contract
    @Test fun tableNormalisationAndErrors() {
        val t = PronunciationEngine.parseTable("""{
            " He {asked} me  to open the window. ": [{"word":"He","wordIndex":0,"ipa":"h ˈi","src":"rule"},{"word":"asked","wordIndex":1,"ipa":"ˈæ s k t"}],
            "I’ve read it.": [{"word":"I've","wordIndex":0,"ipa":"aɪv"}]
        }""")
        assertEquals(2, t.size)
        val w = t[PronunciationEngine.normKey("He asked me to open the window.")]!!
        assertEquals(listOf("h", "ˈi"), w[0].phones)
        assertEquals(1, w[1].wordIndex)
        assertTrue(t.containsKey(PronunciationEngine.normKey("I've read it.")))   // curly folded
        assertNull(t[PronunciationEngine.normKey("Not here.")])
        for (bad in listOf("nope", """{"s": "x"}""", """{"s": [1]}""", """{"s": [{"word":"a"}]}""")) {
            try { PronunciationEngine.parseTable(bad); throw AssertionError("accepted $bad") }
            catch (e: IllegalArgumentException) { assertTrue(e.message!!.startsWith("invalid table")) }
        }
    }

    // 4. the whole post-ORT chain on the dumped log-probs for test.wav
    @Test fun scoreOnLogProbFixture() {
        val meta = JSONObject(String(res("test_logprobs.json")))
        val T = meta.getInt("outT"); val V = meta.getInt("V")
        val bytes = res("test_logprobs_T${T}_V$V.bin")
        val lp = FloatArray(T * V)
        java.nio.ByteBuffer.wrap(bytes).order(java.nio.ByteOrder.LITTLE_ENDIAN).asFloatBuffer().get(lp)
        val tokens = Tokens(String(res("tokens.txt")))
        val (samples, _) = decodeWavPcm16(res("test.wav"))
        assertEquals(meta.getInt("nSamples"), samples.size)
        val table = PronunciationEngine.parseTable(String(res("test_table.json")))
        val sentence = "She had your dark suit in greasy wash water all year."
        val words = table[PronunciationEngine.normKey(sentence)]!!

        val timings = LinkedHashMap<String, Long>()
        val r = score(lp, T, V, tokens, samples, words, Method.A, timings)
        assertEquals(meta.getString("freeIpa"), r.freeIpa)
        assertEquals(11, r.words.size)
        assertEquals(r.words.map { it.text }, words.map { it.word })
        // a native TIMIT read against its own transcript scores high on every method
        // (rate-matched pass thresholds from the 1,000-clip bench: A 50, B 60, C 68)
        assertTrue("A=${r.scores.a}", r.scores.a >= 80)
        assertTrue("B=${r.scores.pferSlot}", r.scores.pferSlot >= 75)
        assertTrue("C=${r.scores.pferSeq}", r.scores.pferSeq >= 75)
        assertEquals(r.scores.a, r.overall); assertEquals(gradeOf(r.overall), r.grade)
        // spans are monotone and inside the clip
        var last = 0.0
        for (w in r.words) { assertTrue(w.startS!! >= last - 1e-9); assertTrue(w.endS!! <= r.durS + 1e-9); last = w.endS!! }
        assertTrue("wpm=${r.wpm}", r.wpm!! in 100.0..500.0)
        assertTrue("pitch ${r.pitch.size}/${r.pitch.count { it.f0 != null }}", r.pitch.size > 200 && r.pitch.count { it.f0 != null } > 20)
        // respelling present; stress scored on the polysyllables (greasy, water)
        assertEquals(listOf("W.AW", "DD.ER"), r.words.first { it.text == "water" }.respell!!.syllables)
        assertTrue("stress=${r.stress}", r.stress.scored >= 1)
        assertEquals(listOf("viterbi", "score", "pitch", "stress"), timings.keys.toList())

        // method selector swaps the headline, leaves the trio untouched
        val rb = score(lp, T, V, tokens, samples, words, Method.PFER_SLOT, LinkedHashMap())
        val rc = score(lp, T, V, tokens, samples, words, Method.PFER_SEQ, LinkedHashMap())
        assertEquals(r.scores, rb.scores); assertEquals(r.scores, rc.scores)
        assertEquals(r.scores.pferSlot, rb.overall); assertEquals(r.scores.pferSeq, rc.overall)
        assertEquals(r.words[3].scores.pferSlot, rb.words[3].score)
        println("A=${r.scores.a} B=${r.scores.pferSlot} C=${r.scores.pferSeq} wpm=${"%.0f".format(r.wpm)} " +
            r.words.joinToString(" ") { "${it.text}:${it.scores.a}/${it.scores.pferSlot}/${it.scores.pferSeq}" })
        println("stress: " + r.words.filter { it.respell?.heard != null }.joinToString(" ") { "${it.text}=${it.respell!!.syllables} target=${it.respell.stress} heard=${it.respell.heard} score=${it.respell.stressScore}" })
    }

    @Test fun pferWindowIgnoresRepeatedTake() {
        val target = listOf("h", "ɛ", "l", "oʊ")
        val once = tokenizeIpa("hɛloʊ")
        val thrice = tokenizeIpa("hɛloʊhɛloʊhɛloʊ")
        assertEquals(100, pferSequenceWindow(target, once).score)
        assertEquals(100, pferSequenceWindow(target, thrice).score)   // leading/trailing free
        assertTrue(pferSequenceWindow(target, tokenizeIpa("hɛoʊ")).score < 100) // deletion costs
        assertEquals(listOf("t͡ʃ", "aɪ", "n", "ə"), tokenizeIpa("t͡ʃaɪnə"))
    }
}
