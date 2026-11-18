/* Portado de mUzVidX (https:\/\/github.com\/Spartan117x\/mUzVidX) bajo GPL-3.0. */
package com.nexos.tubelite.extractor

import android.util.Log
import com.google.gson.Gson
import com.google.gson.JsonArray
import com.google.gson.JsonObject
import com.nexos.tubelite.model.ResultType
import com.nexos.tubelite.model.SearchMode
import com.nexos.tubelite.model.SearchResult
import com.nexos.tubelite.model.Source
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.net.HttpURLConnection
import java.net.URL

data class VideoFormatOption(
    val itag: Int,
    val qualityLabel: String,     // e.g. "1080p", "720p", "480p", "360p", "240p"
    val videoUrl: String,
    val audioUrl: String? = null,
    val sizeBytes: Long = 0L,
    val isProgressive: Boolean = false
)

data class VideoStreamsBundle(
    val videoId: String,
    val hlsManifestUrl: String?,
    val audioUrl: String?,
    val formats: List<VideoFormatOption>,
    val defaultStreamUrl: String
)

class YouTubeService {

    companion object {
        private const val TAG = "YouTubeService"
        private const val MUSIC_SEARCH_URL = "https://music.youtube.com/youtubei/v1/search"
        private const val WEB_SEARCH_URL = "https://www.youtube.com/youtubei/v1/search"
        private const val PLAYER_URL = "https://www.youtube.com/youtubei/v1/player"
        private const val USER_AGENT_WEB =
            "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120.0.0.0 Safari/537.36"
        private const val USER_AGENT_IOS =
            "com.google.ios.youtube/20.11.6 (iPhone10,4; U; CPU iOS 16_7_7 like Mac OS X)"
        private const val USER_AGENT_ANDROID =
            "com.google.android.youtube/21.03.36(Linux; U; Android 16; en_US; SM-S908E Build/TP1A.220624.014) gzip"

        // Params oficiales de YouTube Music
        private const val PARAMS_MUSIC_SONGS = "EgWKAQIIAWoQEAMQBBAJEAoQBRAREBAQFQ%3D%3D"
        private const val PARAMS_MUSIC_VIDEOS = "EgWKAQIQAWoQEAMQBBAJEAoQBRAREBAQFQ%3D%3D"

        fun cleanVideoId(rawUrlOrId: String): String {
            var s = rawUrlOrId.trim()
            if (s.contains("v=")) {
                s = s.substringAfter("v=").substringBefore("&")
            } else if (s.contains("youtu.be/")) {
                s = s.substringAfter("youtu.be/").substringBefore("?").substringBefore("&")
            } else if (s.contains("/shorts/")) {
                s = s.substringAfter("/shorts/").substringBefore("?").substringBefore("&")
            } else if (s.contains("/embed/")) {
                s = s.substringAfter("/embed/").substringBefore("?").substringBefore("&")
            }
            return s.removePrefix("yt_").removePrefix("yt_vid_")
        }
    }

    suspend fun search(query: String, mode: SearchMode, limit: Int = 30): List<SearchResult> =
        withContext(Dispatchers.IO) {
            val results = mutableListOf<SearchResult>()
            try {
                when (mode) {
                    SearchMode.LIBRARY -> {
                        // Canciones oficiales de catálogo (YouTube Music)
                        results.addAll(searchYouTubeMusic(query, isVideoFilter = false, limit))
                    }
                    SearchMode.VIDEOS -> {
                        // Videos musicales oficiales (Videoclips de YouTube Music)
                        results.addAll(searchYouTubeMusic(query, isVideoFilter = true, limit))
                    }
                    SearchMode.FREE -> {
                        // Búsqueda general en YouTube Web (Modo Libre)
                        results.addAll(searchYouTubeWeb(query, limit))
                    }
                }
            } catch (e: Exception) {
                Log.e(TAG, "Search failed for mode $mode", e)
            }
            results
        }

    /**
     * Búsqueda en YouTube Music
     * isVideoFilter = false -> Filtro de Canciones (Audio HQ + Carátula HD)
     * isVideoFilter = true  -> Filtro de Videoclips Oficiales en movimiento
     */
    private fun searchYouTubeMusic(query: String, isVideoFilter: Boolean, limit: Int): List<SearchResult> {
        val filterParam = if (isVideoFilter) PARAMS_MUSIC_VIDEOS else PARAMS_MUSIC_SONGS
        val payload = """
            {
                "query": "${jsonEscape(query)}",
                "params": "$filterParam",
                "context": {
                    "client": {
                        "clientName": "WEB_REMIX",
                        "clientVersion": "1.20240101.01.00",
                        "hl": "es",
                        "gl": "US"
                    }
                }
            }
        """.trimIndent()

        val json = post(MUSIC_SEARCH_URL, payload, USER_AGENT_WEB) ?: return emptyList()
        val results = mutableListOf<SearchResult>()

        try {
            val tabs = json.getAsJsonObject("contents")
                ?.getAsJsonObject("tabbedSearchResultsRenderer")
                ?.getAsJsonArray("tabs") ?: return emptyList()

            if (tabs.size() == 0) return emptyList()

            val sections = tabs.get(0).asJsonObject
                .getAsJsonObject("tabRenderer")
                ?.getAsJsonObject("content")
                ?.getAsJsonObject("sectionListRenderer")
                ?.getAsJsonArray("contents") ?: return emptyList()

            for (secElement in sections) {
                val sec = secElement.asJsonObject

                // 1. Estante principal de canciones o videos filtrados (musicShelfRenderer)
                val shelf = sec.getAsJsonObject("musicShelfRenderer")
                if (shelf != null) {
                    val items = shelf.getAsJsonArray("contents") ?: continue
                    for (itemElement in items) {
                        val item = itemElement.asJsonObject.getAsJsonObject("musicResponsiveListItemRenderer")
                            ?: continue

                        val cols = item.getAsJsonArray("flexColumns") ?: continue
                        if (cols.size() == 0) continue

                        val titleRuns = cols.get(0).asJsonObject
                            .getAsJsonObject("musicResponsiveListItemFlexColumnRenderer")
                            ?.getAsJsonObject("text")
                            ?.getAsJsonArray("runs")
                        val title = runsToText(titleRuns)

                        var artist = if (isVideoFilter) "Video Oficial" else "Oficial"
                        var durationSec = 0L

                        if (cols.size() > 1) {
                            val infoRuns = cols.get(1).asJsonObject
                                .getAsJsonObject("musicResponsiveListItemFlexColumnRenderer")
                                ?.getAsJsonObject("text")
                                ?.getAsJsonArray("runs")

                            if (infoRuns != null && infoRuns.size() > 0) {
                                artist = infoRuns.get(0).asJsonObject.get("text")?.asString ?: artist
                                for (r in infoRuns) {
                                    val t = r.asJsonObject.get("text")?.asString ?: ""
                                    if (t.contains(":") && t.length in 3..8) {
                                        durationSec = parseDuration(t)
                                    }
                                }
                            }
                        }

                        var videoId = item.getAsJsonObject("playlistItemData")
                            ?.get("videoId")?.asString ?: ""

                        if (videoId.isEmpty()) {
                            videoId = item.getAsJsonObject("overlay")
                                ?.getAsJsonObject("musicItemThumbnailOverlayRenderer")
                                ?.getAsJsonObject("content")
                                ?.getAsJsonObject("musicPlayButtonRenderer")
                                ?.getAsJsonObject("playNavigationEndpoint")
                                ?.getAsJsonObject("watchEndpoint")
                                ?.get("videoId")?.asString ?: ""
                        }

                        val thumb = extractThumb(
                            item.getAsJsonObject("thumbnail")
                                ?.getAsJsonObject("musicThumbnailRenderer")
                                ?.getAsJsonObject("thumbnail")
                                ?.getAsJsonArray("thumbnails"),
                            videoId
                        )

                        if (videoId.isNotEmpty() && title.isNotEmpty()) {
                            results.add(
                                SearchResult(
                                    id = "yt_$videoId",
                                    title = title,
                                    artist = artist,
                                    source = Source.YOUTUBE,
                                    type = if (isVideoFilter) ResultType.VIDEO else ResultType.TRACK,
                                    durationSec = durationSec,
                                    thumbUrl = thumb,
                                    audioUrl = "https://www.youtube.com/watch?v=$videoId",
                                    videoUrl = "https://www.youtube.com/watch?v=$videoId",
                                    isOfficial = true,
                                    officialChannel = artist,
                                    isCommunity = false
                                )
                            )
                        }
                        if (results.size >= limit) break
                    }
                }

                // 2. Tarjeta principal (musicCardShelfRenderer) - solo en modo canciones
                if (!isVideoFilter) {
                    val card = sec.getAsJsonObject("musicCardShelfRenderer")
                    if (card != null) {
                        val titleRuns = card.getAsJsonObject("title")?.getAsJsonArray("runs")
                        val title = runsToText(titleRuns)
                        val subtitleRuns = card.getAsJsonObject("subtitle")?.getAsJsonArray("runs")
                        val subtitle = runsToText(subtitleRuns)
                        var videoId = ""

                        if (titleRuns != null && titleRuns.size() > 0) {
                            videoId = titleRuns.get(0).asJsonObject
                                .getAsJsonObject("navigationEndpoint")
                                ?.getAsJsonObject("watchEndpoint")
                                ?.get("videoId")?.asString ?: ""
                        }

                        val thumb = extractThumb(
                            card.getAsJsonObject("thumbnail")
                                ?.getAsJsonObject("musicThumbnailRenderer")
                                ?.getAsJsonObject("thumbnail")
                                ?.getAsJsonArray("thumbnails"),
                            videoId
                        )

                        if (videoId.isNotEmpty() && title.isNotEmpty()) {
                            results.add(
                                0,
                                SearchResult(
                                    id = "yt_$videoId",
                                    title = title,
                                    artist = subtitle.ifEmpty { "Canal Oficial" },
                                    source = Source.YOUTUBE,
                                    type = ResultType.TRACK,
                                    durationSec = 0,
                                    thumbUrl = thumb,
                                    audioUrl = "https://www.youtube.com/watch?v=$videoId",
                                    videoUrl = "https://www.youtube.com/watch?v=$videoId",
                                    isOfficial = true,
                                    officialChannel = subtitle,
                                    isCommunity = false
                                )
                            )
                        }
                    }
                }
                if (results.size >= limit) break
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error parsing YouTube Music results", e)
        }

        return results
    }

    /**
     * Búsqueda en YouTube Web Normal (Modo Libre)
     */
    fun searchYouTubeWeb(query: String, limit: Int = 30): List<SearchResult> {
        val payload = """
            {
                "query": "${jsonEscape(query)}",
                "context": {
                    "client": {
                        "clientName": "WEB",
                        "clientVersion": "2.20240101.01.00",
                        "hl": "es",
                        "gl": "US"
                    }
                }
            }
        """.trimIndent()

        val json = post(WEB_SEARCH_URL, payload, USER_AGENT_WEB) ?: return emptyList()
        val results = mutableListOf<SearchResult>()

        try {
            val sections = json.getAsJsonObject("contents")
                ?.getAsJsonObject("twoColumnSearchResultsRenderer")
                ?.getAsJsonObject("primaryContents")
                ?.getAsJsonObject("sectionListRenderer")
                ?.getAsJsonArray("contents") ?: return emptyList()

            for (secElement in sections) {
                val itemSec = secElement.asJsonObject.getAsJsonObject("itemSectionRenderer") ?: continue
                val items = itemSec.getAsJsonArray("contents") ?: continue

                for (itemElement in items) {
                    val vr = itemElement.asJsonObject.getAsJsonObject("videoRenderer") ?: continue
                    val videoId = vr.get("videoId")?.asString ?: continue

                    val title = runsToText(vr.getAsJsonObject("title")?.getAsJsonArray("runs"))
                    val channel = runsToText(vr.getAsJsonObject("ownerText")?.getAsJsonArray("runs"))
                    val durText = vr.getAsJsonObject("lengthText")?.get("simpleText")?.asString ?: "0:00"
                    val durationSec = parseDuration(durText)

                    val thumb = extractThumb(
                        vr.getAsJsonObject("thumbnail")?.getAsJsonArray("thumbnails"),
                        videoId
                    )

                    results.add(
                        SearchResult(
                            id = "yt_$videoId",
                            title = title,
                            artist = channel.ifEmpty { "YouTube" },
                            source = Source.YOUTUBE,
                            type = ResultType.VIDEO,
                            durationSec = durationSec,
                            thumbUrl = thumb,
                            audioUrl = "https://www.youtube.com/watch?v=$videoId",
                            videoUrl = "https://www.youtube.com/watch?v=$videoId",
                            isOfficial = false,
                            officialChannel = channel,
                            isCommunity = true
                        )
                    )

                    if (results.size >= limit) break
                }
                if (results.size >= limit) break
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error parsing YouTube Web results", e)
        }

        return results
    }

    /**
     * Resuelve el videoclip oficial real (no la carátula fija) a partir del título y artista
     */
    suspend fun findOfficialVideo(title: String, artist: String): SearchResult? =
        withContext(Dispatchers.IO) {
            try {
                val cleanTitle = title.replace(
                    Regex("(?i)\\(official.*?\\)|\\[official.*?\\]|\\(audio\\)|\\[audio\\]|\\(video.*?\\)|\\[video.*?\\]"),
                    ""
                ).trim()
                val cleanArtist = if (artist.equals("Oficial", ignoreCase = true) || artist.isEmpty()) "" else artist

                // 1. Primero intentar en YouTube Music con filtro de videos oficiales
                val musicVideoResults = searchYouTubeMusic("$cleanArtist $cleanTitle", isVideoFilter = true, limit = 5)
                if (musicVideoResults.isNotEmpty()) {
                    return@withContext musicVideoResults[0]
                }

                // 2. Fallback a búsqueda Web para video oficial
                val webQuery = "$cleanArtist $cleanTitle official video".trim()
                val webResults = searchYouTubeWeb(webQuery, limit = 5)
                if (webResults.isNotEmpty()) {
                    return@withContext webResults[0]
                }
            } catch (e: Exception) {
                Log.e(TAG, "Error finding official video for $title", e)
            }
            null
        }

    /**
     * Resuelve el stream directo real (audio AAC o video MP4) sin dependencias externas
     */
    suspend fun resolveStreamUrl(videoId: String, isAudioOnly: Boolean): String? =
        withContext(Dispatchers.IO) {
            var cleanId = videoId.trim()
            if (cleanId.contains("v=")) {
                cleanId = cleanId.substringAfter("v=").substringBefore("&")
            } else if (cleanId.contains("youtu.be/")) {
                cleanId = cleanId.substringAfter("youtu.be/").substringBefore("?").substringBefore("&")
            } else if (cleanId.contains("/shorts/")) {
                cleanId = cleanId.substringAfter("/shorts/").substringBefore("?").substringBefore("&")
            } else if (cleanId.contains("/embed/")) {
                cleanId = cleanId.substringAfter("/embed/").substringBefore("?").substringBefore("&")
            }
            cleanId = cleanId.removePrefix("yt_").removePrefix("yt_vid_")

            // 1. Cliente ANDROID para stream progresivo (itag 18: video MP4 360p + audio AAC estéreo)
            // Este stream garantiza HTTP 200 directo sin bloqueos 403, sin ciphers n-sig
            val androidPayload = """
                {
                    "videoId": "$cleanId",
                    "context": {
                        "client": {
                            "clientName": "ANDROID",
                            "clientVersion": "21.03.36",
                            "androidSdkVersion": 36,
                            "hl": "es",
                            "gl": "US"
                        }
                    }
                }
            """.trimIndent()

            val json = post(PLAYER_URL, androidPayload, USER_AGENT_ANDROID)
            val progUrl = extractProgressiveFromPlayerJson(json)
            if (!progUrl.isNullOrEmpty()) {
                Log.i(TAG, "Stream progresivo resuelto (ANDROID itag 18): $cleanId")
                return@withContext progUrl
            }

            // Fallback: Cliente IOS para extraer audio si no hay progresivo
            val iosPayload = """
                {
                    "videoId": "$cleanId",
                    "context": {
                        "client": {
                            "clientName": "IOS",
                            "clientVersion": "20.11.6",
                            "deviceModel": "iPhone10,4",
                            "hl": "es",
                            "gl": "US"
                        }
                    }
                }
            """.trimIndent()

            val iosJson = post(PLAYER_URL, iosPayload, USER_AGENT_IOS)
            val streamUrl = extractAudioFromPlayerJson(iosJson)
            if (!streamUrl.isNullOrEmpty()) {
                Log.i(TAG, "Stream audio resuelto (IOS): $cleanId")
                return@withContext streamUrl
            }

            null
        }

    /**
     * Resuelve los streams en múltiples resoluciones disponibles (1080p, 720p, 480p, 360p, 240p)
     * extrayendo tanto el manifiesto HLS como los formatos progresivos y adaptativos con URLs directas HTTP 200.
     */
    suspend fun resolveVideoStreams(videoId: String): VideoStreamsBundle? =
        withContext(Dispatchers.IO) {
            val cleanId = cleanVideoId(videoId)
            try {
                // 1. Consultar cliente IOS (proporciona URLs directas para video 1080p, 720p, 480p, 360p y audio AAC)
                val iosPayload = """
                    {
                        "videoId": "$cleanId",
                        "context": {
                            "client": {
                                "clientName": "IOS",
                                "clientVersion": "20.11.6",
                                "deviceModel": "iPhone10,4",
                                "hl": "es",
                                "gl": "US"
                            }
                        }
                    }
                """.trimIndent()
                val iosJson = post(PLAYER_URL, iosPayload, USER_AGENT_IOS)
                val streamingData = iosJson?.getAsJsonObject("streamingData")

                val hlsUrl = streamingData?.get("hlsManifestUrl")?.asString
                val adaptive = streamingData?.getAsJsonArray("adaptiveFormats") ?: JsonArray()

                // Extraer el mejor stream de audio AAC/M4A
                var bestAudioUrl: String? = null
                var bestAudioBitrate = 0L
                for (elem in adaptive) {
                    val f = elem.asJsonObject
                    val mime = f.get("mimeType")?.asString ?: ""
                    val url = f.get("url")?.asString ?: continue
                    val bitrate = f.get("bitrate")?.asLong ?: 0L
                    if (mime.contains("audio/mp4")) {
                        if (bitrate > bestAudioBitrate) {
                            bestAudioBitrate = bitrate
                            bestAudioUrl = url
                        }
                    } else if (bestAudioUrl == null && mime.contains("audio")) {
                        bestAudioUrl = url
                    }
                }

                // Extraer streams de video por resolución (1080p, 720p, 480p, 360p, 240p)
                val formatMap = mutableMapOf<String, VideoFormatOption>()
                for (elem in adaptive) {
                    val f = elem.asJsonObject
                    val mime = f.get("mimeType")?.asString ?: ""
                    val url = f.get("url")?.asString ?: continue
                    val ql = f.get("qualityLabel")?.asString ?: continue
                    val itag = f.get("itag")?.asInt ?: 0
                    val size = f.get("contentLength")?.asLong ?: 0L

                    if (mime.contains("video/mp4")) {
                        val cleanQl = ql.split(" ")[0].trim() // "1080p", "720p", etc.
                        if (!formatMap.containsKey(cleanQl)) {
                            formatMap[cleanQl] = VideoFormatOption(
                                itag = itag,
                                qualityLabel = cleanQl,
                                videoUrl = url,
                                audioUrl = bestAudioUrl,
                                sizeBytes = size,
                                isProgressive = false
                            )
                        }
                    }
                }

                // 2. Consultar cliente ANDROID para stream progresivo 360p muxed (itag 18)
                var progUrl: String? = null
                try {
                    val androidPayload = """
                        {
                            "videoId": "$cleanId",
                            "context": {
                                "client": {
                                    "clientName": "ANDROID",
                                    "clientVersion": "21.03.36",
                                    "androidSdkVersion": 36,
                                    "hl": "es",
                                    "gl": "US"
                                }
                            }
                        }
                    """.trimIndent()
                    val androidJson = post(PLAYER_URL, androidPayload, USER_AGENT_ANDROID)
                    progUrl = extractProgressiveFromPlayerJson(androidJson)
                    if (!progUrl.isNullOrEmpty()) {
                        formatMap["360p"] = VideoFormatOption(
                            itag = 18,
                            qualityLabel = "360p",
                            videoUrl = progUrl,
                            audioUrl = null,
                            sizeBytes = 0L,
                            isProgressive = true
                        )
                    }
                } catch (e: Exception) {
                    Log.w(TAG, "Error fetching ANDROID progressive format: ${e.message}")
                }

                // Ordenar resoluciones de mayor a menor calidad
                val sortedFormats = formatMap.values.sortedByDescending { opt ->
                    when (opt.qualityLabel) {
                        "2160p" -> 2160
                        "1440p" -> 1440
                        "1080p" -> 1080
                        "720p" -> 720
                        "480p" -> 480
                        "360p" -> 360
                        "240p" -> 240
                        "144p" -> 144
                        else -> 0
                    }
                }

                val defaultUrl = progUrl
                    ?: formatMap["720p"]?.videoUrl
                    ?: formatMap["360p"]?.videoUrl
                    ?: sortedFormats.firstOrNull()?.videoUrl
                    ?: ""

                Log.i(TAG, "Streams resueltos para $cleanId: ${sortedFormats.map { it.qualityLabel }} (HLS=${!hlsUrl.isNullOrEmpty()})")

                VideoStreamsBundle(
                    videoId = cleanId,
                    hlsManifestUrl = hlsUrl,
                    audioUrl = bestAudioUrl,
                    formats = sortedFormats,
                    defaultStreamUrl = defaultUrl
                )
            } catch (e: Exception) {
                Log.e(TAG, "Error resolving video streams for $cleanId: ${e.message}", e)
                null
            }
        }

    private fun extractAudioFromPlayerJson(json: JsonObject?): String? {
        if (json == null) return null
        val streamingData = json.getAsJsonObject("streamingData") ?: return null
        val adaptiveFormats = streamingData.getAsJsonArray("adaptiveFormats") ?: return null

        var bestUrl: String? = null
        var bestBitrate = 0L

        for (formatElement in adaptiveFormats) {
            val f = formatElement.asJsonObject
            val mime = f.get("mimeType")?.asString ?: ""
            val url = f.get("url")?.asString ?: continue
            val bitrate = f.get("bitrate")?.asLong ?: 0L

            if (mime.contains("audio")) {
                if (mime.contains("audio/mp4")) {
                    if (bitrate > bestBitrate) {
                        bestBitrate = bitrate
                        bestUrl = url
                    }
                } else if (bestUrl == null) {
                    bestUrl = url
                }
            }
        }
        return bestUrl
    }

    private fun extractProgressiveFromPlayerJson(json: JsonObject?): String? {
        if (json == null) return null
        val streamingData = json.getAsJsonObject("streamingData") ?: return null
        val formats = streamingData.getAsJsonArray("formats") ?: return null

        for (formatElement in formats) {
            val f = formatElement.asJsonObject
            val mime = f.get("mimeType")?.asString ?: ""
            val url = f.get("url")?.asString ?: continue
            if (mime.contains("video/mp4")) {
                return url
            }
        }
        return null
    }

    private fun runsToText(runs: JsonArray?): String {
        if (runs == null) return ""
        val sb = StringBuilder()
        for (r in runs) {
            val t = r.asJsonObject.get("text")?.asString
            if (!t.isNullOrEmpty()) sb.append(t)
        }
        return sb.toString().trim()
    }

    /**
     * Extrae y optimiza la URL de carátula para garantizar Ultra-HD (800x800 o hqdefault).
     * Elimina miniaturas borrosas o de baja resolución.
     */
    private fun extractThumb(thumbnails: JsonArray?, videoId: String = ""): String {
        if (thumbnails == null || thumbnails.size() == 0) {
            return if (videoId.isNotEmpty()) "https://i.ytimg.com/vi/$videoId/hqdefault.jpg" else ""
        }
        val last = thumbnails.get(thumbnails.size() - 1).asJsonObject
        var url = last.get("url")?.asString ?: ""
        if (url.startsWith("//")) {
            url = "https:$url"
        }

        // Mejora a Ultra-HD
        if (url.contains("googleusercontent.com")) {
            url = url.replace(Regex("=w\\d+-h\\d+.*"), "=w800-h800-l90-rj")
            if (url.contains("=s") && !url.contains("=w800")) {
                url = url.replace(Regex("=s\\d+.*"), "=s800-c")
            }
        } else if (url.contains("i.ytimg.com/vi/")) {
            val id = url.substringAfter("i.ytimg.com/vi/").substringBefore("/")
            if (id.isNotEmpty() && id.length == 11) {
                url = "https://i.ytimg.com/vi/$id/hqdefault.jpg"
            }
        }
        return url
    }

    private fun parseDuration(text: String): Long {
        return try {
            val parts = text.split(":").map { it.trim().toLong() }
            if (parts.size == 3) parts[0] * 3600 + parts[1] * 60 + parts[2]
            else if (parts.size == 2) parts[0] * 60 + parts[1]
            else parts[0]
        } catch (e: Exception) {
            0
        }
    }

    private fun post(urlString: String, body: String, userAgent: String): JsonObject? {
        return try {
            val conn = (URL(urlString).openConnection() as HttpURLConnection).apply {
                requestMethod = "POST"
                doOutput = true
                connectTimeout = 8000
                readTimeout = 12000
                setRequestProperty("Content-Type", "application/json")
                setRequestProperty("User-Agent", userAgent)
            }
            conn.outputStream.use { it.write(body.toByteArray(Charsets.UTF_8)) }
            if (conn.responseCode == 200) {
                conn.inputStream.bufferedReader(Charsets.UTF_8).use {
                    Gson().fromJson(it.readText(), JsonObject::class.java)
                }
            } else {
                Log.w(TAG, "HTTP ${conn.responseCode} from $urlString")
                null
            }
        } catch (e: Exception) {
            Log.e(TAG, "Request failed to $urlString: ${e.message}")
            null
        }
    }

    private fun jsonEscape(s: String): String =
        s.replace("\\", "\\\\").replace("\"", "\\\"").replace("\n", " ")
}
