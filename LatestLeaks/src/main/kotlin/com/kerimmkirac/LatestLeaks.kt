package com.kerimmkirac

import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.utils.*
import com.lagradost.cloudstream3.utils.ExtractorLink
import org.jsoup.nodes.Element

class LatestLeaks : MainAPI() {
    override var mainUrl = "https://latestleaks.co"
    override var name = "Latest Leaks"
    override val hasMainPage = true
    override var lang = "en"
    override val hasQuickSearch = false
    override val supportedTypes = setOf(TvType.NSFW)

    override val mainPage = mainPageOf(
        "$mainUrl" to "Latest Videos",
        "$mainUrl/tag/onlyfans/" to "OnlyFans",
        "$mainUrl/tag/4k/" to "4K Videos",
        "$mainUrl/tag/brazzers/" to "Brazzers",
        "$mainUrl/tag/milf/" to "MILF",
        "$mainUrl/tag/teen/" to "Teen",
        "$mainUrl/tag/anal/" to "Anal",
        "$mainUrl/tag/big-tits/" to "Big Tits",
        "$mainUrl/tag/blonde/" to "Blonde",
        "$mainUrl/tag/brunette/" to "Brunette"
    )

    override suspend fun getMainPage(page: Int, request: MainPageRequest): HomePageResponse {
        val url = if (page <= 1) request.data else "${request.data}/page/$page/"
        val document = app.get(url).document
        val home = document.select("article").mapNotNull {
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
        val titleElement = this.selectFirst("h2 a") ?: return null
        val title = titleElement.text()
        val href = titleElement.attr("href")
        // FIX: Website uses lazy loading - actual image URL is in data-src attribute
        // The src attribute only contains an SVG placeholder (data:image/svg+xml,...)
        val img = this.selectFirst("img")
        val posterUrl = img?.attr("data-src")?.takeIf { it.isNotBlank() }
            ?: img?.attr("src")?.takeIf { it.isNotBlank() && !it.startsWith("data:") }
        return newMovieSearchResponse(title, href, TvType.NSFW) {
            this.posterUrl = posterUrl
        }
    }

    override suspend fun search(query: String, page: Int): SearchResponseList {
        val url = if (page <= 1) "$mainUrl/?s=$query" else "$mainUrl/page/$page/?s=$query"
        val document = app.get(url).document
        val searchResults = document.select("article").mapNotNull {
            it.toSearchResult()
        }
        return newSearchResponseList(searchResults, hasNext = true)
    }

    override suspend fun load(url: String): LoadResponse {
        val document = app.get(url).document
        val title = document.selectFirst("h1.entry-title")?.text() ?: ""
        val poster = document.selectFirst("meta[property='og:image']")?.attr("content")
        val tags = document.select(".entry-content a[href*='/tag/']").map { it.text() }
        
        val recommendations = document.select(".yarpp-related article").mapNotNull {
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
        val document = app.get(data).document
        
        // Extract iframes (Streamtape, etc.)
        document.select("iframe").forEach { iframe ->
            val src = iframe.attr("src")
            if (src.isNotBlank()) {
                loadExtractor(src, data, subtitleCallback, callback)
            }
        }

        // Extract direct video links if any
        document.select("video source").forEach { source ->
            val videoUrl = source.attr("src")
            if (videoUrl.isNotBlank()) {
                callback.invoke(
                    newExtractorLink(
                        source = "LatestLeaks",
                        name = "LatestLeaks",
                        url = videoUrl,
                        type = if (videoUrl.contains(".m3u8")) ExtractorLinkType.M3U8 else ExtractorLinkType.VIDEO
                    ) {
                        this.referer = data
                        this.quality = getQualityFromName(source.attr("label"))
                    }
                )
            }
        }

        return true
    }
}
