package com.weaversmind.pronunciation

import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.math.abs

class GraphTest {
    private fun res(name: String) = javaClass.getResourceAsStream("/$name")!!.readBytes()

    @Test
    fun franceClipMatchesPythonTwinAndAcdMaker() {
        // EPD span of the native clip ny2_r101_3 ("This right here was our gift from France.")
        val bb = ByteBuffer.wrap(res("graph_ny2_r101_3_epd.s16")).order(ByteOrder.LITTLE_ENDIAN)
        val samples = FloatArray(bb.remaining() / 2) { bb.getShort(it * 2) / 32768f }
        val exp = JSONObject(String(res("graph_ny2_r101_3_expected.json")))
        assertEquals(exp.getInt("samples"), samples.size)

        val g = userGraph(samples, 16000, 0.0, samples.size / 16000.0)
        assertEquals(100, g.size)
        assertEquals(100, g.max())

        val twin = exp.getJSONArray("userGraph"); val acd = exp.getJSONArray("acdMakerGraph")
        var maxTwin = 0; var sumAcd = 0
        for (i in 0 until 100) { maxTwin = maxOf(maxTwin, abs(g[i] - twin.getInt(i))); sumAcd += abs(g[i] - acd.getInt(i)) }
        assertTrue("differs from the Python twin by $maxTwin", maxTwin <= 1)
        assertTrue("mean |diff| vs ACD Maker grp→converter = ${sumAcd / 100.0}", sumAcd / 100.0 < 10)
    }

    @Test
    fun tableRowsWithAndWithoutTutorGraph() {
        val graphs = HashMap<String, TutorGraph>()
        val t = PronunciationEngine.parseTable("""{
            "I see stars.": {"words": [{"word":"I","wordIndex":0,"ipa":"ˈaɪ"}], "graph": [${(1..100).joinToString(",")}], "accent": [75,42,14], "spanMs": 2160, "wordMs": [["I",80,590],["see",600,1130],["stars.",1190,2000]]},
            "Plain row.": [{"word":"Plain","wordIndex":0,"ipa":"pleɪn"}]
        }""", graphs)
        assertEquals(2, t.size)
        assertEquals(listOf("ˈaɪ"), t["I see stars."]!![0].phones)
        val g = graphs["I see stars."]!!
        assertEquals(100, g.graph[99]); assertEquals(listOf(75, 42, 14), g.accent!!.toList())
        assertEquals(2160, g.spanMs); assertEquals(GraphWord("see", 600, 1130), g.words!![1])
        assertEquals(null, graphs["Plain row."])
        try { PronunciationEngine.parseTable("""{"x": {"words": [{"ipa":"a"}], "graph": [1,2,3]}}"""); throw AssertionError("accepted a 3-point graph") }
        catch (_: IllegalArgumentException) {}
    }

    @Test
    fun converterMatchesPhpOnRawInt16Range() {
        // maxNum ≥ 100 branch: int16-scale input is scaled down so the tallest bin is 100
        val data = DoubleArray(1000) { if (it in 400..420) 30000.0 else 100.0 }
        val g = toGraph100(data)
        assertEquals(100, g.max())
        assertTrue(g[0] in 0..1)
    }
}
