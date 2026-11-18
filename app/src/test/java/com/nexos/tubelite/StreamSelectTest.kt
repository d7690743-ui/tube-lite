package com.nexos.tubelite

import com.nexos.tubelite.extractor.VideoFormatOption
import com.nexos.tubelite.extractor.selectVideoFormat
import com.nexos.tubelite.extractor.videoSizeForQuality
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * Selección de calidad del video nativo: si falla, el reproductor carga
 * resolución equivocada o cae al stream por defecto sin necesidad.
 */
class StreamSelectTest {

    private fun fmt(label: String) = VideoFormatOption(
        itag = 0,
        qualityLabel = label,
        videoUrl = "https://x/$label",
        isProgressive = true
    )

    private val formats = listOf(fmt("1080p"), fmt("720p"), fmt("480p"), fmt("360p"))

    @Test
    fun auto_prefiere720p() {
        assertEquals("720p", selectVideoFormat(formats, "Auto")?.qualityLabel)
    }

    @Test
    fun auto_sin720p_usaPrimero() {
        val fs = listOf(fmt("480p"), fmt("360p"))
        assertEquals("480p", selectVideoFormat(fs, "Auto")?.qualityLabel)
    }

    @Test
    fun exacta_devuelveEsa() {
        assertEquals("480p", selectVideoFormat(formats, "480p")?.qualityLabel)
    }

    @Test
    fun desconocida_caeAlPrimero() {
        assertEquals("1080p", selectVideoFormat(formats, "144p")?.qualityLabel)
    }

    @Test
    fun vacia_devuelveNull() {
        assertNull(selectVideoFormat(emptyList(), "Auto"))
    }

    @Test
    fun tamanio_autoEsNull() {
        assertNull(videoSizeForQuality("Auto"))
    }

    @Test
    fun tamanio_mapeaCalidades() {
        assertEquals(Pair(1920, 1080), videoSizeForQuality("1080p"))
        assertEquals(Pair(854, 480), videoSizeForQuality("480p"))
        assertEquals(Pair(640, 360), videoSizeForQuality("360p"))
    }

    @Test
    fun tamanio_desconocidaCaeA720p() {
        assertEquals(Pair(1280, 720), videoSizeForQuality("144p"))
    }
}
