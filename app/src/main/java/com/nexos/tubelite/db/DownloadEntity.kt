/* Portado de mUzVidX (https:\/\/github.com\/Spartan117x\/mUzVidX) bajo GPL-3.0. */
package com.nexos.tubelite.db

data class DownloadEntity(
    val id: String,
    val title: String,
    val artist: String,
    val type: String, // AUDIO or VIDEO
    val sourceUrl: String,
    val originalPath: String,
    val mp3Path: String?,
    val status: String, // DOWNLOADING, CONVERTING, READY, ERROR
    val progress: Int,
    val downloadedAt: Long
)
