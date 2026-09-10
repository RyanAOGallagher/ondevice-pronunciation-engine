package com.weaversmind.pronunciation

import android.media.MediaCodec
import android.media.MediaExtractor
import android.media.MediaFormat
import java.io.ByteArrayOutputStream
import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder

/** Decodes any audio file Android can play (MP3, M4A/AAC, OGG, FLAC…) to mono float32 [-1,1]
 *  plus its sample rate, via MediaExtractor + MediaCodec. No resampling. */
internal fun decodeWithMediaCodec(file: File): Pair<FloatArray, Int> {
    val ex = MediaExtractor().apply { setDataSource(file.path) }
    val track = (0 until ex.trackCount).firstOrNull {
        ex.getTrackFormat(it).getString(MediaFormat.KEY_MIME)?.startsWith("audio/") == true
    } ?: throw IllegalArgumentException("no audio track in ${file.name}")
    ex.selectTrack(track)
    val fmt = ex.getTrackFormat(track)
    var rate = fmt.getInteger(MediaFormat.KEY_SAMPLE_RATE)
    var channels = fmt.getInteger(MediaFormat.KEY_CHANNEL_COUNT)
    val codec = MediaCodec.createDecoderByType(fmt.getString(MediaFormat.KEY_MIME)!!)
    val pcm = ByteArrayOutputStream()
    try {
        codec.configure(fmt, null, null, 0)
        codec.start()
        val info = MediaCodec.BufferInfo()
        var inDone = false
        var outDone = false
        while (!outDone) {
            if (!inDone) {
                val i = codec.dequeueInputBuffer(10_000)
                if (i >= 0) {
                    val n = ex.readSampleData(codec.getInputBuffer(i)!!, 0)
                    if (n < 0) { codec.queueInputBuffer(i, 0, 0, 0, MediaCodec.BUFFER_FLAG_END_OF_STREAM); inDone = true }
                    else { codec.queueInputBuffer(i, 0, n, ex.sampleTime, 0); ex.advance() }
                }
            }
            val o = codec.dequeueOutputBuffer(info, 10_000)
            if (o >= 0) {
                val buf = codec.getOutputBuffer(o)!!
                val bytes = ByteArray(info.size)
                buf.position(info.offset); buf.get(bytes); pcm.write(bytes)
                codec.releaseOutputBuffer(o, false)
                if (info.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM != 0) outDone = true
            } else if (o == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED) {
                rate = codec.outputFormat.getInteger(MediaFormat.KEY_SAMPLE_RATE)
                channels = codec.outputFormat.getInteger(MediaFormat.KEY_CHANNEL_COUNT)
            }
        }
    } finally {
        codec.release(); ex.release()
    }
    return pcm16ToMono(ByteBuffer.wrap(pcm.toByteArray()).order(ByteOrder.LITTLE_ENDIAN), 0, pcm.size(), channels) to rate
}

/** Interleaved 16-bit little-endian PCM → mono float32 [-1,1], averaging channels. */
internal fun pcm16ToMono(bb: ByteBuffer, off: Int, len: Int, channels: Int): FloatArray {
    val frames = len / 2 / channels
    val out = FloatArray(frames)
    var p = off
    for (i in 0 until frames) {
        var acc = 0
        for (c in 0 until channels) { acc += bb.getShort(p).toInt(); p += 2 }
        out[i] = (acc.toFloat() / channels) / 32768f
    }
    return out
}
