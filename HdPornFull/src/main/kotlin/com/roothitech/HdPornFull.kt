package com.roothitech

import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.utils.ExtractorLink
import com.lagradost.cloudstream3.utils.ExtractorLinkType
import com.lagradost.cloudstream3.utils.newExtractorLink

class HdPornFull : MainAPI() {
    override var mainUrl = "https://en16.pornhd8k.net"
    override var name = "HdPornFull"
    override var lang = "en"
    override val hasMainPage = true
    override val hasDownloadSupport = true
    override val supportedTypes = setOf(TvType.NSFW)

    private val UA = "Mozilla/5.0 (Linux; Android 13; Pixel 7) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120.0.0.0 Mobile Safari/537.36"

    override suspend fun getMainPage(page: Int, request: MainPageRequest): HomePageResponse {
        val url = if (page == 1) "$mainUrl/" else "$mainUrl/page/$page/"
        val doc = app.get(url, headers = mapOf("User-Agent" to UA)).document
        val items = parseCards(doc)
        return newHomePageResponse(listOf(HomePageList("Latest", items)))
    }

    override suspend fun search(query: String): List<SearchResponse> {
        val q = java.net.URLEncoder.encode(query, "UTF-8")
        val doc = app.get("$mainUrl/?s=$q", headers = mapOf("User-Agent" to UA)).document
        return parseCards(doc)
    }

    override suspend fun load(url: String): LoadResponse {
        val doc = app.get(url, headers = mapOf("User-Agent" to UA)).document
        val title = doc.selectFirst("h1, .entry-title, .video-title")?.text()?.trim() ?: "Untitled"
        val poster = doc.selectFirst("video[poster]")?.attr("poster")
            ?: doc.selectFirst("meta[property=og:image]")?.attr("content")
            ?: ""
        return newMovieLoadResponse(title, url, TvType.NSFW, url) {
            this.posterUrl = poster
        }
    }

    override suspend fun loadLinks(
        data: String,
        isCasting: Boolean,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        val doc = app.get(data, headers = mapOf("User-Agent" to UA)).document
        var found = false

        // direct <video src>
        doc.selectFirst("video[src]")?.absUrl("src")?.takeIf { it.isNotBlank() }?.let { src ->
            callback(newExtractorLink(
                source = this.name,
                name = this.name,
                url = src,
                type = ExtractorLinkType.VIDEO
            ) {
                this.referer = data
                this.quality = 1080
            })
            found = true
        }

        // <source type=application/x-mpegURL>  or  <video data-hls>
        val hls = doc.selectFirst("source[type=application/x-mpegURL]")?.absUrl("src")
            ?.takeIf { it.isNotBlank() }
            ?: doc.selectFirst("video[data-hls]")?.attr("data-hls")?.takeIf { it.isNotBlank() }
        hls?.let {
            callback(newExtractorLink(
                source = this.name,
                name = "${this.name} HLS",
                url = it,
                type = ExtractorLinkType.M3U8
            ) {
                this.referer = data
                this.quality = 1080
            })
            found = true
        }

        // fallback: koi bhi iframe/embed
        if (!found) {
            doc.select("iframe[src]").forEach { iframe ->
                iframe.absUrl("src").takeIf { it.isNotBlank() }?.let { emb ->
                    callback(newExtractorLink(
                        source = this.name,
                        name = this.name,
                        url = emb,
                        type = ExtractorLinkType.VIDEO
                    ) {
                        this.referer = data
                        this.quality = 720
                    })
                    found = true
                }
            }
        }

        return found
    }

    private fun parseCards(doc: org.jsoup.nodes.Document): List<SearchResponse> {
        val selectors = listOf(
            "article.item",
            "div.video-card",
            ".thumb-block",
            ".video-item",
            "article",
            ".post"
        )
        val cards = selectors.firstNotNullOfOrNull { sel ->
            doc.select(sel).takeIf { it.isNotEmpty() }
        } ?: return emptyList()

        return cards.mapNotNull { el ->
            val a = el.selectFirst("a[href]") ?: return@mapNotNull null
            val href = a.absUrl("href").takeIf { it.isNotBlank() } ?: return@mapNotNull null
            val title = el.selectFirst(".title, h2, h3, .entry-title")?.text()?.trim()
                ?: a.attr("title").takeIf { it.isNotBlank() }
                ?: return@mapNotNull null
            val img = el.selectFirst("img")
            val poster = img?.absUrl("data-src")?.takeIf { it.isNotBlank() }
                ?: img?.absUrl("src").orEmpty()
            newMovieSearchResponse(title, href, TvType.NSFW) {
                this.posterUrl = poster
            }
        }
    }
}