package com.kraptor

import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.utils.*
import org.jsoup.nodes.Element

class LatestLeaks : MainAPI() {
    override var mainUrl              = "https://latestleaks.co"
    override var name                 = "LatestLeaks"
    override val hasMainPage          = true
    override var lang                 = "en"
    override val hasQuickSearch       = false
    override val supportedTypes       = setOf(TvType.NSFW)

    override val mainPage = mainPageOf(
        "$mainUrl/" to "Home",
        "$mainUrl/onlyfans/" to "OnlyFans",
        "$mainUrl/movies/" to "Movies",
        "$mainUrl/theporndude/" to "ThePornDude",
        "$mainUrl/porn-sites/" to "Porn Sites"
    )

    override suspend fun getMainPage(page: Int, request: MainPageRequest): HomePageResponse {
        val url = if (page <= 1) request.data else "${request.data}page/$page/"
        val document = app.get(url).document
        val home = document.select("div.item").mapNotNull { it.toMainPageResult() }

        return newHomePageResponse(request.name, home, hasNext = true)
    }

    private fun Element.toMainPageResult(): SearchResponse? {
        val title = this.selectFirst("h2.entry-title a")?.text() ?: return null
        val href = this.selectFirst("h2.entry-title a")?.attr("href") ?: return null
        val posterUrl = this.selectFirst("img")?.attr("data-src") ?: this.selectFirst("img")?.attr("src")

        return newMovieSearchResponse(title, fixUrl(href), TvType.NSFW) {
            this.posterUrl = fixUrl(posterUrl ?: "")
        }
    }

    override suspend fun search(query: String, page: Int): SearchResponseList {
        val url = if (page <= 1) "$mainUrl/?s=$query" else "$mainUrl/page/$page/?s=$query"
        val document = app.get(url).document
        val searchResults = document.select("div.item").mapNotNull { it.toMainPageResult() }
        return newSearchResponseList(searchResults, hasNext = true)
    }

    override suspend fun load(url: String): LoadResponse? {
        val document = app.get(url).document

        val title = document.selectFirst("h1.entry-title")?.text()?.trim() ?: return null
        val poster = document.selectFirst("meta[property=og:image]")?.attr("content")
        val description = document.selectFirst("div.entry-content p")?.text()?.trim()
        val tags = document.select("p.post-tags a").map { it.text() }

        return newMovieLoadResponse(title, url, TvType.NSFW, url) {
            this.posterUrl = fixUrl(poster ?: "")
            this.plot = description
            this.tags = tags
        }
    }

    override suspend fun loadLinks(
        data: String,
        isCasting: Boolean,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        val document = app.get(data).document
        val iframeSrc = document.selectFirst("iframe")?.attr("src")

        if (iframeSrc != null) {
            val fixedIframeSrc = fixUrl(iframeSrc)
            if (fixedIframeSrc.contains("streamtape.com")) {
                extractStreamtape(fixedIframeSrc, callback)
            }
        }
        return true
    }

    private suspend fun extractStreamtape(url: String, callback: (ExtractorLink) -> Unit) {
        val document = app.get(url).text
        
        // Streamtape direct link extraction logic using Regex for better compatibility
        val robotlinkRegex = Regex("""id=["']robotlink["']>([^<]+)""")
        val tokenRegex = Regex("""token=([^&'"]+)""")
        
        val mainId = robotlinkRegex.find(document)?.groupValues?.get(1)
        val token = tokenRegex.find(document)?.groupValues?.get(1)
        
        if (mainId != null && token != null) {
            val videoUrl = "https://streamtape.com/get_video?id=$mainId&token=$token&stream=1"
            callback(
                newExtractorLink(
                    name = "Streamtape",
                    source = "Streamtape",
                    url = videoUrl,
                    referer = url,
                    quality = Qualities.P720.value,
                    type = ExtractorLinkType.VIDEO
                )
            )
        }
    }
}
