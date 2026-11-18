/* Portado de mUzVidX (https:\/\/github.com\/Spartan117x\/mUzVidX) bajo GPL-3.0. */
package com.nexos.tubelite.model

enum class DownloadType { AUDIO, VIDEO }
enum class DownloadStatus { DOWNLOADING, CONVERTING, READY, ERROR }

data class DownloadRecord(
    val id: String,
    val title: String,
    val artist: String,
    val type: DownloadType,
    val sourceUrl: String,
    val originalPath: String,
    val mp3Path: String? = null,
    val status: DownloadStatus = DownloadStatus.DOWNLOADING,
    val progress: Int = 0,
    val downloadedAt: Long = System.currentTimeMillis()
)