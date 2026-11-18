package com.nexos.tubelite.ui

import android.app.Activity
import android.app.AlertDialog
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.View
import android.widget.Button
import android.widget.SeekBar
import android.widget.TextView
import android.widget.Toast
import androidx.media3.common.Player
import androidx.media3.ui.PlayerView
import com.nexos.tubelite.R
import com.nexos.tubelite.db.DownloadDatabase
import com.nexos.tubelite.db.FavoriteEntity
import com.nexos.tubelite.downloader.DownloadQueueManager
import com.nexos.tubelite.model.ResultType
import com.nexos.tubelite.player.NativePlaybackService
import com.nexos.tubelite.player.PlaybackManager
import com.nexos.tubelite.ui.views.AudioVisualizerView

/** Reproductor nativo: audio/video, cola, calidad, velocidad, sleep, EQ y visualizador. */
class NativePlayerActivity : Activity() {

    private lateinit var playerView: PlayerView
    private lateinit var visualizer: AudioVisualizerView
    private lateinit var titleView: TextView
    private lateinit var artistView: TextView
    private lateinit var posView: TextView
    private lateinit var durView: TextView
    private lateinit var seek: SeekBar
    private lateinit var playPause: Button
    private lateinit var repeatBtn: Button
    private lateinit var avBtn: Button
    private lateinit var qualityBtn: Button
    private lateinit var speedBtn: Button
    private lateinit var sleepBtn: Button
    private lateinit var silenceBtn: Button
    private lateinit var favBtn: Button
    private lateinit var queueBtn: Button
    private var seeking = false

    private val ui = Handler(Looper.getMainLooper())
    private val ticker = object : Runnable {
        override fun run() {
            try {
                refreshProgress()
            } catch (_: Exception) {
            }
            ui.postDelayed(this, 500)
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_native_player)
        NotifPermission.ensure(this)
        PlaybackManager.init(this)
        bind()
        try {
            playerView.player = PlaybackManager.player
        } catch (_: Exception) {
        }
        PlaybackManager.onTrackChangedListener = { refreshTrack() }
        PlaybackManager.onIsPlayingListener = { refreshPlayPause() }
        PlaybackManager.onLoadingStateListener = { refreshTrack() }
        refreshAll()
        ui.post(ticker)
    }

    override fun onDestroy() {
        ui.removeCallbacks(ticker)
        PlaybackManager.onTrackChangedListener = null
        PlaybackManager.onIsPlayingListener = null
        PlaybackManager.onLoadingStateListener = null
        try {
            playerView.player = null
        } catch (_: Exception) {
        }
        super.onDestroy()
    }

    override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<String>, grantResults: IntArray) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode == NotifPermission.CODE && !NotifPermission.has(this)) {
            Toast.makeText(this, "Sin permiso de notificaciones no hay segundo plano nativo", Toast.LENGTH_LONG).show()
        }
    }

    private fun bind() {
        playerView = findViewById(R.id.player_view)
        visualizer = findViewById(R.id.visualizer)
        titleView = findViewById(R.id.np_title)
        artistView = findViewById(R.id.np_artist)
        posView = findViewById(R.id.np_pos)
        durView = findViewById(R.id.np_dur)
        seek = findViewById(R.id.np_seek)
        playPause = findViewById(R.id.np_playpause)
        repeatBtn = findViewById(R.id.np_repeat)
        avBtn = findViewById(R.id.np_av)
        qualityBtn = findViewById(R.id.np_quality)
        speedBtn = findViewById(R.id.np_speed)
        sleepBtn = findViewById(R.id.np_sleep)
        silenceBtn = findViewById(R.id.np_silence)
        favBtn = findViewById(R.id.np_fav)
        queueBtn = findViewById(R.id.np_queue)

        seek.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(sb: SeekBar, progress: Int, fromUser: Boolean) {
                if (fromUser) posView.text = fmtTime(progress.toLong())
            }

            override fun onStartTrackingTouch(sb: SeekBar) {
                seeking = true
            }

            override fun onStopTrackingTouch(sb: SeekBar) {
                seeking = false
                PlaybackManager.seekTo(sb.progress.toLong())
            }
        })

        playPause.setOnClickListener {
            if (PlaybackManager.isPlaying) PlaybackManager.pause() else PlaybackManager.resume()
            refreshPlayPause()
        }
        findViewById<Button>(R.id.np_prev).setOnClickListener { PlaybackManager.playPrev() }
        findViewById<Button>(R.id.np_next).setOnClickListener { PlaybackManager.playNext() }
        repeatBtn.setOnClickListener {
            PlaybackManager.cycleRepeatMode()
            refreshRepeat()
        }
        avBtn.setOnClickListener { toggleAudioVideo() }
        qualityBtn.setOnClickListener { askQuality() }
        speedBtn.setOnClickListener { askSpeed() }
        sleepBtn.setOnClickListener { askSleep() }
        silenceBtn.setOnClickListener {
            PlaybackManager.setSkipSilence(!PlaybackManager.isSkipSilenceEnabled())
            refreshSilence()
        }
        findViewById<Button>(R.id.np_eq).setOnClickListener {
            EqualizerDialog.show(this)
        }
        favBtn.setOnClickListener { toggleFav() }
        findViewById<Button>(R.id.np_download).setOnClickListener { askDownload() }
        queueBtn.setOnClickListener { showQueue() }
    }

    private fun toggleAudioVideo() {
        if (PlaybackManager.getCurrentTrack() == null) {
            Toast.makeText(this, "Sin pista: busca algo primero", Toast.LENGTH_SHORT).show()
            return
        }
        val pos = PlaybackManager.getCurrentPositionMs()
        PlaybackManager.isVideoMode = !PlaybackManager.isVideoMode
        PlaybackManager.pendingSeekMs = pos
        PlaybackManager.loadCurrent()
        NativePlaybackService.start(this)
        refreshModeViews()
        refreshTrack()
    }

    private fun askQuality() {
        val bundle = PlaybackManager.currentVideoStreamsBundle
        if (!PlaybackManager.isVideo || bundle == null || bundle.formats.isEmpty()) {
            Toast.makeText(this, "Calidad solo en modo video", Toast.LENGTH_SHORT).show()
            return
        }
        val labels = mutableListOf("Auto")
        labels.addAll(bundle.formats.map { it.qualityLabel })
        val current = PlaybackManager.currentVideoResolution
        val checked = labels.indexOf(current).coerceAtLeast(0)
        AlertDialog.Builder(this)
            .setTitle("Calidad de video")
            .setSingleChoiceItems(labels.toTypedArray(), checked) { dlg, which ->
                PlaybackManager.switchVideoResolution(labels[which])
                dlg.dismiss()
            }
            .setNegativeButton("Cerrar", null)
            .show()
    }

    private fun askSpeed() {
        val speeds = floatArrayOf(0.5f, 0.75f, 1.0f, 1.25f, 1.5f, 2.0f)
        val labels = arrayOf("0.5x", "0.75x", "1x", "1.25x", "1.5x", "2x")
        val current = PlaybackManager.getPlaybackSpeed()
        var checked = 2
        speeds.forEachIndexed { i, s -> if (s == current) checked = i }
        AlertDialog.Builder(this)
            .setTitle("Velocidad")
            .setSingleChoiceItems(labels, checked) { dlg, which ->
                PlaybackManager.setPlaybackSpeed(speeds[which])
                refreshSpeed()
                dlg.dismiss()
            }
            .setNegativeButton("Cerrar", null)
            .show()
    }

    private fun askSleep() {
        val labels = arrayOf("Off", "15 min", "30 min", "60 min", "90 min")
        val values = intArrayOf(0, 15, 30, 60, 90)
        AlertDialog.Builder(this)
            .setTitle("Apagar en…")
            .setItems(labels) { dlg, which ->
                val m = values[which]
                if (m == 0) {
                    PlaybackManager.cancelSleepTimer()
                    Toast.makeText(this, "Sleep timer apagado", Toast.LENGTH_SHORT).show()
                } else {
                    PlaybackManager.startSleepTimer(m)
                    Toast.makeText(this, "Se apaga en $m min", Toast.LENGTH_SHORT).show()
                }
                refreshSleep()
                dlg.dismiss()
            }
            .setNegativeButton("Cerrar", null)
            .show()
    }

    private fun toggleFav() {
        val t = PlaybackManager.getCurrentTrack()
        if (t == null) {
            Toast.makeText(this, "Sin pista", Toast.LENGTH_SHORT).show()
            return
        }
        try {
            val dao = DownloadDatabase.getInstance(this).downloadDao()
            if (dao.isFavorite(t.id)) {
                dao.removeFavorite(t.id)
                Toast.makeText(this, "Quitado de favoritos", Toast.LENGTH_SHORT).show()
            } else {
                dao.addFavorite(
                    FavoriteEntity(
                        id = t.id,
                        title = t.title,
                        artist = t.artist,
                        thumbUrl = t.thumbUrl,
                        sourceUrl = t.audioUrl.ifEmpty { t.videoUrl },
                        type = t.type.name,
                        addedAt = System.currentTimeMillis()
                    )
                )
                Toast.makeText(this, "★ Favorito", Toast.LENGTH_SHORT).show()
            }
        } catch (e: Exception) {
            Toast.makeText(this, "Error: ${e.message}", Toast.LENGTH_SHORT).show()
        }
        refreshFav()
    }

    private fun askDownload() {
        val t = PlaybackManager.getCurrentTrack()
        if (t == null) {
            Toast.makeText(this, "Sin pista", Toast.LENGTH_SHORT).show()
            return
        }
        AlertDialog.Builder(this)
            .setTitle("Descargar")
            .setItems(arrayOf("Audio (M4A)", "Video (360p MP4)")) { dlg, which ->
                if (which == 0) {
                    DownloadQueueManager.enqueueAudioDownload(this, t)
                } else {
                    DownloadQueueManager.enqueueVideoDownload(this, t, "360p")
                }
                Toast.makeText(this, "Descarga encolada", Toast.LENGTH_SHORT).show()
                dlg.dismiss()
            }
            .setNegativeButton("Cerrar", null)
            .show()
    }

    private fun showQueue() {
        val q = PlaybackManager.queue
        if (q.isEmpty()) {
            Toast.makeText(this, "Cola vacía", Toast.LENGTH_SHORT).show()
            return
        }
        val labels = q.mapIndexed { i, t ->
            (if (i == PlaybackManager.currentIndex) "▶ " else "") + t.title
        }.toTypedArray()
        AlertDialog.Builder(this)
            .setTitle("Cola (${q.size})")
            .setItems(labels) { dlg, which ->
                PlaybackManager.playAt(which)
                dlg.dismiss()
            }
            .setNegativeButton("Cerrar", null)
            .show()
    }

    // ---------- Refresco ----------

    private fun refreshAll() {
        refreshTrack()
        refreshPlayPause()
        refreshRepeat()
        refreshModeViews()
        refreshSpeed()
        refreshSleep()
        refreshSilence()
        refreshFav()
        refreshProgress()
    }

    private fun refreshTrack() {
        ui.post {
            val t = PlaybackManager.getCurrentTrack()
            if (t == null) {
                titleView.text = "Sin pista — busca algo primero"
                artistView.text = ""
            } else {
                val loading = if (PlaybackManager.isLoadingTrack) " …" else ""
                titleView.text = t.title + loading
                artistView.text = t.artist
            }
            queueBtn.text = "Cola (${PlaybackManager.queue.size})"
            refreshFav()
            refreshModeViews()
        }
    }

    private fun refreshPlayPause() {
        ui.post {
            playPause.text = if (PlaybackManager.isPlaying) "⏸" else "▶"
        }
    }

    private fun refreshRepeat() {
        repeatBtn.text = when (PlaybackManager.getCurrentRepeatMode()) {
            Player.REPEAT_MODE_ALL -> "Rep: ALL"
            Player.REPEAT_MODE_ONE -> "Rep: ONE"
            else -> "Rep: OFF"
        }
    }

    private fun refreshModeViews() {
        val video = PlaybackManager.isVideoMode ||
            PlaybackManager.getCurrentTrack()?.type == ResultType.VIDEO
        playerView.visibility = if (video) View.VISIBLE else View.GONE
        visualizer.visibility = if (video) View.GONE else View.VISIBLE
        avBtn.text = if (PlaybackManager.isVideoMode) "Video" else "Audio"
        qualityBtn.isEnabled = video
    }

    private fun refreshSpeed() {
        val s = PlaybackManager.getPlaybackSpeed()
        speedBtn.text = if (s == s.toInt().toFloat()) "${s.toInt()}x" else "${s}x"
    }

    private fun refreshSleep() {
        val left = PlaybackManager.sleepTimerLeftMin()
        sleepBtn.text = if (left > 0) "Sleep: ${left}m" else "Sleep: OFF"
    }

    private fun refreshSilence() {
        silenceBtn.text = if (PlaybackManager.isSkipSilenceEnabled()) "Sil: ON" else "Sil: OFF"
    }

    private fun refreshFav() {
        ui.post {
            val t = PlaybackManager.getCurrentTrack()
            var fav = false
            if (t != null) {
                try {
                    fav = DownloadDatabase.getInstance(this).downloadDao().isFavorite(t.id)
                } catch (_: Exception) {
                }
            }
            favBtn.text = if (fav) "★" else "☆"
        }
    }

    private fun refreshProgress() {
        val dur = PlaybackManager.getDurationMs()
        val pos = PlaybackManager.getCurrentPositionMs()
        if (!seeking && dur > 0) {
            seek.max = dur.toInt()
            seek.progress = pos.toInt().coerceIn(0, dur.toInt())
        }
        posView.text = fmtTime(pos)
        durView.text = fmtTime(dur)
        refreshSleep()
    }

    private fun fmtTime(ms: Long): String {
        if (ms < 0) return "0:00"
        val total = ms / 1000
        val m = total / 60
        val s = total % 60
        return "$m:${if (s < 10) "0" else ""}$s"
    }
}
