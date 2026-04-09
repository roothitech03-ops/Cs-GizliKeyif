// WatchAnimeWorldProvider.kt
package com.kraptor

import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.utils.*
import com.lagradost.cloudstream3.utils.AppUtils.parseJson
import com.lagradost.cloudstream3.utils.AppUtils.toJson
import org.jsoup.nodes.Document
import org.jsoup.nodes.Element

class WatchAnimeWorldProvider : MainAPI() {

    override var mainUrl = "https://watchanimeworld.net"
    override var name = "WatchAnimeWorld"
    override val hasMainPage = true
    override var lang = "hi"
    override val hasDownloadSupport = true
    override val supportedTypes = setOf(
        TvType.Anime,
        TvType.AnimeMovie,
        TvType.OVA,
    )

    // ========================
    // Companion Object
    // ========================
    companion object {
        fun getType(t: String?): TvType {
            return when {
                t?.contains("Movie", true) == true -> TvType.AnimeMovie
                t?.contains("OVA", true) == true -> TvType.OVA
                else -> TvType.Anime
            }
        }

        fun getStatus(t: String?): ShowStatus {
            return when (t?.trim()) {
                "Completed" -> ShowStatus.Completed
                "Ongoing" -> ShowStatus.Ongoing
                else -> ShowStatus.Completed
            }
        }
    }

    // ========================
    // Main Page Categories
    // ========================
    override val mainPage = mainPageOf(
        "$mainUrl/anime/?page=" to "Latest Anime",
        "$mainUrl/anime/?type=Hindi+Dubbed&page=" to "Hindi Dubbed",
        "$mainUrl/anime/?status=ongoing&page=" to "Ongoing Anime",
        "$mainUrl/anime/?type=Movie&page=" to "Anime Movies",
        "$mainUrl/anime/?type=OVA&page=" to "OVA",
        "$mainUrl/anime/?sub=sub&page=" to "Subbed Anime",
    )

    // ========================
    // Get Main Page
    // ========================
    override suspend fun getMainPage(
        page: Int,
        request: MainPageRequest
    ): HomePageResponse {
        val url = request.data + page
        val document = app.get(url).document

        val home = document.select("div.film_list-wrap div.flw-item, div.anime_list-wrap li").mapNotNull {
            it.toSearchResult()
        }

        return newHomePageResponse(request.name, home)
    }

    // ========================
    // Element to SearchResult
    // ========================
    private fun Element.toSearchResult(): AnimeSearchResponse? {
        // Site structure ke hisab se selectors adjust karein
        val title = this.selectFirst("h3.film-name a, h2.anime_name a")?.text()?.trim()
            ?: return null
        val href = fixUrl(
            this.selectFirst("h3.film-name a, h2.anime_name a")?.attr("href") ?: return null
        )
        val posterUrl = this.selectFirst("img")?.let {
            it.attr("data-src").ifEmpty { it.attr("src") }
        }
        val type = this.selectFirst("div.fd-infor span.fdi-type")?.text()

        return newAnimeSearchResponse(title, href, getType(type)) {
            this.posterUrl = posterUrl
        }
    }

    // ========================
    // Search
    // ========================
    override suspend fun search(query: String): List<SearchResponse> {
        val url = "$mainUrl/?s=$query"
        val document = app.get(url).document

        return document.select("div.film_list-wrap div.flw-item, ul.anime_list li, div.anime-search-item")
            .mapNotNull { it.toSearchResult() }
    }

    // ========================
    // Load (Detail Page)
    // ========================
    override suspend fun load(url: String): LoadResponse? {
        val document = app.get(url).document

        // Basic Info
        val title = document.selectFirst(
            "h2.film-name, h1.anime_info h2, div.anis-content h2"
        )?.text()?.trim() ?: return null

        val poster = document.selectFirst(
            "div.film-poster img, div.anime-image img"
        )?.let {
            it.attr("data-src").ifEmpty { it.attr("src") }
        }

        val description = document.selectFirst(
            "div.film-description div.text, div.anime-description, p.overview"
        )?.text()?.trim()

        // Tags/Genres
        val tags = document.select(
            "div.film-info div.item-list a[href*=genre], a.genre-tag"
        ).map { it.text().trim() }

        // Details
        val detailInfo = document.select("div.film-stats span.item, div.anime-detail li")

        var type: String? = null
        var status: String? = null
        var episodes: Int? = null

        detailInfo.forEach { item ->
            val text = item.text()
            when {
                text.contains("Type:", true) ->
                    type = text.replace("Type:", "").trim()
                text.contains("Status:", true) ->
                    status = text.replace("Status:", "").trim()
                text.contains("Episodes:", true) ->
                    episodes = text.replace("Episodes:", "").trim()
                        .toIntOrNull()
            }
        }

        // Episode List
        val episodeList = loadEpisodes(document, url)

        return newAnimeLoadResponse(title, url, getType(type)) {
            this.posterUrl = poster
            this.plot = description
            this.tags = tags
            this.showStatus = getStatus(status)
            addEpisodes(DubStatus.Dubbed, episodeList)
        }
    }

    // ========================
    // Load Episodes
    // ========================
    private suspend fun loadEpisodes(
        document: Document,
        animeUrl: String
    ): List<Episode> {
        val episodes = mutableListOf<Episode>()

        // Method 1: Direct episode links on page
        val directEpisodes = document.select(
            "div.ss-list a[href], ul.episodelist li a, div.episode-list a"
        )

        if (directEpisodes.isNotEmpty()) {
            directEpisodes.forEachIndexed { index, el ->
                val epUrl = fixUrl(el.attr("href"))
                val epTitle = el.text().trim()
                val epNum = el.attr("data-number").toIntOrNull()
                    ?: epTitle.filter { it.isDigit() }.toIntOrNull()
                    ?: (index + 1)

                episodes.add(
                    newEpisode(epUrl) {
                        this.episode = epNum
                        this.name = epTitle.ifEmpty { "Episode $epNum" }
                    }
                )
            }
        } else {
            // Method 2: AJAX se episodes load karna
            val animeId = document.selectFirst("[data-id], [id*=watch]")
                ?.attr("data-id")
                ?: animeUrl.split("/").lastOrNull { it.isNotEmpty() }

            animeId?.let { id ->
                tryAjaxEpisodes(id)?.let { ajaxEps ->
                    episodes.addAll(ajaxEps)
                }
            }
        }

        return episodes.sortedBy { it.episode }
    }

    // ========================
    // AJAX Episode Loader
    // ========================
    private suspend fun tryAjaxEpisodes(animeId: String): List<Episode>? {
        return try {
            val ajaxUrl = "$mainUrl/ajax/v2/episode/list/$animeId"
            val response = app.get(
                ajaxUrl,
                headers = mapOf(
                    "X-Requested-With" to "XMLHttpRequest",
                    "Referer" to mainUrl
                )
            )

            val json = response.parsedSafe<EpisodeResponse>()
            val html = json?.html ?: return null

            val doc = android.util.Xml.newPullParser().let {
                org.jsoup.Jsoup.parse(html)
            }

            doc.select("a.ep-item, a[href*=episode]").mapIndexed { index, el ->
                val epUrl = fixUrl(el.attr("href"))
                val epNum = el.attr("data-number").toIntOrNull()
                    ?: el.text().trim().toIntOrNull()
                    ?: (index + 1)
                val epTitle = el.attr("title").ifEmpty { "Episode $epNum" }

                newEpisode(epUrl) {
                    this.episode = epNum
                    this.name = epTitle
                }
            }
        } catch (e: Exception) {
            null
        }
    }

    // ========================
    // Load Links (Video Sources)
    // ========================
    override suspend fun loadLinks(
        data: String,
        isCasting: Boolean,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        val document = app.get(data).document

        // Method 1: Embedded iframes
        val iframes = document.select("iframe[src], iframe[data-src]")
        iframes.forEach { iframe ->
            val iframeSrc = iframe.attr("src").ifEmpty { iframe.attr("data-src") }
            if (iframeSrc.isNotEmpty()) {
                loadExtractor(fixUrl(iframeSrc), data, subtitleCallback, callback)
            }
        }

        // Method 2: Direct video sources
        document.select("source[src]").forEach { source ->
            val src = source.attr("src")
            val quality = when {
                src.contains("1080") -> Qualities.P1080.value
                src.contains("720") -> Qualities.P720.value
                src.contains("480") -> Qualities.P480.value
                src.contains("360") -> Qualities.P360.value
                else -> Qualities.Unknown.value
            }
            callback(
                newExtractorLink(
                    source = this.name,
                    name = this.name,
                    url = src,
                    type = ExtractorLinkType.M3U8
                ) {
                    this.quality = quality
                }
            )
        }

        // Method 3: AJAX video servers
        tryLoadAjaxServers(data, document, subtitleCallback, callback)

        return true
    }

    // ========================
    // AJAX Video Servers
    // ========================
    private suspend fun tryLoadAjaxServers(
        episodeUrl: String,
        document: Document,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ) {
        try {
            val episodeId = document.selectFirst("[data-id], [id*=ep]")
                ?.attr("data-id")
                ?: return

            val serversUrl = "$mainUrl/ajax/v2/episode/servers?episodeId=$episodeId"
            val serversDoc = app.get(
                serversUrl,
                headers = mapOf(
                    "X-Requested-With" to "XMLHttpRequest",
                    "Referer" to episodeUrl
                )
            ).document

            serversDoc.select("div.server-item, li.server-item").forEach { server ->
                val serverId = server.attr("data-id")
                val serverName = server.text().trim()

                if (serverId.isNotEmpty()) {
                    val sourceUrl = "$mainUrl/ajax/v2/episode/sources?id=$serverId"
                    val sourceResp = app.get(
                        sourceUrl,
                        headers = mapOf(
                            "X-Requested-With" to "XMLHttpRequest",
                            "Referer" to episodeUrl
                        )
                    ).parsedSafe<SourceResponse>()

                    sourceResp?.link?.let { link ->
                        loadExtractor(link, episodeUrl, subtitleCallback, callback)
                    }
                }
            }
        } catch (e: Exception) {
            // Silently fail
        }
    }

    // ========================
    // Data Classes
    // ========================
    data class EpisodeResponse(
        val html: String? = null,
        val totalItems: Int? = null
    )

    data class SourceResponse(
        val link: String? = null,
        val type: String? = null,
        val server: Int? = null
    )
}
