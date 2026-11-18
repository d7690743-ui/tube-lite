package com.nexos.tubelite.player

import android.content.Context
import android.content.Intent
import android.os.Build
import android.util.Log
import androidx.media3.session.DefaultMediaNotificationProvider
import androidx.media3.session.MediaSession
import androidx.media3.session.MediaSessionService

/**
 * Segundo plano del reproductor nativo (ExoPlayer).
 * MediaSessionService de Media3: notificación multimedia, lockscreen,
 * botones Bluetooth y audio focus gestionados por el framework.
 * Convive con PlaybackService (modo WebView): solo uno suena a la vez.
 */
class NativePlaybackService : MediaSessionService() {

    private var session: MediaSession? = null

    override fun onCreate() {
        super.onCreate()
        running = true
        try {
            PlaybackManager.init(this)
            val player = PlaybackManager.player
            if (player == null) {
                Log.e(TAG, "ExoPlayer nulo; deteniendo servicio nativo")
                stopSelf()
                return
            }
            session = MediaSession.Builder(this, player).build()
            setMediaNotificationProvider(
                DefaultMediaNotificationProvider(this).apply {
                    setSmallIcon(android.R.drawable.ic_media_play)
                }
            )
        } catch (e: Exception) {
            Log.e(TAG, "Error creando servicio nativo", e)
            stopSelf()
        }
    }

    override fun onGetSession(controllerInfo: MediaSession.ControllerInfo): MediaSession? = session

    override fun onTaskRemoved(rootIntent: Intent?) {
        // Barrido de recientes con audio sonando (o cargando): NO detener,
        // igual que PlaybackService. Pausado: comportamiento base (stop).
        if (PlaybackManager.isPlaying || PlaybackManager.isLoadingTrack) return
        super.onTaskRemoved(rootIntent)
    }

    override fun onDestroy() {
        running = false
        try {
            session?.release()
        } catch (_: Exception) {
        }
        session = null
        super.onDestroy()
    }

    companion object {
        private const val TAG = "NativePlaybackService"

        /** true mientras el servicio vive; evita arranques FGS prohibidos en fondo. */
        @Volatile
        var running = false
            private set

        /**
         * Arranca o actualiza el servicio. Si ya corre usa startService normal
         * (permitido en segundo plano); solo el primer arranque usa FGS.
         */
        fun start(context: Context) {
            try {
                val app = context.applicationContext
                val intent = Intent(app, NativePlaybackService::class.java)
                if (running || Build.VERSION.SDK_INT < 26) {
                    app.startService(intent)
                } else {
                    app.startForegroundService(intent)
                }
            } catch (e: Exception) {
                Log.w(TAG, "No se pudo arrancar servicio nativo: ${e.message}")
            }
        }

        fun stop(context: Context) {
            try {
                val app = context.applicationContext
                app.stopService(Intent(app, NativePlaybackService::class.java))
            } catch (_: Exception) {
            }
        }
    }
}
