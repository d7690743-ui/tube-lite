package com.nexos.tubelite

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.ServiceInfo
import android.media.MediaMetadata
import android.media.session.MediaSession
import android.media.session.PlaybackState
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.os.PowerManager

/**
 * Segundo plano forzado: foreground inmediato con tipo mediaPlayback,
 * STICKY (el sistema lo recrea si lo mata), wake lock parcial para que
 * la CPU no se duerma con pantalla apagada, y control play/pausa directo
 * sobre el WebView (vivo o aparcado). Sin AndroidX.
 */
class PlaybackService : Service() {

    private var session: MediaSession? = null
    private var wakeLock: PowerManager.WakeLock? = null
    private var playing = true
    private var trackTitle = ""
    private val channelId = "tubelite_playback"
    private val mainHandler = Handler(Looper.getMainLooper())

    private val stateReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            playing = intent.getBooleanExtra("playing", playing)
            val title = intent.getStringExtra("title")
            if (!title.isNullOrEmpty()) trackTitle = title
            session?.updateState()
            session?.updateMeta()
            refreshNotification()
        }
    }

    override fun onCreate() {
        super.onCreate()
        createChannel()
        val pm = getSystemService(Context.POWER_SERVICE) as PowerManager
        wakeLock = pm.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "TubeLite:audio").apply {
            try {
                acquire()
            } catch (_: Exception) {
            }
        }
        session = MediaSession(this, "TubeLite").apply {
            setCallback(object : MediaSession.Callback() {
                override fun onPlay() {
                    sendToggle()
                }

                override fun onPause() {
                    sendToggle()
                }
            })
            isActive = true
            updateState()
            updateMeta()
        }
        val filter = IntentFilter(ACTION_STATE)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            registerReceiver(stateReceiver, filter, RECEIVER_NOT_EXPORTED)
        } else {
            @Suppress("DEPRECATION")
            registerReceiver(stateReceiver, filter)
        }
        startFg()
        mainHandler.postDelayed(watchdog, 2000)
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_STOP -> {
                try {
                    val app = application as TubeApp
                    app.parkedWebView?.destroy()
                    app.parkedWebView = null
                } catch (_: Exception) {
                }
                releaseWakeLock()
                stopForeground(STOP_FOREGROUND_REMOVE)
                stopSelf()
                return START_NOT_STICKY
            }
            ACTION_TOGGLE -> {
                playing = !playing
                session?.updateState()
                sendToggle()
                refreshNotification()
                return START_STICKY
            }
            else -> {
                startFg()
                return START_STICKY
            }
        }
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onDestroy() {
        try {
            mainHandler.removeCallbacks(watchdog)
        } catch (_: Exception) {
        }
        try {
            unregisterReceiver(stateReceiver)
        } catch (_: Exception) {
        }
        releaseWakeLock()
        session?.release()
        session = null
        super.onDestroy()
    }

    private fun startFg() {
        if (Build.VERSION.SDK_INT >= 29) {
            startForeground(NOTIFY_ID, buildNotification(), ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PLAYBACK)
        } else {
            startForeground(NOTIFY_ID, buildNotification())
        }
    }

    private fun releaseWakeLock() {
        try {
            val lock = wakeLock
            if (lock != null && lock.isHeld) lock.release()
        } catch (_: Exception) {
        }
        wakeLock = null
    }

    private fun MediaSession.updateState() {
        val state = if (playing) PlaybackState.STATE_PLAYING else PlaybackState.STATE_PAUSED
        setPlaybackState(
            PlaybackState.Builder()
                .setActions(PlaybackState.ACTION_PLAY or PlaybackState.ACTION_PAUSE or PlaybackState.ACTION_PLAY_PAUSE)
                .setState(state, 0L, 1.0f)
                .build()
        )
    }

    private fun MediaSession.updateMeta() {
        setMetadata(
            MediaMetadata.Builder()
                .putString(MediaMetadata.METADATA_KEY_TITLE, trackTitle.ifEmpty { "TubeLite" })
                .putString(MediaMetadata.METADATA_KEY_ARTIST, "YouTube")
                .build()
        )
    }

    /** Play/pausa directo sobre el WebView, en el hilo UI. Funciona sin actividad. */
    private fun sendToggle() {
        mainHandler.post {
            try {
                (application as TubeApp).activeWebView?.evaluateJavascript(
                    "(function(){var v=document.querySelector('video');if(!v)return;if(v.paused){window.__wasPlaying=true;v.play();}else{window.__wasPlaying=false;v.pause();}})();",
                    null
                )
            } catch (_: Exception) {
            }
        }
    }

    /**
     * Vigilante nativo: cada 2 s reanuda si YouTube pausó solo. Los timers
     * del servicio no los frena el sistema como los de la página oculta.
     */
    private val watchdog = object : Runnable {
        override fun run() {
            try {
                (application as TubeApp).activeWebView?.evaluateJavascript(
                    "(function(){try{var v=document.querySelector('video');" +
                        "if(v&&v.paused&&window.__wasPlaying&&!document.querySelector('.ad-showing,.ytp-ad-playing')){v.play();}" +
                        "}catch(e){}})();",
                    null
                )
            } catch (_: Exception) {
            }
            mainHandler.postDelayed(this, 2000)
        }
    }

    private fun refreshNotification() {
        val nm = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        nm.notify(NOTIFY_ID, buildNotification())
    }

    private fun actionIntent(action: String): PendingIntent {
        val intent = Intent(this, PlaybackService::class.java).setAction(action)
        return PendingIntent.getService(this, action.hashCode(), intent, PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
    }

    @Suppress("DEPRECATION") // addAction(int,...): variante compatible con minSdk 24.
    private fun buildNotification(): Notification {
        val builder = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            Notification.Builder(this, channelId)
        } else {
            @Suppress("DEPRECATION")
            Notification.Builder(this)
        }
        val openApp = PendingIntent.getActivity(
            this, 0,
            Intent(this, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        val toggleIcon = if (playing) android.R.drawable.ic_media_pause else android.R.drawable.ic_media_play
        val toggleText = if (playing) "Pausar" else "Reproducir"
        builder.setSmallIcon(android.R.drawable.ic_media_play)
            .setContentTitle(if (trackTitle.isEmpty()) "TubeLite en segundo plano" else trackTitle)
            .setContentText(if (playing) "Reproduciendo" else "Pausado")
            .setContentIntent(openApp)
            .setOngoing(true)
            .setCategory(Notification.CATEGORY_TRANSPORT)
            .addAction(toggleIcon, toggleText, actionIntent(ACTION_TOGGLE))
            .addAction(android.R.drawable.ic_menu_close_clear_cancel, "Detener", actionIntent(ACTION_STOP))
        val token = session?.sessionToken
        if (token != null) {
            builder.setStyle(Notification.MediaStyle().setMediaSession(token).setShowActionsInCompactView(0))
        }
        return builder.build()
    }

    private fun createChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val nm = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
            nm.createNotificationChannel(NotificationChannel(channelId, "Reproducción", NotificationManager.IMPORTANCE_LOW))
        }
    }

    companion object {
        const val ACTION_TOGGLE = "com.nexos.tubelite.TOGGLE"
        const val ACTION_STOP = "com.nexos.tubelite.STOP"
        const val ACTION_STATE = "com.nexos.tubelite.STATE"
        const val NOTIFY_ID = 3001
    }
}
