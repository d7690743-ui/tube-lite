package com.nexos.tubelite

import android.content.Intent
import android.webkit.JavascriptInterface

/**
 * Puente JS → nativo: la página reporta play/pausa y título del video.
 * Atado a la aplicación (no a la actividad) para seguir vivo con el
 * WebView aparcado. Seguro en minSdk 24: requiere @JavascriptInterface.
 */
class TubeBridge(private val app: TubeApp) {

    @JavascriptInterface
    fun onState(playing: Boolean, title: String) {
        val clean = title.take(80)
        val changed = app.lastPlaying != playing || app.lastTitle != clean
        app.lastPlaying = playing
        app.lastTitle = clean
        if (changed) {
            app.sendBroadcast(
                Intent(PlaybackService.ACTION_STATE)
                    .setPackage(app.packageName)
                    .putExtra("playing", playing)
                    .putExtra("title", clean)
            )
        }
    }
}
