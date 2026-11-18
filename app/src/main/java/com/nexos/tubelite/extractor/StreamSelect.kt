package com.nexos.tubelite.extractor

/**
 * Selección de stream de video: lógica pura (sin Android) para poder probarla
 * en JVM. La usa PlaybackManager al cargar video y al cambiar calidad.
 */

/** Elige el formato para [quality] ("Auto" prefiere 720p); null si no hay. */
fun selectVideoFormat(formats: List<VideoFormatOption>, quality: String): VideoFormatOption? {
    if (formats.isEmpty()) return null
    return if (quality == "Auto") {
        formats.find { it.qualityLabel == "720p" } ?: formats.first()
    } else {
        formats.find { it.qualityLabel == quality } ?: formats.first()
    }
}

/**
 * Tamaño (ancho, alto) para limitar la pista HLS, o null en "Auto"
 * (sin límite, deja el adaptativo). Etiquetas raras caen a 720p.
 */
fun videoSizeForQuality(quality: String): Pair<Int, Int>? {
    if (quality == "Auto") return null
    return when (quality) {
        "1080p" -> Pair(1920, 1080)
        "720p" -> Pair(1280, 720)
        "480p" -> Pair(854, 480)
        "360p" -> Pair(640, 360)
        "240p" -> Pair(426, 240)
        else -> Pair(1280, 720)
    }
}
