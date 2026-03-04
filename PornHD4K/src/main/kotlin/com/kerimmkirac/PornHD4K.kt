package com.kerimmkirac

import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.utils.*
import com.lagradost.cloudstream3.utils.ExtractorLink
import org.jsoup.nodes.Element

class PornHD4K : MainAPI() {
    override var mainUrl = "https://www.pornhd4k.net"
    override var name = "PornHD4K"
    override val hasMainPage = true
    override var lang = "en"
    override val hasQuickSearch = false
    override val supportedTypes = setOf(TvType.NSFW)

    override val mainPage = mainPageOf(
        "$mainUrl" to "Latest Videos",
        "$mainUrl/indian-porn" to "Indian Porn",
        "$mainUrl/mylf" to "MYLF",
        "$mainUrl/blacked" to "Blacked",
        "$mainUrl/teamskeet" to "TeamSkeet",
        "$mainUrl/bangbros" to "BangBros",
        "$mainUrl/brazzers" to "Brazzers",
        "$mainUrl/jav" to "JAV",
        "$mainUrl/category/4k-porn" to "4K Porn",
        "$mainUrl/category/teen" to "Teen",
        "$mainUrl/category/milf" to "MILF",
        "$mainUrl/category/anal" to "Anal"
    )

    override suspend fun getMainPage(page: Int, request: MainPageRequest): HomePageResponse {
        val url = if (page <= 1) request.data else "${request.data}/${page}"
        val document = app.get(url).document
        val home = document.select(".movie-item").mapNotNull {
            it.toSearchResult()
        }
        return newHomePageResponse(
            list = HomePageList(
                name = request.name,
                list = home,
                isHorizontalImages = true
            ),
            hasNext = true
        )
    }

    private fun Element.toSearchResult(): SearchResponse? {
        val titleElement = this.selectFirst(".title a") ?: return null
        val title = titleElement.text()
        val href = fixUrl(titleElement.attr("href"))
        val posterUrl = this.selectFirst("img")?.attr("src")

        return newMovieSearchResponse(title, href, TvType.NSFW) {
            this.posterUrl = posterUrl
        }
    }

    override suspend fun search(query: String, page: Int): SearchResponseList {
        val url = if (page <= 1) "$mainUrl/search?q=$query" else "$mainUrl/search?q=$query&page=$page"
        val document = app.get(url).document
        val searchResults = document.select(".movie-item").mapNotNull {
            it.toSearchResult()
        }
        return newSearchResponseList(searchResults, hasNext = true)
    }

    override suspend fun load(url: String): LoadResponse {
        val document = app.get(url).document
        val title = document.selectFirst("h1")?.text() ?: ""
        val poster = document.selectFirst("meta[property='og:image']")?.attr("content")
        val tags = document.select(".tags a").map { it.text() }
        
        val recommendations = document.select(".movie-item").mapNotNull {
            it.toSearchResult()
        }

        return newMovieLoadResponse(title, url, TvType.NSFW, url) {
            this.posterUrl = poster
            this.tags = tags
            this.recommendations = recommendations
        }
    }

    override suspend fun loadLinks(
        data: String,
        isCasting: Boolean,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        val response = app.get(data)
        val document = response.document
        
        // Try to find video sources in scripts (JWPlayer config)
        val script = document.select("script").map { it.data() }.find { it.contains("jwplayer") && it.contains("file") }
        if (script != null) {
            val fileRegex = """["']file["']\s*:\s*["']([^"']+)["']""".toRegex()
            fileRegex.findAll(script).forEach { match ->
                val videoUrl = match.groupValues[1]
                callback.invoke(
                        newExtractorLink(
                            source = name,
                            name = name,
                            url = videoUrl,
                            type = if (videoUrl.contains(".m3u8")) ExtractorLinkType.M3U8 else ExtractorLinkType.VIDEO,
                            initializer = {
                                this.referer = data
                                this.quality = Qualities.Unknown.value
                            }
                        )
                )
            }
        }

        // Fallback to iframe extraction
        document.select("iframe").forEach { iframe ->
            val src = iframe.attr("src")
            if (src.isNotBlank() && !src.contains("tsyndicate")) {
                loadExtractor(src, data, subtitleCallback, callback)
            }
        }

        return true
    }
}
