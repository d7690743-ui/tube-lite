package com.nexos.tubelite

import com.nexos.tubelite.extractor.TrackRetry
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** Un reintento por pista fallida; el éxito reinicia la cuenta. */
class TrackRetryTest {

    @Test
    fun primero_reintenta() {
        assertTrue(TrackRetry().shouldRetry("a"))
    }

    @Test
    fun mismo_seguido_noReintenta() {
        val r = TrackRetry()
        assertTrue(r.shouldRetry("a"))
        assertFalse(r.shouldRetry("a"))
    }

    @Test
    fun otraPista_reintenta() {
        val r = TrackRetry()
        assertTrue(r.shouldRetry("a"))
        assertTrue(r.shouldRetry("b"))
    }

    @Test
    fun recuperado_reintentaDeNuevo() {
        val r = TrackRetry()
        assertTrue(r.shouldRetry("a"))
        assertFalse(r.shouldRetry("a"))
        r.onRecovered()
        assertTrue(r.shouldRetry("a"))
    }
}
