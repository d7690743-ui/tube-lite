package com.nexos.tubelite

import android.app.Activity
import android.os.Bundle
import android.view.View
import android.webkit.WebChromeClient
import android.webkit.WebResourceRequest
import android.webkit.WebResourceResponse
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.FrameLayout
import java.io.ByteArrayInputStream

class MainActivity : Activity() {

    private lateinit var webView: WebView
    private lateinit var fullscreenBox: FrameLayout
    private var customView: View? = null
    private var customCallback: WebChromeClient.CustomViewCallback? = null

    // Hosts de anuncios y rastreo: se responden vacíos. Los anuncios de video
    // de YouTube salen de los mismos hosts que el video, esos se saltan con JS.
    private val blockedHosts = setOf(
        "doubleclick.net",
        "googlesyndication.com",
        "google-analytics.com",
        "adservice.google.com",
        "ads.youtube.com"
    )

    private val skipJs = """
        (function() {
          function css() {
            try {
              var s = document.createElement('style');
              s.textContent = '.ytd-display-ad-renderer,#masthead-ad,ytd-ad-slot-renderer,.ytp-ad-overlay-container{display:none!important}';
              document.head.appendChild(s);
            } catch (e) {}
          }
          function skip() {
            try {
              var v = document.querySelector('video');
              var ad = document.querySelector('.ad-showing,.ytp-ad-playing');
              var btn = document.querySelector('.ytp-skip-ad-button,.ytp-ad-skip-button,.ytp-ad-skip-button-modern');
              if (btn) { btn.click(); }
              if (ad && v) { v.muted = true; try { v.playbackRate = 16; } catch (e) {} }
              else if (v && !document.querySelector('.ad-showing')) {
                if (v.playbackRate !== 1) { try { v.playbackRate = 1; } catch (e) {} }
                v.muted = false;
              }
            } catch (e) {}
          }
          css();
          setInterval(skip, 500);
        })();
    """.trimIndent()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        webView = findViewById(R.id.web)
        fullscreenBox = findViewById(R.id.fullscreen)

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
                val blocked = blockedHosts.any { h -> host == h || host.endsWith(".$h") }
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
                view?.evaluateJavascript(skipJs, null)
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

        if (savedInstanceState == null) {
            webView.loadUrl("https://m.youtube.com")
        }
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

    override fun onDestroy() {
        webView.destroy()
        super.onDestroy()
    }
}
