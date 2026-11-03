package com.nexos.tubelite

import android.app.Activity
import android.app.PictureInPictureParams
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.content.res.Configuration
import android.os.Build
import android.os.Bundle
import android.view.View
import android.webkit.WebChromeClient
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

    private lateinit var webView: WebView
    private lateinit var fullscreenBox: FrameLayout
    private lateinit var controlsRow: LinearLayout
    private lateinit var bgButton: Button
    private var customView: View? = null
    private var customCallback: WebChromeClient.CustomViewCallback? = null

    private var bgOn = false
    private var pendingBg = false
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

    private val controlReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            if (intent.getStringExtra("cmd") == "toggle") {
                webView.evaluateJavascript(
                    "(function(){var v=document.querySelector('video');if(!v)return;if(v.paused){v.play();}else{v.pause();}})();",
                    null
                )
            }
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        loadCachedBlocklist()
        refreshBlocklist()

        webView = findViewById(R.id.web)
        fullscreenBox = findViewById(R.id.fullscreen)
        controlsRow = findViewById(R.id.controls)
        bgButton = findViewById(R.id.bg_toggle)

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

            override fun onPageFinished(view: WebView?, url: String?) {
                super.onPageFinished(view, url)
                view?.evaluateJavascript(skipJs(), null)
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

        bgOn = prefs().getBoolean("bg_on", false)
        updateBgButton()
        bgButton.setOnClickListener { toggleBg() }
        findViewById<Button>(R.id.pip).setOnClickListener { enterPip() }

        val filter = IntentFilter(PlaybackService.ACTION_CONTROL)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            registerReceiver(controlReceiver, filter, RECEIVER_NOT_EXPORTED)
        } else {
            @Suppress("DEPRECATION")
            registerReceiver(controlReceiver, filter)
        }

        if (savedInstanceState == null) {
            webView.loadUrl("https://m.youtube.com")
        }
    }

    override fun onDestroy() {
        try {
            unregisterReceiver(controlReceiver)
        } catch (_: Exception) {
        }
        webView.destroy()
        super.onDestroy()
    }

    @Suppress("DEPRECATION") // Activity sin AndroidX: única forma de manejar atrás.
    override fun onBackPressed() {
        if (customView != null) {
            webView.webChromeClient?.onHideCustomView()
        } else if (webView.canGoBack()) {
            webView.goBack()
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
        controlsRow.visibility = if (isInPictureInPictureMode) View.GONE else View.VISIBLE
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
        Toast.makeText(this, "Segundo plano activado: el audio sigue con la app cerrada", Toast.LENGTH_LONG).show()
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
            } catch (_: Exception) {
                // Sin red: se sigue con la lista integrada o la caché.
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
          var HIDE = '${b.hideCss}';
          var SKIP = '${b.skipButtons}';
          var MARK = '${b.adMarkers}';
          try {
            var s = document.createElement('style');
            s.textContent = HIDE + '{display:none!important}';
            document.head.appendChild(s);
          } catch (e) {}
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
              }
            } catch (e) {}
          }
          setInterval(skip, 500);
        })();
        """.trimIndent()
    }
}
