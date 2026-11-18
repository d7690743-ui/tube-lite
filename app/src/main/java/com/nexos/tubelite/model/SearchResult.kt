/* Portado de mUzVidX (https:\/\/github.com\/Spartan117x\/mUzVidX) bajo GPL-3.0. */
package com.nexos.tubelite.model

enum class Source { YOUTUBE, SOUNDCLOUD }
enum class ResultType { TRACK, VIDEO }
enum class SearchMode { LIBRARY, VIDEOS, FREE }

data class SearchResult(
    val id: String,
    val title: String,
    val artist: String,
    val source: Source,
    val type: ResultType,
    val durationSec: Long,
    val thumbUrl: String,
    val audioUrl: String,
    val videoUrl: String,
    val isOfficial: Boolean = false,
    val officialChannel: String = "",
    val isCommunity: Boolean = false,
    val companionVideoId: String? = null
)
