package com.kraptor

import com.fasterxml.jackson.annotation.JsonProperty
import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.LoadResponse.Companion.addTrailer
import com.lagradost.cloudstream.utils.ExtractorLink
import com.lagradost.cloudstream.utils.M3u8Helper
import com.lagradost.cloudstream3.utils.AppUtils.tryParseJson
import org.jsoup.Jsoup
import org.jsoup.nodes.Document
import org.jsoup.nodes.Element

class WatchAnimeWorld : MainAPI() {

    // Main variables
    override var mainUrl = "https://watchanimeworld.net"
    override var name = "Watch Anime World"
    override val hasMainPage = true
    override var lang = "hi"
    override val hasDownloadSupport = true
    override val supportedTypes = setOf(
        TvType.Anime,
        TvType.AnimeMovie,
        TvType.OVA
    )

    // Companion object for constants
    companion object {
        const val USER_AGENT = "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120.0.0.0 Safari/537.36"
    }

    // Main page categories
    override val mainPage = listOf(
        MainPageRequest(
            "/",
            "Trending",
            fixedUrl = true
        ),
        MainPageRequest(
            "/dubbed-anime/",
            "Dubbed",
            fixedUrl = true
        ),
        MainPageRequest(
            "/subbed-anime/",
            "Subbed", 
            fixedUrl = true
        ),
        MainPageRequest(
            "/movies/",
            "Movies",
            fixedUrl = true
        ),
        MainPageRequest(
            "/popular/",
            "Popular",
            fixedUrl = true
        ),
        MainPageRequest(
            "/genre/action/",
            "Action",
            fixedUrl = true
        )
    )

    // Get main page data
    override suspend fun getMainPage(
        page: Int,
        request: MainPageRequest
    ): HomePageResponse {
        val url = if (page == 1) {
            request.data
        } else {
            if (request.data.contains("?")) {
                "${request.data}&page=$page"
            } else {
                "${request.data}page/$page/"
            }
        }
        
        val document = app.get(url).document
        val items = document.select("div.video-block, div.item, article.post, div.anime-card, div.bloques, li.video-block")
            .mapNotNull { element ->
                element.toSearchResult()
            }
        
        return newHomePageResponse(request.name, items)
    }

    // Parse search result from element
    private fun Element.toSearchResult(): SearchResponse? {
        return try {
            // Try different title selectors
            val titleElement = this.selectFirst("h2.title, h3.title, a.title, .post-title a, .anime-title, .name a")
                ?: this.selectFirst("a[href*='/anime/'], a[href*='/watch/']")
                ?: return@tryParseJson null
            
            val title = titleElement.text().trim().takeIf { it.isNotEmpty() } ?: return@tryParseJson null
            
            // Get href
            val href = titleElement.attr("href")
                ?: this.selectFirst("a")?.attr("href")
                ?: return@tryParseJson null
            
            // Get poster
            val poster = this.selectFirst("img")?.run {
                attr("data-src").ifEmpty { 
                    attr("data-lazy-src").ifEmpty { 
                        attr("src").takeIf { !it.contains("placeholder") && !it.contains("empty") } 
                    } 
                }
            }?.let { 
                if (it.startsWith("//")) "https:$it" else it 
            }
            
            newAnimeSearchResponse(title, fixUrl(href)) {
                this.posterUrl = poster
                addSub(1)
            }
        } catch (e: Exception) {
            null
        }
    }

    // Search functionality
    override suspend fun search(query: String): List<SearchResponse> {
        return try {
            val url = "$mainUrl/?s=${query.replace(" ", "+")}"
            val document = app.get(url).document
            
            document.select("div.video-block, div.item, article.post, div.result-item, div.search-result-item")
                .mapNotNull { it.toSearchResult() }
        } catch (e: Exception) {
            emptyList()
        }
    }

    // Load detailed info
    override suspend fun load(url: String): LoadResponse? {
        return try {
            val document = app.get(url).document
            
            // Title - try multiple selectors
            val title = document.selectFirst("h1.entry-title, h1.post-title, h1.anime-title, .anime-info h1, .title h1, .single-anime-title")
                ?.text()?.trim()
                ?: return@tryParseJson null
            
            // Poster
            val poster = document.selectFirst(".poster img, .anime-poster img, .thumb img, .featured-image img, .anime-image img")
                ?.run {
                    attr("data-src").ifEmpty { 
                        attr("data-lazy-src").ifEmpty { 
                            attr("src").takeIf { !it.contains("placeholder") } 
                        } 
                    }
                }?.let { if (it.startsWith("//")) "https:$it" else it }
            
            // Description
            val description = document.selectFirst(".synopsis, .description, .entry-content p, .anime-synopsis, .content-synopsis")
                ?.text()?.trim()
            
            // Year
            val year = document.selectFirst(".year, .release-date, span:contains(Released), .date")
                ?.text()?.trim()
                ?.let { Regex("\\d{4}").find(it)?.value?.toIntOrNull() }
            
            // Tags/Genres
            val tags = document.select(".genres a, .genre a, .tags a, .anime-genres a")
                .mapNotNull { it.text().trim() }
                .filter { it.isNotEmpty() }
            
            // Status
            val statusText = document.selectFirst(".status, span:contains(Status), .anime-status")
                ?.text()?.lowercase()?.trim()
            val status = when {
                statusText?.contains("ongoing") == true -> ShowStatus.Ongoing
                statusText?.contains("completed") == true -> ShowStatus.Completed
                else -> null
            }
            
            // Type
            val typeText = document.selectFirst(".type, span:contains(Type), .anime-type")
                ?.text()?.lowercase() ?: ""
            val type = when {
                typeText.contains("movie") -> TvType.AnimeMovie
                typeText.contains("ova") || typeText.contains("ona") -> TvType.OVA
                else -> TvType.Anime
            }
            
            // Rating
            val rating = document.selectFirst(".rating, .score, span:contains(Rating), .imdb-rating")
                ?.text()?.replace("[^0-9.]".toRegex(), "")
                ?.toFloatOrNull()
                ?.let { if (it <= 10) it * 10 else it }
                ?.toInt()
            
            // Episodes
            val episodes = mutableListOf<Episode>()
            
            // Method 1: Episode list container
            val epList = document.select(".episodes-list li, .episode-list a, .ep-list a, .episodes a, .episodes-range a, .wp-content a[href*='episode']")
            
            if (epList.isNotEmpty()) {
                epList.forEachIndexed { index, ep ->
                    val epTitle = ep.selectFirst(".eptitle, .ep-title, span, .episode-name")
                        ?.text()?.trim()
                        ?: "Episode ${index + 1}"
                    
                    val epHref = ep.attr("href")
                        ?: ep.selectFirst("a")?.attr("href")
                        ?: return@forEachIndexed
                    
                    episodes.add(
                        Episode(
                            data = fixUrl(epHref),
                            name = epTitle,
                            episode = extractEpisodeNumber(epTitle, index + 1),
                            posterUrl = ep.selectFirst("img")?.attr("src")
                        )
                    )
                }
            } else {
                // Method 2: Single page / movie
                episodes.add(
                    Episode(
                        data = url,
                        name = if (type == TvType.AnimeMovie) title else "Episode 1",
                        episode = 1,
                        posterUrl = poster
                    )
                )
            }
            
            // Trailer
            val trailer = document.selectFirst("iframe[src*='youtube'], iframe[src*='youtu.be']")
                ?.attr("src")
                ?.let { src ->
                    if (src.contains("embed")) src
                    else "https://www.youtube.com/embed/${src.substringAfterLast("/").substringBefore("?")}"
                }
            
            // Recommendations
            val recommendations = document.select(".related-anime a, .recommendations a, .also-like a")
                .mapNotNull { rec ->
                    val recTitle = rec.text()?.trim()?.takeIf { it.isNotEmpty() } ?: return@mapNotNull null
                    val recHref = rec.attr("href") ?: return@mapNotNull null
                    newAnimeSearchResponse(recTitle, fixUrl(recHref)) {
                        this.posterUrl = rec.selectFirst("img")?.run {
                            attr("data-src").ifEmpty { attr("src") }
                        }
                    }
                }
            
            // Build response
            newAnimeLoadResponse(title, url, type) {
                engName = title
                posterUrl = poster
                year = year
                plot = description
                showStatus = status
                tags = tags
                rating = rating
                this.episodes = episodes
                addTrailer(trailer)
                this.recommendations = recommendations.takeIf { it.isNotEmpty() }
            }
            
        } catch (e: Exception) {
            null
        }
    }

    // Extract episode number from title
    private fun extractEpisodeNumber(title: String, default: Int): Int {
        return Regex("(?i)(?:ep|episode|e)[\\s.-]*(\\d+)")
            .find(title)
            ?.groupValues?.get(1)
            ?.toIntOrNull() ?: default
    }

    // Load video links
    override suspend fun loadLinks(
        data: String,
        isCasting: Boolean,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        return try {
            val document = app.get(data).document
            val extractedUrls = mutableSetOf<String>()
            
            // Method 1: Direct iframe sources
            document.select("iframe").forEach { iframe ->
                iframe.attr("src")?.takeIf { it.isNotBlank() && it != "about:blank" }?.let {
                    extractedUrls.add(fixUrl(it))
                }
            }
            
            // Method 2: Server options / source buttons with data attributes
            document.select("[data-video], [data-src], [data-url], [data-embed], [data-link]").forEach { item ->
                listOf("data-video", "data-src", "data-url", "data-embed", "data-link").forEach { attr ->
                    item.attr(attr)?.takeIf { it.isNotBlank() }?.let {
                        extractedUrls.add(fixUrl(it))
                    }
                }
            }
            
            // Method 3: Server list items
            document.select(".server-item, .source-btn, .option-btn, .server-option, .video-source").forEach { item ->
                val link = item.attr("data-video") 
                    ?: item.attr("data-src")
                    ?: item.attr("data-url")
                    ?: item.selectFirst("a")?.attr("href")
                    ?: return@forEach
                
                if (link.isNotBlank()) extractedUrls.add(fixUrl(link))
            }
            
            // Method 4: Script extraction
            val html = document.html()
            
            // Pattern: Direct URLs
            Regex("""['"]?(https?://[^\s'"]+\.(?:mp4|m3u8|mkv)(?:\?[^\s'"]*)?)['"]?""")
                .findAll(html)
                .forEach { match ->
                    match.groupValues.getOrNull(1)?.let { extractedUrls.add(it) }
                }
            
            // Pattern: Base64 encoded
            Regex("""atob\(['"]([A-Za-z0-9+/=]+)['"]\)""")
                .findAll(html)
                .forEach { match ->
                    try {
                        val decoded = String(android.util.Base64.decode(match.groupValues[1], android.util.Base64.DEFAULT))
                        Regex("""https?://[^\s"'<>]+\.(?:mp4|m3u8)""").findAll(decoded).forEach { urlMatch ->
                            extractedUrls.add(urlMatch.value)
                        }
                    } catch (_: Exception) {}
                }
            
            // Pattern: JSON sources
            Regex("""(?:sources|files|videos)\s*[=:]\s*(\[[^\]]+\])""")
                .findAll(html)
                .forEach { match ->
                    tryParseJson<List<VideoSource>>(match.groupValues[1])?.forEach { source ->
                        source.file?.let { extractedUrls.add(it) }
                    }
                }
            
            // Pattern: Player configuration
            Regex("""player\.config\s*=\s*(\{[^}]+\})""")
                .findAll(html)
                .forEach { match ->
                    tryParseJson<PlayerConfig>(match.groupValues[1])]?.file?.let {
                        extractedUrls.add(it)
                    }
                }
            
            // Process all found URLs
            extractedUrls.forEach { url ->
                when {
                    url.endsWith(".mp4", ignoreCase = true) -> {
                        callback.invoke(
                            ExtractorLink(
                                source = name,
                                name = "Direct MP4",
                                url = url,
                                referer = mainUrl,
                                quality = Qualities.Unknown.value,
                                isM3u8 = false,
                                headers = mapOf("User-Agent" to USER_AGENT)
                            )
                        )
                    }
                    url.contains(".m3u8", ignoreCase = true) -> {
                        M3u8Helper.generateM3u8(
                            source = name,
                            streamUrl = url,
                            referer = mainUrl
                        ).forEach(callback)
                    }
                    url.contains("youtube", ignoreCase = true) || url.contains("youtu.be", ignoreCase = true) -> {
                        // Skip YouTube, let extractor handle it
                        loadExtractor(url, data, subtitleCallback, callback)
                    }
                    else -> {
                        // Try loading as generic extractor
                        loadExtractor(url, data, subtitleCallback, callback)
                    }
                }
            }
            
            // Fallback: Check player containers
            if (extractedUrls.isEmpty()) {
                document.select("#player, #video-player, .player-container, .video-player, #iframeEmbed, .responsive-player")
                    .flatMap { it.select("iframe, video, source") }
                    .forEach { media ->
                        val mediaSrc = when (media.tagName()) {
                            "iframe" -> media.attr("src")
                            "video" -> media.attr("src")
                            "source" -> media.attr("src")
                            else -> null
                        }
                        
                        mediaSrc?.takeIf { it.isNotBlank() && it != "about:blank" }?.let {
                            loadExtractor(fixUrl(it), data, subtitleCallback, callback)
                        }
                    }
            }
            
            true
        } catch (e: Exception) {
            false
        }
    }

    // Helper: Fix URL
    private fun fixUrl(url: String): String {
        return when {
            url.isBlank() -> url
            url.startsWith("http://") || url.startsWith("https://") -> url
            url.startsWith("//") -> "https:$url"
            url.startsWith("/") -> "$mainUrl$url"
            else -> "$mainUrl/$url"
        }
    }

    // Data classes for JSON parsing
    data class VideoSource(
        @JsonProperty("file") val file: String? = null,
        @JsonProperty("label") val label: String? = null,
        @JsonProperty("type") val type: String? = null,
        @JsonProperty("src") val src: String? = null
    )

    data class PlayerConfig(
        @JsonProperty("file") val file: String? = null,
        @JsonProperty("url") val url: String? = null,
        @JsonProperty("source") val source: String? = null
    )
}