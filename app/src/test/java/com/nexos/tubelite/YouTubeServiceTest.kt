package com.nexos.tubelite

import com.nexos.tubelite.extractor.YouTubeService
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * Extracción del id de video (11 chars) desde cualquier forma de URL.
 * Es la base del traspaso WebView→nativo y de las descargas: si falla,
 * esas funciones no encuentran el video actual.
 */
class YouTubeServiceTest {

    @Test
    fun cleanVideoId_watchUrl() {
        assertEquals(
            "dQw4w9WgXcQ",
            YouTubeService.cleanVideoId("https://www.youtube.com/watch?v=dQw4w9WgXcQ")
        )
    }

    @Test
    fun cleanVideoId_watchConParametros() {
        assertEquals(
            "dQw4w9WgXcQ",
            YouTubeService.cleanVideoId("https://www.youtube.com/watch?v=dQw4w9WgXcQ&t=42s")
        )
    }

    @Test
    fun cleanVideoId_music() {
        assertEquals(
            "dQw4w9WgXcQ",
            YouTubeService.cleanVideoId("https://music.youtube.com/watch?v=dQw4w9WgXcQ&list=xyz")
        )
    }

    @Test
    fun cleanVideoId_corta() {
        assertEquals(
            "dQw4w9WgXcQ",
            YouTubeService.cleanVideoId("https://youtu.be/dQw4w9WgXcQ")
        )
    }

    @Test
    fun cleanVideoId_shorts() {
        assertEquals(
            "dQw4w9WgXcQ",
            YouTubeService.cleanVideoId("https://www.youtube.com/shorts/dQw4w9WgXcQ")
        )
    }

    @Test
    fun cleanVideoId_embed() {
        assertEquals(
            "dQw4w9WgXcQ",
            YouTubeService.cleanVideoId("https://www.youtube.com/embed/dQw4w9WgXcQ")
        )
    }

    @Test
    fun cleanVideoId_pelado() {
        assertEquals("dQw4w9WgXcQ", YouTubeService.cleanVideoId("dQw4w9WgXcQ"))
    }

    @Test
    fun cleanVideoId_conPrefijo() {
        assertEquals("dQw4w9WgXcQ", YouTubeService.cleanVideoId("yt_dQw4w9WgXcQ"))
    }
}
