/* Portado de mUzVidX (https:\/\/github.com\/Spartan117x\/mUzVidX) bajo GPL-3.0. */
package com.nexos.tubelite.db

interface DownloadDao {
    fun insert(entity: DownloadEntity)
    fun update(entity: DownloadEntity)
    fun delete(entity: DownloadEntity)
    fun getAll(): List<DownloadEntity>
    fun getByType(type: String): List<DownloadEntity>
    fun getById(id: String): DownloadEntity?
    fun clearAll()

    // Favoritos
    fun isFavorite(id: String): Boolean
    fun addFavorite(favorite: FavoriteEntity)
    fun removeFavorite(id: String)
    fun getAllFavorites(): List<FavoriteEntity>

    // Playlists
    fun createPlaylist(name: String): String
    fun getAllPlaylists(): List<PlaylistEntity>
    fun deletePlaylist(playlistId: String)
    fun addTrackToPlaylist(item: PlaylistItemEntity)
    fun getTracksForPlaylist(playlistId: String): List<PlaylistItemEntity>
    fun removeTrackFromPlaylist(itemId: String)
}
