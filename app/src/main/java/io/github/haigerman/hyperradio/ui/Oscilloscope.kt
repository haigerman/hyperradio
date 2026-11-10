package io.github.haigerman.hyperradio.ui

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import io.github.haigerman.hyperradio.playback.AudioTap
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.sin

private const val POINTS = 256
private const val TRAILS = 4

/**
 * Oszilloskop im Stil alter Röhrenschirme: die echte Wellenform des laufenden Senders,
 * leuchtend mit Farbverlauf und Nachglühen der vorherigen Bilder.
 */
@Composable
fun Oscilloscope(active: Boolean, modifier: Modifier = Modifier, height: Dp = 92.dp) {
    val state = remember { ScopeState() }
    var frame by remember { mutableLongStateOf(0L) }

    LaunchedEffect(active) {
        while (true) {
            withFrameNanos { now ->
                state.update(active, now)
                frame = now
            }
        }
    }

    Canvas(
        modifier
            .fillMaxWidth()
            .height(height)
            .clip(RoundedCornerShape(18.dp))
            .background(Color.Black.copy(alpha = 0.25f)),
    ) {
        if (frame < 0) return@Canvas // liest den Bildzähler → bei jedem Bild neu zeichnen
        drawGrid()
        val brush = Brush.horizontalGradient(listOf(Mist, Sand, Coral))
        // Nachglühen: ältere Bilder blasser
        for (age in TRAILS - 1 downTo 1) {
            val wave = state.trail(age) ?: continue
            drawWave(wave, brush, alpha = 0.32f / (age + 1), glow = false)
        }
        drawWave(state.trail(0) ?: return@Canvas, brush, alpha = 1f, glow = true)
    }
}

private class ScopeState {
    private val raw = FloatArray(1024)
    private val history = Array(TRAILS) { FloatArray(POINTS) }
    private var head = 0
    private var filled = 0
    private var gain = 3f
    private var lastNanos = 0L

    fun update(active: Boolean, now: Long) {
        // ~40 Bilder pro Sekunde reichen und schonen den Akku
        if (now - lastNanos < 24_000_000L) return
        lastNanos = now
        head = (head + 1) % TRAILS
        val out = history[head]
        if (active && AudioTap.snapshot(raw)) {
            var peak = 0f
            for (i in 0 until POINTS) {
                // je Punkt den betragsgrößten Wert des Abschnitts nehmen → kräftigere Kontur
                val from = i * raw.size / POINTS
                val to = (i + 1) * raw.size / POINTS
                var v = raw[from]
                for (j in from + 1 until to) if (abs(raw[j]) > abs(v)) v = raw[j]
                out[i] = v
                peak = maxOf(peak, abs(v))
            }
            // weiche automatische Verstärkung, damit leise und laute Sender gleich gut aussehen
            val target = (0.85f / peak.coerceAtLeast(0.04f)).coerceIn(1f, 8f)
            gain += (target - gain) * if (target < gain) 0.3f else 0.04f
            for (i in 0 until POINTS) out[i] = (out[i] * gain).coerceIn(-1f, 1f)
        } else {
            // Ruhe: fast flache, langsam atmende Linie
            val t = now / 1e9
            val a = 0.04f + 0.02f * sin(t * 1.3).toFloat()
            for (i in 0 until POINTS) {
                val x = i / (POINTS - 1f)
                out[i] = a * sin(2 * PI * (2 * x + t * 0.4)).toFloat() * sin(PI * x).toFloat()
            }
        }
        filled = minOf(filled + 1, TRAILS)
    }

    fun trail(age: Int): FloatArray? = if (age < filled) history[(head - age + TRAILS) % TRAILS] else null
}

private fun DrawScope.drawGrid() {
    val line = Color.White.copy(alpha = 0.05f)
    val w = 1.dp.toPx()
    for (i in 1 until 10) {
        val x = size.width * i / 10f
        drawLine(line, Offset(x, 0f), Offset(x, size.height), w)
    }
    for (i in 1 until 4) {
        val y = size.height * i / 4f
        drawLine(line, Offset(0f, y), Offset(size.width, y), w)
    }
    drawLine(Color.White.copy(alpha = 0.09f), Offset(0f, size.height / 2), Offset(size.width, size.height / 2), w)
}

private fun DrawScope.drawWave(wave: FloatArray, brush: Brush, alpha: Float, glow: Boolean) {
    val padX = 10.dp.toPx()
    val mid = size.height / 2
    val amp = size.height / 2 * 0.86f
    val path = Path()
    for (i in wave.indices) {
        val x = padX + (size.width - 2 * padX) * i / (wave.size - 1f)
        val y = mid - wave[i] * amp
        if (i == 0) path.moveTo(x, y) else path.lineTo(x, y)
    }
    fun stroke(width: Dp) = Stroke(width = width.toPx(), cap = StrokeCap.Round, join = StrokeJoin.Round)
    if (glow) {
        drawPath(path, brush, alpha = 0.10f, style = stroke(12.dp))
        drawPath(path, brush, alpha = 0.22f, style = stroke(6.dp))
    }
    drawPath(path, brush, alpha = alpha, style = stroke(2.dp))
    if (glow) drawPath(path, Color.White.copy(alpha = 0.7f), style = stroke(0.8.dp))
}
