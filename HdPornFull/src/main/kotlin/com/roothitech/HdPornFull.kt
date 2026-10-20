package com.roothitech

import org.jsoup.nodes.Element
import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.utils.*

class HdPornFull : MainAPI() {
    override var mainUrl = "https://www.hdpornfull.com"
    override var name = "HdPornFull"
    override val hasMainPage = true
    override var lang = "en"
    override val supportedTypes = setOf(TvType.NSFW)

    // The site sits behind Cloudflare and rejects non-browser clients,
    // so every request sends browser-like headers (same approach as XTapes).
    private val browserHeaders = mapOf(
        "User-Agent" to "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/126.0.0.0 Safari/537.36",
        "Accept" to "text/html,application/xhtml+xml,application/xml;q=0.9,*/*;q=0.8",
        "Accept-Language" to "en-US,en;q=0.9"
    )

    // Verified on the site: cat=110 is the "Latest Porn Movies" listing.
    override val mainPage = mainPageOf(
        "$mainUrl/?filter=latest" to "Latest Porn Videos",
        "$mainUrl/?filter=latest&cat=110" to "Latest Porn Movies"
    )

    override suspend fun getMainPage(page: Int, request: MainPageRequest): HomePageResponse {
        // Verified pattern: https://www.hdpornfull.com/page/2/?filter=latest
        val url = if (page <= 1) request.data else {
            val q = request.data.indexOf('?')
            val path = if (q >= 0) request.data.substring(0, q) else request.data
            val query = if (q >= 0) request.data.substring(q) else ""
            "${path.trimEnd('/')}/page/$page/$query"
        }
        val document = app.get(url, headers = browserHeaders).document
        val home = document.select("main article").mapNotNull { it.toSearchResult() }
        return newHomePageResponse(request.name, home, hasNext = home.isNotEmpty())
    }

    // Listing cards are "main article" elements whose single anchor wraps
    // the thumbnail and metadata; the title is the thumbnail's alt text.
    private fun Element.toSearchResult(): SearchResponse? {
        val anchor = selectFirst("a[href]") ?: return null
        val href = fixUrlNull(anchor.attr("abs:href").ifBlank { anchor.attr("href") }) ?: return null
        val img = selectFirst("img")
        val title = img?.attr("alt")?.takeIf { it.isNotBlank() }
            ?: anchor.attr("title").takeIf { it.isNotBlank() }
            ?: return null
        val poster = fixUrlNull(
            img?.attr("data-src")?.takeIf { it.isNotBlank() } ?: img?.attr("src")
        )
        return newMovieSearchResponse(title.trim(), href, TvType.NSFW) {
            this.posterUrl = poster
        }
    }

    override suspend fun search(query: String, page: Int): SearchResponseList {
        // Verified format: https://www.hdpornfull.com/?s={query}
        val q = query.trim().replace(" ", "+")
        val url = if (page <= 1) "$mainUrl/?s=$q" else "$mainUrl/page/$page/?s=$q"
        val document = app.get(url, headers = browserHeaders).document
        val results = document.select("main article").mapNotNull { it.toSearchResult() }
        return newSearchResponseList(results, hasNext = results.isNotEmpty())
    }

    override suspend fun load(url: String): LoadResponse? {
        val document = app.get(url, headers = browserHeaders).document
        val title = document.selectFirst("main article h1")?.text()?.trim()
            ?: document.selectFirst("meta[property=og:title]")?.attr("content")?.trim()
            ?: return null
        return newMovieLoadResponse(title, url, TvType.NSFW, url) {
            posterUrl = fixUrlNull(document.selectFirst("meta[property=og:image]")?.attr("content"))
            // The page has no prose description; its h4 block carries the
            // release info (file name, size, duration, codec).
            plot = document.selectFirst("main article h4")?.text()?.trim()
        }
    }

    override suspend fun loadLinks(
        data: String,
        isCasting: Boolean,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        val document = app.get(data, headers = browserHeaders).document
        val links = LinkedHashSet<String>()

        // In-page player: an iframe inside the article (StreamTape player).
        document.select("main article iframe[src]").forEach { iframe ->
            val src = iframe.attr("abs:src").ifBlank { iframe.attr("src") }
            if (src.isNotBlank()) links.add(src)
        }

        // Stream links listed on the page. shortcut: RapidGator links are
        // skipped on purpose, it is a download-only host with no extractor.
        val hostHints = listOf("streamtape", "lulust", "smoothpre", "earnvids")
        document.select("a[href]").forEach { anchor ->
            val href = anchor.attr("abs:href").ifBlank { anchor.attr("href") }
            if (hostHints.any { href.contains(it) }) links.add(href)
        }

        var found = false
        links.forEach { link ->
            if (loadExtractor(link, "$mainUrl/", subtitleCallback, callback)) found = true
        }
        return found
    }
}
