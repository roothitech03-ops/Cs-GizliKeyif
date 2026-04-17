package com.kraptor

import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.utils.*
import com.lagradost.cloudstream3.utils.ExtractorApi
import com.lagradost.cloudstream3.utils.Quality
import com.lagradost.cloudstream3.utils.ExtractorLink
import org.jsoup.nodes.Element

class WatchAnimeWorld : MainAPI() {
    override var mainUrl = "https://watchanimeworld.net"
    override var name = "WatchAnimeWorld"
    override val hasMainPage = true
    override val lang = "hi"
    override val hasDownloadSupport = true
    override val supportedTypes = setOf(TvType.Anime, TvType.AnimeMovie, TvType.OVA)

    override val mainPage = mainPageOf(
        "$mainUrl/trending?page=" to "Trending",
        "$mainUrl/popular?page=" to "Popular",
        "$mainUrl/latest-updates?page=" to "Latest Updates",
        "$mainUrl/az-list?page=" to "Alphabetical List"
    )

    override suspend fun getMainPage(
        page: Int,
        request: MainPageRequest
    ): HomePageResponse {
        val response = app.get(request.data + page).let { 
            if (it.code == 404) null else it 
        } ?: return newHomePageResponse(request.name, emptyList())

        val document = response.document
        val items = document.select("div.item").mapNotNull { element ->
            element.toSearchResult()
        }

        return newHomePageResponse(request.name, items)
    }

    private fun Element.toSearchResult(): AnimeSearchResponse? {
        val title = this.selectFirst("h3.title")?.text()?.trim() ?: return null
        val href = this.selectFirst("a")?.attr("href")?.trim() ?: return null
        val posterUrl = this.selectFirst("img")?.attr("src")?.trim()
        
        if (posterUrl.isNullOrBlank()) return null

        val url = if (href.startsWith("/")) "$mainUrl$href" else href
        
        return newAnimeSearchResponse(
            title = title,
            url = url,
            type = TvType.Anime
        ) {
            this.posterUrl = fixUrl(url, posterUrl)
        }
    }

    override suspend fun search(query: String): List<SearchResponse> {
        val url = "$mainUrl/search?keyword=${Uri.encode(query)}"
        val response = app.get(url).let {
            if (it.code == 404) null else it
        } ?: return emptyList()

        val document = response.document
        return document.select("div.item").mapNotNull { it.toSearchResult() }
    }

    override suspend fun load(url: String): LoadResponse? {
        val response = app.get(url).let {
            if (it.code == 404) null else it
        } ?: return null

        val document = response.document

        val title = document.selectFirst("h1.title")?.text()?.trim() ?: return null
        val poster = document.selectFirst("div.poster img")?.attr("src")
        val year = document.selectFirst("span.year")?.text()?.trim()?.toIntOrNull()
        val description = document.selectFirst("div.description")?.text()?.trim()
        val tags = document.select("div.genres a").map { it.text().trim() }
        val scoreText = document.selectFirst("span.rating")?.text()?.trim()
        val score = scoreText?.toDoubleOrNull()

        val episodeLinks = document.select("div.episodes-list a")
        val episodes = episodeLinks.mapNotNull { element ->
            val epTitle = element.selectFirst("span.ep-title")?.text()?.trim()
            val epUrl = element.attr("href").trim()
            
            if (epTitle != null && epUrl.isNotEmpty()) {
                Episode(
                    data = epUrl,
                    name = epTitle,
                    url = if (epUrl.startsWith("/")) "$mainUrl$epUrl" else epUrl
                )
            } else null
        }.reversed()

        if (episodes.isEmpty()) return null

        return newAnimeLoadResponse(
            title = title,
            url = url,
            type = TvType.Anime
        ) {
            this.posterUrl = if (poster != null) fixUrl(url, poster) else null
            this.year = year
            this.plot = description
            this.tags = tags
            this.score = score?.toFloatOrNull()
            addEpisodes(DubStatus.Subbed, episodes)
        }
    }

    override suspend fun loadLinks(
        data: String,
        isCasting: Boolean,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        val response = app.get(data).let {
            if (it.code == 404) null else it
        } ?: return false

        val document = response.document
        
        // Extract video sources from script tags
        document.select("script").forEach { script ->
            val scriptData = script.data().trim()
            if (scriptData.contains("sources") || scriptData.contains("file")) {
                // Extract direct video links
                val videoRegex = Regex("""["']file["']\s*:\s*["']([^"'\s]+)""")
                videoRegex.findAll(scriptData).forEach { matchResult ->
                    val videoUrl = matchResult.groupValues[1]
                    if (videoUrl.isNotEmpty()) {
                        callback.invoke(
                            ExtractorLink(
                                source = name,
                                name = name,
                                url = videoUrl,
                                referer = data,
                                quality = if (videoUrl.contains(".m3u8")) Quality.M3U8 else Quality.Unknown,
                                isM3u8 = videoUrl.contains(".m3u8")
                            )
                        )
                    }
                }
            }
        }

        // Extract from iframes
        document.select("iframe").forEach { iframe ->
            val src = iframe.attr("src")
            if (src.isNotEmpty()) {
                val iframeUrl = if (src.startsWith("http")) src else "$mainUrl$src"
                try {
                    val iframeDoc = app.get(iframeUrl).document
                    val videoSource = iframeDoc.selectFirst("video > source") ?: iframeDoc.selectFirst("video source")
                    val srcAttr = videoSource?.attr("src") ?: ""
                    
                    if (srcAttr.isNotEmpty()) {
                        callback.invoke(
                            ExtractorLink(
                                source = name,
                                name = name,
                                url = srcAttr,
                                referer = iframeUrl,
                                quality = if (srcAttr.contains(".m3u8")) Quality.M3U8 else Quality.Unknown,
                                isM3u8 = srcAttr.contains(".m3u8")
                            )
                        )
                    }
                } catch (e: Exception) {
                    // Ignore iframe errors
                }
            }
        }

        // Try to load streams from known providers
        loadExtractor(data, data, subtitleCallback, callback)

        return true
    }
}