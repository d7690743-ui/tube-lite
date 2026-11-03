package com.nexos.tubelite

import android.app.Application
import android.webkit.WebView
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

class TubeApp : Application() {
    /** WebView aparcado cuando la actividad muere con Fondo ON: el audio sigue. */
    var parkedWebView: WebView? = null

    /** WebView que recibe play/pausa (el de la actividad viva o el aparcado). */
    var activeWebView: WebView? = null

    /** Último estado reportado por el puente JS (notificación/metadata). */
    @Volatile var lastPlaying = true

    @Volatile var lastTitle = ""

    @Volatile var wwNullLogged = false

    /** true mientras PlaybackService vive (muere con el proceso). */
    @Volatile var serviceAlive = false
    private val diagLock = Any()
    private val diagBuf: ArrayDeque<String> = ArrayDeque()

    /** Bitácora en memoria (100 entradas con hora; sin URLs ni títulos). */
    fun addDiag(tag: String, msg: String) {
        val time = try {
            SimpleDateFormat("HH:mm:ss", Locale.getDefault()).format(Date())
        } catch (_: Exception) {
            "--:--:--"
        }
        val clean = msg.take(200).replace('\n', ' ')
        synchronized(diagLock) {
            diagBuf.addLast("[$time] $tag: $clean")
            while (diagBuf.size > 100) diagBuf.removeFirst()
        }
    }

    fun diagSummary(): String = synchronized(diagLock) {
        if (diagBuf.isEmpty()) "sin errores registrados"
        else "${diagBuf.size} errores, último: ${diagBuf.last()}"
    }
}
