package com.kraptor

import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.utils.*
import com.lagradost.cloudstream3.utils.AppUtils.tryParseJson
import org.jsoup.Jsoup
import org.jsoup.nodes.Element

class RareAnimes : MainAPI() {

    // Main configuration
    override var mainUrl = "https://hindi.rareanimes.com"
    override var name = "Rare Animes (Hindi)"
    override val hasMainPage = true
    override var lang = "hi"
    override val hasDownloadSupport = true
    override val supportedTypes = setOf(
        TvType.Anime,
        TvType.AnimeMovie,
        TvType.OVA,
        TvType.Cartoon
    )

    // Main page categories
    override val mainPage = listOf(
        MainPageRequest("/", "Latest", fixedUrl = true),
        MainPageRequest("/category/hindi-dubbed/", "Hindi Dubbed", fixedUrl = true),
        MainPageRequest("/category/cartoon/", "Cartoons", fixedUrl = true),
        MainPageRequest("/category/anime-movie/", "Movies", fixedUrl = true),
        MainPageRequest("/category/complete-series/", "Complete Series", fixedUrl = true),
        MainPageRequest("/category/ben-10/", "Ben 10 Series", fixedUrl = true)
    )

    companion object {
        const val USER_AGENT = "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120.0.0.0 Safari/537.36"
        
        // Quality patterns
        private val qualityRegex = Regex("(?i)(\\d{3,4})[pP]|(HD|SD|4K|1080|720|480)")
    }

    // Parse main page items
    override suspend fun getMainPage(page: Int, request: MainPageRequest): HomePageResponse {
        val url = if (page == 1) request.data else "${request.data}page/$page/"
        
        val document = app.get(url).document
        
        val items = document.select("article.post, div.post, div.blog-post, div.entry-post, .site-main article")
            .mapNotNull { element ->
                element.toSearchResult()
            }
        
        return newHomePageResponse(request.name, items)
    }

    // Convert element to search result
    private fun Element.toSearchResult(): SearchResponse? {
        return try {
            // Title selectors - WordPress sites usually have these
            val titleElement = this.selectFirst(
                "h2.entry-title a, " +
                "h2.post-title a, " +
                ".entry-header h2 a, " +
                ".post-title a, " +
                "a.entry-title, " +
                "h1.entry-title a"
            ) ?: return@tryParseJson null
            
            val title = titleElement.text().trim()
                .takeIf { it.isNotEmpty() } ?: return@tryParseJson null
            
            val href = titleElement.attr("href")
                .takeIf { it.isNotEmpty() } ?: return@tryParseJson null
            
            // Poster image
            val poster = this.selectFirst(
                "img.wp-post-image, " +
                "img.attachment-medium, " +
                ".post-thumbnail img, " +
                ".entry-thumbnail img, " +
                "img[src*='wp-content']"
            )?.getImageUrl()
            
            // Extract quality from title
            val quality = qualityRegex.find(title)?.value?.let { qual ->
                when {
                    qual.contains("1080") || qual.contains("4K") -> Qualities.P1080.value
                    qual.contains("720") || qual.equals("HD", true) -> Qualities.P720.value
                    qual.contains("480") || qual.equals("SD", true) -> Qualities.P480.value
                    else -> Qualities.Unknown.value
                }
            } ?: Qualities.Unknown.value
            
            newAnimeSearchResponse(title, fixUrl(href)) {
                this.posterUrl = poster
                this.quality = quality
                addDubStatus(dubbed = true) // Hindi site hai, toh dubbed hi hogi
            }
            
        } catch (e: Exception) {
            null
        }
    }

    // Helper to get image URL
    private fun Element.getImageUrl(): String? {
        return listOf(
            attr("data-src"),
            attr("data-lazy-src"),
            attr("data-srcset")?.split(" ")?.first(),
            attr("src")
        ).firstOrNull { 
            it.isNotBlank() && 
            !it.contains("placeholder") && 
            !it.contains("empty") &&
            !it.contains(".svg")
        }?.let { 
            if (it.startsWith("//")) "https:$it" else it 
        }
    }

    // Search functionality
    override suspend fun search(query: String): List<SearchResponse> {
        val url = "$mainUrl/?s=${query.replace(" ", "+")}"
        val document = app.get(url).document
        
        return document.select("article.post, div.post, .search-result article, .result-item")
            .mapNotNull { it.toSearchResult() }
    }

    // Load detailed page info
    override suspend fun load(url: String): LoadResponse? {
        val document = app.get(url).document
        
        // Title
        val title = document.selectFirst(
            "h1.entry-title, " +
            "h1.post-title, " +
            ".entry-title, " +
            ".page-title"
        )?.text()?.trim() ?: return null
        
        // Poster
        val poster = document.selectFirst(
            ".post-thumbnail img, " +
            "img.wp-post-image, " +
            ".featured-image img, " +
            ".single-featured img"
        )?.getImageUrl()
        
        // Description/Synopsis - WordPress content
        val description = buildString {
            // Try different description locations
            document.selectFirst(".entry-content p, .post-content p, .description, .synopsis")
                ?.text()?.trim()?.let { append(it) }
            
            // Also check for meta description
            if (isEmpty()) {
                document.selectFirst("meta[property='og:description']")
                    ?.attr("content")?.let { append(it) }
            }
        }.takeIf { it.isNotEmpty() }
        
        // Year from title or content
        val year = Regex("(19|20)\\d{2}")
            .find(title)?.value?.toIntOrNull()
        
        // Tags/Categories
        val tags = document.select(
            ".entry-meta a[rel=category], " +
            ".cat-links a, " +
            ".tags-links a, " +
            ".entry-categories a"
        ).mapNotNull { it.text().trim() }
            .filter { it.isNotEmpty() && it != "Uncategorized" }
        
        // Determine type based on tags/title
        val type = when {
            title.contains("movie", ignoreCase = true) ||
            tags.any { it.contains("movie", ignoreCase = true) } -> TvType.AnimeMovie
            
            title.contains("ova", ignoreCase = true) ||
            tags.any { it.contains("ova", ignoreCase = true) } -> TvType.OVA
            
            title.contains("cartoon", ignoreCase = true) ||
            tags.any { it.contains("cartoon", ignoreCase = true) } -> TvType.Cartoon
            
            else -> TvType.Anime
        }
        
        // Status - try to determine
        val statusText = document.selectFirst(
            ".status, .entry-status, span:contains(complete), span:contains(ongoing)"
        )?.text()?.lowercase()
        
        val status = when {
            statusText?.contains("complete") == true || 
            statusText?.contains("completed") == true ||
            title.contains("complete", ignoreCase = true) -> ShowStatus.Completed
            
            statusText?.contains("ongoing") == true -> ShowStatus.Ongoing
            else -> null
        }
        
        // === EPISODES EXTRACTION ===
        val episodes = mutableListOf<Episode>()
        
        // Method 1: Look for download links/episode list in content
        val contentElement = document.selectFirst(".entry-content, .post-content, .page-content")
        
        if (contentElement != null) {
            // Pattern A: Numbered episodes like "Episode 1", "Episode 2" etc.
            val epLinks = contentElement.select("a[href*='download'], a[href*='episode'], a[href*='ep-'], a.btn, a.button, a.download-btn")
            
            if (epLinks.isNotEmpty()) {
                epLinks.forEachIndexed { index, link ->
                    val epName = link.text().trim()
                        .takeIf { it.isNotEmpty() } 
                        ?: "Episode ${index + 1}"
                    
                    val epHref = link.attr("href").takeIf { it.isNotEmpty() } ?: return@forEachIndexed
                    
                    val epNum = extractEpisodeNumber(epName, index + 1)
                    
                    episodes.add(
                        Episode(
                            data = fixUrl(epHref),
                            name = epName,
                            episode = epNum,
                            posterUrl = poster
                        )
                    )
                }
            } else {
                // Pattern B: Direct video embeds in content
                val iframes = contentElement.select("iframe")
                
                if (iframes.isNotEmpty()) {
                    iframes.forEachIndexed { index, iframe ->
                        val src = iframe.attr("src").takeIf { it.isNotEmpty() } ?: return@forEachIndexed
                        
                        episodes.add(
                            Episode(
                                data = fixUrl(src),
                                name = "Part ${index + 1}",
                                episode = index + 1,
                                posterUrl = poster
                            )
                        )
                    })
                } else {
                    // Pattern C: Single page with direct download
                    episodes.add(
                        Episode(
                            data = url,
                            name = if (type == TvType.AnimeMovie) title else "Full Series",
                            episode = 1,
                            posterUrl = poster
                        )
                    )
                }
            }
        } else {
            // Fallback: Single episode
            episodes.add(
                Episode(
                    data = url,
                    name = title,
                    episode = 1,
                    posterUrl = poster
                )
            )
        }
        
        // If no episodes found yet, try alternative methods
        if (episodes.isEmpty()) {
            // Check for shortcodes or embedded players
            document.select("[data-src], [data-video], [data-url]").forEach { elem ->
                val dataSrc = elem.attr("data-src") 
                    ?: elem.attr("data-video") 
                    ?: elem.attr("data-url")
                
                if (!dataSrc.isNullOrBlank()) {
                    episodes.add(
                        Episode(
                            data = fixUrl(dataSrc),
                            name = "Video ${episodes.size + 1}",
                            episode = episodes.size + 1
                        )
                    )
                }
            }
        }
        
        // Final fallback
        if (episodes.isEmpty()) {
            episodes.add(Episode(data = url, name = "Watch", episode = 1))
        }
        
        // Recommendations / Related posts
        val recommendations = document.select(
            ".related-posts a, " +
            ".yarpp-related a, " +
            ".crp_related a, " +
            "#upprev-triger a"
        ).mapNotNull { rec ->
            val recTitle = rec.text().trim().takeIf { it.isNotEmpty() } ?: return@mapNotNull null
            val recHref = rec.attr("href").takeIf { it.isNotEmpty() } ?: return@mapNotNull null
            
            newAnimeSearchResponse(recTitle, fixUrl(recHref)) {
                this.posterUrl = rec.selectFirst("img")?.getImageUrl()
            }
        }
        
        return newAnimeLoadResponse(title, url, type) {
            engName = title
            posterUrl = poster
            year = year
            plot = description
            showStatus = status
            tags = tags
            rating = null // Usually not available on these sites
            this.episodes = episodes
            this.recommendations = recommendations.takeIf { it.isNotEmpty() }
        }
    }

    // Extract episode number from text
    private fun extractEpisodeNumber(text: String, default: Int): Int {
        // Try patterns like "Episode 1", "Ep 1", "E01", etc.
        val patterns = listOf(
            Regex("(?i)(?:episode|ep)[\\.\\s:-]*(\\d+)"),
            Regex("(?i)e(\\d{1,3})\\b"),
            Regex("\\b(\\d{1,3})\\s*(?:st|nd|rd|th)?\\s*(?:episode)?\\b", RegexOption.IGNORE_CASE)
        )
        
        for (pattern in patterns) {
            pattern.find(text)?.groupValues?.get(1)?.toIntOrNull()?.let { return it }
        }
        
        return default
    }

    // Load video links
    override suspend fun loadLinks(
        data: String,
        isCasting: Boolean,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        
        val urlToLoad = data
        
        // Check if it's a direct video URL
        if (isDirectVideoLink(urlToLoad)) {
            callback.invoke(
                ExtractorLink(
                    source = name,
                    name = "Direct Video",
                    url = urlToLoad,
                    referer = mainUrl,
                    quality = getQualityFromUrl(urlToLoad),
                    isM3u8 = urlToLoad.contains(".m3u8"),
                    headers = mapOf("User-Agent" to USER_AGENT)
                )
            )
            return true
        }
        
        // Load the page and extract sources
        val document = app.get(urlToLoad, headers = mapOf("User-Agent" to USER_AGENT)).document
        
        val extractedSources = mutableSetOf<String>()
        
        // Method 1: Iframe sources
        document.select("iframe").forEach { iframe ->
            iframe.attr("src")?.takeIf { 
                it.isNotBlank() && 
                it != "about:blank" && 
                !it.contains("ads") &&
                !it.contains("advertisement")
            }?.let { extractedSources.add(fixUrl(it)) }
        }
        
        // Method 2: Video elements
        document.select("video source, video").forEach { video ->
            val src = when (video.tagName()) {
                "source" -> video.attr("src")
                "video" -> video.attr("src")
                else -> null
            }
            src?.takeIf { it.isNotBlank() }?.let { extractedSources.add(fixUrl(it)) }
        }
        
        // Method 3: Data attributes (common in modern themes)
        document.select("[data-src], [data-video], [data-url], [data-source], [data-link]").forEach { elem ->
            listOf("data-src", "data-video", "data-url", "data-source", "data-link").forEach { attr ->
                elem.attr(attr)?.takeIf { it.isNotBlank() }?.let { extractedSources.add(fixUrl(it)) }
            }
        }
        
        // Method 4: Download buttons/links
        document.select(
            "a.download-btn, " +
            "a.btn-download, " +
            "a[href*='.mp4'], " +
            "a[href*='.mkv'], " +
            "a[href*='drive.google'], " +
            "a[href*='mega.nz'], " +
            "a[href*='mediafire']"
        ).forEach { link ->
            link.attr("href")?.takeIf { it.isNotBlank() }?.let { extractedSources.add(fixUrl(it)) }
        }
        
        // Method 5: Script extraction for embedded URLs
        val htmlContent = document.html()
        
        // Direct MP4/M3U8 URLs in scripts
        Regex("""['"]?(https?://[^\s'"]+\.(?:mp4|m3u8|mkv)(?:\?[^\s'"]*)?)['"]?""")
            .findAll(htmlContent)
            .forEach { match ->
                match.groupValues.getOrNull(1)?.let { extractedSources.add(it) }
            }
        
        // Shortcode patterns
        Regex("""\[video.*?src=['"]([^'"]+)['"]""")
            .findAll(htmlContent)
            .forEach { match ->
                match.groupValues.getOrNull(1)?.let { extractedSources.add(it) }
            }
        
        // Process all found sources
        extractedSources.forEach { sourceUrl ->
            when {
                // Direct MP4
                sourceUrl.endsWith(".mp4", ignoreCase = true) -> {
                    callback.invoke(
                        ExtractorLink(
                            source = name,
                            name = "MP4 Source",
                            url = sourceUrl,
                            referer = urlToLoad,
                            quality = getQualityFromUrl(sourceUrl),
                            isM3u8 = false,
                            headers = mapOf("User-Agent" to USER_AGENT)
                        )
                    )
                }
                
                // M3U8/HLS streams
                sourceUrl.contains(".m3u8", ignoreCase = true) -> {
                    M3u8Helper.generateM3u8(
                        source = name,
                        streamUrl = sourceUrl,
                        referer = urlToLoad
                    ).forEach(callback)
                }
                
                // Known extractors/embeds
                sourceUrl.containsAny(listOf(
                    "doodstream", "dood.", "ds2play",
                    "streamtape", "stape",
                    "filelions", "lion.",
                    "streamhub", "embed",
                    "player", "video"
                ), ignoreCase = true) -> {
                    loadExtractor(sourceUrl, urlToLoad, subtitleCallback, callback)
                }
                
                // Google Drive
                sourceUrl.contains("drive.google", ignoreCase = true) -> {
                    loadExtractor(sourceUrl, urlToLoad, subtitleCallback, callback)
                }
                
                // Mega.nz
                sourceUrl.contains("mega.nz", ignoreCase = true) -> {
                    callback.invoke(
                        ExtractorLink(
                            source = name,
                            name = "Mega Link",
                            url = sourceUrl,
                            referer = urlToLoad,
                            quality = Qualities.Unknown.value,
                            isM3u8 = false
                        )
                    )
                }
                
                // MediaFire
                sourceUrl.contains("mediafire", ignoreCase = true) -> {
                    loadExtractor(sourceUrl, urlToLoad, subtitleCallback, callback)
                }
                
                // Generic - try as extractor
                else -> {
                    loadExtractor(sourceUrl, urlToLoad, subtitleCallback, callback)
                }
            }
        }
        
        // Fallback: If nothing found, try loading the original URL as extractor
        if (extractedSources.isEmpty()) {
            loadExtractor(urlToLoad, urlToLoad, subtitleCallback, callback)
        }
        
        return true
    }
    
    // Helper: Check if URL is direct video
    private fun isDirectVideoLink(url: String): Boolean {
        return url.endsWith(".mp4", ignoreCase = true) ||
               url.endsWith(".m3u8", ignoreCase = true) ||
               url.endsWith(".mkv", ignoreCase = true)
    }
    
    // Helper: Get quality from URL
    private fun getQualityFromUrl(url: String): Int {
        return when {
            url.contains("1080", ignoreCase = true) -> Qualities.P1080.value
            url.contains("720", ignoreCase = true) -> Qualities.P720.value
            url.contains("480", ignoreCase = true) -> Qualities.P480.value
            url.contains("360", ignoreCase = true) -> Qualities.P360.value
            else -> Qualities.Unknown.value
        }
    }
    
    // Helper: Fix relative URLs
    private fun fixUrl(url: String): String {
        return when {
            url.isBlank() -> url
            url.startsWith("http://") || url.startsWith("https://") -> url
            url.startsWith("//") -> "https:$url"
            url.startsWith("/") -> "$mainUrl$url"
            else -> "$mainUrl/$url"
        }
    }
    
    // Extension function: Contains any of strings
    private fun String.containsAny(strings: List<String>, ignoreCase: Boolean = false): Boolean {
        return strings.any { this.contains(it, ignoreCase) }
    }
}