package com.kraptor

import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.network.CloudflareKiller
import com.lagradost.cloudstream3.utils.*
import org.jsoup.nodes.Element
import org.jsoup.select.Elements
import com.lagradost.cloudstream3.base64Decode
import com.lagradost.cloudstream3.LoadResponse.Companion.addImdbUrl
import com.lagradost.cloudstream3.LoadResponse.Companion.addActors
import com.lagradost.cloudstream3.utils.AppUtils.tryParseJson
import com.lagradost.cloudstream3.utils.AppUtils.parseJson
import kotlinx.coroutines.runBlocking
import org.json.JSONObject
import kotlinx.coroutines.async
import kotlinx.coroutines.supervisorScope
import java.util.concurrent.ConcurrentHashMap
import android.util.Log

open class MoviesmodProvider : MainAPI() {
    override var mainUrl = "https://moviesmod.farm"
    override var name = "Moviesmod"
    override val hasMainPage = true
    override var lang = "hi"
    override val hasDownloadSupport = true
    val cinemeta_url = "https://aiometadata.elfhosted.com/stremio/9197a4a9-2f5b-4911-845e-8704c520bdf7/meta"
    private val cfKiller by lazy { CloudflareKiller() }
    override val supportedTypes = setOf(
        TvType.Movie,
        TvType.TvSeries,
        TvType.AsianDrama,
        TvType.Anime
    )

    init {
        runBlocking {
            basemainUrl?.let {
                mainUrl = it
            }
        }
    }

    companion object {
        val basemainUrl: String? by lazy {
            runBlocking {
                try {
                    val response = app.get("https://raw.githubusercontent.com/SaurabhKaperwan/Utils/refs/heads/main/urls.json")
                    val json = response.text
                    val jsonObject = JSONObject(json)
                    jsonObject.optString("moviesmod")
                } catch (e: Exception) {
                    null
                }
            }
        }
    }

    override val mainPage = mainPageOf(
        "/page/" to "Home",
        "/movies/page/" to "Latest Movies",
        "/web-series/page/" to "Latest Web Series",
        "/tv-series/page/" to "Latest TV Series",
        "/anime/page/" to "Anime",
        "/animation-web-series/page/" to "Animation",
    )

    override suspend fun getMainPage(
        page: Int,
        request: MainPageRequest
    ): HomePageResponse {
        val document = app.get(mainUrl + request.data + page, interceptor = cfKiller).document
        val home = document.select("div.post-cards > article").mapNotNull {
            it.toSearchResult()
        }
        return newHomePageResponse(request.name, home)
    }

    fun Element.toSearchResult(): SearchResponse? {
        val title = this.select("a").attr("title").replace("Download ", "")
        val href = this.select("a").attr("href")
        val posterUrl = this.select("a > div > img").attr("src")

        if (title.isEmpty() || href.isEmpty()) return null

        return newMovieSearchResponse(title, href, TvType.Movie) {
            this.posterUrl = posterUrl
        }
    }

    override suspend fun search(query: String, page: Int): SearchResponseList? {
        val document = app.get("$mainUrl/?s=$query" + if(page > 1) "/page/$page" else "", interceptor = cfKiller).document
        val results = document.select("div.post-cards > article").mapNotNull { it.toSearchResult() }
        val hasNext = results.isNotEmpty()
        return newSearchResponseList(results, hasNext)
    }

    override suspend fun load(url: String): LoadResponse? {
        val document = app.get(url, interceptor = cfKiller).document
        var title = document.select("meta[property=og:title]").attr("content").replace("Download ", "")
        val ogTitle = title
        var posterUrl = document.select("meta[property=og:image]").attr("content")
        var description = document.select("div.imdbwp__teaser").text()
        val div = document.select("div.thecontent").text()
        val tvtype = if (div.contains("season", ignoreCase = true)) "series" else "movie"
        val imdbUrl = document.select("a[href*=imdb.com]").attr("href")
        val imdbId = imdbUrl.substringAfter("title/").substringBefore("/").substringBefore("?")

        var cast: List<String> = emptyList()
        var genre: List<String> = emptyList()
        var imdbRating: String = ""
        var year: String = ""
        var background: String = posterUrl

        if (imdbId.isNotEmpty()) {
            try {
                val jsonResponse = app.get("$cinemeta_url/$tvtype/$imdbId.json").text
                val responseData = tryParseJson<ResponseData>(jsonResponse)
                if(responseData != null) {
                    description = responseData.meta.description ?: description
                    cast = responseData.meta.cast ?: emptyList()
                    title = responseData.meta.name ?: title
                    genre = responseData.meta.genre ?: emptyList()
                    imdbRating = responseData.meta.imdbRating ?: ""
                    year = responseData.meta.year ?: ""
                    posterUrl = responseData.meta.poster ?: posterUrl
                    background = responseData.meta.background ?: background
                }
            } catch (e: Exception) {
                // Fallback
                if(description.isEmpty()) {
                    description = document.select("div.thecontent p").firstOrNull()?.text() ?: ""
                }
                val genreText = document.select("a[rel=category tag]").map { it.text() }
                if(genreText.isNotEmpty()) genre = genreText
            }
        }

        if(tvtype == "series") {
            if(title != ogTitle) {
                val checkSeason = Regex("""Season\s*\d*1|S\s*\d*1""").find(ogTitle)
                if (checkSeason == null) {
                    val seasonText = Regex("""Season\s*\d+|S\s*\d+""").find(ogTitle)?.value
                    if(seasonText != null) {
                        title = title + " " + seasonText
                    }
                }
            }

            val tvSeriesEpisodes = mutableListOf<Episode>()
            val episodesMap = ConcurrentHashMap<Pair<Int, Int>, MutableList<String>>()
            val buttons = document.select("a.maxbutton-episode-links,.maxbutton-g-drive,.maxbutton-af-download")

            supervisorScope {
                buttons.map { button ->
                    async {
                        runCatching {
                            var link = button.attr("href")
                            val seasonText = button.parent()?.previousElementSibling()?.text().orEmpty()
                            val realSeason = Regex("""(?:Season |S)(\d+)""")
                                .find(seasonText)
                                ?.groupValues
                                ?.getOrNull(1)
                                ?.toIntOrNull() ?: 1

                            if (link.contains("url=")) {
                                val base64Value = link.substringAfter("url=")
                                link = base64Decode(base64Value)
                            }

                            val doc = app.get(link, interceptor = cfKiller).document

                            // Try new structure first (episode links page)
                            val episodeLinks = doc.select("a[href*=unblockedgames], a[href*=driveseed], a[href*=driveleech], a[href*=gdflix], a[href*=vcloud], a[href*=hubcloud], a[href*=drivefire], a[href*=fastdrive], a[href*=drivehub]")
                            var epNum = 1
                            episodeLinks.forEach { epLink ->
                                val href = epLink.attr("href").takeIf { it.isNotBlank() } ?: return@forEach
                                val key = Pair(realSeason, epNum)
                                episodesMap.compute(key) { _, current ->
                                    (current ?: mutableListOf()).apply { add(href) }
                                }
                                epNum++
                            }

                            // Try h3/h4 structure (older format)
                            if(episodeLinks.isEmpty()) {
                                val hTags = doc.select("h3,h4")
                                var e = 1
                                hTags.forEach { hTag ->
                                    val epLink = hTag.select("a").first()
                                    val epUrl = epLink?.attr("href")?.takeIf { it.isNotBlank() } ?: return@forEach
                                    val key = Pair(realSeason, e)
                                    episodesMap.compute(key) { _, current ->
                                        (current ?: mutableListOf()).apply { add(epUrl) }
                                    }
                                    e++
                                }
                            }
                        }.onFailure { it.printStackTrace() }
                    }
                }.forEach { it.await() }
            }

            for ((key, value) in episodesMap.toSortedMap(compareBy({ it.first }, { it.second }))) {
                val episodeInfo = if(imdbId.isNotEmpty()) {
                    try {
                        val jsonResponse = app.get("$cinemeta_url/$tvtype/$imdbId.json").text
                        val responseData = tryParseJson<ResponseData>(jsonResponse)
                        responseData?.meta?.videos?.find { it.season == key.first && it.episode == key.second }
                    } catch (e: Exception) { null }
                } else null

                val data = value.distinct().map { source ->
                    EpisodeLink(source)
                }

                if(data.isNotEmpty()) {
                    tvSeriesEpisodes.add(
                        newEpisode(data) {
                            this.name = episodeInfo?.name ?: episodeInfo?.title ?: "Episode ${key.second}"
                            this.season = key.first
                            this.episode = key.second
                            this.posterUrl = episodeInfo?.thumbnail ?: posterUrl
                            this.description = episodeInfo?.overview ?: ""
                        }
                    )
                }
            }

            return if(tvSeriesEpisodes.isNotEmpty()) {
                newTvSeriesLoadResponse(title, url, TvType.TvSeries, tvSeriesEpisodes.sortedWith(compareBy({ it.season }, { it.episode }))) {
                    this.posterUrl = posterUrl
                    this.plot = description
                    this.tags = genre
                    this.score = if(imdbRating.isNotEmpty()) Score.from10(imdbRating) else null
                    this.year = year.toIntOrNull()
                    this.backgroundPosterUrl = background
                    addActors(cast)
                    if(imdbUrl.isNotEmpty()) addImdbUrl(imdbUrl)
                }
            } else null
        }
        else {
            val data = mutableListOf<EpisodeLink>()
            
            // پہلے maxbutton-download-links سے
            document.select("a.maxbutton-download-links").mapNotNull {
                var link = it.attr("href")
                if(link.contains("url=")) {
                    val base64Value = link.substringAfter("url=")
                    link = base64Decode(base64Value)
                }

                try {
                    val doc = app.get(link, interceptor = cfKiller).document
                    val source = doc.select("a.maxbutton-1, a.maxbutton-5").attr("href")
                    if(source.isNotEmpty()) EpisodeLink(source) else null
                } catch (e: Exception) {
                    null
                }
            }.forEach { data.add(it) }

            // اگر کوئی نہیں ملے تو episodes page سے links لیں
            if(data.isEmpty()) {
                document.select("a.maxbutton").forEach { button ->
                    val buttonHref = button.attr("href")
                    if(buttonHref.isNotBlank() && buttonHref.contains("episodes.modpro.blog")) {
                        try {
                            val episodeDoc = app.get(buttonHref, interceptor = cfKiller).document
                            // episodes page سے تمام download links نکالیں
                            episodeDoc.select("a[href*=unblockedgames], a[href*=driveseed], a[href*=driveleech], a[href*=gdflix], a[href*=vcloud], a[href*=hubcloud], a[href*=drivefire], a[href*=fastdrive], a[href*=drivehub]").forEach {
                                val href = it.attr("href")
                                if(href.isNotBlank()) {
                                    data.add(EpisodeLink(href))
                                }
                            }
                        } catch (e: Exception) {
                            Log.d("Error:", "Failed to load episode links: ${e.message}")
                        }
                    }
                }
            }

            // اگر abھی بھی نہیں ملے تو direct links
            if(data.isEmpty()) {
                document.select("a[href*=unblockedgames], a[href*=driveseed], a[href*=driveleech], a[href*=gdflix], a[href*=vcloud], a[href*=hubcloud], a[href*=drivefire], a[href*=fastdrive], a[href*=drivehub]").forEach {
                    val href = it.attr("href")
                    if(href.isNotBlank()) {
                        data.add(EpisodeLink(href))
                    }
                }
            }

            return if(data.isNotEmpty()) {
                newMovieLoadResponse(title, url, TvType.Movie, data.distinctBy { it.source }) {
                    this.posterUrl = posterUrl
                    this.plot = description
                    this.tags = genre
                    this.score = if(imdbRating.isNotEmpty()) Score.from10(imdbRating) else null
                    this.year = year.toIntOrNull()
                    this.backgroundPosterUrl = background
                    addActors(cast)
                    if(imdbUrl.isNotEmpty()) addImdbUrl(imdbUrl)
                }
            } else null
        }
    }

    override suspend fun loadLinks(
        data: String,
        isCasting: Boolean,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        val sources = parseJson<ArrayList<EpisodeLink>>(data)
        sources.amap {
            var source = it.source
            if(source.contains("unblockedgames", ignoreCase = true)) {
                source = bypass(source) ?: source
            }

            when {
                source.contains("driveseed", ignoreCase = true) || source.contains("driveleech", ignoreCase = true) -> {
                    Driveleech().getUrl(source, "", subtitleCallback, callback)
                }
                source.contains("gdflix", ignoreCase = true) -> {
                    loadExtractor(source, "", subtitleCallback, callback)
                }
                source.contains("vcloud", ignoreCase = true) -> {
                    loadExtractor(source, "", subtitleCallback, callback)
                }
                source.contains("hubcloud", ignoreCase = true) -> {
                    loadExtractor(source, "", subtitleCallback, callback)
                }
                source.contains("drivefire", ignoreCase = true) -> {
                    loadExtractor(source, "", subtitleCallback, callback)
                }
                source.contains("fastdrive", ignoreCase = true) -> {
                    loadExtractor(source, "", subtitleCallback, callback)
                }
                source.contains("drivehub", ignoreCase = true) -> {
                    loadExtractor(source, "", subtitleCallback, callback)
                }
                else -> {
                    loadExtractor(source, "", subtitleCallback, callback)
                }
            }
        }
        return true
    }

    data class Meta(
        val id: String? = null,
        val imdb_id: String? = null,
        val type: String? = null,
        val poster: String? = null,
        val logo: String? = null,
        val background: String? = null,
        val moviedb_id: Int? = null,
        val name: String? = null,
        val description: String? = null,
        val genre: List<String>? = null,
        val releaseInfo: String? = null,
        val status: String? = null,
        val runtime: String? = null,
        val cast: List<String>? = null,
        val language: String? = null,
        val country: String? = null,
        val imdbRating: String? = null,
        val slug: String? = null,
        val year: String? = null,
        val videos: List<EpisodeDetails>? = null
    )

    data class EpisodeDetails(
        val id: String? = null,
        val name: String? = null,
        val title: String? = null,
        val season: Int? = null,
        val episode: Int? = null,
        val released: String? = null,
        val overview: String? = null,
        val thumbnail: String? = null,
        val moviedb_id: Int? = null
    )

    data class ResponseData(
        val meta: Meta
    )

    data class EpisodeLink(
        val source: String
    )
}