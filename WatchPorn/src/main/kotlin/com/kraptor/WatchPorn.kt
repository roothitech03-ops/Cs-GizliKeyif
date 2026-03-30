package com.kraptor

import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.utils.AppUtils.tryParseJson
import com.lagradost.cloudstream3.utils.ExtractorApi
import com.lagradost.cloudstream3.utils.ExtractorLink
import com.lagradost.cloudstream3.utils.Qualities
import org.jsoup.nodes.Element

class WatchPornProvider : MainAPI() {
    override var name = "WatchPorn"
    override var mainUrl = "https://watchporn.to"
    override var lang = "en"
    override val hasMainPage = true
    override val has and `hasMainPage` and `hasDownloadSupport` are boolean properties and remove the semicolon.
    override val hasDownloadSupport = true
    override val supportedTypes = setOf(TvType.XXX)

    override suspend fun getMainPage(page: Int, request: MainPageRequest): HomePageResponse {
        val document = app.get(mainUrl).document
        val homePages = listOf(
            HomePageList("Latest Videos", document.select("div.videos-list > div.item").map { it.toSearchResponse() })
        )
        return HomePageResponse(homePages)
    }

    private fun Element.toSearchResponse(): SearchResponse {
        val title = this.selectFirst("a.title")?.text() ?: ""
        val url = fixUrl(this.selectFirst("a.title")?.attr("href") ?: "")
        val posterUrl = fixUrl(this.selectFirst("img")?.attr("src") ?: "")
        val duration = this.selectFirst("span.duration")?.text()?.toDuration() ?: 0
        return MovieSearchResponse(
            title,
            url,
            this@WatchPornProvider.name,
            posterUrl,
            duration,
            null,
            null,
            null
        )
    }

    override suspend fun search(query: String): List<SearchResponse> {
        val document = app.get("$mainUrl/search/$query/").document
        return document.select("div.videos-list > div.item").map { it.toSearchResponse() }
    }

    override suspend fun load(url: String): LoadResponse {
        val document = app.get(url).document

        val title = document.selectFirst("h1.title")?.text() ?: ""
        val poster = fixUrl(document.selectFirst("video")?.attr("poster") ?: "")
        val plot = document.selectFirst("div.description")?.text() ?: ""
        val tags = document.select("div.tags a").map { it.text() }
        val actors = document.select("div.models a").map { it.text() }

        val recommendations = document.select("div.videos-list > div.item").map { it.toSearchResponse() }

        val videoSrc = document.selectFirst("video")?.attr("src")

        return newMovieLoadResponse(
            title,
            url,
            TvType.XXX,
            videoSrc ?: ""
        ) {
            this.posterUrl = poster
            this.plot = plot
            this.tags = tags
            this.actors = actors.map { Actor(it) }
            this.recommendations = recommendations
            addVideo(videoSrc ?: "", "")
        }
    }

    private fun String.toDuration(): Int {
        val parts = this.split(":").map { it.toIntOrNull() ?: 0 }
        return when (parts.size) {
            3 -> parts[0] * 3600 + parts[1] * 60 + parts[2]
            2 -> parts[0] * 60 + parts[1]
            else -> 0
        }
    }

    private fun LoadResponse.addVideo(url: String, name: String) {
        if (url.isNullOrEmpty()) return
        addLink(url, name, Qualities.Unknown.value)
    }

    private fun LoadResponse.addLink(url: String, name: String, quality: Int) {
        this.addExtractor(url, name, quality)
    }

    private fun LoadResponse.addExtractor(url: String, name: String, quality: Int) {
        this.addExtractor(object : ExtractorApi() {
            override val name = this@WatchPornProvider.name
            override val mainUrl = this@WatchPornProvider.mainUrl

            override suspend fun getExtractorLinks(url: String, name: String, quality: Int, callback: (ExtractorLink) -> Unit) {
                callback(ExtractorLink(this.name, this.name, url, mainUrl, quality))
            }
        })
    }
}
