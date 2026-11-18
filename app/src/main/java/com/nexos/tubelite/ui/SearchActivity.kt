package com.nexos.tubelite.ui

import android.app.Activity
import android.app.AlertDialog
import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.view.KeyEvent
import android.view.inputmethod.EditorInfo
import android.view.inputmethod.InputMethodManager
import android.widget.Button
import android.widget.EditText
import android.widget.ListView
import android.widget.RadioGroup
import android.widget.SimpleAdapter
import android.widget.TextView
import android.widget.Toast
import com.nexos.tubelite.R
import com.nexos.tubelite.downloader.DownloadQueueManager
import com.nexos.tubelite.extractor.YouTubeService
import com.nexos.tubelite.model.SearchMode
import com.nexos.tubelite.model.SearchResult
import com.nexos.tubelite.player.NativePlaybackService
import com.nexos.tubelite.player.PlaybackManager
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch

/** Búsqueda nativa (sin WebView): toca para reproducir, mantén para descargar. */
class SearchActivity : Activity() {

    private val scope = CoroutineScope(Dispatchers.Main + SupervisorJob())
    private val results = mutableListOf<SearchResult>()
    private val rows = mutableListOf<Map<String, String>>()
    private lateinit var adapter: SimpleAdapter
    private lateinit var input: EditText
    private lateinit var status: TextView

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_search)

        input = findViewById(R.id.search_input)
        status = findViewById(R.id.search_status)
        val modeGroup = findViewById<RadioGroup>(R.id.mode_group)
        val list = findViewById<ListView>(R.id.search_results)

        adapter = SimpleAdapter(
            this, rows, android.R.layout.simple_list_item_2,
            arrayOf("t", "s"), intArrayOf(android.R.id.text1, android.R.id.text2)
        )
        list.adapter = adapter

        val go = { doSearch(modeGroup.checkedRadioButtonId) }
        findViewById<Button>(R.id.search_go).setOnClickListener { go() }
        input.setOnEditorActionListener { _, actionId, event ->
            if (actionId == EditorInfo.IME_ACTION_SEARCH ||
                actionId == EditorInfo.IME_ACTION_DONE ||
                event?.keyCode == KeyEvent.KEYCODE_ENTER
            ) {
                go()
                true
            } else false
        }
        list.setOnItemClickListener { _, _, pos, _ -> play(pos) }
        list.setOnItemLongClickListener { _, _, pos, _ ->
            askDownload(pos)
            true
        }
    }

    override fun onDestroy() {
        scope.cancel()
        super.onDestroy()
    }

    private fun doSearch(checkedId: Int) {
        val q = input.text.toString().trim()
        if (q.isEmpty()) {
            Toast.makeText(this, "Escribe algo para buscar", Toast.LENGTH_SHORT).show()
            return
        }
        val mode = when (checkedId) {
            R.id.mode_videos -> SearchMode.VIDEOS
            R.id.mode_free -> SearchMode.FREE
            else -> SearchMode.LIBRARY
        }
        try {
            val imm = getSystemService(Context.INPUT_METHOD_SERVICE) as InputMethodManager
            imm.hideSoftInputFromWindow(input.windowToken, 0)
        } catch (_: Exception) {
        }
        status.text = "Buscando…"
        scope.launch {
            val found = try {
                YouTubeService().search(q, mode, 30)
            } catch (e: Exception) {
                status.text = "Error: ${e.message}"
                return@launch
            }
            results.clear()
            results.addAll(found)
            rows.clear()
            rows.addAll(found.map {
                mapOf("t" to it.title, "s" to "${it.artist} · ${fmtDur(it.durationSec)}")
            })
            adapter.notifyDataSetChanged()
            status.text = if (found.isEmpty()) {
                "Sin resultados (revisa tu red o prueba otro término)"
            } else {
                "${found.size} resultados"
            }
        }
    }

    private fun play(pos: Int) {
        if (pos !in results.indices) return
        NotifPermission.ensure(this)
        try {
            PlaybackManager.init(this)
            PlaybackManager.setQueue(results.toList(), pos)
            NativePlaybackService.start(this)
            startActivity(Intent(this, NativePlayerActivity::class.java))
        } catch (e: Exception) {
            Toast.makeText(this, "Error: ${e.message}", Toast.LENGTH_SHORT).show()
        }
    }

    private fun askDownload(pos: Int) {
        if (pos !in results.indices) return
        val t = results[pos]
        AlertDialog.Builder(this)
            .setTitle(t.title.take(60))
            .setItems(arrayOf("Descargar audio (M4A)", "Descargar video (360p)")) { dlg, which ->
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

    private fun fmtDur(sec: Long): String {
        if (sec <= 0) return "--:--"
        val m = sec / 60
        val s = sec % 60
        return "$m:${if (s < 10) "0" else ""}$s"
    }
}
