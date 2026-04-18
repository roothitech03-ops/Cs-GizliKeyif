package com.kraptor

import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.utils.*
import com.lagradost.cloudstream3.utils.AppUtils.parseJson
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
        TvType.OVA
    )

    override val mainPage = mainPageOf(
        "$mainUrl/page/" to "Latest Releases",
        "$mainUrl/genre/tv/page/" to "TV Series",
        "$mainUrl/genre/movie/page/" to "Anime Movies",
        "$mainUrl/genre/ova/page/" to "OVAs"
    )

    override suspend fun getMainPage(
        page: Int,
        request: MainPageRequest
    ): HomePageResponse {
        val url = request.data + page
        val document = app.get(url).document
        val home = document.select("article, .item, .post, .movies-list .ml-item")
            .mapNotNull { it.toSearchResult() }
        return newHomePageResponse(request.name, home)
    }

    private fun Element.toSearchResult(): SearchResponse? {
        val linkEl = this.selectFirst("a") ?: return null
        val href = fixUrlNull(linkEl.attr("href")) ?: return null
        val title = this.selectFirst("h2, h3, .title, .entry-title")?.text()
            ?: linkEl.attr("title").ifBlank { return null }
        val posterUrl = fixUrlNull(
            this.selectFirst("img")?.let {
                it.attr("data-src").ifBlank { it.attr("src") }
            }
        )

        return newAnimeSearchResponse(title, href, TvType.Anime) {
            this.posterUrl = posterUrl
        }
    }

    override suspend fun search(query: String): List<SearchResponse> {
        val url = "$mainUrl/?s=$query"
        val document = app.get(url).document
        return document.select("article, .item, .post")
            .mapNotNull { it.toSearchResult() }
    }

    override suspend fun load(url: String): LoadResponse? {
        val document = app.get(url).document

        val title = document.selectFirst("h1.entry-title, h1.title, h1")?.text()
            ?.trim() ?: return null
        val poster = fixUrlNull(
            document.selectFirst("meta[property=og:image]")?.attr("content")
                ?: document.selectFirst(".poster img, .thumb img")?.attr("src")
        )
        val description = document.selectFirst(
            ".entry-content p, .description, .synopsis, meta[name=description]"
        )?.let {
            if (it.tagName() == "meta") it.attr("content") else it.text()
        }

        val tags = document.select(".genres a, .genre a, a[rel=tag]").map { it.text() }

        // Try to find episodes list
        val episodes = document.select(
            ".episodes-list a, .eplister li a, .episodios li a, ul.episodes a"
        ).mapNotNull { ep ->
            val epHref = fixUrlNull(ep.attr("href")) ?: return@mapNotNull null
            val epName = ep.selectFirst(".epl-title, .episodiotitle")?.text()
                ?: ep.text().ifBlank { "Episode" }
            val epNum = Regex("""(\d+)""").find(epName)?.value?.toIntOrNull()
            Episode(
                data = epHref,
                name = epName.trim(),
                episode = epNum
            )
        }.reversed()

        return if (episodes.isNotEmpty()) {
            newAnimeLoadResponse(title, url, TvType.Anime) {
                engName = title
                posterUrl = poster
                plot = description
                this.tags = tags
                addEpisodes(DubStatus.Dubbed, episodes)
            }
        } else {
            newMovieLoadResponse(title, url, TvType.AnimeMovie, url) {
                posterUrl = poster
                plot = description
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

        // Look for iframes / embed players
        val iframes = document.select("iframe").mapNotNull { it.attr("src") }
        val sources = document.select("source").mapNotNull { it.attr("src") }

        val allLinks = (iframes + sources).filter { it.isNotBlank() }.map { fixUrl(it) }

        allLinks.forEach { link ->
            loadExtractor(link, data, subtitleCallback, callback)
        }

        // Check for server select buttons (data-url attributes)
        document.select("[data-url], .server-item, .option").forEach { srv ->
            val srvUrl = srv.attr("data-url").ifBlank { srv.attr("data-embed") }
            if (srvUrl.isNotBlank()) {
                loadExtractor(fixUrl(srvUrl), data, subtitleCallback, callback)
            }
        }

        return allLinks.isNotEmpty()
    }
}