package com.weaversmind.pronunciation

import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assume.assumeTrue
import org.junit.Test
import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder

/**
 * Score dumped clips with the engine (methods A/B/C) and write `scores.json` next to them.
 * Not a unit test — runs only with `-Pbench.dir=<dir>` (one sub-dir per clip: meta.json with
 * sentence/table/T/V, samples.bin, lp_int8.bin — see tools/bench_dump.py).
 */
class BenchScoreTest {
    private fun floats(f: File): FloatArray {
        val b = f.readBytes(); val out = FloatArray(b.size / 4)
        ByteBuffer.wrap(b).order(ByteOrder.LITTLE_ENDIAN).asFloatBuffer().get(out); return out
    }

    @Test fun scoreClips() {
        val dir = System.getProperty("bench.dir")?.let(::File)
        assumeTrue("set -Dbench.dir", dir != null && dir.isDirectory)
        val tokens = Tokens(String(javaClass.getResourceAsStream("/tokens.txt")!!.readBytes()))
        val out = JSONArray()
        for (clip in dir!!.listFiles()!!.filter { it.isDirectory }.sortedBy { it.name.toIntOrNull() ?: 0 }) {
            val meta = JSONObject(clip.resolve("meta.json").readText())
            val words = PronunciationEngine.parseTable(JSONObject().put("s", meta.getJSONArray("table")).toString()).values.first()
            val o = JSONObject().put("id", meta.getInt("id")).put("sentence", meta.getString("sentence"))
                .put("target_ipa", words.joinToString("") { w -> w.phones.joinToString("") })
            try {
                val r = score(floats(clip.resolve("lp_int8.bin")), meta.getInt("T"), meta.getInt("V"), tokens,
                    floats(clip.resolve("samples.bin")), words, Method.A, LinkedHashMap())
                o.put("a", r.scores.a).put("b", r.scores.pferSlot).put("c", r.scores.pferSeq).put("ipa", r.freeIpa)
            } catch (e: Exception) { o.put("error", e.message) }
            out.put(o)
        }
        dir.resolve("scores.json").writeText(out.toString(1))
        println("BENCH scored ${out.length()} clips -> ${dir.resolve("scores.json")}")
    }
}
