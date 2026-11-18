package com.nexos.tubelite.extractor

/**
 * Reintentos de pista con error: uno por pista; al sonar bien se reinicia.
 * Puro (sin Android) para probarlo en JVM.
 */
class TrackRetry {
    private var lastKey: String? = null

    /** true la primera vez que falla [key]; false si es el mismo fallo seguido. */
    fun shouldRetry(key: String): Boolean {
        if (lastKey == key) return false
        lastKey = key
        return true
    }

    fun onRecovered() {
        lastKey = null
    }
}
