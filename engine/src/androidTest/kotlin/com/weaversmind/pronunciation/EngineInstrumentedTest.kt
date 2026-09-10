package com.weaversmind.pronunciation

import android.util.Log
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File

/** End-to-end on a device: table + ZIPA + WAV → Result. Uses the same test.wav /
 *  test_table.json as the JVM fixture test, so the numbers should agree with it up to
 *  int8 kernel differences between desktop and Android ORT. */
@RunWith(AndroidJUnit4::class)
class EngineInstrumentedTest {
    private val ctx = InstrumentationRegistry.getInstrumentation().targetContext
    private val testCtx = InstrumentationRegistry.getInstrumentation().context
    private val sentence = "She had your dark suit in greasy wash water all year."

    private fun res(name: String) = testCtx.assets.open(name).use { it.readBytes() }

    @Test fun evaluatesTestWav() {
        val t0 = System.currentTimeMillis()
        val engine = PronunciationEngine.load(ctx, String(res("test_table.json")))
        Log.i("PronEngine", "load ${System.currentTimeMillis() - t0} ms")
        val wav = File(ctx.cacheDir, "test.wav").apply { writeBytes(res("test.wav")) }
        engine.use {
            it.evaluate(sentence, wav) // warm-up
            val runs = (1..5).map { _ -> val t1 = System.nanoTime(); val rr = it.evaluate(sentence, wav); (System.nanoTime() - t1) / 1_000_000 to rr.timingsMs["ort"]!! }
            val r = it.evaluate(sentence, wav)
            Log.i("PronEngine", "evaluate median ${runs.map { p -> p.first }.sorted()[2]} ms, ort median ${runs.map { p -> p.second }.sorted()[2]} ms  (ort runs ${runs.map { p -> p.second }})  timings=${r.timingsMs}")
            Log.i("PronEngine", "freeIpa=${r.freeIpa} A=${r.scores.a} B=${r.scores.pferSlot} C=${r.scores.pferSeq} " +
                r.words.joinToString(" ") { w -> "${w.text}:${w.score}" })
            assertEquals(11, r.words.size)
            assertTrue("A=${r.scores.a}", r.scores.a >= 80)
            assertTrue("B=${r.scores.pferSlot}", r.scores.pferSlot >= 75)
            assertTrue("C=${r.scores.pferSeq}", r.scores.pferSeq >= 75)
            assertTrue(r.freeIpa.startsWith("ʃihæd"))
            assertEquals(listOf("W.AW", "DD.ER"), r.words.first { w -> w.text == "water" }.respell!!.syllables)
            assertEquals(setOf("fbank", "ort", "viterbi", "score", "pitch", "stress"), r.timingsMs.keys)

            try { it.evaluate("not in the table", wav); throw AssertionError("expected SentenceNotFoundException") }
            catch (e: SentenceNotFoundException) { assertEquals("not in the table", e.sentence) }
        }
        try { engine.evaluate(sentence, wav); throw AssertionError("expected closed engine to throw") }
        catch (_: IllegalStateException) {}
    }

    /** MP3 through MediaCodec. test.mp3 = ffmpeg libmp3lame 128k at 16 kHz with the default
     *  Xing/LAME info tag; test_noxing.mp3 = same without the tag (`-write_xing 0`). The tag
     *  is what lets a decoder strip the 1,105-sample encoder delay; on the desktop ffmpeg
     *  yields lag 0 with it and 1105 without. This test measures what Android does. */
    @Test fun evaluatesTestMp3() {
        val wav = decodeAudio(File(ctx.cacheDir, "test.wav").apply { writeBytes(res("test.wav")) }).first
        val engine = PronunciationEngine.load(ctx, String(res("test_table.json")))
        val ref = engine.evaluate(sentence, wav)
        for (name in listOf("test.mp3", "test_noxing.mp3")) {
            val f = File(ctx.cacheDir, name).apply { writeBytes(res(name)) }
            val (mp3, rate) = decodeAudio(f)
            val lag = lag(wav, mp3)
            val r = engine.evaluate(sentence, f)
            Log.i("PronEngine", "$name: rate=$rate samples=${mp3.size} (wav ${wav.size}) lag=$lag  " +
                "A=${r.scores.a}/${ref.scores.a} B=${r.scores.pferSlot}/${ref.scores.pferSlot} C=${r.scores.pferSeq}/${ref.scores.pferSeq}  freeIpa=${r.freeIpa}")
            assertEquals(16000, rate)
            assertTrue("$name A=${r.scores.a} vs wav ${ref.scores.a}", kotlin.math.abs(r.scores.a - ref.scores.a) <= 5)
            if (name == "test.mp3") assertTrue("tagged mp3 lag=$lag", kotlin.math.abs(lag) <= 2)
        }
        engine.close()
    }

    /** Offset of [b] relative to [a] (positive = b starts later) by brute-force cross-correlation. */
    private fun lag(a: FloatArray, b: FloatArray): Int {
        val n = minOf(a.size, b.size)
        var best = 0; var bestDot = Double.NEGATIVE_INFINITY
        for (k in -1500..1500) {
            var dot = 0.0
            var i = maxOf(0, -k)
            while (i + k < n && i < n) { dot += a[i] * b[i + k]; i++ }
            if (dot > bestDot) { bestDot = dot; best = k }
        }
        return best
    }

    @Test fun rejectsWrongSampleRate() {
        val engine = PronunciationEngine.load(ctx, String(res("test_table.json")))
        // patch the fmt chunk's sample rate to 44100 in a copy of test.wav
        val bytes = res("test.wav")
        var pos = 12
        while (pos + 8 <= bytes.size) {
            if (String(bytes, pos, 4, Charsets.US_ASCII) == "fmt ") {
                java.nio.ByteBuffer.wrap(bytes).order(java.nio.ByteOrder.LITTLE_ENDIAN).putInt(pos + 8 + 4, 44100); break
            }
            val size = java.nio.ByteBuffer.wrap(bytes).order(java.nio.ByteOrder.LITTLE_ENDIAN).getInt(pos + 4)
            pos += 8 + size + (size and 1)
        }
        val wav = File(ctx.cacheDir, "test44k.wav").apply { writeBytes(bytes) }
        try { engine.evaluate(sentence, wav); throw AssertionError("expected IllegalArgumentException") }
        catch (e: IllegalArgumentException) { assertTrue(e.message!!.contains("44100")) }
        engine.close()
    }
}
