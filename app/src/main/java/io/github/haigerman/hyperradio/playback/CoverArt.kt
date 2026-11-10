package io.github.haigerman.hyperradio.playback

import android.graphics.Bitmap
import android.graphics.BlurMaskFilter
import android.graphics.Canvas
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RadialGradient
import android.graphics.Shader
import android.graphics.Typeface
import io.github.haigerman.hyperradio.data.Station
import java.io.ByteArrayOutputStream
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.sin

/**
 * Erzeugt das Cover für Benachrichtigung und Android Auto: dunkler Verlauf im App-Stil,
 * Senderemoji, Name und eine leuchtende Oszilloskop-Welle – aus dem echten Audiosignal,
 * oder (noch ohne Ton) eine ruhige, für jeden Sender eigene Welle.
 */
object CoverArt {

    private const val NIGHT = 0xFF0F1A24.toInt()
    private const val DEEP_TEAL = 0xFF123238.toInt()
    private const val SAND = 0xFFE8C77A.toInt()
    private const val MIST = 0xFF8CC7C4.toInt()
    private const val CORAL = 0xFFFF8A65.toInt()

    fun render(station: Station, samples: FloatArray?, size: Int = 512, quality: Int = 88): ByteArray {
        val bmp = Bitmap.createBitmap(size, size, Bitmap.Config.ARGB_8888)
        val c = Canvas(bmp)
        val s = size.toFloat()

        // Hintergrund
        val bg = Paint().apply { shader = LinearGradient(0f, 0f, 0f, s, NIGHT, DEEP_TEAL, Shader.TileMode.CLAMP) }
        c.drawRect(0f, 0f, s, s, bg)
        val halo = Paint().apply {
            shader = RadialGradient(s / 2, s * 0.56f, s * 0.55f, 0x33E8C77A, 0x00E8C77A, Shader.TileMode.CLAMP)
        }
        c.drawRect(0f, 0f, s, s, halo)

        // Raster wie auf einem Oszilloskop-Schirm
        val grid = Paint().apply { color = 0x14FFFFFF; strokeWidth = s / 512f }
        val top = s * 0.36f
        val bottom = s * 0.76f
        for (i in 0..8) {
            val x = s * (0.06f + 0.88f * i / 8f)
            c.drawLine(x, top, x, bottom, grid)
        }
        for (i in 0..4) {
            val y = top + (bottom - top) * i / 4f
            c.drawLine(s * 0.06f, y, s * 0.94f, y, grid)
        }

        // Welle
        val points = 180
        val wave = FloatArray(points)
        if (samples != null && samples.isNotEmpty()) {
            var peak = 0.05f
            for (v in samples) peak = maxOf(peak, abs(v))
            val gain = (0.9f / peak).coerceAtMost(6f)
            for (i in 0 until points) wave[i] = (samples[i * samples.size / points] * gain).coerceIn(-1f, 1f)
        } else {
            val seed = station.id.hashCode()
            val f1 = 2 + (seed and 3)
            val f2 = 5 + ((seed shr 3) and 3)
            for (i in 0 until points) {
                val t = i / (points - 1f)
                val env = sin(PI * t).toFloat()
                wave[i] = env * (0.55f * sin(2 * PI * f1 * t).toFloat() + 0.3f * sin(2 * PI * f2 * t + 1.3).toFloat())
            }
        }
        val mid = (top + bottom) / 2
        val amp = (bottom - top) / 2 * 0.92f
        val path = Path()
        for (i in 0 until points) {
            val x = s * 0.06f + s * 0.88f * i / (points - 1f)
            val y = mid - wave[i] * amp
            if (i == 0) path.moveTo(x, y) else path.lineTo(x, y)
        }
        val waveShader = LinearGradient(s * 0.06f, 0f, s * 0.94f, 0f, intArrayOf(MIST, SAND, CORAL), null, Shader.TileMode.CLAMP)
        val glow = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            style = Paint.Style.STROKE
            strokeWidth = s * 0.035f
            strokeCap = Paint.Cap.ROUND
            strokeJoin = Paint.Join.ROUND
            shader = waveShader
            alpha = 110
            maskFilter = BlurMaskFilter(s * 0.03f, BlurMaskFilter.Blur.NORMAL)
        }
        c.drawPath(path, glow)
        val line = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            style = Paint.Style.STROKE
            strokeWidth = s * 0.009f
            strokeCap = Paint.Cap.ROUND
            strokeJoin = Paint.Join.ROUND
            shader = waveShader
        }
        c.drawPath(path, line)
        val core = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            style = Paint.Style.STROKE
            strokeWidth = s * 0.003f
            color = 0xCCFFFFFF.toInt()
        }
        c.drawPath(path, core)

        // Emoji und Name
        val emoji = Paint(Paint.ANTI_ALIAS_FLAG).apply { textSize = s * 0.17f; textAlign = Paint.Align.CENTER }
        c.drawText(station.emoji, s / 2, s * 0.27f, emoji)
        val name = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = SAND
            textAlign = Paint.Align.CENTER
            typeface = Typeface.create("sans-serif-light", Typeface.NORMAL)
            textSize = s * 0.085f
        }
        val maxW = s * 0.88f
        while (name.measureText(station.name) > maxW && name.textSize > s * 0.045f) name.textSize *= 0.92f
        c.drawText(station.name, s / 2, s * 0.89f, name)

        val out = ByteArrayOutputStream()
        bmp.compress(Bitmap.CompressFormat.JPEG, quality, out)
        bmp.recycle()
        return out.toByteArray()
    }
}
