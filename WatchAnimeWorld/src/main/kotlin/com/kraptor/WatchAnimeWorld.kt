package com.kraptor

import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.utils.*
import com.lagradost.cloudstream3.LoadResponse.Companion.addTrailer
import org.jsoup.nodes.Element

class WatchAnimeWorldProvider : MainAPI() {
    override var mainUrl = "https://watchanimeworld.net"
    override var name = "WatchAnimeWorld"
    override val hasMainPage = true
    override var lang = "hi"
    override val hasDownloadSupport = true
    override val supportedTypes = setOf(TvType.Anime, TvType.AnimeMovie, TvType.OVA)

    override val mainPage = mainPageOf(
        "$mainUrl/trending?page=" to "Trending",
        "$mainUrl/popular?page=" to "Popular",
        "$mainUrl/latest-updates?page=" to "Latest Updates",
        "$mainUrl/az-list?page=" to "Alphabetical List",
        "$mainUrl/genre/action?page=" to "Action",
        "$mainUrl/genre/adventure?page=" to "Adventure",
        "$mainUrl/genre/comedy?page=" to "Comedy",
        "$mainUrl/genre/drama?page=" to "Drama",
        "$mainUrl/genre/fantasy?page=" to "Fantasy",
        "$mainUrl/genre/horror?page=" to "Horror",
        "$mainUrl/genre/mystery?page=" to "Mystery",
        "$mainUrl/genre/romance?page=" to "Romance",
        "$mainUrl/genre/sci-fi?page=" to "Sci-Fi",
        "$mainUrl/genre/slice-of-life?page=" to "Slice of Life",
        "$mainUrl/genre/sports?page=" to "Sports",
        "$mainUrl/genre/supernatural?page=" to "Supernatural",
        "$mainUrl/genre/thriller?page=" to "Thriller"
    )

    override suspend fun getMainPage(
        page: Int,
        request: MainPageRequest
    ): HomePageResponse {
        val document = app.get(request.data + page).document
        val items = document.select("div.item").mapNotNull { element ->
            element.toSearchResult()
        }

        return newHomePageResponse(request.name, items)
    }

    private fun Element.toSearchResult(): AnimeSearchResponse? {
        val title = this.selectFirst("h3.title")?.text() ?: return null
        val href = this.selectFirst("a")?.attr("href") ?: return null
        val posterUrl = this.selectFirst("img")?.attr("src") ?: return null
        
        return newAnimeSearchResponse(title, fixUrl(href), TvType.Anime) {
            this.posterUrl = fixUrl(posterUrl)
        }
    }

    override suspend fun search(query: String): List<SearchResponse> {
        val document = app.get("$mainUrl/search?keyword=$query").document
        return document.select("div.item").mapNotNull { element ->
            element.toSearchResult()
        }
    }

    override suspend fun load(url: String): LoadResponse? {
        val document = app.get(url).document
        
        val title = document.selectFirst("h1.title")?.text() ?: return null
        val poster = document.selectFirst("div.poster img")?.attr("src")
        val year = document.selectFirst("span.year")?.text()?.toIntOrNull()
        val description = document.selectFirst("div.description")?.text()
        val tags = document.select("div.genres a").map { it.text() }
        val rating = document.selectFirst("span.rating")?.text()?.toRatingInt()
        
        val episodes = document.select("div.episodes-list a").mapNotNull {
            val epTitle = it.selectFirst("span.ep-title")?.text() ?: return@mapNotNull null
            val epUrl = it.attr("href")
            Episode(
                data = epUrl,
                name = epTitle
            )
        }.reversed()
        
        return newAnimeLoadResponse(title, url, TvType.Anime) {
            this.posterUrl = poster
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
        val document = app.get(data).document
        
        // Try to find iframe sources
        document.select("iframe").forEach { iframe ->
            val src = iframe.attr("src")
            if (src.isNotEmpty()) {
                loadExtractor(fixUrl(src), data, subtitleCallback, callback)
            }
        }
        
        // Try to find script tags with video sources
        document.select("script").forEach { script ->
            if (script.data().contains("sources") || script.data().contains("file")) {
                val scriptData = script.data()
                
                // Extract direct video links
                val videoRegex = Regex("""["']file["']\s*:\s*["']([^"']+)["']""")
                videoRegex.findAll(scriptData).forEach { match ->
                    val videoUrl = match.groupValues[1]
                    if (videoUrl.isNotEmpty()) {
                        callback.invoke(
                            ExtractorLink(
                                name = name,
                                url = videoUrl,
                                referer = data,
                                quality = Qualities.Unknown.value,
                                isM3u8 = videoUrl.contains(".m3u8")
                            )
                        )
                    }
                }
                
                // Extract m3u8 links
                val m3u8Regex = Regex("""["']([^"']*\.m3u8[^"']*)["']""")
                m3u8Regex.findAll(scriptData).forEach { match ->
                    val m3u8Url = match.groupValues[1]
                    if (m3u8Url.isNotEmpty()) {
                        callback.invoke(
                            ExtractorLink(
                                name = name,
                                url = m3u8Url,
                                referer = data,
                                quality = Qualities.Unknown.value,
                                isM3u8 = true
                            )
                        )
                    }
                }
            }
        }
        
        return true
    }
}
