/* Portado de mUzVidX (https:\/\/github.com\/Spartan117x\/mUzVidX) bajo GPL-3.0. */
package com.nexos.tubelite.player

import android.content.Context
import android.media.audiofx.BassBoost
import android.media.audiofx.Equalizer
import android.media.audiofx.Virtualizer
import android.util.Log

data class BandInfo(
    val index: Short,
    val centerFreqHz: Int,
    val minLevel: Short,
    val maxLevel: Short,
    var currentLevel: Short
)

object AudioEqualizerManager {
    private const val TAG = "AudioEqualizerManager"
    private const val PREFS_NAME = "tubelite_equalizer_prefs"

    private var equalizer: Equalizer? = null
    private var bassBoost: BassBoost? = null
    private var virtualizer: Virtualizer? = null
    private var currentSessionId: Int = 0

    var isEnabled: Boolean = false
        private set

    fun init(context: Context, audioSessionId: Int) {
        if (audioSessionId == 0 || audioSessionId == currentSessionId && equalizer != null) {
            return
        }

        release()
        currentSessionId = audioSessionId

        try {
            equalizer = Equalizer(0, audioSessionId).apply {
                enabled = true
            }
            bassBoost = BassBoost(0, audioSessionId).apply {
                enabled = true
            }
            virtualizer = Virtualizer(0, audioSessionId).apply {
                enabled = true
            }
            isEnabled = true

            // Cargar configuración guardada
            loadPreferences(context)
            Log.i(TAG, "Equalizer initialized for session $audioSessionId with ${equalizer?.numberOfBands} bands")
        } catch (e: Exception) {
            Log.e(TAG, "Failed to initialize Equalizer for session $audioSessionId", e)
        }
    }

    fun setEnabled(enabled: Boolean, context: Context) {
        isEnabled = enabled
        try {
            equalizer?.enabled = enabled
            bassBoost?.enabled = enabled
            virtualizer?.enabled = enabled
            context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
                .edit().putBoolean("eq_enabled", enabled).apply()
        } catch (e: Exception) {
            Log.e(TAG, "Error toggling equalizer", e)
        }
    }

    fun getBands(): List<BandInfo> {
        val eq = equalizer ?: return emptyList()
        val numBands = eq.numberOfBands.toInt()
        val minLevel = eq.bandLevelRange.getOrNull(0) ?: -1500
        val maxLevel = eq.bandLevelRange.getOrNull(1) ?: 1500

        val list = mutableListOf<BandInfo>()
        for (i in 0 until numBands) {
            val band = i.toShort()
            val freq = (eq.getCenterFreq(band) / 1000) // mHz -> Hz
            val current = eq.getBandLevel(band)
            list.add(BandInfo(band, freq, minLevel, maxLevel, current))
        }
        return list
    }

    fun setBandLevel(band: Short, level: Short, context: Context) {
        try {
            equalizer?.setBandLevel(band, level)
            context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
                .edit().putInt("band_$band", level.toInt()).apply()
        } catch (e: Exception) {
            Log.e(TAG, "Error setting band $band level", e)
        }
    }

    fun getPresets(): List<String> {
        val eq = equalizer ?: return emptyList()
        val numPresets = eq.numberOfPresets.toInt()
        val list = mutableListOf<String>()
        for (i in 0 until numPresets) {
            list.add(eq.getPresetName(i.toShort()))
        }
        return list
    }

    fun usePreset(presetIndex: Short, context: Context) {
        try {
            equalizer?.usePreset(presetIndex)
            context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
                .edit().putInt("preset_index", presetIndex.toInt()).apply()
        } catch (e: Exception) {
            Log.e(TAG, "Error using preset $presetIndex", e)
        }
    }

    fun setBassBoost(strength: Short, context: Context) {
        try {
            bassBoost?.setStrength(strength)
            context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
                .edit().putInt("bass_strength", strength.toInt()).apply()
        } catch (e: Exception) {
            Log.e(TAG, "Error setting bass boost", e)
        }
    }

    fun getBassBoostStrength(): Short {
        return bassBoost?.roundedStrength ?: 0
    }

    fun setVirtualizer(strength: Short, context: Context) {
        try {
            virtualizer?.setStrength(strength)
            context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
                .edit().putInt("virtualizer_strength", strength.toInt()).apply()
        } catch (e: Exception) {
            Log.e(TAG, "Error setting virtualizer", e)
        }
    }

    fun getVirtualizerStrength(): Short {
        return virtualizer?.roundedStrength ?: 0
    }

    private fun loadPreferences(context: Context) {
        val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        val eq = equalizer ?: return

        val enabled = prefs.getBoolean("eq_enabled", true)
        eq.enabled = enabled
        bassBoost?.enabled = enabled
        virtualizer?.enabled = enabled

        val preset = prefs.getInt("preset_index", -1)
        if (preset in 0 until eq.numberOfPresets) {
            eq.usePreset(preset.toShort())
        } else {
            for (i in 0 until eq.numberOfBands) {
                val band = i.toShort()
                val saved = prefs.getInt("band_$band", 0).toShort()
                eq.setBandLevel(band, saved)
            }
        }

        val bass = prefs.getInt("bass_strength", 0).toShort()
        bassBoost?.setStrength(bass)

        val virt = prefs.getInt("virtualizer_strength", 0).toShort()
        virtualizer?.setStrength(virt)
    }

    fun release() {
        try {
            equalizer?.release()
            bassBoost?.release()
            virtualizer?.release()
        } catch (e: Exception) {
            Log.e(TAG, "Error releasing audio effects", e)
        } finally {
            equalizer = null
            bassBoost = null
            virtualizer = null
            currentSessionId = 0
        }
    }
}
