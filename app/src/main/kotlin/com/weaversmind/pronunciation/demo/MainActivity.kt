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
import androidx.compose.foundation.background
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

/** Plays 16 kHz mono float PCM once, streaming (static mode fails silently on long clips on some
 *  devices). Returns the track and its length in frames; the caller polls playbackHeadPosition. */
class Playback(val track: android.media.AudioTrack, val frames: Int)
fun playPcm(pcm: FloatArray): Playback {
    val shorts = ShortArray(pcm.size) { (pcm[it].coerceIn(-1f, 1f) * 32767).toInt().toShort() }
    val minBuf = android.media.AudioTrack.getMinBufferSize(16000, android.media.AudioFormat.CHANNEL_OUT_MONO, android.media.AudioFormat.ENCODING_PCM_16BIT)
    val track = android.media.AudioTrack.Builder()
        .setAudioAttributes(android.media.AudioAttributes.Builder().setUsage(android.media.AudioAttributes.USAGE_MEDIA).setContentType(android.media.AudioAttributes.CONTENT_TYPE_SPEECH).build())
        .setAudioFormat(android.media.AudioFormat.Builder().setEncoding(android.media.AudioFormat.ENCODING_PCM_16BIT).setSampleRate(16000).setChannelMask(android.media.AudioFormat.CHANNEL_OUT_MONO).build())
        .setTransferMode(android.media.AudioTrack.MODE_STREAM).setBufferSizeInBytes(minBuf * 4).build()
    track.play()
    Thread { var off = 0; while (off < shorts.size) { val n = track.write(shorts, off, shorts.size - off); if (n <= 0) break; off += n } }.start()
    return Playback(track, shorts.size)
}

private data class Loaded(val engine: PronunciationEngine, val keys: List<String>, val ms: Long, val root: JSONObject)

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
    var tutorComputed by remember { mutableStateOf<IntArray?>(null) }   // engine.graph() on the bundled native wav
    var tutorPcm by remember { mutableStateOf<FloatArray?>(null) }      // the bundled reference audio (the .dat's PCM)
    var takePcm by remember { mutableStateOf<FloatArray?>(null) }       // your last recording, as handed to evaluate()
    var playhead by remember { mutableStateOf<Pair<Boolean, Int>?>(null) } // (isTutor, ms into that audio) while something plays
    var useComputed by remember { mutableStateOf(false) }               // Standard panel: stored (hand-tuned) or computed
    var tableRoot by remember { mutableStateOf<JSONObject?>(null) }
    var recorder by remember { mutableStateOf<Recorder?>(null) }
    var hasMic by remember {
        mutableStateOf(ctx.checkSelfPermission(Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED)
    }
    val askMic = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { hasMic = it }

    LaunchedEffect(Unit) {
        val (e, keys, ms, root) = withContext(Dispatchers.IO) {
            val t0 = System.currentTimeMillis()
            val json = ctx.assets.open("sentence_ipa.json").bufferedReader().use { it.readText() }
            val e = PronunciationEngine.load(ctx, json)
            val root = JSONObject(json)
            // sentences with a tutor graph first, so the demo shows tutor vs learner
            val keys = root.keys().asSequence().filter { it.length in 12..45 }
                .sortedByDescending { root.optJSONObject(it)?.has("graph") == true }.take(12).toList()
            Loaded(e, keys, System.currentTimeMillis() - t0, root)
        }
        engine = e; sentences = keys; selected = keys.first(); status = "ready · load $ms ms"; tableRoot = root
        if (!hasMic) askMic.launch(Manifest.permission.RECORD_AUDIO)
    }

    LaunchedEffect(selected, engine) {
        val e = engine ?: return@LaunchedEffect
        tutorPcm = withContext(Dispatchers.IO) {
            val audio = tableRoot?.optJSONObject(selected)?.optString("audio", "")?.takeIf { it.isNotEmpty() } ?: return@withContext null
            runCatching {
                val bytes = ctx.assets.open("tutor_audio/" + audio.substringBeforeLast('.') + ".wav").readBytes()
                val bb = java.nio.ByteBuffer.wrap(bytes).order(java.nio.ByteOrder.LITTLE_ENDIAN)
                FloatArray((bytes.size - 44) / 2) { bb.getShort(44 + it * 2) / 32768f }   // plain 44-byte wav header
            }.getOrNull()
        }
        tutorComputed = tutorPcm?.let { e.graph(it) }
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
                val samples = rec.stop() // raw take — the SDK normalises and trims it
                takePcm = samples
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
        Row(horizontalArrangement = androidx.compose.foundation.layout.Arrangement.spacedBy(8.dp)) {
            fun play(pcm: FloatArray, isTutor: Boolean) = scope.launch {
                val pb = playPcm(pcm); val t0 = System.currentTimeMillis()
                while (pb.track.playbackHeadPosition < pb.frames && System.currentTimeMillis() - t0 < pb.frames / 16 + 2000) {
                    playhead = isTutor to pb.track.playbackHeadPosition * 1000 / 16000; kotlinx.coroutines.delay(30)
                }
                playhead = null; runCatching { pb.track.stop(); pb.track.release() }
            }
            Button(enabled = tutorPcm != null, onClick = { play(tutorPcm!!, true) }) { Text("▶ Reference") }
            Button(enabled = takePcm != null, onClick = { play(takePcm!!, false) }) { Text("▶ Your take") }
        }

        result?.let { r ->
            Spacer(Modifier.height(16.dp))
            Text("${r.overall}  ${r.rating.name.lowercase().replaceFirstChar { it.uppercase() }}", style = MaterialTheme.typography.displaySmall,
                color = ratingColor(r.rating))
            Text("A ${r.scores.a} · B ${r.scores.pferSlot} · C ${r.scores.pferSeq} · " +
                "${r.wpm?.roundToInt() ?: "–"} wpm · ${"%.1f".format(r.durS)} s")
            Text("heard: ${r.freeIpa}", color = Color.Gray)
            Row(Modifier.padding(vertical = 6.dp), horizontalArrangement = androidx.compose.foundation.layout.Arrangement.spacedBy(8.dp)) {
                Text("Standard:", color = Color.Gray, style = MaterialTheme.typography.bodySmall)
                for ((label, v) in listOf("stored" to false, "computed" to true)) {
                    val on = useComputed == v
                    Text(label, Modifier.clickable { useComputed = v }
                        .then(if (on) Modifier.background(Color(0xFF424242), androidx.compose.foundation.shape.RoundedCornerShape(10.dp)) else Modifier)
                        .padding(horizontal = 10.dp, vertical = 2.dp),
                        color = if (on) Color.White else Color.Gray, style = MaterialTheme.typography.bodySmall)
                }
            }
            GraphPanel(r, if (useComputed) tutorComputed else r.tutorGraph, useComputed, playhead)
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


/** The app's "Standard / Yours" panel drawn from [Result] alone: tutor line with word bands on top,
 *  learner line below, dashed connectors between the two sets of word boundaries, and a bar per
 *  word coloured by its score. Bar of a time on either side = ms * 100 / spanMs. */
@Composable
fun GraphPanel(r: Result, tutor: IntArray?, computed: Boolean, playhead: Pair<Boolean, Int>? = null) {
    val tWords = r.tutorWords ?: emptyList()
    val tSpan = (r.tutorSpanMs ?: 1).toFloat()
    val uSpan = r.userSpanMs.toFloat()
    val panelBg = Color(0xFF2B2B2B); val lineCol = Color(0xFFA8D8E8); val dash = Color(0xFFBDBDBD)
    fun scoreColor(i: Int): Color = ratingColor(com.weaversmind.pronunciation.ratingOf(r.words.getOrNull(i)?.scores?.a ?: 0))
    val textMeasurer = androidx.compose.ui.text.rememberTextMeasurer()
    val labelStyle = MaterialTheme.typography.bodySmall
    androidx.compose.foundation.Canvas(Modifier.fillMaxWidth().height(300.dp)) {
        val w = size.width; val h = size.height
        val gap = 34f; val gh = (h - gap) / 2
        val dashFx = androidx.compose.ui.graphics.PathEffect.dashPathEffect(floatArrayOf(8f, 8f))
        fun panel(y0: Float, title: String) {
            drawRect(panelBg, topLeft = androidx.compose.ui.geometry.Offset(0f, y0), size = androidx.compose.ui.geometry.Size(w, gh))
            for (k in 0 until 5) if (k % 2 == 1) drawRect(Color(0x14FFFFFF), topLeft = androidx.compose.ui.geometry.Offset(0f, y0 + gh * k / 5), size = androidx.compose.ui.geometry.Size(w, gh / 5))
            val t = textMeasurer.measure(title, labelStyle.copy(color = dash)); drawText(t, topLeft = androidx.compose.ui.geometry.Offset(10f, y0 + 6f))
        }
        fun line(g: IntArray, y0: Float, color: Color = lineCol) {
            val path = androidx.compose.ui.graphics.Path()
            g.forEachIndexed { i, v -> val x = w * (i + 0.5f) / 100; val y = y0 + gh - gh * 0.85f * v / 100f; if (i == 0) path.moveTo(x, y) else path.lineTo(x, y) }
            drawPath(path, color, style = androidx.compose.ui.graphics.drawscope.Stroke(width = 4f, cap = androidx.compose.ui.graphics.StrokeCap.Round, join = androidx.compose.ui.graphics.StrokeJoin.Round))
        }
        fun vline(x: Float, y0: Float, y1: Float) = drawLine(dash, androidx.compose.ui.geometry.Offset(x, y0), androidx.compose.ui.geometry.Offset(x, y1), strokeWidth = 2f, pathEffect = dashFx)
        fun tutorBounds(y0: Float, labels: Boolean) {
            tWords.forEachIndexed { i, tw ->
                val xs = w * tw.startMs / tSpan; val xe = w * tw.endMs / tSpan
                vline(xs, y0, y0 + gh); if (i == tWords.lastIndex) vline(xe, y0, y0 + gh)
                if (labels) {
                    val label = textMeasurer.measure(tw.text.trimEnd('.', ',', '!', '?'), labelStyle.copy(color = scoreColor(i), fontWeight = androidx.compose.ui.text.font.FontWeight.Bold))
                    drawText(label, topLeft = androidx.compose.ui.geometry.Offset((xs + xe) / 2 - label.size.width / 2, y0 + gh - label.size.height - 4f))
                }
            }
        }
        // 1. Standard (stored or computed, per the toggle)
        var y = 0f; panel(y, if (computed) "Standard · computed (engine.graph on the native audio)" else "Standard · stored (hand-tuned)")
        if (tutor != null) {
            line(tutor, y, if (computed) Color(0xFFFFB74D) else lineCol); tutorBounds(y, true)
            // accent dots: stored indices on either curve (same timeline; y follows whichever curve is shown)
            r.tutorAccent?.forEach { i -> if (i in 0..99) drawCircle(Color(0xFFFF6D00), 6f, androidx.compose.ui.geometry.Offset(w * (i + 0.5f) / 100, y + gh - gh * 0.85f * tutor[i] / 100f)) }
            if (tWords.size == r.userWords.size) tWords.forEachIndexed { i, tw ->   // connectors to our word starts
                drawLine(dash, androidx.compose.ui.geometry.Offset(w * tw.startMs / tSpan, y + gh), androidx.compose.ui.geometry.Offset(w * r.userWords[i].startMs / uSpan, y + gh + gap), strokeWidth = 2f, pathEffect = dashFx)
            }
        } else { val t = textMeasurer.measure(if (computed) "no native audio bundled" else "no stored graph for this sentence", labelStyle.copy(color = dash)); drawText(t, topLeft = androidx.compose.ui.geometry.Offset(w / 2 - t.size.width / 2, y + gh / 2)) }
        // 2. Yours
        y += gh + gap; panel(y, "Yours"); line(r.userGraph, y)
        r.userWords.forEachIndexed { i, uw ->
            val xs = w * uw.startMs / uSpan; val xe = w * uw.endMs / uSpan
            vline(xs, y, y + gh)
            drawLine(scoreColor(i), androidx.compose.ui.geometry.Offset(xs, y + gh - 5f), androidx.compose.ui.geometry.Offset(xe, y + gh - 5f), strokeWidth = 8f)
        }
        // playhead last, so no panel background paints over it
        playhead?.let { (isTutor, ms) ->
            val x = if (isTutor) w * ms / tSpan else w * (ms - r.trimStartMs - r.userGraphStartMs) / uSpan
            val py = if (isTutor) 0f else gh + gap
            if (x in 0f..w) drawLine(Color(0xFFFF6D00), androidx.compose.ui.geometry.Offset(x, py), androidx.compose.ui.geometry.Offset(x, py + gh), strokeWidth = 3f)
        }
    }
    Text((if (r.tutorGraph == null) "no tutor graph · " else "tutor ${r.tutorSpanMs} ms · accent ${r.tutorAccent?.toList()} · ") +
        "you ${r.userSpanMs} ms: " + r.userWords.joinToString("  ") { "${it.text} ${it.startMs}-${it.endMs}" },
        color = Color.Gray, style = MaterialTheme.typography.bodySmall)
}

fun ratingColor(r: Rating): Color = when (r) {
    Rating.BAD -> Color(0xFFE53935); Rating.OK -> Color(0xFFF5A623); Rating.GOOD -> Color(0xFF7CB342); Rating.EXCELLENT -> Color(0xFF2E7D32)
}
