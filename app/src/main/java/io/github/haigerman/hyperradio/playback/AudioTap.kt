package io.github.haigerman.hyperradio.playback

import androidx.annotation.OptIn
import androidx.media3.common.C
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.audio.TeeAudioProcessor
import java.nio.ByteBuffer
import java.nio.ByteOrder

/**
 * Zapft das dekodierte Audiosignal ab (ohne Mikrofon-Berechtigung) und hält die letzten
 * Samples als Mono-Signal in einem Ringpuffer – für die Welle im Cover
 * in Android Auto.
 */
@OptIn(UnstableApi::class)
object AudioTap : TeeAudioProcessor.AudioBufferSink {

    private const val SIZE = 1 shl 15 // ~0,7 s bei 44,1 kHz
    private val ring = FloatArray(SIZE)
    private var writePos = 0L
    @Volatile private var sampleRate = 44_100
    private var channels = 2
    private var encoding = C.ENCODING_PCM_16BIT

    /** Wann zuletzt Audio ankam (für "spielt gerade?") */
    @Volatile var lastWriteNanos = 0L
        private set

    override fun flush(sampleRateHz: Int, channelCount: Int, encoding: Int) {
        synchronized(this) {
            sampleRate = sampleRateHz
            channels = channelCount.coerceAtLeast(1)
            this.encoding = encoding
        }
    }

    override fun handleBuffer(buffer: ByteBuffer) {
        val buf = buffer.duplicate().order(ByteOrder.nativeOrder())
        synchronized(this) {
            val ch = channels
            when (encoding) {
                C.ENCODING_PCM_16BIT -> {
                    val frames = buf.remaining() / (2 * ch)
                    for (f in 0 until frames) {
                        var sum = 0f
                        for (c in 0 until ch) sum += buf.short / 32768f
                        ring[(writePos++ and (SIZE - 1).toLong()).toInt()] = sum / ch
                    }
                }
                C.ENCODING_PCM_FLOAT -> {
                    val frames = buf.remaining() / (4 * ch)
                    for (f in 0 until frames) {
                        var sum = 0f
                        for (c in 0 until ch) sum += buf.float
                        ring[(writePos++ and (SIZE - 1).toLong()).toInt()] = sum / ch
                    }
                }
                else -> return
            }
        }
        lastWriteNanos = System.nanoTime()
    }

    val isActive: Boolean get() = System.nanoTime() - lastWriteNanos < 500_000_000L

    /**
     * Kopiert [out].size Samples, die gerade zu hören sind. Der Abgriff liegt vor dem
     * Ausgabepuffer des Systems; [latencyMs] gleicht diesen Vorlauf aus, damit Bild und Ton
     * zusammenpassen. Der Ausschnitt beginnt an einem steigenden Nulldurchgang, damit die Welle
     * wie auf einem echten Oszilloskop "steht".
     */
    fun snapshot(out: FloatArray, latencyMs: Int = 200): Boolean {
        if (!isActive) return false
        synchronized(this) {
            val latency = sampleRate.toLong() * latencyMs / 1000
            val search = out.size.toLong()
            val end = writePos - latency
            var start = end - out.size - search
            if (start < 0 || writePos - start > SIZE) return false
            // Trigger: steigender Nulldurchgang im Suchbereich
            var trigger = start
            var prev = ring[(start and (SIZE - 1).toLong()).toInt()]
            for (i in 1 until search) {
                val v = ring[((start + i) and (SIZE - 1).toLong()).toInt()]
                if (prev < 0f && v >= 0f) {
                    trigger = start + i
                    break
                }
                prev = v
            }
            start = trigger
            for (i in out.indices) out[i] = ring[((start + i) and (SIZE - 1).toLong()).toInt()]
        }
        return true
    }
}
