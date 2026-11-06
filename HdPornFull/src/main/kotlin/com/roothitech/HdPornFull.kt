package com.roothitech

/**
 * Contract the host app binds to. Host owns UI, player, networking.
 * Plugin only returns structured data.
 */
interface Provider {

    val id: String
    val name: String
    val version: String

    fun init(config: ProviderConfig)

    suspend fun home(page: Int = 1): ProviderResult
    suspend fun category(slug: String, page: Int = 1): ProviderResult
    suspend fun search(query: String, page: Int = 1): ProviderResult
    suspend fun resolve(videoPageUrl: String): StreamInfo

    fun release()
}

data class ProviderResult(
    val items: List<VideoItem>,
    val page: Int,
    val hasNext: Boolean
)

data class VideoItem(
    val id: String,
    val title: String,
    val thumb: String,
    val pageUrl: String,
    val durationSec: Int? = null,
    val tags: List<String> = emptyList()
)

data class StreamInfo(
    val videoUrl: String? = null,
    val hlsUrl: String? = null,
    val headers: Map<String, String> = emptyMap()
)