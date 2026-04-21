package com.kraptor

import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.utils.*
import com.lagradost.cloudstream3.utils.ExtractorLink
import com.lagradost.cloudstream3.LoadResponse
import com.lagradost.cloudstream3.SearchResponse
import com.lagradost.cloudstream3.MainAPI
import com.lagradost.cloudstream3.TvType
import com.lagradost.cloudstream3.MainPageRequest
import com.lagradost.cloudstream3.HomePageResponse
import com.lagradost.cloudstream3.Episode
import com.lagradost.cloudstream3.newEpisode
import com.lagradost.cloudstream3.newAnimeSearchResponse
import com.lagradost.cloudstream3.newMovieSearchResponse
import com.lagradost.cloudstream3.newTvSeriesLoadResponse
import com.lagradost.cloudstream3.newMovieLoadResponse
import com.lagradost.cloudstream3.fixUrl
import com.lagradost.cloudstream3.fixUrlNull
import com.lagradost.cloudstream3.app
import org.jsoup.nodes.Element
import java.util.Base64

class WatchAnimeWorldProvider : MainAPI() {
    override var mainUrl = "https://watchanimeworld.net"
    override var name = "WatchAnimeWorld"
    override val hasMainPage = true
    override var lang = "hi"
    override val hasDownloadSupport = true
    override val supportedTypes = setOf(
        TvType.Anime,
        TvType.AnimeMovie,
        TvType.Cartoon
    )

    override val mainPage = mainPageOf(
        "$mainUrl/" to "Newest Drops",
        "$mainUrl/series/page/" to "Series",
        "$mainUrl/movies/page/" to "Movies",
        "$mainUrl/category/anime/page/" to "Anime",
        "$mainUrl/category/cartoon/page/" to "Cartoon"
    )

    override suspend fun getMainPage(
        page: Int,
        request: MainPageRequest
    ): HomePageResponse {
        val url = if (page <= 1) request.data else "${request.data}$page/"
        val document = app.get(url).document
        val home = document.select("article.post").mapNotNull {
            it.toSearchResult()
        }
        return newHomePageResponse(request.name, home)
    }

    private fun Element.toSearchResult(): SearchResponse? {
        val title = this.selectFirst(".entry-title, h2, h3")?.text() ?: return null
        val href = fixUrl(this.selectFirst("a")?.attr("href") ?: return null)
        val posterUrl = fixUrlNull(this.selectFirst("img")?.attr("src") ?: this.selectFirst("img")?.attr("data-src"))
        
        return if (href.contains("/movies/")) {
            newMovieSearchResponse(title, href, TvType.AnimeMovie) {
                this.posterUrl = posterUrl
            }
        } else {
            newAnimeSearchResponse(title, href, TvType.Anime) {
                this.posterUrl = posterUrl
            }
        }
    }

    override suspend fun search(query: String): List<SearchResponse> {
        val document = app.get("$mainUrl/?s=$query").document
        return document.select("article.post").mapNotNull {
            it.toSearchResult()
        }
    }

    override suspend fun load(url: String): LoadResponse {
        val document = app.get(url).document

        val title = document.selectFirst("h1")?.text() ?: return throw ErrorLoadingException("No title found")
        val poster = fixUrlNull(document.selectFirst("div.poster img")?.attr("src") ?: document.selectFirst("div.poster img")?.attr("data-src"))
        val tags = document.select("div.sgeneros a").map { it.text() }
        val year = document.selectFirst("span.date")?.text()?.trim()?.take(4)?.toIntOrNull()
        val description = document.selectFirst("div.wp-content p, div.description p")?.text()
        
        val isMovie = url.contains("/movies/")
        val tvType = if (isMovie) TvType.AnimeMovie else TvType.Anime

        if (isMovie) {
            return newMovieLoadResponse(title, url, tvType, url) {
                this.posterUrl = poster
                this.year = year
                this.plot = description
                this.tags = tags
            }
        } else {
            val episodes = mutableListOf<Episode>()
            document.select("#episode_by_temp li").forEach { epElement ->
                val epHref = fixUrlNull(epElement.selectFirst("a")?.attr("href")) ?: return@forEach
                val epTitle = epElement.selectFirst(".entry-title, h2")?.text()
                val meta = epElement.selectFirst(".num-epi")?.text() // Format: 1x1
                val seasonNum = meta?.split("x")?.firstOrNull()?.trim()?.toIntOrNull()
                val epNum = meta?.split("x")?.lastOrNull()?.trim()?.toIntOrNull()
                val epPoster = fixUrlNull(epElement.selectFirst("img")?.attr("src") ?: epElement.selectFirst("img")?.attr("data-src"))

                episodes.add(
                    newEpisode(epHref) {
                        this.name = epTitle
                        this.season = seasonNum
                        this.episode = epNum
                        this.posterUrl = epPoster
                    }
                )
            }

            return newTvSeriesLoadResponse(title, url, tvType, episodes) {
                this.posterUrl = poster
                this.year = year
                this.plot = description
                this.tags = tags
            }
        }
    }

    override suspend fun loadLinks(
        data: String,
        isCasting: Boolean,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        val document = app.get(data).document
        
        // Method 1: Direct Iframes (Server 1)
        document.select("iframe").forEach { iframe ->
            val src = iframe.attr("src")
            if (src.isNotBlank() && !src.contains("google.com") && !src.contains("facebook.com")) {
                loadExtractor(src, data, subtitleCallback, callback)
            }
        }
        
        // Method 2: API Player (Server 2)
        // Check for the player1.php pattern in scripts
        document.select("script").forEach { script ->
            val scriptData = script.data()
            if (scriptData.contains("player1.php?data=")) {
                val dataParam = Regex("""player1\.php\?data=([^"']+)""").find(scriptData)?.groupValues?.get(1)
                if (dataParam != null) {
                    try {
                        val decoded = String(Base64.getDecoder().decode(dataParam))
                        val jsonArray = AppUtils.parseJson<List<PlayerItem>>(decoded)
                        jsonArray.forEach { item ->
                            if (!item.link.isNullOrBlank()) {
                                loadExtractor(item.link, data, subtitleCallback, callback)
                            }
                        }
                    } catch (e: Exception) {
                        // Fallback: try calling the API directly if possible
                        val apiUrl = "$mainUrl/api/player1.php?data=$dataParam"
                        val apiResponse = app.get(apiUrl).text
                        try {
                            val jsonArray = AppUtils.parseJson<List<PlayerItem>>(apiResponse)
                            jsonArray.forEach { item ->
                                if (!item.link.isNullOrBlank()) {
                                    loadExtractor(item.link, data, subtitleCallback, callback)
                                }
                            }
                        } catch (e2: Exception) {}
                    }
                }
            }
        }

        return true
    }

    data class PlayerItem(
        val language: String? = null,
        val link: String? = null
    )
}