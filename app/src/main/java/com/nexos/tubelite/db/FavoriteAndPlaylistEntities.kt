/* Portado de mUzVidX (https:\/\/github.com\/Spartan117x\/mUzVidX) bajo GPL-3.0. */
package com.nexos.tubelite.db

data class FavoriteEntity(
    val id: String,
    val title: String,
    val artist: String,
    val thumbUrl: String,
    val sourceUrl: String,
    val type: String,
    val addedAt: Long
)

data class PlaylistEntity(
    val id: String,
    val name: String,
    val createdAt: Long,
    val trackCount: Int = 0
)

data class PlaylistItemEntity(
    val id: String,
    val playlistId: String,
    val trackId: String,
    val title: String,
    val artist: String,
    val thumbUrl: String,
    val sourceUrl: String,
    val type: String,
    val addedAt: Long
)
