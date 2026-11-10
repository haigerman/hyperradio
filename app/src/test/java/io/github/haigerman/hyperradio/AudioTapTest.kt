package io.github.haigerman.hyperradio

import androidx.media3.common.C
import io.github.haigerman.hyperradio.playback.AudioTap
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.sin

class AudioTapTest {

    @Test
    fun capturesStereoPcmAsTriggeredMonoWave() {
        val rate = 44_100
        AudioTap.flush(rate, 2, C.ENCODING_PCM_16BIT)
        val frames = rate // 1 s, 441 Hz Sinus mit Amplitude 0.5
        val buf = ByteBuffer.allocate(frames * 4).order(ByteOrder.nativeOrder())
        for (i in 0 until frames) {
            val v = (0.5 * sin(2 * PI * 441.0 * i / rate) * 32767).toInt().toShort()
            buf.putShort(v)
            buf.putShort(v)
        }
        buf.flip()
        AudioTap.handleBuffer(buf)

        val out = FloatArray(1024)
        assertTrue(AudioTap.snapshot(out, latencyMs = 200))
        // Trigger: beginnt am steigenden Nulldurchgang
        assertTrue("Start ${out[0]}", abs(out[0]) < 0.05f && out[3] > out[0])
        val peak = out.maxOf { abs(it) }
        assertEquals(0.5f, peak, 0.02f)
    }
}
