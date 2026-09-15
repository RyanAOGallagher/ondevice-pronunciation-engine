package com.weaversmind.pronunciation.demo

import android.Manifest
import android.annotation.SuppressLint
import android.content.pm.PackageManager
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.MediaRecorder
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.compose.ui.text.drawText
import com.weaversmind.pronunciation.PronunciationEngine
import com.weaversmind.pronunciation.Rating
import com.weaversmind.pronunciation.Result
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONObject
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sqrt
import kotlin.math.roundToInt

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent { MaterialTheme { DemoScreen() } }
    }
}

@Composable
fun DemoScreen() {
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()
    var engine by remember { mutableStateOf<PronunciationEngine?>(null) }
    var sentences by remember { mutableStateOf<List<String>>(emptyList()) }
    var selected by remember { mutableStateOf("") }
    var status by remember { mutableStateOf("loading model…") }
    var result by remember { mutableStateOf<Result?>(null) }
    var recorder by remember { mutableStateOf<Recorder?>(null) }
    var hasMic by remember {
        mutableStateOf(ctx.checkSelfPermission(Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED)
    }
    val askMic = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { hasMic = it }

    LaunchedEffect(Unit) {
        val (e, keys, ms) = withContext(Dispatchers.IO) {
            val t0 = System.currentTimeMillis()
            val json = ctx.assets.open("sentence_ipa.json").bufferedReader().use { it.readText() }
            val e = PronunciationEngine.load(ctx, json)
            val root = JSONObject(json)
            // sentences with a tutor graph first, so the demo shows tutor vs learner
            val keys = root.keys().asSequence().filter { it.length in 12..45 }
                .sortedByDescending { root.optJSONObject(it)?.has("graph") == true }.take(12).toList()
            Triple(e, keys, System.currentTimeMillis() - t0)
        }
        engine = e; sentences = keys; selected = keys.first(); status = "ready · load $ms ms"
        if (!hasMic) askMic.launch(Manifest.permission.RECORD_AUDIO)
    }

    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(16.dp)) {
        Text("Pronunciation Demo", style = MaterialTheme.typography.titleLarge)
        Text(status, color = Color.Gray)
        Spacer(Modifier.height(12.dp))

        for (s in sentences) {
            Text(s, Modifier.fillMaxWidth().clickable { selected = s; result = null }.padding(vertical = 4.dp),
                color = if (s == selected) MaterialTheme.colorScheme.primary else Color.Unspecified)
        }
        HorizontalDivider(Modifier.padding(vertical = 8.dp))

        engine?.respell(selected)?.let { Text(it, style = MaterialTheme.typography.bodyMedium) }
        Spacer(Modifier.height(12.dp))

        val rec = recorder
        Button(enabled = engine != null && hasMic, onClick = {
            if (rec == null) {
                recorder = Recorder().also { it.start() }; status = "recording…"
            } else {
                recorder = null; status = "scoring…"
                val samples = preprocess(rec.stop()) // normalise + trim silence — the SDK scores what it's given
                scope.launch {
                    try {
                        val r = withContext(Dispatchers.Default) { engine!!.evaluate(selected, samples) }
                        result = r; status = "ready · ${r.timingsMs.values.sum()} ms ${r.timingsMs}"
                    } catch (e: Exception) {
                        status = "error: ${e.message}"
                    }
                }
            }
        }) { Text(if (rec == null) "Record" else "Stop & score") }

        result?.let { r ->
            Spacer(Modifier.height(16.dp))
            Text("${r.overall}  ${r.rating.name.lowercase().replaceFirstChar { it.uppercase() }}", style = MaterialTheme.typography.displaySmall,
                color = ratingColor(r.rating))
            Text("A ${r.scores.a} · B ${r.scores.pferSlot} · C ${r.scores.pferSeq} · " +
                "${r.wpm?.roundToInt() ?: "–"} wpm · ${"%.1f".format(r.durS)} s")
            Text("heard: ${r.freeIpa}", color = Color.Gray)
            GraphPanel(r)
            Spacer(Modifier.height(8.dp))
            for (w in r.words) {
                Row(Modifier.fillMaxWidth()) {
                    Text(w.text, Modifier.weight(1f))
                    Text("${w.score}", Modifier.weight(0.4f))
                    Text((w.respell?.text ?: "") +
                        (w.stress?.let { st -> st.score?.let { "  stress ${if (st.correct == true) "✓" else "✗"} $it" } } ?: ""),
                        Modifier.weight(2f), color = Color.Gray)
                }
            }
        }
    }
}

/** 16 kHz mono PCM16 capture into memory; [stop] returns ±1 floats. */
class Recorder {
    private val rate = 16000
    @SuppressLint("MissingPermission")
    private val rec = AudioRecord(
        MediaRecorder.AudioSource.MIC, rate, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT,
        max(AudioRecord.getMinBufferSize(rate, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT), rate * 2),
    )
    private val chunks = ArrayList<ShortArray>()
    @Volatile private var running = false
    private var thread: Thread? = null

    fun start() {
        running = true
        rec.startRecording()
        thread = Thread {
            val buf = ShortArray(rate / 10)
            while (running) {
                val n = rec.read(buf, 0, buf.size)
                if (n > 0) synchronized(chunks) { chunks.add(buf.copyOf(n)) }
            }
        }.also { it.start() }
    }

    fun stop(): FloatArray {
        running = false
        thread?.join()
        rec.stop(); rec.release()
        val all = synchronized(chunks) { chunks.toList() }
        val out = FloatArray(all.sumOf { it.size })
        var i = 0
        for (c in all) for (s in c) out[i++] = s / 32768f
        return out
    }
}

/** Peak-normalise to 0.9 and trim to the voiced span (250 ms pad). Required before the
 *  models — quiet phone-mic audio otherwise decodes to nothing.
 *  Same as mini-coach `dsp.dart#preprocess`. Client-side on purpose: the SDK only scores. */
fun preprocess(x: FloatArray, rate: Int = 16000): FloatArray {
    var peak = 1e-9
    for (v in x) peak = max(peak, abs(v).toDouble())
    val g = if (peak < 0.9) 0.9 / peak else 1.0

    val hop = (rate * 0.01).roundToInt()
    var first = -1
    var last = -1
    var start = 0
    while (start + hop <= x.size) {
        var sq = 0.0
        for (i in start until start + hop) sq += x[i] * g * x[i] * g
        if (sqrt(sq / hop) > 0.02) {
            if (first < 0) first = start
            last = start + hop
        }
        start += hop
    }
    if (first < 0) return FloatArray(x.size) { (x[it] * g).toFloat() }
    val pad = (rate * 0.25).roundToInt()
    val lo = max(0, first - pad)
    val hi = min(x.size, last + pad)
    return FloatArray(hi - lo) { (x[lo + it] * g).toFloat() }
}

/** The app's "Standard / Yours" panel drawn from [Result] alone: tutor line with word bands on top,
 *  learner line below, dashed connectors between the two sets of word boundaries, and a bar per
 *  word coloured by its score. Bar of a time on either side = ms * 100 / spanMs. */
@Composable
fun GraphPanel(r: Result) {
    val tutor = r.tutorGraph
    val tWords = r.tutorWords ?: emptyList()
    val tSpan = (r.tutorSpanMs ?: 1).toFloat()
    val uSpan = r.userSpanMs.toFloat()
    val panelBg = Color(0xFF2B2B2B); val lineCol = Color(0xFFA8D8E8); val dash = Color(0xFFBDBDBD)
    // per-word colour from the same bands as the take rating (the calibration was per take; per word is indicative)
    fun scoreColor(i: Int): Color = ratingColor(com.weaversmind.pronunciation.ratingOf(r.words.getOrNull(i)?.scores?.a ?: 0))
    val textMeasurer = androidx.compose.ui.text.rememberTextMeasurer()
    val labelStyle = MaterialTheme.typography.bodyMedium
    androidx.compose.foundation.Canvas(Modifier.fillMaxWidth().height(300.dp)) {
        val w = size.width; val h = size.height
        val gh = h * 0.36f; val gap = h - 2 * gh          // two graph panels + a label band between
        val topY = 0f; val botY = gh + gap
        val dashFx = androidx.compose.ui.graphics.PathEffect.dashPathEffect(floatArrayOf(8f, 8f))
        fun panel(y0: Float) {
            drawRect(panelBg, topLeft = androidx.compose.ui.geometry.Offset(0f, y0), size = androidx.compose.ui.geometry.Size(w, gh))
            for (k in 0 until 5) if (k % 2 == 1) drawRect(Color(0x14FFFFFF), topLeft = androidx.compose.ui.geometry.Offset(0f, y0 + gh * k / 5), size = androidx.compose.ui.geometry.Size(w, gh / 5))
        }
        fun line(g: IntArray, y0: Float) {
            val path = androidx.compose.ui.graphics.Path()
            g.forEachIndexed { i, v -> val x = w * (i + 0.5f) / 100; val y = y0 + gh - gh * 0.9f * v / 100f; if (i == 0) path.moveTo(x, y) else path.lineTo(x, y) }
            drawPath(path, lineCol, style = androidx.compose.ui.graphics.drawscope.Stroke(width = 5f, cap = androidx.compose.ui.graphics.StrokeCap.Round, join = androidx.compose.ui.graphics.StrokeJoin.Round))
        }
        fun vline(x: Float, y0: Float, y1: Float) = drawLine(dash, androidx.compose.ui.geometry.Offset(x, y0), androidx.compose.ui.geometry.Offset(x, y1), strokeWidth = 2f, pathEffect = dashFx)
        // Standard
        panel(topY)
        if (tutor != null) {
            line(tutor, topY)
            tWords.forEachIndexed { i, tw ->
                val xs = w * tw.startMs / tSpan; val xe = w * tw.endMs / tSpan
                vline(xs, topY, botY); if (i == tWords.lastIndex) vline(xe, topY, botY)
                val label = textMeasurer.measure(tw.text.trimEnd('.', ',', '!', '?'), labelStyle.copy(color = scoreColor(i), fontWeight = androidx.compose.ui.text.font.FontWeight.Bold))
                drawText(label, topLeft = androidx.compose.ui.geometry.Offset((xs + xe) / 2 - label.size.width / 2, gh + gap / 2 - label.size.height / 2))
            }
        } else {
            val t = textMeasurer.measure("no Standard graph for this sentence", labelStyle.copy(color = dash))
            drawText(t, topLeft = androidx.compose.ui.geometry.Offset(w / 2 - t.size.width / 2, topY + gh / 2 - t.size.height / 2))
        }
        // Yours
        panel(botY)
        line(r.userGraph, botY)
        r.userWords.forEachIndexed { i, uw ->
            val xs = w * uw.startMs / uSpan; val xe = w * uw.endMs / uSpan
            vline(xs, botY, botY + gh)
            drawLine(scoreColor(i), androidx.compose.ui.geometry.Offset(xs, botY + gh - 6f), androidx.compose.ui.geometry.Offset(xe, botY + gh - 6f), strokeWidth = 8f)
            // connector from the tutor's boundary to ours (index-wise, when the word lists line up)
            if (tutor != null && tWords.size == r.userWords.size) {
                val tx = w * tWords[i].startMs / tSpan
                drawLine(dash, androidx.compose.ui.geometry.Offset(tx, gh + gap * 0.8f), androidx.compose.ui.geometry.Offset(xs, botY + gh * 0.25f), strokeWidth = 2f, pathEffect = dashFx)
            }
        }
    }
    Text((if (tutor == null) "no tutor graph · " else "tutor ${r.tutorSpanMs} ms · accent ${r.tutorAccent?.toList()} · ") +
        "you ${r.userSpanMs} ms: " + r.userWords.joinToString("  ") { "${it.text} ${it.startMs}-${it.endMs}" },
        color = Color.Gray, style = MaterialTheme.typography.bodySmall)
}

fun ratingColor(r: Rating): Color = when (r) {
    Rating.BAD -> Color(0xFFE53935); Rating.OK -> Color(0xFFF5A623); Rating.GOOD -> Color(0xFF7CB342); Rating.EXCELLENT -> Color(0xFF2E7D32)
}
