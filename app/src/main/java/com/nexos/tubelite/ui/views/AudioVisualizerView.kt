/* Portado de mUzVidX (https:\/\/github.com\/Spartan117x\/mUzVidX) bajo GPL-3.0. */
package com.nexos.tubelite.ui.views

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.RectF
import android.graphics.Shader
import android.os.SystemClock
import android.util.AttributeSet
import android.util.Log
import android.view.View
import com.nexos.tubelite.player.PlaybackManager

/**
 * Espectrógrafo visualizador de barras en tiempo real (portado a TubeLite).
 * Recibe el análisis FFT auténtico de las muestras PCM decodificadas por ExoPlayer
 * a través de RealtimeVisualizerAudioProcessor.
 *
 * Muestra 36 barras independientes divididas en:
 * - GRAVES (Sub-bass / Kick / Bajo): 12 barras
 * - MEDIOS (Vocal / Guitarra / Teclado): 12 barras
 * - AGUDOS (Platillos / Hi-hats / Sintetizador): 12 barras
 */
class AudioVisualizerView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = 0
) : View(context, attrs, defStyleAttr) {

    companion object {
        private const val TAG = "AudioVisualizerView"
        const val NUM_BARS = 36
        const val BARS_PER_SECTION = 12 // 12 Graves, 12 Medios, 12 Agudos
    }

    // Magnitudes objetivo (recibidas de FFT) y magnitudes interpoladas para suavidad
    private val targetMagnitudes = FloatArray(NUM_BARS) { 0.04f }
    private val magnitudes = FloatArray(NUM_BARS) { 0.04f }
    private val peaks = FloatArray(NUM_BARS) { 0.06f }
    private val peakHoldFrames = IntArray(NUM_BARS) { 0 }

    // Colores Neon de Estudio
    private val colorBassTop = Color.parseColor("#FF1744")     // Rojo Neón vibrante
    private val colorBassBottom = Color.parseColor("#880E4F")  // Magenta profundo

    private val colorMidTop = Color.parseColor("#00E5FF")      // Cyan eléctrico
    private val colorMidBottom = Color.parseColor("#0D47A1")   // Azul profundo

    private val colorTrebleTop = Color.parseColor("#E040FB")   // Violeta Neón
    private val colorTrebleBottom = Color.parseColor("#4A148C") // Púrpura profundo

    // Pinceles
    private val barPaint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val peakPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.FILL
    }
    private val textPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        textAlign = Paint.Align.CENTER
        textSize = 24f
        isFakeBoldText = true
        letterSpacing = 0.08f
    }
    private val ledPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.FILL
    }

    private val barRect = RectF()
    private val peakRect = RectF()

    private var lastFrameTime = SystemClock.uptimeMillis()
    private var lastFftTimestamp = 0L

    init {
        for (i in 0 until NUM_BARS) {
            targetMagnitudes[i] = 0.04f
            magnitudes[i] = 0.04f
            peaks[i] = 0.06f
        }

        // Conectar automáticamente con el procesador FFT en tiempo real de ExoPlayer
        connectProcessor()
    }

    fun connectProcessor() {
        // Enlace passthrough con el procesador de ExoPlayer
        Log.i(TAG, "AudioVisualizerView enlazado al procesador PCM de ExoPlayer")
    }

    /**
     * Recibe los datos FFT reales directamente de la tubería de reproducción PCM
     */
    fun onRealtimeFftData(bands: FloatArray) {
        lastFftTimestamp = SystemClock.uptimeMillis()
        val limit = minOf(bands.size, NUM_BARS)
        for (i in 0 until limit) {
            targetMagnitudes[i] = bands[i].coerceIn(0.04f, 1.0f)
        }
    }

    fun linkAudioSession(sessionId: Int) {
        // Enlace passthrough: el procesador PCM maneja la captura directa
        connectProcessor()
    }

    fun releaseVisualizer() {
        for (i in 0 until NUM_BARS) {
            targetMagnitudes[i] = 0.04f
        }
    }

    override fun onAttachedToWindow() {
        super.onAttachedToWindow()
        connectProcessor()
        postInvalidateOnAnimation()
    }

    override fun onDetachedFromWindow() {
        super.onDetachedFromWindow()
        releaseVisualizer()
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)

        val w = width.toFloat()
        val h = height.toFloat()
        if (w <= 0 || h <= 0) return

        val now = SystemClock.uptimeMillis()
        val dt = ((now - lastFrameTime) / 1000f).coerceIn(0.001f, 0.1f)
        lastFrameTime = now

        val isPlaying = PlaybackManager.isPlaying

        // Extraer instantánea FFT directa de alta fidelidad desde el procesador PCM
        if (isPlaying) {
            PlaybackManager.visualizerAudioProcessor.getSpectrumSnapshot(targetMagnitudes)
        } else {
            for (i in 0 until NUM_BARS) {
                targetMagnitudes[i] = 0.04f
            }
        }

        // Suavizado cinemático: respuesta rápida al subir, decaimiento orgánico al bajar
        for (i in 0 until NUM_BARS) {
            val target = targetMagnitudes[i]
            if (target > magnitudes[i]) {
                // Ataque instantáneo (sigue el golpe rítmico con fidelidad)
                magnitudes[i] = magnitudes[i] * 0.25f + target * 0.75f
            } else {
                // Caída elástica gradual
                magnitudes[i] = (magnitudes[i] - 1.8f * dt).coerceAtLeast(target).coerceAtLeast(0.04f)
            }
        }

        // 1. Encabezado de Secciones Profesionales (Sin emojis): GRAVES, MEDIOS, AGUDOS
        val headerHeight = 62f
        val sectionWidth = w / 3f

        drawSectionHeader(canvas, 0, "GRAVES", colorBassTop, colorBassBottom, sectionWidth * 0.5f, headerHeight)
        drawSectionHeader(canvas, 1, "MEDIOS", colorMidTop, colorMidBottom, sectionWidth * 1.5f, headerHeight)
        drawSectionHeader(canvas, 2, "AGUDOS", colorTrebleTop, colorTrebleBottom, sectionWidth * 2.5f, headerHeight)

        // 2. Área de las 36 Barras del Espectro
        val spectrumTop = headerHeight + 16f
        val spectrumBottom = h - 14f
        val maxBarHeight = (spectrumBottom - spectrumTop).coerceAtLeast(10f)

        val totalBarSpacing = w * 0.08f
        val availableBarWidth = w - totalBarSpacing
        val singleBarWidth = (availableBarWidth / NUM_BARS).coerceIn(4f, 32f)
        val spacing = (w - (singleBarWidth * NUM_BARS)) / (NUM_BARS + 1)

        for (i in 0 until NUM_BARS) {
            val left = spacing + i * (singleBarWidth + spacing)
            val right = left + singleBarWidth

            val mag = magnitudes[i].coerceIn(0.04f, 1.0f)
            val barH = maxBarHeight * mag
            val top = spectrumBottom - barH

            barRect.set(left, top, right, spectrumBottom)

            // Gradiente vertical según la banda espectral
            val (cTop, cBottom) = when (i / BARS_PER_SECTION) {
                0 -> Pair(colorBassTop, colorBassBottom)        // Graves
                1 -> Pair(colorMidTop, colorMidBottom)          // Medios
                else -> Pair(colorTrebleTop, colorTrebleBottom) // Agudos
            }

            barPaint.shader = LinearGradient(
                left, top, left, spectrumBottom,
                cTop, cBottom,
                Shader.TileMode.CLAMP
            )

            val cornerRadius = singleBarWidth * 0.35f
            canvas.drawRoundRect(barRect, cornerRadius, cornerRadius, barPaint)

            // 3. Puntos de pico flotantes (Peak Caps con física de caída gravitatoria)
            if (mag >= peaks[i]) {
                peaks[i] = mag
                peakHoldFrames[i] = 8 // Retención de 8 fotogramas antes de iniciar caída
            } else {
                if (peakHoldFrames[i] > 0) {
                    peakHoldFrames[i]--
                } else {
                    peaks[i] = (peaks[i] - 1.1f * dt).coerceAtLeast(0.04f)
                }
            }

            val peakTop = spectrumBottom - (maxBarHeight * peaks[i])
            val peakBottom = peakTop + (singleBarWidth * 0.35f).coerceAtLeast(4f)
            peakRect.set(left, peakTop, right, peakBottom)
            peakPaint.color = cTop
            canvas.drawRoundRect(peakRect, cornerRadius, cornerRadius, peakPaint)
        }

        // Mantener animación a 60 FPS continua
        postInvalidateOnAnimation()
    }

    private fun drawSectionHeader(
        canvas: Canvas,
        sectionIndex: Int,
        title: String,
        colorTop: Int,
        colorBottom: Int,
        centerX: Float,
        headerHeight: Float
    ) {
        val startIdx = sectionIndex * BARS_PER_SECTION
        var sum = 0f
        for (i in startIdx until startIdx + BARS_PER_SECTION) {
            sum += magnitudes[i]
        }
        val avgLevel = (sum / BARS_PER_SECTION).coerceIn(0.05f, 1.0f)

        // LED indicador de actividad de estudio (Sin emojis)
        ledPaint.color = colorTop
        val ledRadius = 5f + (avgLevel * 4f)
        canvas.drawCircle(centerX - 68f, headerHeight * 0.45f, ledRadius, ledPaint)

        // Título limpio de la sección
        textPaint.color = colorTop
        canvas.drawText(title, centerX, headerHeight * 0.55f, textPaint)

        // Mini barra de potencia VU métrica bajo el título
        val barW = 90f
        val barL = centerX - (barW / 2f)
        val barR = barL + barW * avgLevel
        val barY = headerHeight * 0.85f
        barRect.set(barL, barY, barR, barY + 3.5f)
        ledPaint.color = colorBottom
        canvas.drawRoundRect(barRect, 2f, 2f, ledPaint)
    }
}
