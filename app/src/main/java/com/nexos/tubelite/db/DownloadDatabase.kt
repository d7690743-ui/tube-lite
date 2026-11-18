/* Portado de mUzVidX (https:\/\/github.com\/Spartan117x\/mUzVidX) bajo GPL-3.0. */
package com.nexos.tubelite.db

import android.content.ContentValues
import android.content.Context
import android.database.Cursor
import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteOpenHelper
import java.util.UUID

class DownloadDatabase private constructor(context: Context) :
    SQLiteOpenHelper(context.applicationContext, "tubelite_downloads.db", null, 2) {

    private val dao = object : DownloadDao {
        override fun insert(entity: DownloadEntity) {
            val db = writableDatabase
            val values = ContentValues().apply {
                put("id", entity.id)
                put("title", entity.title)
                put("artist", entity.artist)
                put("type", entity.type)
                put("sourceUrl", entity.sourceUrl)
                put("originalPath", entity.originalPath)
                put("mp3Path", entity.mp3Path)
                put("status", entity.status)
                put("progress", entity.progress)
                put("downloadedAt", entity.downloadedAt)
            }
            db.insertWithOnConflict("downloads", null, values, SQLiteDatabase.CONFLICT_REPLACE)
        }

        override fun update(entity: DownloadEntity) {
            val db = writableDatabase
            val values = ContentValues().apply {
                put("title", entity.title)
                put("artist", entity.artist)
                put("type", entity.type)
                put("sourceUrl", entity.sourceUrl)
                put("originalPath", entity.originalPath)
                put("mp3Path", entity.mp3Path)
                put("status", entity.status)
                put("progress", entity.progress)
                put("downloadedAt", entity.downloadedAt)
            }
            db.update("downloads", values, "id = ?", arrayOf(entity.id))
        }

        override fun delete(entity: DownloadEntity) {
            val db = writableDatabase
            db.delete("downloads", "id = ?", arrayOf(entity.id))
        }

        override fun getAll(): List<DownloadEntity> {
            val db = readableDatabase
            val list = mutableListOf<DownloadEntity>()
            val cursor = db.rawQuery("SELECT * FROM downloads ORDER BY downloadedAt DESC", null)
            cursor.use {
                while (it.moveToNext()) {
                    list.add(cursorToEntity(it))
                }
            }
            return list
        }

        override fun getByType(type: String): List<DownloadEntity> {
            val db = readableDatabase
            val list = mutableListOf<DownloadEntity>()
            val cursor = db.rawQuery(
                "SELECT * FROM downloads WHERE type = ? ORDER BY downloadedAt DESC",
                arrayOf(type)
            )
            cursor.use {
                while (it.moveToNext()) {
                    list.add(cursorToEntity(it))
                }
            }
            return list
        }

        override fun getById(id: String): DownloadEntity? {
            val db = readableDatabase
            val cursor = db.rawQuery("SELECT * FROM downloads WHERE id = ?", arrayOf(id))
            cursor.use {
                if (it.moveToNext()) {
                    return cursorToEntity(it)
                }
            }
            return null
        }

        override fun clearAll() {
            val db = writableDatabase
            db.delete("downloads", null, null)
        }

        // ==================== FAVORITOS ====================
        override fun isFavorite(id: String): Boolean {
            val db = readableDatabase
            val cursor = db.rawQuery("SELECT 1 FROM favorites WHERE id = ? LIMIT 1", arrayOf(id))
            cursor.use {
                return it.moveToFirst()
            }
        }

        override fun addFavorite(favorite: FavoriteEntity) {
            val db = writableDatabase
            val values = ContentValues().apply {
                put("id", favorite.id)
                put("title", favorite.title)
                put("artist", favorite.artist)
                put("thumbUrl", favorite.thumbUrl)
                put("sourceUrl", favorite.sourceUrl)
                put("type", favorite.type)
                put("addedAt", favorite.addedAt)
            }
            db.insertWithOnConflict("favorites", null, values, SQLiteDatabase.CONFLICT_REPLACE)
        }

        override fun removeFavorite(id: String) {
            val db = writableDatabase
            db.delete("favorites", "id = ?", arrayOf(id))
        }

        override fun getAllFavorites(): List<FavoriteEntity> {
            val db = readableDatabase
            val list = mutableListOf<FavoriteEntity>()
            val cursor = db.rawQuery("SELECT * FROM favorites ORDER BY addedAt DESC", null)
            cursor.use {
                while (it.moveToNext()) {
                    list.add(
                        FavoriteEntity(
                            id = it.getString(it.getColumnIndexOrThrow("id")),
                            title = it.getString(it.getColumnIndexOrThrow("title")),
                            artist = it.getString(it.getColumnIndexOrThrow("artist")),
                            thumbUrl = it.getString(it.getColumnIndexOrThrow("thumbUrl")),
                            sourceUrl = it.getString(it.getColumnIndexOrThrow("sourceUrl")),
                            type = it.getString(it.getColumnIndexOrThrow("type")),
                            addedAt = it.getLong(it.getColumnIndexOrThrow("addedAt"))
                        )
                    )
                }
            }
            return list
        }

        // ==================== PLAYLISTS ====================
        override fun createPlaylist(name: String): String {
            val db = writableDatabase
            val id = UUID.randomUUID().toString()
            val values = ContentValues().apply {
                put("id", id)
                put("name", name)
                put("createdAt", System.currentTimeMillis())
            }
            db.insertWithOnConflict("playlists", null, values, SQLiteDatabase.CONFLICT_REPLACE)
            return id
        }

        override fun getAllPlaylists(): List<PlaylistEntity> {
            val db = readableDatabase
            val list = mutableListOf<PlaylistEntity>()
            val sql = """
                SELECT p.id, p.name, p.createdAt, COUNT(pi.id) AS trackCount
                FROM playlists p
                LEFT JOIN playlist_items pi ON p.id = pi.playlistId
                GROUP BY p.id
                ORDER BY p.createdAt DESC
            """.trimIndent()
            val cursor = db.rawQuery(sql, null)
            cursor.use {
                while (it.moveToNext()) {
                    list.add(
                        PlaylistEntity(
                            id = it.getString(it.getColumnIndexOrThrow("id")),
                            name = it.getString(it.getColumnIndexOrThrow("name")),
                            createdAt = it.getLong(it.getColumnIndexOrThrow("createdAt")),
                            trackCount = it.getInt(it.getColumnIndexOrThrow("trackCount"))
                        )
                    )
                }
            }
            return list
        }

        override fun deletePlaylist(playlistId: String) {
            val db = writableDatabase
            db.delete("playlists", "id = ?", arrayOf(playlistId))
            db.delete("playlist_items", "playlistId = ?", arrayOf(playlistId))
        }

        override fun addTrackToPlaylist(item: PlaylistItemEntity) {
            val db = writableDatabase
            val values = ContentValues().apply {
                put("id", item.id.ifEmpty { UUID.randomUUID().toString() })
                put("playlistId", item.playlistId)
                put("trackId", item.trackId)
                put("title", item.title)
                put("artist", item.artist)
                put("thumbUrl", item.thumbUrl)
                put("sourceUrl", item.sourceUrl)
                put("type", item.type)
                put("addedAt", item.addedAt)
            }
            db.insertWithOnConflict("playlist_items", null, values, SQLiteDatabase.CONFLICT_REPLACE)
        }

        override fun getTracksForPlaylist(playlistId: String): List<PlaylistItemEntity> {
            val db = readableDatabase
            val list = mutableListOf<PlaylistItemEntity>()
            val cursor = db.rawQuery(
                "SELECT * FROM playlist_items WHERE playlistId = ? ORDER BY addedAt ASC",
                arrayOf(playlistId)
            )
            cursor.use {
                while (it.moveToNext()) {
                    list.add(
                        PlaylistItemEntity(
                            id = it.getString(it.getColumnIndexOrThrow("id")),
                            playlistId = it.getString(it.getColumnIndexOrThrow("playlistId")),
                            trackId = it.getString(it.getColumnIndexOrThrow("trackId")),
                            title = it.getString(it.getColumnIndexOrThrow("title")),
                            artist = it.getString(it.getColumnIndexOrThrow("artist")),
                            thumbUrl = it.getString(it.getColumnIndexOrThrow("thumbUrl")),
                            sourceUrl = it.getString(it.getColumnIndexOrThrow("sourceUrl")),
                            type = it.getString(it.getColumnIndexOrThrow("type")),
                            addedAt = it.getLong(it.getColumnIndexOrThrow("addedAt"))
                        )
                    )
                }
            }
            return list
        }

        override fun removeTrackFromPlaylist(itemId: String) {
            val db = writableDatabase
            db.delete("playlist_items", "id = ?", arrayOf(itemId))
        }
    }

    override fun onCreate(db: SQLiteDatabase) {
        db.execSQL(
            """
            CREATE TABLE IF NOT EXISTS downloads (
                id TEXT PRIMARY KEY,
                title TEXT,
                artist TEXT,
                type TEXT,
                sourceUrl TEXT,
                originalPath TEXT,
                mp3Path TEXT,
                status TEXT,
                progress INTEGER,
                downloadedAt INTEGER
            )
            """.trimIndent()
        )

        db.execSQL(
            """
            CREATE TABLE IF NOT EXISTS favorites (
                id TEXT PRIMARY KEY,
                title TEXT,
                artist TEXT,
                thumbUrl TEXT,
                sourceUrl TEXT,
                type TEXT,
                addedAt INTEGER
            )
            """.trimIndent()
        )

        db.execSQL(
            """
            CREATE TABLE IF NOT EXISTS playlists (
                id TEXT PRIMARY KEY,
                name TEXT,
                createdAt INTEGER
            )
            """.trimIndent()
        )

        db.execSQL(
            """
            CREATE TABLE IF NOT EXISTS playlist_items (
                id TEXT PRIMARY KEY,
                playlistId TEXT,
                trackId TEXT,
                title TEXT,
                artist TEXT,
                thumbUrl TEXT,
                sourceUrl TEXT,
                type TEXT,
                addedAt INTEGER
            )
            """.trimIndent()
        )
    }

    override fun onUpgrade(db: SQLiteDatabase, oldVersion: Int, newVersion: Int) {
        if (oldVersion < 2) {
            onCreate(db)
        }
    }

    fun downloadDao(): DownloadDao = dao

    private fun cursorToEntity(c: Cursor): DownloadEntity {
        return DownloadEntity(
            id = c.getString(c.getColumnIndexOrThrow("id")),
            title = c.getString(c.getColumnIndexOrThrow("title")),
            artist = c.getString(c.getColumnIndexOrThrow("artist")),
            type = c.getString(c.getColumnIndexOrThrow("type")),
            sourceUrl = c.getString(c.getColumnIndexOrThrow("sourceUrl")),
            originalPath = c.getString(c.getColumnIndexOrThrow("originalPath")),
            mp3Path = c.getString(c.getColumnIndexOrThrow("mp3Path")),
            status = c.getString(c.getColumnIndexOrThrow("status")),
            progress = c.getInt(c.getColumnIndexOrThrow("progress")),
            downloadedAt = c.getLong(c.getColumnIndexOrThrow("downloadedAt"))
        )
    }

    companion object {
        @Volatile
        private var INSTANCE: DownloadDatabase? = null

        fun getInstance(context: Context): DownloadDatabase {
            return INSTANCE ?: synchronized(this) {
                INSTANCE ?: DownloadDatabase(context).also { INSTANCE = it }
            }
        }
    }
}
