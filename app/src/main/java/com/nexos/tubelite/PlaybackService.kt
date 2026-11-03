package com.nexos.tubelite

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.media.session.MediaSession
import android.media.session.PlaybackState
import android.os.Build
import android.os.IBinder

/**
 * Mantiene vivo el proceso para audio en segundo plano y expone
 * controles play/pausa en la notificación, bloqueo y Bluetooth.
 * Sin AndroidX: MediaSession y MediaStyle del framework.
 */
class PlaybackService : Service() {

    private var session: MediaSession? = null
    private var playing = true
    private val channelId = "tubelite_playback"

    override fun onCreate() {
        super.onCreate()
        createChannel()
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
        }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_STOP -> {
                stopForeground(STOP_FOREGROUND_REMOVE)
                stopSelf()
                return START_NOT_STICKY
            }
            ACTION_TOGGLE -> {
                playing = !playing
                session?.updateState()
                sendToggle()
                refreshNotification()
                return START_NOT_STICKY
            }
            else -> {
                startForeground(NOTIFY_ID, buildNotification())
                return START_NOT_STICKY
            }
        }
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onDestroy() {
        session?.release()
        session = null
        super.onDestroy()
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

    private fun sendToggle() {
        sendBroadcast(
            Intent(ACTION_CONTROL).setPackage(packageName).putExtra("cmd", "toggle")
        )
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
            this, 0, Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        val toggleIcon = if (playing) android.R.drawable.ic_media_pause else android.R.drawable.ic_media_play
        val toggleText = if (playing) "Pausar" else "Reproducir"
        builder.setSmallIcon(android.R.drawable.ic_media_play)
            .setContentTitle("TubeLite en segundo plano")
            .setContentText("Audio activo. Usa Pausar o vuelve a la app.")
            .setContentIntent(openApp)
            .setOngoing(true)
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
        const val ACTION_CONTROL = "com.nexos.tubelite.CONTROL"
        const val NOTIFY_ID = 3001
    }
}
