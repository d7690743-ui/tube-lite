package com.nexos.tubelite.ui

import android.app.Activity
import android.app.AlertDialog
import android.view.View
import android.widget.AdapterView
import android.widget.ArrayAdapter
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.SeekBar
import android.widget.Spinner
import android.widget.Switch
import android.widget.TextView
import android.widget.Toast
import com.nexos.tubelite.player.AudioEqualizerManager
import com.nexos.tubelite.player.PlaybackManager

/** Ecualizador de bandas + graves + presets, construido sin XML. */
object EqualizerDialog {

    fun show(activity: Activity) {
        var bands = AudioEqualizerManager.getBands()
        if (bands.isEmpty()) {
            // Sin sesión de audio aún: intenta enlazar con la actual.
            try {
                val session = PlaybackManager.player?.audioSessionId ?: 0
                if (session != 0) {
                    AudioEqualizerManager.init(activity, session)
                    bands = AudioEqualizerManager.getBands()
                }
            } catch (_: Exception) {
            }
        }
        if (bands.isEmpty()) {
            Toast.makeText(activity, "Ecualizador no disponible (reproduce algo primero)", Toast.LENGTH_SHORT).show()
            return
        }

        val root = LinearLayout(activity).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(32, 24, 32, 24)
        }

        val enableSwitch = Switch(activity).apply {
            text = "Ecualizador activado"
            isChecked = AudioEqualizerManager.isEnabled
        }
        root.addView(enableSwitch)

        val bandLabels = mutableListOf<TextView>()
        val bandBars = mutableListOf<SeekBar>()
        for (b in bands) {
            val range = (b.maxLevel - b.minLevel).coerceAtLeast(1)
            val label = TextView(activity).apply {
                textSize = 14f
            }
            val bar = SeekBar(activity).apply {
                max = range
                progress = (b.currentLevel - b.minLevel).coerceIn(0, range)
            }
            val idx = b.index
            bar.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
                override fun onProgressChanged(sb: SeekBar, progress: Int, fromUser: Boolean) {
                    if (!fromUser) return
                    val level = (b.minLevel + progress).toShort()
                    AudioEqualizerManager.setBandLevel(idx, level, activity)
                    label.text = bandText(b.centerFreqHz, level)
                }

                override fun onStartTrackingTouch(sb: SeekBar) {}
                override fun onStopTrackingTouch(sb: SeekBar) {}
            })
            label.text = bandText(b.centerFreqHz, b.currentLevel)
            bandLabels.add(label)
            bandBars.add(bar)
            root.addView(label)
            root.addView(bar)
        }

        val presets = AudioEqualizerManager.getPresets()
        if (presets.isNotEmpty()) {
            val presetLabel = TextView(activity).apply {
                text = "Preset"
                textSize = 14f
            }
            root.addView(presetLabel)
            val spinner = Spinner(activity).apply {
                adapter = ArrayAdapter(activity, android.R.layout.simple_spinner_item, presets).also {
                    it.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item)
                }
            }
            var firstEvent = true
            spinner.onItemSelectedListener = object : AdapterView.OnItemSelectedListener {
                override fun onItemSelected(parent: AdapterView<*>, view: View?, pos: Int, id: Long) {
                    if (firstEvent) {
                        firstEvent = false
                        return
                    }
                    AudioEqualizerManager.usePreset(pos.toShort(), activity)
                    // Refrescar sliders con los valores del preset.
                    val fresh = AudioEqualizerManager.getBands()
                    fresh.forEachIndexed { i, nb ->
                        if (i < bandBars.size) {
                            val range = (nb.maxLevel - nb.minLevel).coerceAtLeast(1)
                            bandBars[i].max = range
                            bandBars[i].progress = (nb.currentLevel - nb.minLevel).coerceIn(0, range)
                            bandLabels[i].text = bandText(nb.centerFreqHz, nb.currentLevel)
                        }
                    }
                    Toast.makeText(activity, "Preset: ${presets[pos]}", Toast.LENGTH_SHORT).show()
                }

                override fun onNothingSelected(parent: AdapterView<*>) {}
            }
            root.addView(spinner)
        }

        root.addView(effectRow(activity, "Graves (Bass Boost)", AudioEqualizerManager.getBassBoostStrength()) { v ->
            AudioEqualizerManager.setBassBoost(v, activity)
        })
        root.addView(effectRow(activity, "Virtualizador", AudioEqualizerManager.getVirtualizerStrength()) { v ->
            AudioEqualizerManager.setVirtualizer(v, activity)
        })

        enableSwitch.setOnCheckedChangeListener { _, checked ->
            AudioEqualizerManager.setEnabled(checked, activity)
        }

        AlertDialog.Builder(activity)
            .setTitle("Ecualizador")
            .setView(ScrollView(activity).apply { addView(root) })
            .setPositiveButton("Cerrar", null)
            .show()
    }

    private fun bandText(freqHz: Int, level: Short): String {
        val hz = if (freqHz >= 1000) "${freqHz / 1000}.${(freqHz % 1000) / 100} kHz" else "$freqHz Hz"
        val db = level / 100.0
        val sign = if (db > 0) "+" else ""
        return "$hz: $sign$db dB"
    }

    private fun effectRow(activity: Activity, name: String, current: Short, onChange: (Short) -> Unit): View {
        val box = LinearLayout(activity).apply { orientation = LinearLayout.VERTICAL }
        val label = TextView(activity).apply {
            textSize = 14f
            text = "$name: ${(current / 10)}%"
        }
        val bar = SeekBar(activity).apply {
            max = 1000
            progress = current.toInt().coerceIn(0, 1000)
        }
        bar.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(sb: SeekBar, progress: Int, fromUser: Boolean) {
                if (!fromUser) return
                onChange(progress.toShort())
                label.text = "$name: ${progress / 10}%"
            }

            override fun onStartTrackingTouch(sb: SeekBar) {}
            override fun onStopTrackingTouch(sb: SeekBar) {}
        })
        box.addView(label)
        box.addView(bar)
        return box
    }
}
