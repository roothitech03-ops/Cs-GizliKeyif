package com.kraptor

import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.utils.*
import com.lagradost.cloudstream3.LoadResponse.Companion.addTrailer
import com.lagradost.cloudstream3.utils.AppUtils.tryParseJson
import org.jsoup.nodes.Element
import java.util.Base64

class WatchAnimeWorld : MainAPI() {
    
    // Basic Provider Details
    override var mainUrl = "https://watchanimeworld.net"
    override var name = "WatchAnimeWorld"
    override val hasMainPage = true
    override var lang = "hi"
    override val hasDownloadSupport = true
    override val supportedTypes = setOf(
        TvType.Anime,
        TvType.AnimeMovie,
        TvType.OVA
    )

    // Override main URL for different domains if needed
    override val mainPage = listOf(
        MainPageRequest(
            "/",
            "Latest Episodes",
            fixedUrl = true
        ),
        MainPageRequest(
            "/dubbed-anime/",
            "Dubbed Anime",
            fixedUrl = true
        ),
        MainPageRequest(
            "/subbed-anime/",
            "Subbed Anime",
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
        )
    )

    // Parse main page items
    override suspend fun getMainPage(
        page: Int,
        request: MainPageRequest
    ): HomePageResponse {
        val url = if (page == 1) {
            request.data
        } else {
            request.data + if (request.data.contains("?")) "&page=$page" else "page/$page/"
        }
        
        val document = app.get(url).document
        
        val items = document.select("div.video-block, div.item, article.post, div.anime-card").mapNotNull { element ->
            element.toSearchResult()
        }
        
        return newHomePageResponse(request.name, items)
    }

    // Convert element to search result
    private fun Element.toSearchResult(): SearchResponse? {
        val titleElement = this.selectFirst("h3.title, h2.title, a.title, .post-title a, .anime-title")
            ?: this.selectFirst("a[href*='/anime/'], a[href*='/watch/']")
        
        val title = titleElement?.text()?.trim() ?: return null
        val href = titleElement.attr("href") ?: this.selectFirst("a")?.attr("href") ?: return null
        val posterUrl = this.selectFirst("img")?.getImageUrl()
        
        return newAnimeSearchResponse(title, fixUrl(href)) {
            this.posterUrl = posterUrl
            addSub(1)
        }
    }

    // Helper to get image URL
    private fun Element.getImageUrl(): String? {
        return when {
            this.hasAttr("data-src") -> this.attr("data-src")
            this.hasAttr("data-lazy-src") -> this.attr("data-lazy-src")
            this.hasAttr("src") -> {
                val src = this.attr("src")
                if (src.contains("placeholder") || src.contains("empty")) null else src
            }
            else -> null
        }?.let { 
            if (it.startsWith("//")) "https:$it" else it 
        }
    }

    // Search functionality
    override suspend fun search(query: String): List<SearchResponse> {
        val url = "$mainUrl/?s=${query.replace(" ", "+")}"
        val document = app.get(url).document
        
        return document.select("div.video-block, div.item, article.post, div.anime-card, div.result-item").mapNotNull { element ->
            element.toSearchResult()
        }
    }

    // Load detailed information about an anime
    override suspend fun load(url: String): LoadResponse? {
        val document = app.get(url).document
        
        // Try to find title from various selectors
        val title = document.selectFirst("h1.entry-title, h1.post-title, h1.anime-title, .anime-info h1, .title h1")
            ?.text()?.trim() ?: return null
        
        // Poster image
        val poster = document.selectFirst(".poster img, .anime-poster img, .thumb img, .featured-image img")
            ?.getImageUrl()
        
        // Description/Synopsis
        val description = document.selectFirst(".synopsis, .description, .entry-content p, .anime-synopsis")
            ?.text()?.trim()
        
        // Year/Release date
        val year = document.selectFirst(".year, .release-date, span:contains(Released)")
            ?.text()?.trim()?.toIntOrNull()
        
        // Tags/Genres
        val tags = document.select(".genres a, .genre a, .tags a").mapNotNull { it.text().trim() }
        
        // Status (Ongoing/Completed)
        val statusText = document.selectFirst(".status, span:contains(Status)")
            ?.text()?.lowercase()?.trim()
        val status = when {
            statusText?.contains("ongoing") == true -> ShowStatus.Ongoing
            statusText?.contains("completed") == true -> ShowStatus.Completed
            else -> null
        }
        
        // Rating
        val rating = document.selectFirst(".rating, .score, span:contains(Rating)")
            ?.text()?.replace("[^0-9.]".toRegex(), "")?.toRatingInt()
        
        // Determine type
        val typeText = document.selectFirst(".type, span:contains(Type)")?.text()?.lowercase() ?: ""
        val type = when {
            typeText.contains("movie") -> TvType.AnimeMovie
            typeText.contains("ova") || typeText.contains("ona") -> TvType.OVA
            else -> TvType.Anime
        }
        
        // Extract episodes
        val episodes = mutableListOf<Episode>()
        
        // Method 1: Episode list in a container
        val episodeElements = document.select(".episodes-list li, .episode-list a, .ep-list a, .episodes a")
        
        if (episodeElements.isNotEmpty()) {
            episodeElements.forEachIndexed { index, ep ->
                val epTitle = ep.selectFirst(".eptitle, .ep-title, span")?.text()?.trim() ?: "Episode ${index + 1}"
                val epHref = ep.attr("href") ?: ep.selectFirst("a")?.attr("href") ?: return@forEachIndexed
                
                episodes.add(
                    Episode(
                        data = fixUrl(epHref),
                        name = epTitle,
                        episode = index + 1
                    )
                )
            }
        } else {
            // Method 2: Single page anime - create single episode
            episodes.add(
                Episode(
                    data = url,
                    name = "Episode 1",
                    episode = 1
                )
            )
        }
        
        // Trailer (if available)
        val trailer = document.selectFirst("iframe[src*='youtube'], iframe[src*='youtu.be']")
            ?.attr("src")
            ?.let { 
                if (it.contains("embed")) it 
                else "https://www.youtube.com/embed/${it.substringAfterLast("/")}" 
            }
        
        return newAnimeLoadResponse(title, url, type) {
            engName = title
            posterUrl = poster
            year = year
            plot = description
            showStatus = status
            tags = tags
            rating = rating
            this.episodes = episodes
            addTrailer(trailer)
            
            // Add recommendations if available
            val recommendations = document.select(".related-anime a, .recommendations a").mapNotNull { rec ->
                val recTitle = rec.text()?.trim() ?: return@mapNotNull null
                val recHref = rec.attr("href") ?: return@mapNotNull null
                newAnimeSearchResponse(recTitle, fixUrl(recHref)) {
                    this.posterUrl = rec.selectFirst("img")?.getImageUrl()
                }
            }
            this.recommendations = recommendations
        }
    }

    // Load and extract video links from episode page
    override suspend fun loadLinks(
        data: String,
        isCasting: Boolean,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ) {
        val document = app.get(data).document
        
        // Method 1: Direct embed URLs in iframes or video sources
        val embedUrls = mutableSetOf<String>()
        
        // Check for direct iframe sources
        document.select("iframe").forEach { iframe ->
            val src = iframe.attr("src")
            if (!src.isNullOrBlank()) {
                embedUrls.add(fixUrl(src))
            }
        }
        
        // Method 2: Look for server options / source buttons
        document.select(".server-item, .source-btn, .option-btn, [data-src], [data-video]").forEach { item ->
            val videoSrc = item.attr("data-src") 
                ?: item.attr("data-video") 
                ?: item.attr("data-url")
                ?: item.selectFirst("a")?.attr("href")
            if (!videoSrc.isNullOrBlank()) {
                embedUrls.add(fixUrl(videoSrc))
            }
        }
        
        // Method 3: Script-based extraction (common in anime sites)
        val scripts = document.html()
        
        // Pattern 1: Direct video URLs in JavaScript
        Regex("""['"]?(https?://[^'"]*\.(?:mp4|m3u8|mkv)[^'"]*)['"]?""").findAll(scripts).forEach { match ->
            match.groupValues.getOrNull(1)?.let { embedUrls.add(it) }
        }
        
        // Pattern 2: Encoded/Base64 URLs
        Regex("""atob\(['"]([A-Za-z0-9+/=]+)['"]\)""").findAll(scripts).forEach { match ->
            try {
                val decoded = String(Base64.getDecoder().decode(match.groupValues[1]))
                Regex("""https?://[^\s"']+\.(?:mp4|m3u8)""").find(decoded)?.value?.let { 
                    embedUrls.add(it) 
                }
            } catch (_: Exception) {}
        }
        
        // Pattern 3: JSON data
        Regex("""(?:sources|files|videos)\s*[:=]\s*(\[[^\]]+\])""").findAll(scripts).forEach { match ->
            tryParseJson<List<VideoSource>>(match.groupValues[1])?.forEach { source ->
                source.file?.let { embedUrls.add(it) }
            }
        }
        
        // Process all found URLs
        embedUrls.forEach { url ->
            when {
                // Direct MP4 links
                url.contains(".mp4", ignoreCase = true) -> {
                    callback.invoke(
                        ExtractorLink(
                            source = name,
                            name = "Direct MP4",
                            url = url,
                            referer = data,
                            quality = Qualities.Unknown.value,
                            isM3u8 = false
                        )
                    )
                }
                
                // HLS/M3U8 streams
                url.contains(".m3u8", ignoreCase = true) -> {
                    callback.invoke(
                        ExtractorLink(
                            source = name,
                            name = "HLS Stream",
                            url = url,
                            referer = data,
                            quality = Qualities.Unknown.value,
                            isM3u8 = true
                        )
                    )
                }
                
                // Known extractors/embeds
                else -> loadExtractor(url, data, subtitleCallback, callback)
            }
        }
        
        // If no links found, try loading the page as a stream extractor
        if (embedUrls.isEmpty()) {
            // Fallback: Try to find player container
            val playerContainer = document.selectFirst("#player, #video-player, .player-container, .video-player")
            playerContainer?.select("iframe, video, source")?.forEach { media ->
                val mediaSrc = when {
                    media.tagName() == "iframe" -> media.attr("src")
                    media.tagName() == "video" -> media.attr("src")
                    media.tagName() == "source" -> media.attr("src")
                    else -> null
                }
                
                mediaSrc?.let { src ->
                    if (src.isNotBlank()) {
                        loadExtractor(fixUrl(src), data, subtitleCallback, callback)
                    }
                }
            }
        }
    }

    // Data class for parsing video sources
    data class VideoSource(
        val file: String? = null,
        val label: String? = null,
        val type: String? = null
    )

    // Helper to fix relative URLs
    private fun fixUrl(url: String): String {
        return when {
            url.startsWith("http") -> url
            url.startsWith("//") -> "https:$url"
            url.startsWith("/") -> "$mainUrl$url"
            else -> "$mainUrl/$url"
        }
    }

    // Helper to convert string rating to int
    private fun String.toRatingInt(): Int {
        return try {
            val num = this.toFloat()
            when {
                num <= 10 -> (num * 100).toInt()
                num <= 100 -> num.toInt()
                else -> Qualities.Unknown.value
            }
        } catch (_: Exception) {
            Qualities.Unknown.value
        }
    }
}