package com.kraptor

import android.content.Context
import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.utils.ExtractorLink
import com.lagradost.cloudstream3.utils.ExtractorLinkType
import com.lagradost.cloudstream3.utils.getQualityFromName
import com.lagradost.cloudstream3.utils.fixUrlNull
import org.jsoup.nodes.Element
import com.lagradost.cloudstream3.LoadResponse.Companion.addActors
import com.lagradost.cloudstream3.LoadResponse.Companion.addTrailer
import com.lagradost.cloudstream3.utils.AppUtils.parseJson
import com.lagradost.cloudstream3.utils.AppUtils.toJson
import com.lagradost.cloudstream3.utils.ExtractorApi
import com.lagradost.cloudstream3.extractors.Streamtape

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
        val document = app.get("${request.data}page/$page/").document
        val home     = document.select("div.item").mapNotNull { it.toMainPageResult() }

        return newHomePageResponse(request.name, home)
    }

    private fun Element.toMainPageResult(): SearchResponse? {
        val title     = this.selectFirst("h2.entry-title a")?.text() ?: return null
        val href      = fixUrlNull(this.selectFirst("h2.entry-title a")?.attr("href")) ?: return null
        val posterUrl = fixUrlNull(this.selectFirst("img")?.attr("data-src"))

        return newMovieSearchResponse(title, href, TvType.NSFW) {
            this.posterUrl = posterUrl
        }
    }

    override suspend fun search(query: String, page: Int): SearchResponseList {
        val document = app.get("$mainUrl/?s=$query&paged=$page").document

        val searchResults = document.select("div.item").mapNotNull { it.toMainPageResult() }
        return newSearchResponseList(searchResults, hasNext = true)
    }

    override suspend fun load(url: String): LoadResponse? {
        val document = app.get(url).document

        val title           = document.selectFirst("h1.entry-title")?.text()?.trim() ?: return null
        val poster          = fixUrlNull(document.selectFirst("meta[property=og:image]")?.attr("content"))
        val description     = document.selectFirst("div.entry-content p")?.text()?.trim()
        val tags            = document.select("p.post-tags a").map { it.text() }

        val iframeSrc = document.selectFirst("iframe")?.attr("src")

        return newMovieLoadResponse(title, url, TvType.NSFW, url) {
            this.posterUrl       = poster
            this.plot            = description
            this.tags            = tags
            // No direct year, score, duration, recommendations, actors, trailer from page
        }
    }

    override suspend fun loadLinks(data: String, isCasting: Boolean, subtitleCallback: (SubtitleFile) -> Unit, callback: (ExtractorLink) -> Unit): Boolean {
        val document = app.get(data).document
        val iframeSrc = document.selectFirst("iframe")?.attr("src")

        if (iframeSrc != null) {
            if (iframeSrc.contains("streamtape.com")) {
                Streamtape().call(iframeSrc, data, subtitleCallback, callback)
            }
            // Add other extractors here if needed
        }
        return true
    }
}
