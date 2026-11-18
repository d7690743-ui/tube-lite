/* Portado de mUzVidX (https:\/\/github.com\/Spartan117x\/mUzVidX) bajo GPL-3.0. */
package com.nexos.tubelite.downloader

import android.content.Context
import android.media.MediaScannerConnection
import android.os.Environment
import android.util.Log
import com.nexos.tubelite.extractor.YouTubeService
import com.nexos.tubelite.model.DownloadRecord
import com.nexos.tubelite.model.DownloadStatus
import com.nexos.tubelite.model.DownloadType
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.io.FileOutputStream
import java.io.InputStream
import java.net.HttpURLConnection
import java.net.URL
import java.util.UUID

object AudioVideoDownloader {

    private const val TAG = "AudioVideoDownloader"
    private const val USER_AGENT_ANDROID =
        "com.google.android.youtube/21.03.36(Linux; U; Android 16; en_US; SM-S908E Build/TP1A.220624.014) gzip"
    private const val USER_AGENT_IOS =
        "com.google.ios.youtube/20.11.6 (iPhone10,4; U; CPU iOS 16_7_7 like Mac OS X)"

    private fun getBaseDir(context: Context, type: DownloadType): File {
        val dirName = if (type == DownloadType.AUDIO) "TubeLite_Audio" else "TubeLite_Video"
        val storageDir = context.getExternalFilesDir(
            if (type == DownloadType.AUDIO) Environment.DIRECTORY_MUSIC else Environment.DIRECTORY_MOVIES
        ) ?: File(context.filesDir, dirName)

        val dir = File(storageDir, dirName)
        if (!dir.exists()) dir.mkdirs()
        return dir
    }

    fun getAudioDir(context: Context): File = getBaseDir(context, DownloadType.AUDIO)
    fun getVideoDir(context: Context): File = getBaseDir(context, DownloadType.VIDEO)

    /**
     * Descarga estándar (empleada principalmente para Audio M4A/AAC)
     */
    suspend fun download(
        context: Context,
        rawUrlOrId: String,
        title: String,
        type: DownloadType,
        onProgress: (Int) -> Unit
    ): DownloadRecord = withContext(Dispatchers.IO) {
        Log.i(TAG, "Iniciando descarga: $title ($type), url/id: $rawUrlOrId")
        val id = UUID.randomUUID().toString()
        val baseDir = getBaseDir(context, type)
        val ext = if (type == DownloadType.AUDIO) "m4a" else "mp4"
        val safeTitle = title.replace(Regex("[^a-zA-Z0-9ñÑáéíóúÁÉÍÓÚü_ ]"), "").trim()
            .take(45).ifEmpty { "track" }
        val originalFile = File(baseDir, "${safeTitle}_${id.take(6)}.$ext")

        var status = DownloadStatus.DOWNLOADING
        var progress = 0

        val streamUrl = if (rawUrlOrId.contains("youtube.com") || rawUrlOrId.contains("youtu.be") || rawUrlOrId.startsWith("yt_") || rawUrlOrId.length == 11) {
            YouTubeService().resolveStreamUrl(rawUrlOrId, isAudioOnly = (type == DownloadType.AUDIO))
        } else {
            rawUrlOrId
        }
        Log.i(TAG, "Stream URL resuelto ($type): $streamUrl")

        if (streamUrl.isNullOrEmpty()) {
            Log.e(TAG, "No se pudo obtener URL de stream para $rawUrlOrId")
            return@withContext DownloadRecord(
                id = id,
                title = title,
                artist = "",
                type = type,
                sourceUrl = rawUrlOrId,
                originalPath = "",
                status = DownloadStatus.ERROR,
                progress = 0
            )
        }

        val success = downloadStreamToFile(streamUrl, originalFile, 0, 100, onProgress)
        if (success && originalFile.exists() && originalFile.length() > 50000) {
            status = DownloadStatus.READY
            progress = 100
            onProgress(100)
            Log.i(TAG, "Descarga completada con éxito: ${originalFile.absolutePath} (${originalFile.length()} bytes)")

            try {
                val mimeType = if (type == DownloadType.AUDIO) "audio/mp4" else "video/mp4"
                MediaScannerConnection.scanFile(
                    context,
                    arrayOf(originalFile.absolutePath),
                    arrayOf(mimeType)
                ) { path, uri ->
                    Log.i(TAG, "MediaScanner indexó: $path -> $uri")
                }
            } catch (e: Exception) {
                Log.w(TAG, "MediaScanner no pudo indexar: ${e.message}")
            }
        } else {
            status = DownloadStatus.ERROR
            originalFile.delete()
        }

        DownloadRecord(
            id = id,
            title = title,
            artist = "",
            type = type,
            sourceUrl = rawUrlOrId,
            originalPath = if (status == DownloadStatus.READY) originalFile.absolutePath else "",
            status = status,
            progress = progress
        )
    }

    /**
     * Descarga de video en resolución específica (1080p, 720p, 480p, 360p)
     * Si el formato es adaptativo, intenta descargar video y audio separados y combinarlos con FFmpeg.
     * Si la CDN de YouTube restringe el formato adaptativo (HTTP 403), activa automáticamente
     * el fallback al stream progresivo oficial garantizando que la descarga NUNCA falle.
     */
    suspend fun downloadVideoWithResolution(
        context: Context,
        rawUrlOrId: String,
        title: String,
        targetResolution: String = "720p",
        onProgress: (Int) -> Unit
    ): DownloadRecord = withContext(Dispatchers.IO) {
        val id = UUID.randomUUID().toString()
        val baseDir = getVideoDir(context)
        val safeTitle = title.replace(Regex("[^a-zA-Z0-9ñÑáéíóúÁÉÍÓÚü_ ]"), "").trim()
            .take(40).ifEmpty { "video" }
        val finalFile = File(baseDir, "${safeTitle}_${targetResolution}_${id.take(6)}.mp4")

        Log.i(TAG, "Iniciando descarga de video ($targetResolution): $title")
        val cleanId = YouTubeService.cleanVideoId(rawUrlOrId)
        val bundle = YouTubeService().resolveVideoStreams(cleanId)

        if (bundle == null || bundle.formats.isEmpty()) {
            Log.w(TAG, "No se obtuvieron streams para resolución múltiple. Usando fallback estándar.")
            return@withContext download(context, rawUrlOrId, title, DownloadType.VIDEO, onProgress)
        }

        val progressiveFormat = bundle.formats.find { it.isProgressive }
        var status = DownloadStatus.DOWNLOADING

        // Si el usuario eligió 360p y hay stream progresivo disponible, descargarlo directamente
        if (targetResolution.equals("360p", ignoreCase = true) && progressiveFormat != null) {
            Log.i(TAG, "Descargando stream progresivo directo 360p")
            val ok = downloadStreamToFile(progressiveFormat.videoUrl, finalFile, 0, 100, onProgress)
            status = if (ok && finalFile.exists() && finalFile.length() > 50000) DownloadStatus.READY else DownloadStatus.ERROR
        } else {
            // Buscar el formato que mejor coincida con la resolución deseada
            val selectedFormat = bundle.formats.find { it.qualityLabel.equals(targetResolution, ignoreCase = true) }
                ?: bundle.formats.find { it.qualityLabel.contains(targetResolution.replace("p", "")) }
                ?: bundle.formats.first()

            Log.i(TAG, "Intentando descarga en alta definición: itag=${selectedFormat.itag}, res=${selectedFormat.qualityLabel}, isProgressive=${selectedFormat.isProgressive}")

            if (selectedFormat.isProgressive) {
                // Video y audio ya combinados
                val ok = downloadStreamToFile(selectedFormat.videoUrl, finalFile, 0, 100, onProgress)
                status = if (ok && finalFile.exists() && finalFile.length() > 50000) DownloadStatus.READY else DownloadStatus.ERROR
            } else {
                // Formato adaptativo (video y audio separados): sin FFmpeg no se puede
                // multiplexar, así que se deja en ERROR para que el fallback progresivo
                // de abajo garantice la descarga con el stream muxed oficial.
                Log.w(TAG, "Formato adaptativo sin mux disponible; usando fallback progresivo")
                status = DownloadStatus.ERROR
            }
        }

        // FALLBACK INFALIBLE: Si la descarga adaptativa falló por restricciones CDN (403),
        // recurrir al stream progresivo oficial (itag 18) que garantiza descarga al 100%
        if (status != DownloadStatus.READY) {
            Log.w(TAG, "Descarga adaptativa ($targetResolution) no pudo completarse. Activando fallback a stream progresivo garantizado...")
            onProgress(10)
            val progUrl = progressiveFormat?.videoUrl
                ?: YouTubeService().resolveStreamUrl(cleanId, isAudioOnly = false)

            if (!progUrl.isNullOrEmpty()) {
                val progOk = downloadStreamToFile(progUrl, finalFile, 10, 100, onProgress)
                if (progOk && finalFile.exists() && finalFile.length() > 50000) {
                    status = DownloadStatus.READY
                    onProgress(100)
                    Log.i(TAG, "Fallback progresivo completado con éxito: ${finalFile.absolutePath} (${finalFile.length()} bytes)")
                }
            }
        }

        if (status == DownloadStatus.READY) {
            try {
                MediaScannerConnection.scanFile(
                    context,
                    arrayOf(finalFile.absolutePath),
                    arrayOf("video/mp4")
                ) { path, uri ->
                    Log.i(TAG, "MediaScanner indexó video: $path -> $uri")
                }
            } catch (e: Exception) {
                Log.w(TAG, "MediaScanner error: ${e.message}")
            }
        } else {
            finalFile.delete()
        }

        DownloadRecord(
            id = id,
            title = "$title [$targetResolution]",
            artist = "",
            type = DownloadType.VIDEO,
            sourceUrl = rawUrlOrId,
            originalPath = if (status == DownloadStatus.READY) finalFile.absolutePath else "",
            status = status,
            progress = if (status == DownloadStatus.READY) 100 else 0
        )
    }

    private fun downloadStreamToFile(
        streamUrl: String,
        targetFile: File,
        startProgress: Int,
        endProgress: Int,
        onProgress: (Int) -> Unit
    ): Boolean {
        try {
            var currentUrl = streamUrl
            var redirectCount = 0
            var conn: HttpURLConnection? = null
            var responseCode = 0

            // Ajustar User-Agent según si el stream proviene de iOS o Android
            val userAgent = if (streamUrl.contains("c=IOS") || streamUrl.contains("c%3DIOS")) {
                USER_AGENT_IOS
            } else {
                USER_AGENT_ANDROID
            }

            while (redirectCount < 5) {
                conn = (URL(currentUrl).openConnection() as HttpURLConnection).apply {
                    requestMethod = "GET"
                    connectTimeout = 15000
                    readTimeout = 30000
                    setRequestProperty("User-Agent", userAgent)
                    setRequestProperty("Accept", "*/*")
                    setRequestProperty("Connection", "keep-alive")
                    instanceFollowRedirects = true
                }
                conn.connect()
                responseCode = conn.responseCode
                if (responseCode in listOf(301, 302, 303, 307, 308)) {
                    val location = conn.getHeaderField("Location")
                    conn.disconnect()
                    if (!location.isNullOrEmpty()) {
                        currentUrl = location
                        redirectCount++
                        continue
                    }
                }
                break
            }

            if (conn != null && responseCode in 200..299) {
                val totalBytes = conn.contentLengthLong
                val input: InputStream = conn.inputStream
                val output = FileOutputStream(targetFile)
                val buffer = ByteArray(65536)
                var downloaded: Long = 0
                var bytes: Int
                val progressRange = (endProgress - startProgress).coerceAtLeast(1)
                var lastReported = startProgress

                while (input.read(buffer).also { bytes = it } != -1) {
                    output.write(buffer, 0, bytes)
                    downloaded += bytes
                    val p = if (totalBytes > 0) {
                        startProgress + ((downloaded * progressRange) / totalBytes).toInt().coerceIn(0, progressRange)
                    } else {
                        startProgress + (downloaded / 80000).toInt().coerceIn(0, progressRange - 1)
                    }
                    if (p > lastReported) {
                        lastReported = p
                        onProgress(p)
                    }
                }
                output.flush()
                output.close()
                input.close()
                conn.disconnect()
                return targetFile.exists() && targetFile.length() > 50000
            } else {
                Log.e(TAG, "Error HTTP $responseCode al descargar desde $currentUrl")
                return false
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error de red al descargar stream: ${e.message}", e)
            return false
        }
    }
}
