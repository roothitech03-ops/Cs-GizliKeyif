package com.kraptor

import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.utils.*
import org.jsoup.nodes.Element

class WatchAnimeWorldProvider : MainAPI() {
    override var mainUrl = "https://watchanimeworld.net"
    override var name = "WatchAnimeWorld"
    override val hasMainPage = true
    override var lang = "hi"
    override val hasDownloadSupport = true
    override val supportedTypes = setOf(TvType.Anime, TvType.AnimeMovie, TvType.OVA)

    override val mainPage = mainPageOf(
        "\$mainUrl/trending?page=" to "Trending",
        "\$mainUrl/popular?page=" to "Popular",
        "\$mainUrl/latest-updates?page=" to "Latest Updates",
        "\$mainUrl/az-list?page=" to "Alphabetical List",
        "\$mainUrl/genre/action?page=" to "Action",
        "\$mainUrl/genre/adventure?page=" to "Adventure",
        "\$mainUrl/genre/comedy?page=" to "Comedy",
        "\$mainUrl/genre/drama?page=" to "Drama",
        "\$mainUrl/genre/fantasy?page=" to "Fantasy",
        "\$mainUrl/genre/horror?page=" to "Horror",
        "\$mainUrl/genre/mystery?page=" to "Mystery",
        "\$mainUrl/genre/romance?page=" to "Romance",
        "\$mainUrl/genre/sci-fi?page=" to "Sci-Fi",
        "\$mainUrl/genre/slice-of-life?page=" to "Slice of Life",
        "\$mainUrl/genre/sports?page=" to "Sports",
        "\$mainUrl/genre/supernatural?page=" to "Supernatural",
        "\$mainUrl/genre/thriller?page=" to "Thriller"
    )

    override suspend fun getMainPage(
        page: Int,
        request: MainPageRequest
    ): HomePageResponse {
        val response = app.get(request.data + page).let { response ->
            if (response.code == 404) return@let null
            response
        } ?: return newHomePageResponse(request.name, emptyList())

        val document = response.document
        val items = document.select("div.item").mapNotNull { element ->
            element.toSearchResult(response.mainUrl)
        }

        return newHomePageResponse(request.name, items)
    }

    private fun Element.toSearchResult(baseUrl: String): AnimeSearchResponse? {
        val title = this.selectFirst("h3.title")?.text()?.trim() ?: return null
        val href = this.selectFirst("a")?.attr("href")?.trim() ?: return null
        val posterUrl = this.selectFirst("img")?.attr("src")?.trim()
        
        if (posterUrl == null || !posterUrl.startsWith("http")) return null

        return newAnimeSearchResponse(
            title = title,
            url = if (href.startsWith("/")) "\$baseUrl\$href" else href,
            type = TvType.Anime
        ) {
            this.posterUrl = fixUrl(baseUrl, posterUrl)
        }
    }

    override suspend fun search(query: String): List<SearchResponse> {
        val url = "$mainUrl/search?keyword=${Uri.encode(query)}"
        val response = app.get(url).let { response ->
            if (response.code == 404) return@let null
            response
        } ?: return emptyList()

        val document = response.document
        return document.select("div.item").mapNotNull { element ->
            element.toSearchResult(mainUrl)
        }
    }

    override suspend fun load(url: String): LoadResponse? {
        val response = app.get(url).let { response ->
            if (response.code == 404) return@let null
            response
        } ?: return null

        val document = response.document

        val title = document.selectFirst("h1.title")?.text()?.trim() ?: return null
        val poster = document.selectFirst("div.poster img")?.attr("src")
        val year = document.selectFirst("span.year")?.text()?.trim()?.toIntOrNull()
        val description = document.selectFirst("div.description")?.text()?.trim()
        val tags = document.select("div.genres a").map { it.text().trim() }
        val rating = document.selectFirst("span.rating")?.text()?.trim()?.toRatingInt()
        
        val episodeLinks = document.select("div.episodes-list a")
        val episodes = episodeLinks.mapNotNull { element ->
            val epTitle = element.selectFirst("span.ep-title")?.text()?.trim()
            val epUrl = element.attr("href").trim()
            
            if (epTitle != null && epUrl.isNotEmpty()) {
                Episode(
                    data = epUrl,
                    name = epTitle,
                    url = if (epUrl.startsWith("/")) "\$mainUrl\$epUrl" else epUrl
                )
            } else null
        }.reversed()

        if (episodes.isEmpty()) return null

        return newAnimeLoadResponse(
            title = title,
            url = url,
            type = TvType.Anime
        ) {
            this.posterUrl = if (poster != null) fixUrl(mainUrl, poster) else null
            this.year = year
            this.plot = description
            this.tags = tags
            this.rating = rating
            addEpisodes(DubStatus.Subbed, episodes)
        }
    }

    override suspend fun loadLinks(
        data: String,
        isCasting: Boolean,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        val response = app.get(data).let { response ->
            if (response.code == 404) return@let null
            response
        } ?: return false

        val document = response.document
        
        // Extract iframes
        document.select("iframe").forEach { iframe ->
            val src = iframe.attr("src")
            if (src.isNotEmpty()) {
                val iframeUrl = if (src.startsWith("http")) src else "\$mainUrl\$src"
                try {
                    val iframeDoc = app.get(iframeUrl).document
                    // Extract video sources from iframe content if needed
                    val videoSource = iframeDoc.select("video > source").first() ?: iframeDoc.select("video source").first()
                    val srcAttr = videoSource?.attr("src") ?: ""
                    
                    if (srcAttr.isNotEmpty()) {
                        callback.invoke(
                            ExtractorLink(
                                source = "WatchAnimeWorld",
                                name = "WatchAnimeWorld",
                                url = srcAttr,
                                referer = iframeUrl,
                                quality = Qualities.Unknown.value,
                                isM3