/* Portado de mUzVidX (https:\/\/github.com\/Spartan117x\/mUzVidX) bajo GPL-3.0. */
package com.nexos.tubelite.player

import android.content.Context
import android.net.Uri
import android.util.Log
import android.util.LruCache
import androidx.media3.common.AudioAttributes
import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.common.MediaMetadata
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.database.StandaloneDatabaseProvider
import androidx.media3.datasource.DataSource
import androidx.media3.datasource.DefaultDataSource
import androidx.media3.datasource.DefaultHttpDataSource
import androidx.media3.datasource.cache.CacheDataSource
import androidx.media3.datasource.cache.LeastRecentlyUsedCacheEvictor
import androidx.media3.datasource.cache.SimpleCache
import androidx.media3.exoplayer.DefaultLoadControl
import androidx.media3.exoplayer.DefaultRenderersFactory
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.audio.AudioSink
import androidx.media3.exoplayer.audio.DefaultAudioSink
import androidx.media3.exoplayer.source.DefaultMediaSourceFactory
import androidx.media3.exoplayer.source.MergingMediaSource
import androidx.media3.exoplayer.source.ProgressiveMediaSource
import com.nexos.tubelite.TubeApp
import com.nexos.tubelite.extractor.TrackRetry
import com.nexos.tubelite.extractor.VideoStreamsBundle
import com.nexos.tubelite.extractor.YouTubeService
import com.nexos.tubelite.extractor.selectVideoFormat
import com.nexos.tubelite.extractor.videoSizeForQuality
import com.nexos.tubelite.model.ResultType
import com.nexos.tubelite.model.SearchResult
import com.nexos.tubelite.ui.views.RealtimeVisualizerAudioProcessor
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File

object PlaybackManager {
    private const val TAG = "PlaybackManager"
    private const val USER_AGENT_ANDROID =
        "com.google.android.youtube/21.03.36(Linux; U; Android 16; en_US; SM-S908E Build/TP1A.220624.014) gzip"

    var player: ExoPlayer? = null
    var queue: MutableList<SearchResult> = mutableListOf()
    var currentIndex: Int = 0
    var isContinuous: Boolean = true
    var isVideoMode: Boolean = false // false = Audio (carátula), true = Video (reproductor MP4)
    var isLoadingTrack: Boolean = false

    private var appContext: Context? = null
    private val scope = CoroutineScope(Dispatchers.Main + Job())
    private var loadJob: Job? = null
    val streamCache = LruCache<String, String>(60)

    val isPlaying: Boolean get() = player?.isPlaying == true
    val isVideo: Boolean get() = isVideoMode || (getCurrentTrack()?.type == ResultType.VIDEO)

    var onTrackChangedListener: ((SearchResult?) -> Unit)? = null
    var onLoadingStateListener: ((Boolean) -> Unit)? = null
    var onResolutionChangedListener: ((String) -> Unit)? = null
    var onIsPlayingListener: ((Boolean) -> Unit)? = null

    var currentVideoResolution: String = "Auto"
    var pendingSeekMs: Long = 0L
    var currentVideoStreamsBundle: VideoStreamsBundle? = null
    val visualizerAudioProcessor = RealtimeVisualizerAudioProcessor()
    private val trackRetry = TrackRetry()

    fun init(context: Context) {
        if (player != null) return
        val app = context.applicationContext
        appContext = app

        // 1. Fábrica de datos con caché en disco: CRUCIAL para reproducir archivos
        // locales (file://) Y streams remotos (http/https). Lo remoto pasa por
        // HTTP con UA de YouTube y se guarda en caché (ver cachedDataSourceFactory).
        val defaultDataSourceFactory = DefaultDataSource.Factory(app, cachedDataSourceFactory(app))

        val mediaSourceFactory = DefaultMediaSourceFactory(app)
            .setDataSourceFactory(defaultDataSourceFactory)

        // 3. RenderersFactory con AudioProcessor para análisis espectral en tiempo real (PCM direct sink)
        val renderersFactory = object : DefaultRenderersFactory(app) {
            override fun buildAudioSink(
                context: Context,
                enableFloatOutput: Boolean,
                enableAudioTrackPlaybackParams: Boolean
            ): AudioSink {
                return DefaultAudioSink.Builder(context)
                    .setAudioProcessors(arrayOf(visualizerAudioProcessor))
                    .build()
            }
        }

        player = ExoPlayer.Builder(app, renderersFactory)
            .setMediaSourceFactory(mediaSourceFactory)
            // Búfer de carga: arranca con 1.5 s, guarda hasta 60 s para aguantar
            // cortes de red y prioriza tiempo sobre bytes en redes lentas.
            .setLoadControl(
                DefaultLoadControl.Builder()
                    .setBufferDurationsMs(15_000, 60_000, 1_500, 3_000)
                    .setPrioritizeTimeOverSizeThresholds(true)
                    .build()
            )
            .setWakeMode(C.WAKE_MODE_NETWORK)
            .setAudioAttributes(
                AudioAttributes.Builder()
                    .setUsage(C.USAGE_MEDIA)
                    .setContentType(C.AUDIO_CONTENT_TYPE_MUSIC)
                    .build(),
                true
            )
            .setHandleAudioBecomingNoisy(true)
            .build().apply {
                playWhenReady = true
                addListener(object : Player.Listener {
                    override fun onPlaybackStateChanged(state: Int) {
                        when (state) {
                            Player.STATE_BUFFERING -> {
                                isLoadingTrack = true
                                onLoadingStateListener?.invoke(true)
                            }
                            Player.STATE_READY -> {
                                isLoadingTrack = false
                                onLoadingStateListener?.invoke(false)
                                trackRetry.onRecovered()
                                val sessionId = player?.audioSessionId ?: 0
                                if (sessionId != 0) {
                                    AudioEqualizerManager.init(app, sessionId)
                                }
                                val pendingSeek = pendingSeekMs
                                if (pendingSeek > 0) {
                                    pendingSeekMs = 0L
                                    try { player?.seekTo(pendingSeek) } catch (_: Exception) { }
                                }
                            }
                            Player.STATE_ENDED -> {
                                isLoadingTrack = false
                                onLoadingStateListener?.invoke(false)
                                Log.i(TAG, "Track finalizado. isContinuous=$isContinuous, repeatMode=$repeatMode")
                                if (repeatMode == Player.REPEAT_MODE_ONE) {
                                    seekTo(0)
                                    play()
                                } else if (isContinuous && queue.isNotEmpty()) {
                                    playNext()
                                }
                            }
                            Player.STATE_IDLE -> {
                                isLoadingTrack = false
                                onLoadingStateListener?.invoke(false)
                            }
                        }
                    }

                    override fun onPlayerError(error: PlaybackException) {
                        isLoadingTrack = false
                        onLoadingStateListener?.invoke(false)
                        Log.e(TAG, "Error en ExoPlayer: ${error.errorCodeName} - ${error.message}", error)
                        try {
                            (appContext as? TubeApp)?.addDiag("exo", "err=${error.errorCodeName}")
                        } catch (_: Exception) {
                        }
                        // Auto-recuperación: reintenta la pista con URL fresca (las de
                        // YouTube caducan) y solo si repite el fallo pasa a la siguiente.
                        val track = getCurrentTrack()
                        val key = track?.id ?: currentIndex.toString()
                        if (track != null && trackRetry.shouldRetry(key)) {
                            scope.launch {
                                delay(1500)
                                try {
                                    streamCache.remove(cleanVideoId(rawUrlFor(track)))
                                } catch (_: Exception) {
                                }
                                currentVideoStreamsBundle = null
                                loadCurrent()
                            }
                        } else if (isContinuous && queue.size > 1) {
                            scope.launch {
                                delay(1200)
                                playNext()
                            }
                        } else {
                            // Sin a dónde ir: estado parado honesto, sin notificación colgada.
                            try {
                                player?.stop()
                            } catch (_: Exception) {
                            }
                            appContext?.let { NativePlaybackService.stop(it) }
                            onTrackChangedListener?.invoke(track)
                        }
                    }

                    override fun onAudioSessionIdChanged(audioSessionId: Int) {
                        if (audioSessionId != 0) {
                            AudioEqualizerManager.init(app, audioSessionId)
                        }
                    }

                    override fun onIsPlayingChanged(isPlaying: Boolean) {
                        onIsPlayingListener?.invoke(isPlaying)
                        appContext?.let { ctx ->
                            NativePlaybackService.start(ctx)
                        }
                    }
                })
            }

        if (queue.isNotEmpty()) {
            loadCurrent()
        }
    }

    // ---------- Búfer y caché en disco ----------

    private var mediaCache: SimpleCache? = null

    /** Caché LRU de 200 MB en cacheDir: lo ya visto no se descarga de nuevo. */
    @Synchronized
    private fun cache(app: Context): SimpleCache {
        mediaCache?.let { return it }
        return SimpleCache(
            File(app.cacheDir, "tubelite-media"),
            LeastRecentlyUsedCacheEvictor(200L * 1024 * 1024),
            StandaloneDatabaseProvider(app)
        ).also { mediaCache = it }
    }

    /** HTTP con UA de YouTube envuelto en caché (ignora caché corrupta). */
    private fun cachedDataSourceFactory(app: Context): DataSource.Factory {
        val http = DefaultHttpDataSource.Factory()
            .setUserAgent(USER_AGENT_ANDROID)
            .setConnectTimeoutMs(10000)
            .setReadTimeoutMs(20000)
            .setAllowCrossProtocolRedirects(true)
        return CacheDataSource.Factory()
            .setCache(cache(app))
            .setUpstreamDataSourceFactory(http)
            .setFlags(CacheDataSource.FLAG_IGNORE_CACHE_ON_ERROR)
    }

    /**
     * Construye el MediaItem con metadatos (titulo/artista) para que la
     * notificacion, el lockscreen y los controles Bluetooth muestren la pista real.
     */
    private fun mediaItemFor(uri: Uri): MediaItem {
        val t = getCurrentTrack()
        val md = MediaMetadata.Builder()
            .setTitle(t?.title ?: "TubeLite")
            .setArtist(t?.artist ?: "YouTube")
            .build()
        return MediaItem.Builder().setUri(uri).setMediaMetadata(md).build()
    }

    fun setQueue(newQueue: List<SearchResult>, startIndex: Int = 0) {
        queue.clear()
        queue.addAll(newQueue)
        currentIndex = startIndex.coerceIn(0, (queue.size - 1).coerceAtLeast(0))
        val current = getCurrentTrack()
        if (current != null) {
            isVideoMode = (current.type == ResultType.VIDEO)
        }
        loadCurrent()
    }

    fun playNext() {
        if (queue.isEmpty()) return
        currentIndex = (currentIndex + 1) % queue.size
        loadCurrent()
    }

    fun playPrev() {
        if (queue.isEmpty()) return
        currentIndex = (currentIndex - 1 + queue.size) % queue.size
        loadCurrent()
    }

    fun pause() {
        player?.pause()
        player?.playWhenReady = false
        appContext?.let { ctx ->
            NativePlaybackService.start(ctx)
        }
    }

    fun resume() {
        player?.play()
        player?.playWhenReady = true
        appContext?.let { ctx ->
            NativePlaybackService.start(ctx)
        }
    }

    fun release() {
        cancelSleepTimer()
        loadJob?.cancel()
        AudioEqualizerManager.release()
        player?.stop()
        player?.release()
        player = null
        queue.clear()
        currentIndex = 0
    }

    fun toggleContinuous(checked: Boolean) {
        isContinuous = checked
    }

    fun cycleRepeatMode(): Int {
        val p = player ?: return Player.REPEAT_MODE_OFF
        val nextMode = when (p.repeatMode) {
            Player.REPEAT_MODE_OFF -> Player.REPEAT_MODE_ALL
            Player.REPEAT_MODE_ALL -> Player.REPEAT_MODE_ONE
            else -> Player.REPEAT_MODE_OFF
        }
        p.repeatMode = nextMode
        return nextMode
    }

    fun getCurrentRepeatMode(): Int = player?.repeatMode ?: Player.REPEAT_MODE_OFF

    fun getCurrentPositionMs(): Long = player?.currentPosition ?: 0L
    fun getDurationMs(): Long = player?.duration ?: 0L
    fun seekTo(ms: Long) {
        player?.seekTo(ms)
    }

    fun playAt(index: Int) {
        if (queue.isEmpty()) return
        currentIndex = index.coerceIn(0, queue.size - 1)
        loadCurrent()
    }

    // ---------- Velocidad, silencios y sleep timer (agregado TubeLite) ----------

    fun setPlaybackSpeed(speed: Float) {
        try {
            player?.setPlaybackSpeed(speed.coerceIn(0.25f, 3.0f))
        } catch (e: Exception) {
            Log.e(TAG, "Error ajustando velocidad", e)
        }
    }

    fun getPlaybackSpeed(): Float = try {
        player?.playbackParameters?.speed ?: 1.0f
    } catch (e: Exception) {
        1.0f
    }

    fun setSkipSilence(enabled: Boolean) {
        try {
            player?.skipSilenceEnabled = enabled
        } catch (e: Exception) {
            Log.e(TAG, "Error ajustando salto de silencios", e)
        }
    }

    fun isSkipSilenceEnabled(): Boolean = try {
        player?.skipSilenceEnabled == true
    } catch (e: Exception) {
        false
    }

    private var sleepTimerJob: Job? = null
    private var sleepTimerEndMs: Long = 0L

    /** Apaga la reproduccion tras [minutes] minutos. 0 o negativo = cancelar. */
    fun startSleepTimer(minutes: Int) {
        cancelSleepTimer()
        if (minutes <= 0) return
        sleepTimerEndMs = System.currentTimeMillis() + minutes * 60_000L
        sleepTimerJob = scope.launch {
            delay(minutes * 60_000L)
            sleepTimerEndMs = 0L
            pause()
        }
    }

    fun cancelSleepTimer() {
        sleepTimerJob?.cancel()
        sleepTimerJob = null
        sleepTimerEndMs = 0L
    }

    /** Minutos restantes del sleep timer (0 = inactivo). */
    fun sleepTimerLeftMin(): Long {
        if (sleepTimerEndMs == 0L) return 0
        return ((sleepTimerEndMs - System.currentTimeMillis()) / 60_000L).coerceAtLeast(0)
    }

    fun getCurrentTrack(): SearchResult? =
        if (queue.isEmpty()) null else queue.getOrNull(currentIndex)

    fun cleanVideoId(rawUrl: String): String {
        return if (rawUrl.contains("v=")) {
            rawUrl.substringAfter("v=").substringBefore("&")
        } else if (rawUrl.contains("youtu.be/")) {
            rawUrl.substringAfter("youtu.be/").substringBefore("?").substringBefore("&")
        } else {
            rawUrl.removePrefix("yt_").removePrefix("yt_vid_")
        }
    }

    /**
     * Cambia el stream de reproducción en caliente manteniendo la posición actual (útil al cambiar de Audio a Video)
     */
    fun switchMediaStream(rawUrl: String, startPosMs: Long = 0L) {
        loadJob?.cancel()
        isLoadingTrack = true
        onLoadingStateListener?.invoke(true)
        loadJob = scope.launch {
            val streamUrl: String? = if (rawUrl.startsWith("/") || rawUrl.startsWith("file://")) {
                rawUrl
            } else if (rawUrl.contains("youtube.com") || rawUrl.contains("youtu.be") || rawUrl.startsWith("yt_") || rawUrl.length == 11) {
                val vid = cleanVideoId(rawUrl)
                val cached = streamCache.get(vid)
                if (!cached.isNullOrEmpty()) {
                    cached
                } else {
                    val resolved = withContext(Dispatchers.IO) {
                        YouTubeService().resolveStreamUrl(vid, isAudioOnly = false)
                    }
                    if (!resolved.isNullOrEmpty()) {
                        streamCache.put(vid, resolved)
                        resolved
                    } else null
                }
            } else rawUrl

            if (!streamUrl.isNullOrEmpty()) {
                val uri = if (streamUrl.startsWith("/")) Uri.fromFile(File(streamUrl)) else Uri.parse(streamUrl)
                try {
                    player?.setMediaItem(mediaItemFor(uri), startPosMs)
                    player?.prepare()
                    player?.play()
                } catch (e: Exception) {
                    Log.e(TAG, "Error switching media stream", e)
                }
            }
            isLoadingTrack = false
            onLoadingStateListener?.invoke(false)
        }
    }

    fun switchVideoResolution(quality: String) {
        currentVideoResolution = quality
        onResolutionChangedListener?.invoke(quality)

        val track = getCurrentTrack() ?: return
        if (!isVideo) return
        val rawUrl = track.videoUrl.ifEmpty { track.audioUrl }
        val currentPos = getCurrentPositionMs()

        if (rawUrl.startsWith("/") || rawUrl.startsWith("file://")) {
            return
        }

        val bundle = currentVideoStreamsBundle
        if (bundle != null) {
            applyVideoStreamsToPlayer(bundle, quality, currentPos)
        } else {
            val vid = cleanVideoId(rawUrl)
            loadJob?.cancel()
            isLoadingTrack = true
            onLoadingStateListener?.invoke(true)
            loadJob = scope.launch {
                try {
                    val resolvedBundle = withContext(Dispatchers.IO) {
                        YouTubeService().resolveVideoStreams(vid)
                    }
                    if (resolvedBundle != null) {
                        currentVideoStreamsBundle = resolvedBundle
                        applyVideoStreamsToPlayer(resolvedBundle, quality, currentPos)
                    }
                } catch (e: Exception) {
                    Log.e(TAG, "Error switching video resolution: ${e.message}", e)
                } finally {
                    isLoadingTrack = false
                    onLoadingStateListener?.invoke(false)
                }
            }
        }
    }

    private fun applyVideoStreamsToPlayer(bundle: VideoStreamsBundle, quality: String, startPosMs: Long) {
        val p = player ?: return
        val app = appContext ?: return
        val dsFactory = cachedDataSourceFactory(app)

        try {
            // Caso 1: Manifiesto HLS disponible
            if (!bundle.hlsManifestUrl.isNullOrEmpty()) {
                val size = videoSizeForQuality(quality)
                p.trackSelectionParameters = if (size == null) {
                    p.trackSelectionParameters.buildUpon()
                        .clearVideoSizeConstraints()
                        .build()
                } else {
                    p.trackSelectionParameters.buildUpon()
                        .setMaxVideoSize(size.first, size.second)
                        .setMinVideoSize(size.first, size.second)
                        .build()
                }
                Log.i(TAG, "Aplicando HLS en ExoPlayer (calidad=$quality): ${bundle.hlsManifestUrl}")
                p.setMediaItem(mediaItemFor(Uri.parse(bundle.hlsManifestUrl)), startPosMs)
                p.prepare()
                p.play()
                return
            }

            // Caso 2: Formatos directos HTTP (1080p, 720p, 480p, 360p)
            val selectedFormat = selectVideoFormat(bundle.formats, quality)

            if (selectedFormat != null) {
                if (selectedFormat.isProgressive || selectedFormat.audioUrl.isNullOrEmpty()) {
                    Log.i(TAG, "Cargando video progresivo directo ($quality): ${selectedFormat.videoUrl}")
                    p.setMediaItem(mediaItemFor(Uri.parse(selectedFormat.videoUrl)), startPosMs)
                    p.prepare()
                    p.play()
                } else {
                    Log.i(TAG, "Cargando MergingMediaSource video ($quality) + audio AAC")
                    val videoSource = ProgressiveMediaSource.Factory(dsFactory)
                        .createMediaSource(MediaItem.fromUri(Uri.parse(selectedFormat.videoUrl)))
                    val audioSource = ProgressiveMediaSource.Factory(dsFactory)
                        .createMediaSource(MediaItem.fromUri(Uri.parse(selectedFormat.audioUrl)))
                    val merged = MergingMediaSource(videoSource, audioSource)

                    p.setMediaSource(merged, startPosMs)
                    p.prepare()
                    p.play()
                }
            } else {
                Log.i(TAG, "Cargando default stream: ${bundle.defaultStreamUrl}")
                p.setMediaItem(mediaItemFor(Uri.parse(bundle.defaultStreamUrl)), startPosMs)
                p.prepare()
                p.play()
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error aplicando stream de video a ExoPlayer", e)
        }
    }

    private fun rawUrlFor(track: SearchResult): String =
        if (isVideoMode) track.videoUrl.ifEmpty { track.audioUrl } else track.audioUrl.ifEmpty { track.videoUrl }

    fun loadCurrent() {
        val track = getCurrentTrack() ?: return
        val rawUrl = rawUrlFor(track)
        onTrackChangedListener?.invoke(track)

        appContext?.let { ctx ->
            NativePlaybackService.start(ctx)
        }

        // 1. Detener reproducción anterior de inmediato para evitar que se quede pegada
        player?.stop()
        isLoadingTrack = true
        onLoadingStateListener?.invoke(true)

        loadJob?.cancel()
        loadJob = scope.launch {
            if (rawUrl.startsWith("/") || rawUrl.startsWith("file://")) {
                // Archivo local descargado
                val f = File(rawUrl)
                if (!f.exists() || f.length() < 1000) {
                    Log.e(TAG, "El archivo local no existe o está corrupto: $rawUrl")
                    isLoadingTrack = false
                    onLoadingStateListener?.invoke(false)
                    return@launch
                }
                player?.setMediaItem(mediaItemFor(Uri.fromFile(f)))
                player?.prepare()
                player?.play()
                isLoadingTrack = false
                onLoadingStateListener?.invoke(false)
                return@launch
            }

            // Stream en línea de YouTube
            if (rawUrl.contains("youtube.com") || rawUrl.contains("youtu.be") || rawUrl.startsWith("yt_") || rawUrl.length == 11) {
                val vid = cleanVideoId(rawUrl)

                if (isVideoMode) {
                    // Modo Video: Obtener catálogo de resoluciones múltiples
                    val bundle = withContext(Dispatchers.IO) {
                        YouTubeService().resolveVideoStreams(vid)
                    }
                    if (bundle != null && bundle.formats.isNotEmpty()) {
                        currentVideoStreamsBundle = bundle
                        applyVideoStreamsToPlayer(bundle, currentVideoResolution, 0L)
                        isLoadingTrack = false
                        onLoadingStateListener?.invoke(false)
                        return@launch
                    }
                }

                // Modo Audio o fallback de video
                val cached = streamCache.get(vid)
                val streamUrl = if (!cached.isNullOrEmpty()) {
                    cached
                } else {
                    val resolved = withContext(Dispatchers.IO) {
                        YouTubeService().resolveStreamUrl(vid, isAudioOnly = !isVideoMode)
                    }
                    if (!resolved.isNullOrEmpty()) {
                        streamCache.put(vid, resolved)
                        resolved
                    } else null
                }

                if (!streamUrl.isNullOrEmpty()) {
                    try {
                        player?.setMediaItem(mediaItemFor(Uri.parse(streamUrl)))
                        player?.prepare()
                        player?.play()
                    } catch (e: Exception) {
                        Log.e(TAG, "Error preparando ExoPlayer", e)
                    }
                } else {
                    Log.e(TAG, "No se pudo obtener stream para: ${track.title}")
                    if (isContinuous && queue.size > 1) {
                        kotlinx.coroutines.delay(1000)
                        playNext()
                    }
                }
            } else {
                try {
                    player?.setMediaItem(mediaItemFor(Uri.parse(rawUrl)))
                    player?.prepare()
                    player?.play()
                } catch (e: Exception) {
                    Log.e(TAG, "Error cargando URL externa: $rawUrl", e)
                }
            }

            isLoadingTrack = false
            onLoadingStateListener?.invoke(false)
        }
    }
}
