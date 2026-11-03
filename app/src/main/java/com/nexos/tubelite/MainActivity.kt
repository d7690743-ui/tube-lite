package com.nexos.tubelite

import android.app.Activity
import android.app.PictureInPictureParams
import android.content.Intent
import android.content.pm.PackageManager
import android.content.res.Configuration
import android.graphics.Bitmap
import android.os.Build
import android.os.Bundle
import android.view.View
import android.view.ViewGroup
import android.webkit.WebChromeClient
import android.webkit.RenderProcessGoneDetail
import android.webkit.WebResourceRequest
import android.webkit.WebResourceResponse
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.Button
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.Toast
import java.io.ByteArrayInputStream
import java.io.File
import java.net.HttpURLConnection
import java.net.URL
import org.json.JSONObject

/** Lista de bloqueo: la app trae una integrada y la actualiza desde el repo. */
private data class Blocklist(
    val hosts: Set<String>,
    val hideCss: String,
    val skipButtons: String,
    val adMarkers: String
)

class MainActivity : Activity() {

    private lateinit var webContainer: FrameLayout
    private lateinit var webView: WebView
    private lateinit var fullscreenBox: FrameLayout
    private lateinit var controlsRow: LinearLayout
    private lateinit var controlsToggle: Button
    private lateinit var bgButton: Button
    private var controlsOpen = false
    private var customView: View? = null
    private var customCallback: WebChromeClient.CustomViewCallback? = null

    private var bgOn = false
    private var pendingBg = false
    private var pausedByLifecycle = false
    private var pendingResume: Boolean? = null
    private val notifyPermCode = 3001

    private val defaultBlocklist = Blocklist(
        hosts = setOf(
            "doubleclick.net",
            "googlesyndication.com",
            "google-analytics.com",
            "adservice.google.com",
            "ads.youtube.com"
        ),
        hideCss = ".ytd-display-ad-renderer,#masthead-ad,ytd-ad-slot-renderer,.ytp-ad-overlay-container",
        skipButtons = ".ytp-skip-ad-button,.ytp-ad-skip-button,.ytp-ad-skip-button-modern",
        adMarkers = ".ad-showing,.ytp-ad-playing"
    )

    @Volatile private var blocklist = defaultBlocklist
    private val blocklistUrl = "https://raw.githubusercontent.com/d7690743-ui/tube-lite/main/blocklist.json"
    private val homeUrl = "https://m.youtube.com"

    // Se inyecta en onPageStarted, antes de que YouTube ate sus listeners.
    private val EARLY_JS =
        "(function(){try{" +
            "Object.defineProperty(document,'hidden',{value:false,configurable:true});" +
            "Object.defineProperty(document,'visibilityState',{value:'visible',configurable:true});" +
            "}catch(e){}})();"

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        loadCachedBlocklist()
        refreshBlocklist()

        webContainer = findViewById(R.id.web_container)
        fullscreenBox = findViewById(R.id.fullscreen)
        controlsRow = findViewById(R.id.controls)
        controlsToggle = findViewById(R.id.controls_toggle)
        controlsToggle.setOnClickListener { toggleControls() }
        bgButton = findViewById(R.id.bg_toggle)

        obtainWebView(savedInstanceState)

        bgOn = prefs().getBoolean("bg_on", false)
        updateBgButton()
        bgButton.setOnClickListener { toggleBg() }
        findViewById<Button>(R.id.pip).setOnClickListener { enterPip() }
        findViewById<Button>(R.id.diag).setOnClickListener {
            Toast.makeText(this, "Diag: " + (application as TubeApp).diagSummary(), Toast.LENGTH_LONG).show()
        }
    }

    /**
     * Reutiliza el WebView aparcado (audio intacto) o crea uno nuevo
     * con la última URL. El WebView se crea con contexto de aplicación
     * para poder sobrevivir a la actividad.
     */
    private fun obtainWebView(state: Bundle?) {
        val app = application as TubeApp
        val reused = app.parkedWebView
        app.parkedWebView = null
        if (reused != null) {
            webView = reused
            (webView.parent as? ViewGroup)?.removeView(webView)
            webContainer.addView(
                webView,
                FrameLayout.LayoutParams(
                    FrameLayout.LayoutParams.MATCH_PARENT,
                    FrameLayout.LayoutParams.MATCH_PARENT
                )
            )
            app.activeWebView = webView
            configureWebView()
        } else {
            attachFreshWebView()
            if (state == null) loadLastOrHome()
        }
    }

    /** Crea, anexa, configura y puentea un WebView nuevo. No carga URL. */
    private fun attachFreshWebView() {
        val app = application as TubeApp
        webView = WebView(applicationContext)
        webContainer.addView(
            webView,
            FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT,
                FrameLayout.LayoutParams.MATCH_PARENT
            )
        )
        app.activeWebView = webView
        configureWebView()
        // El puente persiste en la instancia: solo se agrega al crear.
        webView.addJavascriptInterface(TubeBridge(app), "TubeBridge")
    }

    private fun loadLastOrHome(withPosition: Boolean = false) {
        val last = prefs().getString("last_url", null)
        var target = if (!last.isNullOrEmpty()) last else homeUrl
        if (withPosition && !last.isNullOrEmpty()) {
            val (pos, dur) = (application as TubeApp).loadProgress()
            // Mejor esfuerzo: YouTube acepta ?t=Ns pero no se promete reanudar exacto.
            if (pos > 10 && dur > 0) {
                target = last + (if (last.contains("?")) "&" else "?") + "t=" + pos + "s"
            }
        }
        webView.loadUrl(target)
    }

    /** Renderer muerto: destruir el cadáver y reconstruir con última URL. */
    private fun recoverRenderer(gone: WebView?) {
        val app = application as TubeApp
        try {
            (gone?.parent as? ViewGroup)?.removeView(gone)
            gone?.destroy()
        } catch (_: Exception) {
        }
        if (app.parkedWebView === gone) app.parkedWebView = null
        if (app.activeWebView === gone) app.activeWebView = null
        if (isFinishing || isDestroyed) return
        // Respeta pausa manual: si estaba pausado, no reanudar tras recargar.
        pendingResume = app.lastPlaying
        attachFreshWebView()
        loadLastOrHome(withPosition = true)
        Toast.makeText(this, "Renderer recuperado (mejor esfuerzo)", Toast.LENGTH_SHORT).show()
    }

    private fun configureWebView() {
        webView.settings.javaScriptEnabled = true
        webView.settings.domStorageEnabled = true
        webView.settings.mediaPlaybackRequiresUserGesture = false
        webView.settings.loadWithOverviewMode = true
        webView.settings.useWideViewPort = true

        webView.webViewClient = object : WebViewClient() {
            override fun shouldOverrideUrlLoading(view: WebView?, request: WebResourceRequest?): Boolean {
                return false // todo se queda dentro del WebView
            }

            override fun shouldInterceptRequest(view: WebView?, request: WebResourceRequest?): WebResourceResponse? {
                val host = request?.url?.host ?: return super.shouldInterceptRequest(view, request)
                val blocked = blocklist.hosts.any { h -> host == h || host.endsWith(".$h") }
                if (blocked) {
                    return WebResourceResponse(
                        "text/plain", "utf-8", 200, "OK",
                        emptyMap<String, String>(), ByteArrayInputStream(ByteArray(0))
                    )
                }
                return super.shouldInterceptRequest(view, request)
            }

            override fun onPageStarted(view: WebView?, url: String?, favicon: Bitmap?) {
                super.onPageStarted(view, url, favicon)
                // Temprano: que YouTube no vea que la página se oculta.
                view?.evaluateJavascript(EARLY_JS, null)
            }

            override fun onPageFinished(view: WebView?, url: String?) {
                super.onPageFinished(view, url)
                if (!url.isNullOrEmpty()) {
                    prefs().edit().putString("last_url", url).apply()
                }
                val resume = pendingResume
                if (resume != null) {
                    pendingResume = null
                    view?.evaluateJavascript("(function(){window.__wasPlaying=" + resume + ";})();", null)
                }
                view?.evaluateJavascript(skipJs(), null)
            }

            override fun onRenderProcessGone(view: WebView?, detail: RenderProcessGoneDetail?): Boolean {
                val crashed = detail?.didCrash() == true
                (application as TubeApp).addDiag("renderer", "gone crashed=$crashed")
                runOnUiThread { recoverRenderer(view) }
                return true // la app lo maneja: no matar el proceso
            }
        }

        webView.webChromeClient = object : WebChromeClient() {
            override fun onShowCustomView(view: View?, callback: CustomViewCallback?) {
                if (customView != null) {
                    callback?.onCustomViewHidden()
                    return
                }
                customView = view
                customCallback = callback
                fullscreenBox.addView(view)
                fullscreenBox.visibility = View.VISIBLE
                webView.visibility = View.GONE
            }

            override fun onHideCustomView() {
                customView?.let { fullscreenBox.removeView(it) }
                customView = null
                fullscreenBox.visibility = View.GONE
                webView.visibility = View.VISIBLE
                customCallback?.onCustomViewHidden()
                customCallback = null
            }
        }
    }

    override fun onPause() {
        super.onPause()
        // En PiP el video debe seguir: no tocar nada.
        if (isInPictureInPictureMode) return
        // Con Fondo ON el servicio manda: no tocar nada.
        if (!bgOn && ::webView.isInitialized) {
            try {
                if ((application as TubeApp).lastPlaying) {
                    pausedByLifecycle = true
                    webView.evaluateJavascript("(function(){var v=document.querySelector('video');if(!v)return;window.__wasPlaying=false;v.pause();})();", null)
                }
                webView.onPause()
            } catch (e: Exception) {
                (application as TubeApp).addDiag("lifecycle", "onPause ex=${e.message ?: "?"}")
            }
        }
    }

    override fun onResume() {
        super.onResume()
        // Si el proceso murió y volvió, bgOn pudo quedar obsoleto: conciliar.
        if (bgOn && !(application as TubeApp).serviceAlive) {
            bgOn = false
            prefs().edit().putBoolean("bg_on", false).apply()
            updateBgButton()
        }
        if (::webView.isInitialized) {
            (application as TubeApp).activeWebView = webView
            if (!bgOn) {
                try {
                    webView.onResume()
                } catch (e: Exception) {
                    (application as TubeApp).addDiag("lifecycle", "onResume ex=${e.message ?: "?"}")
                }
                if (pausedByLifecycle) {
                    pausedByLifecycle = false
                    webView.evaluateJavascript("(function(){var v=document.querySelector('video');if(!v)return;window.__wasPlaying=true;v.play();})();", null)
                }
            }
        }
    }

    override fun onDestroy() {
        val app = application as TubeApp
        if (!::webView.isInitialized) {
            super.onDestroy()
            return
        }
        if (bgOn) {
            // Aparcar: el WebView (y el audio) sobrevive a la actividad.
            try {
                (webView.parent as? ViewGroup)?.removeView(webView)
                webView.webViewClient = WebViewClient()
                webView.webChromeClient = WebChromeClient()
                app.parkedWebView = webView
                app.activeWebView = webView
            } catch (_: Exception) {
                try {
                    webView.destroy()
                } catch (_: Exception) {
                }
            }
        } else {
            try {
                webView.destroy()
            } catch (_: Exception) {
            }
            app.activeWebView = null
        }
        super.onDestroy()
    }

    @Suppress("DEPRECATION") // Activity sin AndroidX: única forma de manejar atrás.
    override fun onBackPressed() {
        if (customView != null) {
            webView.webChromeClient?.onHideCustomView()
        } else if (webView.canGoBack()) {
            webView.goBack()
        } else if (bgOn) {
            // Con Fondo ON, atrás minimiza en vez de cerrar: el audio sigue.
            moveTaskToBack(true)
        } else {
            super.onBackPressed()
        }
    }

    override fun onUserLeaveHint() {
        super.onUserLeaveHint()
        // Saliendo con video en pantalla completa: PiP solo.
        if (customView != null && Build.VERSION.SDK_INT >= 26) {
            try {
                enterPictureInPictureMode(PictureInPictureParams.Builder().build())
            } catch (_: Exception) {
            }
        }
    }

    override fun onPictureInPictureModeChanged(isInPictureInPictureMode: Boolean, newConfig: Configuration) {
        super.onPictureInPictureModeChanged(isInPictureInPictureMode, newConfig)
        if (isInPictureInPictureMode) {
            controlsRow.visibility = View.GONE
            controlsToggle.visibility = View.GONE
        } else {
            controlsToggle.visibility = View.VISIBLE
            controlsRow.visibility = if (controlsOpen) View.VISIBLE else View.GONE
        }
    }

    /** Muestra/oculta la barra de controles sin tapar el buscador de YouTube. */
    private fun toggleControls() {
        controlsOpen = !controlsOpen
        controlsRow.visibility = if (controlsOpen) View.VISIBLE else View.GONE
    }

    private fun prefs() = getSharedPreferences("tubelite", MODE_PRIVATE)

    // ---------- Segundo plano ----------

    private fun toggleBg() {
        if (bgOn) {
            stopService(Intent(this, PlaybackService::class.java))
            bgOn = false
            prefs().edit().putBoolean("bg_on", false).apply()
            updateBgButton()
        } else {
            if (Build.VERSION.SDK_INT >= 33 &&
                checkSelfPermission(android.Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED
            ) {
                pendingBg = true
                requestPermissions(arrayOf(android.Manifest.permission.POST_NOTIFICATIONS), notifyPermCode)
            } else {
                startBg()
            }
        }
    }

    private fun startBg() {
        val intent = Intent(this, PlaybackService::class.java)
        if (Build.VERSION.SDK_INT >= 26) startForegroundService(intent) else startService(intent)
        bgOn = true
        prefs().edit().putBoolean("bg_on", true).apply()
        updateBgButton()
        Toast.makeText(this, "Fondo ON. Si tu marca lo corta al salir (Xiaomi/Oppo/Vivo), pon TubeLite sin restricciones de batería.", Toast.LENGTH_LONG).show()
    }

    private fun updateBgButton() {
        bgButton.text = if (bgOn) "Fondo: ON" else "Fondo: OFF"
    }

    override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<String>, grantResults: IntArray) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode == notifyPermCode) {
            val ok = grantResults.isNotEmpty() && grantResults[0] == PackageManager.PERMISSION_GRANTED
            if (ok && pendingBg) {
                startBg()
            } else if (!ok) {
                Toast.makeText(this, "Sin permiso de notificaciones no hay segundo plano", Toast.LENGTH_LONG).show()
            }
            pendingBg = false
        }
    }

    // ---------- Picture-in-Picture ----------

    private fun enterPip() {
        if (Build.VERSION.SDK_INT < 26) {
            Toast.makeText(this, "PiP requiere Android 8+", Toast.LENGTH_SHORT).show()
            return
        }
        try {
            enterPictureInPictureMode(PictureInPictureParams.Builder().build())
        } catch (_: Exception) {
            Toast.makeText(this, "No se pudo abrir PiP", Toast.LENGTH_SHORT).show()
        }
    }

    // ---------- Blocklist remota ----------

    private fun blocklistFile() = File(filesDir, "blocklist.json")

    private fun loadCachedBlocklist() {
        try {
            val f = blocklistFile()
            if (f.exists()) blocklist = parseBlocklist(f.readText(Charsets.UTF_8))
        } catch (_: Exception) {
        }
    }

    private fun refreshBlocklist() {
        Thread {
            try {
                val conn = (URL(blocklistUrl).openConnection() as HttpURLConnection).apply {
                    connectTimeout = 10_000
                    readTimeout = 10_000
                }
                val text = conn.inputStream.use { it.readBytes().toString(Charsets.UTF_8) }
                conn.disconnect()
                blocklist = parseBlocklist(text)
                try {
                    blocklistFile().writeText(text, Charsets.UTF_8)
                } catch (_: Exception) {
                }
            } catch (e: Exception) {
                // Sin red: se sigue con la lista integrada o la caché.
                (application as TubeApp).addDiag("blocklist", "fetch-fail: ${e.message ?: "?"}")
            }
        }.start()
    }

    private fun parseBlocklist(text: String): Blocklist {
        return try {
            val o = JSONObject(text)
            val hosts = mutableSetOf<String>()
            val arr = o.optJSONArray("hosts")
            if (arr != null) {
                for (i in 0 until arr.length()) {
                    val h = arr.optString(i)
                    if (h.isNotEmpty()) hosts.add(h)
                }
            }
            Blocklist(
                hosts = if (hosts.isEmpty()) defaultBlocklist.hosts else hosts,
                hideCss = o.optString("hideCss", defaultBlocklist.hideCss).ifEmpty { defaultBlocklist.hideCss },
                skipButtons = o.optString("skipButtons", defaultBlocklist.skipButtons).ifEmpty { defaultBlocklist.skipButtons },
                adMarkers = o.optString("adMarkers", defaultBlocklist.adMarkers).ifEmpty { defaultBlocklist.adMarkers }
            )
        } catch (_: Exception) {
            defaultBlocklist
        }
    }

    private fun skipJs(): String {
        val b = blocklist
        return """
        (function() {
          if (window.__tubeInit) return;
          window.__tubeInit = true;
          window.TubePlayback = {
            _v: function(){ return document.querySelector('video'); },
            _err: function(t, m){ try { if (window.TubeBridge) window.TubeBridge.onError(t, m); } catch (e) {} },
            play: function(){ var v = this._v(); if (!v) { this._err('play', 'no-video'); return; } if (v.paused) { window.__wasPlaying = true; try { v.play(); } catch (e) { this._err('play', String((e && e.message) || e)); } } },
            pause: function(){ var v = this._v(); if (!v) { this._err('pause', 'no-video'); return; } if (!v.paused) { window.__wasPlaying = false; try { v.pause(); } catch (e) { this._err('pause', String((e && e.message) || e)); } } },
            toggle: function(){ var v = this._v(); if (!v) { this._err('toggle', 'no-video'); return; } if (v.paused) { window.__wasPlaying = true; v.play(); } else { window.__wasPlaying = false; v.pause(); } },
            next: function(){ var x = document.querySelector('.ytp-next-button'); if (x) { x.click(); } else { this._err('next', 'no-button'); } },
            prev: function(){ var x = document.querySelector('.ytp-prev-button'); if (x) { x.click(); } else { this._err('prev', 'no-button'); } },
            getState: function(){ var v = this._v(); return JSON.stringify({hasVideo: !!v, playing: v ? !v.paused : false, was: !!window.__wasPlaying}); }
          };
          var HIDE = '${b.hideCss}';
          var SKIP = '${b.skipButtons}';
          var MARK = '${b.adMarkers}';
          var lastRep = '';
          if (window.__wasPlaying === undefined) window.__wasPlaying = true;
          try {
            // YouTube pausa al ocultar la página: decirle siempre visible.
            Object.defineProperty(document, 'hidden', {value: false, configurable: true});
            Object.defineProperty(document, 'visibilityState', {value: 'visible', configurable: true});
            document.hasFocus = function() { return true; };
            window.addEventListener('visibilitychange', function(e){ e.stopImmediatePropagation(); }, true);
            document.addEventListener('visibilitychange', function(e){ e.stopImmediatePropagation(); }, true);
          } catch (e) {}
          // Pausa por tap del usuario (con click previo) se respeta; las demás se reanudan.
          var clickT = 0;
          document.addEventListener('click', function(){ clickT = Date.now(); }, true);
          document.addEventListener('pause', function(){ try { if (Date.now() - clickT < 800) window.__wasPlaying = false; } catch (e) {} }, true);
          document.addEventListener('play', function(){ try { window.__wasPlaying = true; } catch (e) {} }, true);
          try {
            var s = document.createElement('style');
            s.textContent = HIDE + '{display:none!important}';
            document.head.appendChild(s);
          } catch (e) {}
          function report() {
            try {
              if (!window.TubeBridge) return;
              var v = document.querySelector('video');
              var playing = v ? !v.paused : false;
              var t = (document.title || '').substring(0, 80);
              var key = (playing ? '1' : '0') + '|' + t;
              if (key !== lastRep) { lastRep = key; TubeBridge.onState(playing, t); }
              if (v && playing) { try { TubeBridge.onProgress(v.currentTime || 0, v.duration || 0); } catch (_) {} }
            } catch (e) { try { if (window.TubeBridge) TubeBridge.onError('report', String((e && e.message) || e)); } catch (_) {} }
          }
          function skip() {
            try {
              var v = document.querySelector('video');
              var ad = MARK.split(',').some(function(sel){ return document.querySelector(sel); });
              var btn = SKIP.split(',').map(function(sel){ return document.querySelector(sel); }).find(function(x){ return x; });
              if (btn) { btn.click(); }
              if (ad && v) { v.muted = true; try { v.playbackRate = 16; } catch (e) {} }
              else if (v && !ad) {
                try { if (v.playbackRate !== 1) v.playbackRate = 1; } catch (e) {}
                v.muted = false;
                if (v.paused && window.__wasPlaying) { try { v.play(); } catch (e) {} }
              }
            } catch (e) { try { if (window.TubeBridge) TubeBridge.onError('skip', String((e && e.message) || e)); } catch (_) {} }
            report();
          }
          setInterval(skip, 500);
          skip();
        })();
        """.trimIndent()
    }
}
