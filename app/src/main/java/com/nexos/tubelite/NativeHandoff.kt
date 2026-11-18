package com.nexos.tubelite

import android.content.Context
import android.content.Intent
import com.nexos.tubelite.extractor.YouTubeService
import com.nexos.tubelite.model.ResultType
import com.nexos.tubelite.model.SearchResult
import com.nexos.tubelite.model.Source
import com.nexos.tubelite.player.NativePlaybackService
import com.nexos.tubelite.player.PlaybackManager

/**
 * Traspaso WebView → reproductor nativo: toma el video actual del WebView y lo
 * sigue reproduciendo en ExoPlayer (solo audio), ahorrando batería y datos con
 * la pantalla apagada. El WebView se pausa para que no suene doble.
 */
object NativeHandoff {

    /** Video actual del WebView (id de 11 chars) o "" si no hay video. */
    fun currentVideoId(ctx: Context): String {
        val url = try {
            ctx.getSharedPreferences("tubelite", Context.MODE_PRIVATE)
                .getString("last_url", "") ?: ""
        } catch (_: Exception) {
            ""
        }
        val vid = YouTubeService.cleanVideoId(url)
        return if (vid.length == 11) vid else ""
    }

    fun trackFor(videoId: String, title: String): SearchResult {
        val watch = "https://www.youtube.com/watch?v=$videoId"
        return SearchResult(
            id = "yt_$videoId",
            title = title.ifEmpty { "Video de YouTube" },
            artist = "YouTube",
            source = Source.YOUTUBE,
            type = ResultType.TRACK,
            durationSec = 0,
            thumbUrl = "",
            audioUrl = watch,
            videoUrl = watch
        )
    }

    fun isNativeActive(): Boolean =
        NativePlaybackService.running || PlaybackManager.isPlaying

    /**
     * Pasa el video actual a audio nativo. [prevBgOn] indica si el modo WebView
     * en segundo plano estaba activo (para poder restaurarlo al volver).
     */
    fun toNative(ctx: Context, prevBgOn: Boolean, onDone: (() -> Unit)? = null) {
        val app = ctx.applicationContext
        val tubeApp = app as? TubeApp ?: return
        val vid = currentVideoId(ctx)
        if (vid.length != 11) return
        // Evita doble traspaso si ya suena nativo.
        if (NativePlaybackService.running && PlaybackManager.isPlaying) return

        // Posición actual del video en el WebView (mejor esfuerzo).
        JsRun.run(
            tubeApp, "handoff-pos",
            "(function(){try{var v=document.querySelector('video');return v?(v.currentTime||0):0;}catch(e){return 0;}})();",
            { raw ->
                val posMs = try {
                    ((raw?.trim()?.toDoubleOrNull() ?: 0.0) * 1000).toLong()
                } catch (_: Exception) {
                    0L
                }
                // Pausar el WebView para que no suene doble.
                JsRun.run(
                    tubeApp, "handoff-pause",
                    "(function(){try{var v=document.querySelector('video');" +
                        "if(v){window.__wasPlaying=false;v.pause();}}catch(e){}})();"
                )
                // Detener el servicio WebView: manda el nativo.
                try {
                    app.stopService(Intent(app, PlaybackService::class.java))
                } catch (_: Exception) {
                }
                try {
                    app.getSharedPreferences("tubelite", Context.MODE_PRIVATE).edit()
                        .putBoolean("bg_on", false)
                        .putBoolean("handoff_prev_bg", prevBgOn)
                        .apply()
                } catch (_: Exception) {
                }
                val track = trackFor(vid, tubeApp.lastTitle)
                try {
                    PlaybackManager.init(app)
                    PlaybackManager.isVideoMode = false
                    PlaybackManager.pendingSeekMs = posMs.coerceAtLeast(0L)
                    PlaybackManager.setQueue(listOf(track), 0)
                    NativePlaybackService.start(app)
                } catch (e: Exception) {
                    tubeApp.addDiag("handoff", "ex=${e.message ?: "?"}")
                }
                try {
                    onDone?.invoke()
                } catch (_: Exception) {
                }
            },
            true
        )
    }
}
