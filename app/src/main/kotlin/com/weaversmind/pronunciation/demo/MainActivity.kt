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
import com.weaversmind.pronunciation.PronunciationEngine
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
            val keys = JSONObject(json).keys().asSequence().filter { it.length in 12..45 }.take(12).toList()
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
            Text("${r.overall}  ${r.grade}", style = MaterialTheme.typography.displaySmall)
            Text("A ${r.scores.a} · B ${r.scores.pferSlot} · C ${r.scores.pferSeq} · " +
                "${r.wpm?.roundToInt() ?: "–"} wpm · ${"%.1f".format(r.durS)} s")
            Text("heard: ${r.freeIpa}", color = Color.Gray)
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
