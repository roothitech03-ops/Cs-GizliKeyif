package com.kraptor

import com.lagradost.api.Log
import org.jsoup.nodes.Element
import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.utils.*
import com.lagradost.cloudstream3.LoadResponse.Companion.addActors

class XTapes : MainAPI() {
    override var mainUrl              = "https://xtapes.tw"
    override var name                 = "XTapes"
    override val hasMainPage          = true
    override var lang                 = "en"
    override val hasQuickSearch       = false
    override val supportedTypes       = setOf(TvType.NSFW)
    override val vpnStatus            = VPNStatus.MightBeNeeded

    override val mainPage = mainPageOf(
        "${mainUrl}/latest-updates/" to "Latest Videos",
        "${mainUrl}/most-popular/" to "Most Popular",
        "${mainUrl}/top-rated/" to "Top Rated",
        "${mainUrl}/full-movies/" to "Full Movies",
        "${mainUrl}/categories/4k/" to "4K",
        "${mainUrl}/categories/anal/" to "Anal",
        "${mainUrl}/categories/asian/" to "Asian",
        "${mainUrl}/categories/big-tits/" to "Big Tits",
        "${mainUrl}/categories/blowjob/" to "Blowjob",
        "${mainUrl}/categories/creampie/" to "Creampie",
        "${mainUrl}/categories/lesbian/" to "Lesbian",
        "${mainUrl}/categories/milfs/" to "MILFs",
        "${mainUrl}/categories/teen/" to "Teen",
        "${mainUrl}/networks/brazzers/" to "Brazzers",
        "${mainUrl}/networks/bangbros/" to "BangBros",
        "${mainUrl}/networks/realitykings/" to "Reality Kings",
        "${mainUrl}/networks/vixen/" to "Vixen",
        "${mainUrl}/networks/blacked/" to "Blacked",
        "${mainUrl}/networks/tushy/" to "Tushy",
    )

    override suspend fun getMainPage(page: Int, request: MainPageRequest): HomePageResponse {
        val url = if (page == 1) request.data else "${request.data}${page}/"
        val document = app.get(url).document
        val home = document.select("div.thumb-list div.thumb, div.videos-list div.video-item, article.thumb").mapNotNull { it.toSearchResult() }
        return newHomePageResponse(request.name, home, hasNext = home.isNotEmpty())
    }

    private fun Element.toSearchResult(): SearchResponse? {
        val anchor = this.selectFirst("a[href*='/'][title]") 
            ?: this.selectFirst("a.thumb-link") 
            ?: this.selectFirst("a") 
            ?: return null
        val title = anchor.attr("title").ifEmpty { 
            this.selectFirst("span.title, div.title, h3, h2")?.text() 
        } ?: return null
        val href = fixUrlNull(anchor.attr("href")) ?: return null
        val posterUrl = fixUrlNull(
            this.selectFirst("img")?.attr("data-src")
                ?: this.selectFirst("img")?.attr("src")
        )
        return newMovieSearchResponse(title.trim(), href, TvType.NSFW) {
            this.posterUrl = posterUrl
        }
    }

    override suspend fun search(query: String, page: Int): SearchResponseList {
        val searchUrl = "${mainUrl}/search/${query.replace(" ", "-")}/"
        val url = if (page == 1) searchUrl else "${searchUrl}${page}/"
        val document = app.get(url).document
        val results = document.select("div.thumb-list div.thumb, div.videos-list div.video-item, article.thumb").mapNotNull { it.toSearchResult() }
        return newSearchResponseList(results, hasNext = results.isNotEmpty())
    }

    override suspend fun quickSearch(query: String): List<SearchResponse>? = search(query)

    override suspend fun load(url: String): LoadResponse? {
        val document = app.get(url).document
        val title = document.selectFirst("h1.title, h1, meta[property=og:title]")?.let {
            it.text().ifEmpty { it.attr("content") }
        }?.trim() ?: return null
        val poster = fixUrlNull(
            document.selectFirst("meta[property=og:image]")?.attr("content")
                ?: document.selectFirst("video")?.attr("poster")
        )
        val description = document.selectFirst("div.description, meta[property=og:description]")?.let {
            it.text().ifEmpty { it.attr("content") }
        }?.trim()
        val tags = document.select("div.tags a, div.categories a, a.tag").map { it.text().trim() }.filter { it.isNotEmpty() }
        val actors = document.select("div.pornstars a, div.models a, a.model").map { 
            Actor(it.text().trim(), null)
        }
        return newMovieLoadResponse(title, url, TvType.NSFW, url) {
            this.posterUrl = poster
            this.plot = description
            this.tags = tags
            addActors(actors)
        }
    }

    override suspend fun loadLinks(
        data: String,
        isCasting: Boolean,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        val document = app.get(data).document
        val pageHtml = document.html()
        
        val patterns = listOf(
            Regex("""video_url['"]?\s*[:=]\s*['"]([^'"]+)['"]"""),
            Regex("""source['"]?\s*[:=]\s*['"]([^'"]+\.mp4[^'"]*)['"]"""),
            Regex("""file['"]?\s*[:=]\s*['"]([^'"]+\.mp4[^'"]*)['"]"""),
            Regex("""(https?://[^\s'"<>]+\.mp4[^\s'"<>]*)"""),
            Regex("""(https?://[^\s'"<>]+\.m3u8[^\s'"<>]*)""")
        )
        
        for (pattern in patterns) {
            val match = pattern.find(pageHtml)
            if (match != null) {
                val videoUrl = match.groupValues[1].replace("\\/", "/")
                if (videoUrl.contains(".mp4") || videoUrl.contains(".m3u8")) {
                    val linkType = if (videoUrl.contains(".m3u8")) ExtractorLinkType.M3U8 else ExtractorLinkType.VIDEO
                    callback.invoke(
                        newExtractorLink(this.name, this.name, videoUrl, linkType) {
                            this.referer = "$mainUrl/"
                            this.quality = Qualities.Unknown.value
                        }
                    )
                    return true
                }
            }
        }
        
        document.select("iframe[src]").forEach { iframe ->
            val src = iframe.attr("src")
            if (src.isNotEmpty()) {
                try { loadExtractor(fixUrl(src), data, subtitleCallback, callback) } catch (_: Exception) {}
            }
        }
        return true
    }
}
