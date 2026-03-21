package com.kraptor

import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.utils.*
import org.jsoup.nodes.Element
import java.net.URI

class WatchPorn : MainApi() {
    override var mainUrl = "https://watchporn.to"
    override var name = "WatchPorn"
    override val lang = "en"
    override val supportedTypes = setOf(TvType.NSFW)
    override val hasMainPage = true

    private val headers = mapOf(
        "User-Agent" to "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36",
        "Referer" to mainUrl
    )

    private fun Element.toSearchResponse(): SearchResponse? {
        val link = selectFirst("a")?.attr("href") ?: return null
        val title = selectFirst("h3, h4, .title")?.text()?.trim()
            ?: selectFirst("a")?.text()?.trim() ?: return null
        
        val poster = selectFirst("img")?.attr("src") ?: 
                    selectFirst("img")?.attr("data-src")
        
        val fullUrl = fixUrl(link)
        return NSFWSearchResponse(
            title,
            fullUrl,
            this@WatchPorn.name,
            TvType.NSFW,
            poster
        )
    }

    override suspend fun getMainPage(page: Int, request: MainPageRequest): HomePageResponse {
        val doc = app.get(mainUrl, headers = headers).document
        
        val trending = doc.select("div[class*='video'], div[class*='thumb'], div[class*='item']")
            .mapNotNull { it.toSearchResponse() }
            .take(20)

        return HomePageResponse(listOf(
            HomePageList("Trending", trending)
        ))
    }

    override suspend fun search(query: String): List<SearchResponse> {
        val searchUrl = "https://watchporn.to/search/${query.lowercase().replace(" ", "-")}/"
        val doc = app.get(searchUrl, headers = headers).document
        return doc.select("div[class*='video'], div[class*='thumb']")
            .mapNotNull { it.toSearchResponse() }
    }

    override suspend fun load(url: String): LoadResponse {
        val doc = app.get(url, headers = headers).document
        
        val title = doc.selectFirst("h1")?.text()?.trim() 
            ?: doc.title()?.substringBefore(" | ") ?: "Unknown"
        
        val poster = doc.selectFirst("meta[property='og:image']")?.attr("content")
            ?: doc.selectFirst(".poster img, .thumb img")?.attr("src")

        return NSFWLoadResponse(
            name = title,
            url = url,
            apiName = name,
            type = TvType.NSFW,
            posterUrl = poster
        )
    }

    override suspend fun loadLinks(
        data: String,
        isEmbedding: Boolean,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ) {
        val doc = app.get(data, headers = headers).document
        
        // Iframe extraction
        doc.select("iframe").forEach { iframe ->
            val src = iframe.attr("src").ifEmpty { iframe.attr("data-src") }
            if (src.isNotBlank()) {
                loadExtractor(src, mainUrl, subtitleCallback, callback)
            }
        }
        
        // Direct sources
        val text = app.get(data).text
        Regex("https?://[^\\s\"']+\\.(m3u8|mp4)").findAll(text).toList()
            .forEach { match ->
                callback(ExtractorLink(
                    name,
                    "Direct",
                    match.value,
                    referer = mainUrl,
                    quality = Quality.Unknown
                ))
            }
    }
    
    private fun fixUrl(url: String): String {
        return if (url.startsWith("http")) url else "$mainUrl$url"
    }
}            NSFWSearchResponse(
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
