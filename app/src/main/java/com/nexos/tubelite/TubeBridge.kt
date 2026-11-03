package com.nexos.tubelite

import android.content.Intent
import android.os.Handler
import android.os.Looper
import android.webkit.JavascriptInterface
import android.webkit.WebView

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

    @JavascriptInterface
    fun onError(tag: String, message: String) {
        app.addDiag("js-$tag", message)
    }
}

/**
 * Ejecutor JS central: hilo UI, detecta WebView ausente (una vez) y
 * registra resultados inesperados de evaluateJavascript.
 */
object JsRun {
    fun run(app: TubeApp, tag: String, js: String) {
        val wv: WebView? = app.activeWebView
        if (wv == null) {
            if (!app.wwNullLogged) {
                app.wwNullLogged = true
                app.addDiag(tag, "webview-null")
            }
            return
        }
        app.wwNullLogged = false
        Handler(Looper.getMainLooper()).post {
            try {
                wv.evaluateJavascript(js) { result ->
                    if (!result.isNullOrEmpty() && result != "null") {
                        app.addDiag(tag, "result=$result")
                    }
                }
            } catch (e: Exception) {
                app.addDiag(tag, "ex=${e.message ?: "?"}")
            }
        }
    }
}
