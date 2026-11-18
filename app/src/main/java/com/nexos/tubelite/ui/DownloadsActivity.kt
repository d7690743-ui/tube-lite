package com.nexos.tubelite.ui

import android.app.Activity
import android.app.AlertDialog
import android.content.Intent
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.widget.Button
import android.widget.ListView
import android.widget.SimpleAdapter
import android.widget.Toast
import com.nexos.tubelite.R
import com.nexos.tubelite.db.DownloadDatabase
import com.nexos.tubelite.db.DownloadEntity
import com.nexos.tubelite.downloader.DownloadQueueManager
import com.nexos.tubelite.model.ResultType
import com.nexos.tubelite.model.SearchResult
import com.nexos.tubelite.model.Source
import com.nexos.tubelite.player.NativePlaybackService
import com.nexos.tubelite.player.PlaybackManager
import java.io.File

/** Biblioteca de descargas: reproducir sin red o borrar. */
class DownloadsActivity : Activity() {

    private val rows = mutableListOf<Map<String, String>>()
    private val entities = mutableListOf<DownloadEntity>()
    private lateinit var adapter: SimpleAdapter
    private val ui = Handler(Looper.getMainLooper())

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_downloads)

        adapter = SimpleAdapter(
            this, rows, android.R.layout.simple_list_item_2,
            arrayOf("t", "s"), intArrayOf(android.R.id.text1, android.R.id.text2)
        )
        val list = findViewById<ListView>(R.id.dl_list)
        list.adapter = adapter
        list.setOnItemClickListener { _, _, pos, _ -> play(pos) }
        list.setOnItemLongClickListener { _, _, pos, _ ->
            askDelete(pos)
            true
        }
        findViewById<Button>(R.id.dl_repair).setOnClickListener {
            DownloadQueueManager.recoverStuckDownloads(this)
            Toast.makeText(this, "Reparando pendientes…", Toast.LENGTH_SHORT).show()
            ui.postDelayed({ refresh() }, 2000)
        }
    }

    override fun onResume() {
        super.onResume()
        refresh()
    }

    private fun refresh() {
        entities.clear()
        rows.clear()
        try {
            val all = DownloadDatabase.getInstance(this).downloadDao().getAll()
            entities.addAll(all)
            rows.addAll(all.map {
                val sub = when (it.status) {
                    "READY" -> "Lista · ${it.type}"
                    "DOWNLOADING" -> "Descargando ${it.progress}%"
                    "CONVERTING" -> "Convirtiendo…"
                    else -> "Error"
                }
                mapOf("t" to it.title, "s" to sub)
            })
        } catch (e: Exception) {
            rows.add(mapOf("t" to "Error leyendo descargas", "s" to (e.message ?: "?")))
        }
        if (entities.isEmpty()) {
            rows.add(mapOf("t" to "Sin descargas todavía", "s" to "Usa ⬇ en el reproductor o mantén un resultado"))
        }
        adapter.notifyDataSetChanged()
    }

    private fun play(pos: Int) {
        if (pos !in entities.indices) return
        val e = entities[pos]
        if (e.status != "READY") {
            Toast.makeText(this, "Aún no está lista", Toast.LENGTH_SHORT).show()
            return
        }
        val path = e.originalPath
        val f = if (path.isNotEmpty()) File(path) else null
        if (f == null || !f.exists()) {
            Toast.makeText(this, "Archivo no encontrado; se marca con error", Toast.LENGTH_SHORT).show()
            try {
                DownloadDatabase.getInstance(this).downloadDao().update(e.copy(status = "ERROR"))
            } catch (_: Exception) {
            }
            refresh()
            return
        }
        val track = SearchResult(
            id = e.id,
            title = e.title,
            artist = e.artist.ifEmpty { "Descarga" },
            source = Source.YOUTUBE,
            type = if (e.type == "AUDIO") ResultType.TRACK else ResultType.VIDEO,
            durationSec = 0,
            thumbUrl = "",
            audioUrl = path,
            videoUrl = path
        )
        try {
            PlaybackManager.init(this)
            PlaybackManager.isVideoMode = (e.type == "VIDEO")
            PlaybackManager.setQueue(listOf(track), 0)
            NativePlaybackService.start(this)
            startActivity(Intent(this, NativePlayerActivity::class.java))
        } catch (ex: Exception) {
            Toast.makeText(this, "Error: ${ex.message}", Toast.LENGTH_SHORT).show()
        }
    }

    private fun askDelete(pos: Int) {
        if (pos !in entities.indices) return
        val e = entities[pos]
        AlertDialog.Builder(this)
            .setTitle("Borrar descarga")
            .setMessage(e.title.take(80))
            .setPositiveButton("Borrar") { dlg, _ ->
                try {
                    if (e.originalPath.isNotEmpty()) File(e.originalPath).delete()
                    if (!e.mp3Path.isNullOrEmpty()) File(e.mp3Path).delete()
                    DownloadDatabase.getInstance(this).downloadDao().delete(e)
                    Toast.makeText(this, "Borrada", Toast.LENGTH_SHORT).show()
                } catch (ex: Exception) {
                    Toast.makeText(this, "Error: ${ex.message}", Toast.LENGTH_SHORT).show()
                }
                refresh()
                dlg.dismiss()
            }
            .setNegativeButton("Cancelar", null)
            .show()
    }
}
