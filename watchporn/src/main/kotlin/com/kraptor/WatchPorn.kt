package com.kraptor

import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.utils.ExtractorLink
import com.lagradost.cloudstream3.utils.Quality
import com.lagradost.cloudstream3.utils.Qualities
import com.lagradost.cloudstream3.utils.loadExtractor
import org.jsoup.nodes.Element
import java.net.URI

class WatchPorn : MainApi() {
    override var mainUrl = "https://watchporn.to"
    override var name = "WatchPorn"
    override val lang = "en"
    override val supportedTypes = setOf(TvType.NSFW)
    override val hasMainPage = true
    override val hasChromecastSupport = true
    override val hasDownloadSupport = true

    companion object {
        private val SEARCH_URL = "$mainUrl/search/%s/"
        private val headers = mapOf("Referer" to mainUrl, "User-Agent" to "Mozilla/5.0")
    }

    private fun Element.toNSFWSearchResponse(): SearchResponse? {
        val link = selectFirst("a[href]")?.attr("href") ?: return null
        val title = selectFirst("h3, h4, .title, .name, .video-title")?.text()
            ?: selectFirst("a")?.ownText()?.trim()?.takeIf { it.isNotBlank() } ?: return null
        
        val poster = selectFirst("img")?.attr("data-src") 
            ?: selectFirst("img")?.attr("src")
            ?: selectFirst("img")?.attr("data-lazy-src")

        val fullUrl = if (link.startsWith("http")) link else "$mainUrl$link"
        
        return NSFWSearchResponse(
            title,
            fullUrl,
            this@WatchPorn.name,
            poster,
            tags = listOf(selectFirst(".tags, .category, .video-cat")?.text()?.trim() ?: "")
        )
    }

    override suspend fun getMainPage(page: Int, request: MainPageRequest): HomePageResponse {
        val mainDoc = app.get(mainUrl, headers = headers).document
        
        // Latest/Trending videos
        val latestVideos = mainDoc.select("div[class*='video-item'], div[class*='thumb-item'], div[class*='post-item'], .video-block")
            .distinctBy { it.selectFirst("a")?.attr("href") }
            .mapNotNull { it.toNSFWSearchResponse() }
            .take(24)

        // Categories
        val catDoc = app.get("$mainUrl/category", headers = headers).document
        val categories = catDoc.select("a[href*='/category/'], .cat-item a, .category-list a")
            .distinctBy { it.attr("href") }
            .mapNotNull { element ->
                val catName = element.text().trim().takeIf { it.isNotBlank() } ?: return@mapNotNull null
                val catUrl = if (element.attr("href").startsWith("/")) "$mainUrl${element.attr("href")}" 
                           else element.attr("href")
                HomePageList(
                    catName, 
                    loadVideosFromPage(catUrl),
                    isCategoryList = true
                )
            }
            .take(12)

        return HomePageResponse(
            listOfNotNull(
                HomePageList("🔥 Latest Videos", latestVideos),
                if (categories.isNotEmpty()) HomePageList("📂 Categories", categories) else null
            )
        )
    }

    override suspend fun search(query: String): List<SearchResponse> {
        val encodedQuery = URI(null, query.lowercase().replace(" ", "-").replace("[^a-z0-9-]".toRegex(), ""), null).rawPath
        val url = SEARCH_URL.format(encodedQuery)
        return loadVideosFromPage(url)
    }

    private suspend fun loadVideosFromPage(pageUrl: String): List<SearchResponse> {
        return app.get(pageUrl, headers = headers).document
            .select("div[class*='video-item'], div[class*='thumb'], div[class*='item'], .post-item, .video-grid-item")
            .distinctBy { it.selectFirst("a[href]")?.attr("href") }
            .mapNotNull { it.toNSFWSearchResponse() }
            .take(30)
    }

    override suspend fun load(url: String): LoadResponse {
        val doc = app.get(url, headers = headers).document
        
        val title = doc.selectFirst("h1.entry-title, h1.post-title, h1, title")?.text()
            ?.trim()?.substringBeforeLast(" -")?.substringBeforeLast("|")?.takeIf { it.length > 3 }
            ?: throw ErrorLoadingException("Title not found")

        val poster = doc.selectFirst(".single-poster img, .post-thumb img, meta[property='og:image']")?.attr("content")
            ?: doc.selectFirst(".post-image img, .featured-image img")?.attr("src")
            ?: doc.selectFirst("img[alt*=$title], meta[property='og:image']")?.attr("content")

        val description = doc.selectFirst(".entry-content p:first-child, .post-description, .video-desc")?.text()?.trim()
        val tags = doc.select("a.tag, .post-tag a, .category a")
            .mapNotNull { it.text().trim().takeIf { it.isNotBlank() } }
            .distinct()
            .take(15)

        // Recommendations
        val recLinks = doc.select(".related-videos a, .you-may-like a, .recommended-videos a, .similar-videos a")
            .mapNotNull { it.attr("href").takeIf { it.contains("/video/") || it.contains("/watch/") } }
            .distinct()
            .take(20)
        
        val recommendations = recLinks.mapNotNull { recUrl ->
            val recFullUrl = if (recUrl.startsWith("http")) recUrl else "$mainUrl$recUrl"
            NSFWSearchResponse(
                doc.select("a[href*=$recUrl]")?.firstOrNull()?.text()?.trim() ?: "Related",
                recFullUrl,
                name
            )
        }

        return NSFWLoadResponse(
            name = title,
            url = url,
            apiName = name,
            type = TvType.NSFW,
            posterUrl = poster,
            tags = tags,
            description = description,
            recommendations = recommendations
        )
    }

    override suspend fun loadLinks(
        data: String,
        isEmbedding: Boolean,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ) {
        val doc = app.get(data, headers = headers).document
        val pageText = app.get(data, headers = headers).text

        // 1. Primary: Iframe players (Dood, Mixdrop, Vidoza, etc.)
        doc.select("iframe[src], .player-frame iframe, #player iframe").apmap { iframe ->
            val iframeUrl = iframe.attr("data-src").ifEmpty { iframe.attr("src") }
            if (iframeUrl.isNotBlank() && (iframeUrl.contains("embed") || iframeUrl.contains("player") || iframeUrl.contains("/e/"))) {
                loadExtractor(iframeUrl, mainUrl, subtitleCallback, callback)
            }
        }

        // 2. Direct HLS/MP4 from JS (JWPlayer, Plyr patterns)
        Regex("""["']https?://[^"',\s]+?\.(?:m3u8|mp4|webm)(?:\?[^"',\s]*)?["']""", setOf(RegexOption.IGNORE_CASE))
            .findAll(pageText).toList().apmap { match ->
                val url = match.value.removeSurrounding("\"", "\"").removeSurrounding("'", "'")
                val quality = when {
                    url.contains("1080") -> Qualities.Quality1080
                    url.contains("720p") -> Qualities.Quality720
                    url.contains("480") -> Qualities.Quality480
                    url.contains(".m3u8") -> Qualities.QualityNone
                    else -> Qualities.Unknown
                }
                callback(ExtractorLink(
                    name,
                    "Direct Stream",
                    url,
                    referer = mainUrl,
                    quality = quality,
                    headers = headers
                ))
            }

        // 3. Player file patterns (common porn sites)
        Regex("""file["'\s:=]+["']([^"'\s]+?\.(?:m3u8|mp4))["']""", setOf(RegexOption.IGNORE_CASE))
            .findAll(pageText).toList().apmap { match ->
                val url = match.groupValues[1]
                callback(ExtractorLink(
                    name,
                    "Player Extract",
                    url,
                    referer = mainUrl,
                    quality = Quality.Unknown,
                    headers = headers
                ))
            }

        // 4. HTML5 video fallback
        doc.select("video source[src]").apmap { source ->
            val src = source.attr("src")
            if (src.isNotBlank()) {
                callback(ExtractorLink(
                    name,
                    "HTML5 Video",
                    src,
                    referer = mainUrl,
                    quality = Quality.Unknown,
                    headers = headers
                ))
            }
        }
    }
}
