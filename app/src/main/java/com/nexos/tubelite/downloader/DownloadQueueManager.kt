/* Portado de mUzVidX (https:\/\/github.com\/Spartan117x\/mUzVidX) bajo GPL-3.0. */
package com.nexos.tubelite.downloader

import android.content.Context
import android.util.Log
import android.widget.Toast
import com.nexos.tubelite.db.DownloadDatabase
import com.nexos.tubelite.db.DownloadEntity
import com.nexos.tubelite.model.DownloadRecord
import com.nexos.tubelite.model.DownloadStatus
import com.nexos.tubelite.model.DownloadType
import com.nexos.tubelite.model.SearchResult
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap

object DownloadQueueManager {

    private const val TAG = "DownloadQueueManager"

    // Scope a nivel de aplicación que sobrevive a cambios de pestaña, fragmentos y rotación de pantalla
    val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val activeTasks = ConcurrentHashMap.newKeySet<String>()

    fun isTaskActive(id: String): Boolean = activeTasks.contains(id)

    /**
     * Encola la descarga de audio y la conversión automática a MP3 si está habilitada.
     */
    fun enqueueAudioDownload(
        context: Context,
        result: SearchResult
    ) {
        val appCtx = context.applicationContext
        val downloadId = UUID.randomUUID().toString()
        val db = DownloadDatabase.getInstance(appCtx)
        val rawUrl = result.audioUrl.ifEmpty { result.videoUrl }

        activeTasks.add(downloadId)

        scope.launch {
            val initialEntity = DownloadEntity(
                id = downloadId,
                title = result.title,
                artist = result.artist,
                type = "AUDIO",
                sourceUrl = rawUrl,
                originalPath = "",
                mp3Path = null,
                status = "DOWNLOADING",
                progress = 0,
                downloadedAt = System.currentTimeMillis()
            )
            db.downloadDao().insert(initialEntity)

            try {
                val record = AudioVideoDownloader.download(
                    context = appCtx,
                    rawUrlOrId = rawUrl,
                    title = result.title,
                    type = DownloadType.AUDIO,
                    onProgress = { p ->
                        db.downloadDao().update(initialEntity.copy(progress = p))
                    }
                )

                if (record.status == DownloadStatus.READY && record.originalPath.isNotEmpty()) {
                    // Sin FFmpeg: el audio M4A/AAC descargado es el archivo final reproducible.
                    val finalEntity = initialEntity.copy(
                        originalPath = record.originalPath,
                        mp3Path = null,
                        status = "READY",
                        progress = 100
                    )
                    db.downloadDao().update(finalEntity)

                    withContext(Dispatchers.Main) {
                        Toast.makeText(appCtx, "✓ Audio descargado con éxito", Toast.LENGTH_SHORT).show()
                    }
                } else {
                    db.downloadDao().update(initialEntity.copy(status = "ERROR"))
                    withContext(Dispatchers.Main) {
                        Toast.makeText(appCtx, "Error al descargar audio", Toast.LENGTH_SHORT).show()
                    }
                }
            } catch (e: Exception) {
                Log.e(TAG, "Excepción en cola de descarga de audio", e)
                db.downloadDao().update(initialEntity.copy(status = "ERROR"))
            } finally {
                activeTasks.remove(downloadId)
            }
        }
    }

    /**
     * Encola la descarga de video en una resolución específica (1080p, 720p, 480p, 360p).
     */
    fun enqueueVideoDownload(
        context: Context,
        result: SearchResult,
        targetResolution: String = "720p"
    ) {
        val appCtx = context.applicationContext
        val downloadId = UUID.randomUUID().toString()
        val db = DownloadDatabase.getInstance(appCtx)
        val rawUrl = result.videoUrl.ifEmpty { result.audioUrl }

        activeTasks.add(downloadId)

        scope.launch {
            val initialEntity = DownloadEntity(
                id = downloadId,
                title = result.title,
                artist = result.artist,
                type = "VIDEO",
                sourceUrl = rawUrl,
                originalPath = "",
                mp3Path = null,
                status = "DOWNLOADING",
                progress = 0,
                downloadedAt = System.currentTimeMillis()
            )
            db.downloadDao().insert(initialEntity)

            try {
                val record = AudioVideoDownloader.downloadVideoWithResolution(
                    context = appCtx,
                    rawUrlOrId = rawUrl,
                    title = result.title,
                    targetResolution = targetResolution,
                    onProgress = { p ->
                        db.downloadDao().update(initialEntity.copy(progress = p))
                    }
                )

                if (record.status == DownloadStatus.READY && record.originalPath.isNotEmpty()) {
                    val finalEntity = initialEntity.copy(
                        title = record.title,
                        originalPath = record.originalPath,
                        status = "READY",
                        progress = 100
                    )
                    db.downloadDao().update(finalEntity)

                    withContext(Dispatchers.Main) {
                        Toast.makeText(appCtx, "✓ Video descargado (${targetResolution})", Toast.LENGTH_SHORT).show()
                    }
                } else {
                    db.downloadDao().update(initialEntity.copy(status = "ERROR"))
                    withContext(Dispatchers.Main) {
                        Toast.makeText(appCtx, "Error al descargar video ($targetResolution)", Toast.LENGTH_SHORT).show()
                    }
                }
            } catch (e: Exception) {
                Log.e(TAG, "Excepción en cola de descarga de video", e)
                db.downloadDao().update(initialEntity.copy(status = "ERROR"))
            } finally {
                activeTasks.remove(downloadId)
            }
        }
    }

    /**
     * Mecanismo de auto-reparación: detecta descargas que quedaron congeladas en "CONVERTING"
     * o "DOWNLOADING" (por ejemplo por cierre previo de la app o corte) y las restablece.
     */
    fun recoverStuckDownloads(context: Context) {
        val appCtx = context.applicationContext
        val db = DownloadDatabase.getInstance(appCtx)

        scope.launch {
            try {
                val all = db.downloadDao().getAll()
                for (entity in all) {
                    if (activeTasks.contains(entity.id)) continue

                    if (entity.status == "CONVERTING" || entity.status == "DOWNLOADING") {
                        val origFile = if (entity.originalPath.isNotEmpty()) File(entity.originalPath) else null
                        val mp3File = if (!entity.mp3Path.isNullOrEmpty()) {
                            File(entity.mp3Path)
                        } else if (origFile != null) {
                            File(origFile.parentFile, origFile.nameWithoutExtension + ".mp3")
                        } else null

                        if (mp3File != null && mp3File.exists() && mp3File.length() > 50000) {
                            Log.i(TAG, "Auto-recuperado: MP3 ya existía en disco: ${entity.title}")
                            db.downloadDao().update(
                                entity.copy(
                                    status = "READY",
                                    mp3Path = mp3File.absolutePath,
                                    progress = 100
                                )
                            )
                        } else if (origFile != null && origFile.exists() && origFile.length() > 50000) {
                            Log.i(TAG, "Auto-recuperado: Archivo original listo: ${entity.title}")
                            db.downloadDao().update(
                                entity.copy(
                                    status = "READY",
                                    progress = 100
                                )
                            )
                        } else {
                            Log.w(TAG, "Marcando descarga incompleta no recuperable: ${entity.title}")
                            db.downloadDao().update(entity.copy(status = "ERROR", progress = 0))
                        }
                    }
                }
            } catch (e: Exception) {
                Log.w(TAG, "Error durante recoverStuckDownloads: ${e.message}")
            }
        }
    }
}
