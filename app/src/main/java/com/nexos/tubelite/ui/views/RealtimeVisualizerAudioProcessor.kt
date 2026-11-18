/* Portado de mUzVidX (https:\/\/github.com\/Spartan117x\/mUzVidX) bajo GPL-3.0. */
package com.nexos.tubelite.ui.views

import androidx.media3.common.C
import androidx.media3.common.audio.AudioProcessor
import androidx.media3.common.audio.BaseAudioProcessor
import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.math.cos
import kotlin.math.hypot
import kotlin.math.log10
import kotlin.math.pow
import kotlin.math.sin

/**
 * AudioProcessor transparente para ExoPlayer (Media3).
 * Intercepta las muestras PCM de 16 bits directamente de la tubería de audio decodificada
 * y calcula en tiempo real un análisis espectral FFT de 1024 puntos mapeado a 36 bandas
 * musicales logarítmicas (Graves, Medios, Agudos) con respuesta en decibelios (dB).
 *
 * Ventajas cruciales:
 * 1. 100% libre de permisos (no requiere RECORD_AUDIO de Android).
 * 2. Analiza exactamente el audio que suena en los altavoces / auriculares.
 * 3. Cada una de las 36 barras se mueve con independencia matemática y musical real.
 * 4. Cero latencia y cero distorsión sonora (passthrough transparente).
 */
class RealtimeVisualizerAudioProcessor : BaseAudioProcessor() {

    companion object {
        const val NUM_BANDS = 36
        private const val FFT_SIZE = 1024
        private const val HALF_SIZE = FFT_SIZE / 2 // 512 bins discretos
        private const val SAMPLE_RATE = 44100.0f
        private const val MIN_FREQ = 38.0f   // Sub-grave inicial
        private const val MAX_FREQ = 16500.0f // Límite de brillo / aire
    }

    var onFftDataListener: ((FloatArray) -> Unit)? = null

    // Snapshots protegidos para lectura ultrarrápida sin asignación de memoria
    private val latestBands = FloatArray(NUM_BANDS) { 0.04f }
    private val bandsLock = Any()

    // Buffers pre-asignados para evitar recolección de basura (GC) en tiempo real
    private val sampleBuffer = FloatArray(FFT_SIZE)
    private var sampleIndex = 0

    // Ventana Hanning precalculada para evitar fuga espectral
    private val hanningWindow = FloatArray(FFT_SIZE) { i ->
        (0.5 * (1.0 - cos(2.0 * Math.PI * i / (FFT_SIZE - 1)))).toFloat()
    }

    private val fftReal = FloatArray(FFT_SIZE)
    private val fftImag = FloatArray(FFT_SIZE)
    private val fftMagnitudes = FloatArray(HALF_SIZE)
    private val bandEnergies = FloatArray(NUM_BANDS)

    // Precalcular índices fraccionarios y rangos de bins para las 36 bandas musicales
    private val centerFreqs = FloatArray(NUM_BANDS) { i ->
        (MIN_FREQ * (MAX_FREQ / MIN_FREQ).toDouble().pow(i.toDouble() / (NUM_BANDS - 1))).toFloat()
    }

    private val binIndices = FloatArray(NUM_BANDS) { i ->
        (centerFreqs[i] * FFT_SIZE / SAMPLE_RATE).coerceIn(1.0f, (HALF_SIZE - 2).toFloat())
    }

    override fun onConfigure(inputAudioFormat: AudioProcessor.AudioFormat): AudioProcessor.AudioFormat {
        // Acepta PCM 16-bit passthrough transparente
        if (inputAudioFormat.encoding != C.ENCODING_PCM_16BIT) {
            return AudioProcessor.AudioFormat.NOT_SET
        }
        return inputAudioFormat
    }

    override fun queueInput(inputBuffer: ByteBuffer) {
        val remaining = inputBuffer.remaining()
        if (remaining == 0) return

        val bytesPerFrame = inputAudioFormat.bytesPerFrame.coerceAtLeast(2)

        // Duplicar el buffer para leer muestras sin afectar la posición de reproducción
        val duplicate = inputBuffer.duplicate()
        duplicate.order(ByteOrder.LITTLE_ENDIAN)

        val frameSkip = bytesPerFrame - 2
        while (duplicate.remaining() >= bytesPerFrame) {
            val sample = duplicate.short.toFloat() / 32768.0f // Normalizar PCM 16-bit a [-1.0, 1.0]

            if (frameSkip > 0 && duplicate.remaining() >= frameSkip) {
                duplicate.position(duplicate.position() + frameSkip)
            }

            sampleBuffer[sampleIndex] = sample
            sampleIndex++

            if (sampleIndex >= FFT_SIZE) {
                sampleIndex = 0
                computeFftSpectrum()
            }
        }

        // Pasar el buffer idéntico a la salida para que ExoPlayer envíe el audio al hardware
        val outputBuffer = replaceOutputBuffer(remaining)
        outputBuffer.put(inputBuffer)
        outputBuffer.flip()
    }

    private fun computeFftSpectrum() {
        // 1. Aplicar ventana Hanning a las muestras PCM
        for (i in 0 until FFT_SIZE) {
            fftReal[i] = sampleBuffer[i] * hanningWindow[i]
            fftImag[i] = 0f
        }

        // 2. Ejecutar FFT Cooley-Tukey Radix-2 in-place
        fftInPlace(fftReal, fftImag, FFT_SIZE)

        // 3. Magnitudes de los bins de frecuencia (0 a HALF_SIZE-1)
        for (k in 0 until HALF_SIZE) {
            fftMagnitudes[k] = hypot(fftReal[k].toDouble(), fftImag[k].toDouble()).toFloat()
        }

        // 4. Mapear cada una de las 36 bandas con interpolación espectral y escala dinámica dB
        for (b in 0 until NUM_BANDS) {
            val centerBin = binIndices[b]
            val b0 = centerBin.toInt()
            val b1 = (b0 + 1).coerceAtMost(HALF_SIZE - 1)
            val frac = centerBin - b0

            // Interpolación continua en la frecuencia central
            val interpMag = fftMagnitudes[b0] * (1.0f - frac) + fftMagnitudes[b1] * frac

            // Detección de armónicos en la vecindad de la banda
            val windowRadius = (centerBin * 0.12f).toInt().coerceIn(1, 14)
            val wStart = (b0 - windowRadius).coerceAtLeast(1)
            val wEnd = (b1 + windowRadius).coerceAtMost(HALF_SIZE - 1)

            var peakInWindow = 0f
            for (k in wStart..wEnd) {
                if (fftMagnitudes[k] > peakInWindow) {
                    peakInWindow = fftMagnitudes[k]
                }
            }

            // Fusión balanceada entre tono fundamental y energía zonal
            val effectiveMag = maxOf(interpMag, peakInWindow * 0.72f)

            // Convertir magnitud FFT normalizada a Decibelios (dB)
            // (La escala FFT teórica para una senoide unitaria con Hanning es FFT_SIZE / 4 = 256)
            val amplitude = effectiveMag / (FFT_SIZE / 4.0f)
            val db = 20.0f * log10(maxOf(1e-4f, amplitude))

            // Rango dinámico de estudio: -52 dB (ruido de fondo / reposo) a 0 dB (pico máximo)
            val floorDb = when {
                b < 12 -> -50.0f // Graves (más presencia física de sub-bass)
                b < 24 -> -54.0f // Medios (excelente definición vocal e instrumental)
                else   -> -58.0f // Agudos (sensibilidad a detalles sutiles de platillos)
            }

            val normalizedDb = ((db - floorDb) / (-floorDb)).coerceIn(0.0f, 1.0f)
            // Curva gamma para acentuar el rebote elástico visual
            val dynamicLevel = (normalizedDb.pow(1.15f) * 0.96f).coerceIn(0.04f, 1.0f)

            bandEnergies[b] = dynamicLevel
        }

        synchronized(bandsLock) {
            System.arraycopy(bandEnergies, 0, latestBands, 0, NUM_BANDS)
        }

        onFftDataListener?.invoke(bandEnergies)
    }

    /**
     * Copia atómica del espectro actual a un buffer externo sin asignación de memoria
     */
    fun getSpectrumSnapshot(out: FloatArray) {
        synchronized(bandsLock) {
            val len = minOf(out.size, NUM_BANDS)
            System.arraycopy(latestBands, 0, out, 0, len)
        }
    }

    /**
     * Algoritmo FFT Cooley-Tukey Radix-2 in-place de alto rendimiento
     */
    private fun fftInPlace(real: FloatArray, imag: FloatArray, n: Int) {
        var j = 0
        for (i in 0 until n - 1) {
            if (i < j) {
                val tempR = real[i]; real[i] = real[j]; real[j] = tempR
                val tempI = imag[i]; imag[i] = imag[j]; imag[j] = tempI
            }
            var k = n / 2
            while (k <= j) {
                j -= k
                k /= 2
            }
            j += k
        }

        var l = 2
        while (l <= n) {
            val halfL = l / 2
            val angle = -2.0 * Math.PI / l
            val wStepR = cos(angle).toFloat()
            val wStepI = sin(angle).toFloat()

            var i = 0
            while (i < n) {
                var wR = 1f
                var wI = 0f
                for (m in 0 until halfL) {
                    val idxA = i + m
                    val idxB = idxA + halfL
                    val uR = real[idxA]
                    val uI = imag[idxA]
                    val vR = real[idxB] * wR - imag[idxB] * wI
                    val vI = real[idxB] * wI + imag[idxB] * wR

                    real[idxA] = uR + vR
                    imag[idxA] = uI + vI
                    real[idxB] = uR - vR
                    imag[idxB] = uI - vI

                    val nextWR = wR * wStepR - wI * wStepI
                    val nextWI = wR * wStepI + wI * wStepR
                    wR = nextWR
                    wI = nextWI
                }
                i += l
            }
            l *= 2
        }
    }
}
