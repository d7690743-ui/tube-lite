package com.nexos.tubelite

import android.app.Application
import android.webkit.WebView

class TubeApp : Application() {
    /** WebView aparcado cuando la actividad muere con Fondo ON: el audio sigue. */
    var parkedWebView: WebView? = null

    /** WebView que recibe play/pausa (el de la actividad viva o el aparcado). */
    var activeWebView: WebView? = null
}
