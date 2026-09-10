package com.weaversmind.pronunciation

import org.json.JSONObject
import org.junit.Assume.assumeTrue
import org.junit.Test
import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.math.abs

/**
 * Offline comparison of two ZIPA builds on real learner clips. Not a unit test — it only
 * runs when `-Dquant.eval.dir=<dir>` points at a directory produced by
 * `~/build/int4eval/batch.py` (one sub-dir per clip: meta.json, samples.bin, lp_<tag>.bin).
 * Prints per-clip and aggregate score deltas for methods A/B/C.
 */
class QuantEvalTest {
    private fun floats(f: File): FloatArray {
        val b = f.readBytes(); val out = FloatArray(b.size / 4)
        ByteBuffer.wrap(b).order(ByteOrder.LITTLE_ENDIAN).asFloatBuffer().get(out); return out
    }

    @Test fun compareQuantisations() {
        val dir = System.getProperty("quant.eval.dir")?.let(::File)
        assumeTrue("set -Dquant.eval.dir", dir != null && dir.isDirectory)
        val tokens = Tokens(String(javaClass.getResourceAsStream("/tokens.txt")!!.readBytes()))
        val first = dir!!.listFiles()!!.first { it.isDirectory }
        val fileTags = first.listFiles()!!.map { it.name }.filter { it.startsWith("lp_") && it != "lp_int8.bin" }
            .map { it.removePrefix("lp_").removeSuffix(".bin") }.sorted()
        val tags = listOf("int8") + fileTags
        val reportTags = fileTags + listOf("int8pol", "int4pol").filter { fileTags.contains(it.removeSuffix("pol") + "rec") }
        val rows = ArrayList<String>()
        val deltas = reportTags.associateWith { mapOf("a" to ArrayList<Int>(), "b" to ArrayList<Int>(), "c" to ArrayList<Int>()) }
        val base = mapOf("a" to ArrayList<Int>(), "b" to ArrayList<Int>(), "c" to ArrayList<Int>())
        val appPron = ArrayList<Double>()
        var n = 0
        for (clip in dir.listFiles()!!.filter { it.isDirectory }.sortedBy { it.name }) {
            val meta = JSONObject(clip.resolve("meta.json").readText())
            val samples = floats(clip.resolve("samples.bin"))
            val V = meta.getInt("V")
            val table = PronunciationEngine.parseTable(JSONObject().put(meta.getString("sentence"), meta.getJSONArray("table")).toString())
            val words = table.values.first()
            val scores = HashMap<String, Scores>()
            for (t in tags) {
                val T = meta.getInt("T_$t")
                val lp = floats(clip.resolve("lp_$t.bin"))
                // recovery variants were decoded on a different waveform (lead + 3x speech + gaps)
                val smp = if (t.endsWith("rec")) floats(clip.resolve("samples_rec.bin")) else samples
                scores[t] = try {
                    score(lp, T, V, tokens, smp, words, Method.A, LinkedHashMap()).scores
                } catch (e: Exception) { Scores(-1, -1, -1) }
            }
            // policy: plain decode, but fall back to the 3x recovery decode when plain C is low
            for (t in listOf("int8", "int4")) if (scores.containsKey("${t}rec")) {
                val p = scores[t]!!; val r = scores["${t}rec"]!!
                scores["${t}pol"] = Scores(p.a, p.pferSlot, if (p.pferSeq < 60) maxOf(p.pferSeq, r.pferSeq) else p.pferSeq)
            }
            val s8 = scores["int8"]!!; if (s8.a < 0) continue
            n++
            base["a"]!!.add(s8.a); base["b"]!!.add(s8.pferSlot); base["c"]!!.add(s8.pferSeq); appPron.add(meta.getDouble("app_pron"))
            for (t in reportTags) {
                val s = scores[t]!!
                deltas[t]!!["a"]!!.add(s.a - s8.a); deltas[t]!!["b"]!!.add(s.pferSlot - s8.pferSlot); deltas[t]!!["c"]!!.add(s.pferSeq - s8.pferSeq)
            }
            rows.add("${meta.getString("uuid")}  app=${meta.getDouble("app_pron").toInt()}  int8 A/B/C=${s8.a}/${s8.pferSlot}/${s8.pferSeq}  " +
                reportTags.joinToString("  ") { t -> val s = scores[t]!!; "$t=${s.a}/${s.pferSlot}/${s.pferSeq}" } +
                "  agree=${"%.0f".format(meta.getDouble("argmax_agree") * 100)}%  ${meta.getString("sentence").take(40)}")
        }
        fun stats(d: List<Int>): String {
            val abs = d.map { abs(it) }
            return "mean Δ %+.2f  mean|Δ| %.2f  max|Δ| %d  |Δ|>5: %d/%d  |Δ|>10: %d/%d".format(
                d.average(), abs.average(), abs.maxOrNull() ?: 0, abs.count { it > 5 }, d.size, abs.count { it > 10 }, d.size)
        }
        fun corr(x: List<Double>, y: List<Double>): Double {
            val mx = x.average(); val my = y.average()
            val sxy = x.indices.sumOf { (x[it] - mx) * (y[it] - my) }
            val sx = Math.sqrt(x.sumOf { (it - mx) * (it - mx) }); val sy = Math.sqrt(y.sumOf { (it - my) * (it - my) })
            return sxy / (sx * sy)
        }
        val sb = StringBuilder("QUANT_EVAL clips=$n\n")
        for (t in reportTags) for (m in if (t.endsWith("rec")) listOf("c") else listOf("a", "b", "c")) sb.append("  ${t.padEnd(8)} vs int8  method $m: ${stats(deltas[t]!![m]!!)}\n")
        for (m in listOf("a", "b", "c")) {
            val b8 = base[m]!!.map { it.toDouble() }
            sb.append("  corr with app pron_score  method $m: int8 %.3f".format(corr(b8, appPron)))
            for (t in reportTags) { val bt = b8.indices.map { b8[it] + deltas[t]!![m]!![it] }; sb.append("  $t %.3f".format(corr(bt, appPron))) }
            sb.append("\n")
        }
        sb.append("  worst 8 by |ΔA|:\n")
        val worst = rows.indices.sortedByDescending { abs(deltas["int4"]!!["a"]!![it]) }.take(8)
        for (i in worst) sb.append("    ${rows[i]}\n")
        println(sb)
    }
}
