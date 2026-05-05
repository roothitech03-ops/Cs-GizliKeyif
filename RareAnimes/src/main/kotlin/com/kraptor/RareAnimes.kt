package com.kraptor

import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.utils.*
import org.jsoup.nodes.Element
import org.jsoup.nodes.Document

/**
 * RareAnimes (Rare Toons India) CloudStream Provider
 *
 * Site structure (WordPress):
 *   - Homepage / categories show post cards (article.post)
 *   - Detail pages have synopsis + season tabs (Season 1 / Season 2 …)
 *   - Each season page links to store.animetoonhindi.com/archives/NNNN
 *     which lists episode names + codedew.com/zipper/?url=… links.
 *
 * Flow:
 *   rareanimes detail  ──► season page ──► store.animetoonhindi page
 *                                                      │
 *                                                      ▼
 *                                           codedew.com/zipper/?url=…
 *                                                      │
 *                                                      ▼
 *                                           codedew.com/multiquality/
 *                                           (CloudStream loadExtractor)
 */

class RareAnimes : MainAPI() {

    // ------------------------------------------------------------------
    // Provider config
    // ------------------------------------------------------------------
    override var mainUrl           = "https://india.rareanimes.com"
    override var name              = "RareAnimes"
    override var lang              = "hi"
    override val hasMainPage       = true
    override val hasDownloadSupport = true
    override val supportedTypes    = setOf(
        TvType.Anime,
        TvType.AnimeMovie,
        TvType.Cartoon,
        TvType.OVA,
    )

    companion object {
        const val USER_AGENT = "Mozilla/5.0 (Windows NT 10.0; Win64; x64) " +
                               "AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120.0.0.0 Safari/537.36"
        // Separator used when joining multiple source URLs per episode
        const val SEP = "|||"
    }

    private fun baseHeaders(referer: String = mainUrl) = mapOf(
        "User-Agent" to USER_AGENT,
        "Referer"    to referer,
        "Accept"     to "text/html,application/xhtml+xml,application/xml;q=0.9,*/*;q=0.8",
    )

    private suspend fun fetchDoc(url: String): Document =
        app.get(url, headers = baseHeaders()).document

    // ------------------------------------------------------------------
    // URL helpers
    // ------------------------------------------------------------------
    private fun fixUrl(raw: String): String = when {
        raw.isBlank()                -> ""
        raw.startsWith("//")         -> "https:$raw"
        raw.startsWith("http")       -> raw
        raw.startsWith("/")          -> "$mainUrl$raw"
        else                         -> "$mainUrl/$raw"
    }

    private fun Element.bestSrc(): String? {
        val raw = attr("data-lazy-src").ifBlank {
            attr("data-src").ifBlank {
                attr("data-original").ifBlank { attr("src") }
            }
        }
        return fixUrl(raw).ifBlank { null }
    }

    // ------------------------------------------------------------------
    // Main page sections
    // ------------------------------------------------------------------
    override val mainPage = mainPageOf(
        "$mainUrl/category/hindi-dubbed/"  to "Hindi Dubbed",
        "$mainUrl/category/cartoon/"       to "Cartoons",
        "$mainUrl/category/anime-movie/"   to "Movies",
        "$mainUrl/category/complete-series/" to "Complete Series",
        "$mainUrl/category/ben-10/"        to "Ben 10",
        "$mainUrl/category/naruto/"        to "Naruto",
        "$mainUrl/category/pokemon/"       to "Pokemon",
        "$mainUrl/category/disney/"        to "Disney",
        "$mainUrl/category/dragon-ball/"   to "Dragon Ball",
    )

    override suspend fun getMainPage(
        page: Int,
        request: MainPageRequest,
    ): HomePageResponse {
        val base = request.data.trimEnd('/')
        val url  = if (page == 1) "$base/" else "$base/page/$page/"
        val doc  = fetchDoc(url)

        val items = doc.select("article.post, article.type-post, div.post")
            .mapNotNull { parseCard(it) }

        val hasNext = doc.selectFirst(
            "a.next, a[rel=next], .pagination a[href*='/page/${page + 1}/']"
        ) != null

        return newHomePageResponse(request.name, items, hasNext)
    }

    // ------------------------------------------------------------------
    // Search
    // ------------------------------------------------------------------
    override suspend fun search(query: String): List<SearchResponse> {
        val doc = fetchDoc("$mainUrl/?s=${query.trim().replace(" ", "+")}")
        return doc.select("article.post, article.type-post, div.post, .search-result article")
            .mapNotNull { parseCard(it) }
    }

    // ------------------------------------------------------------------
    // Parse a card → SearchResponse
    // ------------------------------------------------------------------
    private fun parseCard(el: Element): SearchResponse? {
        val titleEl = el.selectFirst(
            "h2.entry-title a, h2.post-title a, .entry-title a, a[rel='bookmark']"
        ) ?: return null

        val title = titleEl.text().trim().ifBlank { return null }
        val href  = titleEl.attr("href").trim().ifBlank { return null }

        val poster = el.selectFirst(
            "img.wp-post-image, .post-thumbnail img, figure img, a > img"
        )?.bestSrc()

        return newAnimeSearchResponse(title, fixUrl(href), TvType.Anime) {
            this.posterUrl = poster
            addDubStatus(dubbed = true)
        }
    }

    // ------------------------------------------------------------------
    // Detail page loader
    // ------------------------------------------------------------------
    override suspend fun load(url: String): LoadResponse? {
        val doc    = fetchDoc(url)

        val title = doc.selectFirst("h1.entry-title, h1.post-title, .entry-title")
            ?.text()?.trim()
            ?: doc.title().substringBefore(" - ").substringBefore(" | ").trim()
                .ifBlank { return null }

        val poster = doc.selectFirst("meta[property='og:image']")?.attr("content")
            ?.trim()?.ifBlank { null }
            ?: doc.selectFirst(
                "img.wp-post-image, .post-thumbnail img, .featured-image img"
            )?.bestSrc()

        val plot = doc.selectFirst("meta[name='description'], meta[property='og:description']")
            ?.attr("content")?.trim()?.ifBlank { null }
            ?: doc.selectFirst(".entry-content > p, .post-content > p")
                ?.text()?.trim()?.ifBlank { null }

        val tags = doc.select("a[href*='/category/']")
            .map { it.text().trim() }
            .filter { it.length > 1 && !it.equals("Uncategorized", ignoreCase = true) }
            .distinct()

        val type = when {
            title.contains("movie", ignoreCase = true) -> TvType.AnimeMovie
            title.contains("ova", ignoreCase = true)   -> TvType.OVA
            title.contains("cartoon", ignoreCase = true) -> TvType.Cartoon
            tags.any { it.contains("movie", ignoreCase = true) } -> TvType.AnimeMovie
            tags.any { it.contains("cartoon", ignoreCase = true) } -> TvType.Cartoon
            else -> TvType.Anime
        }

        // ---- Try to extract season links first ----
        val content = doc.selectFirst(".entry-content, .post-content") ?: doc.body()
        val seasonLinks = extractSeasonLinks(content, url)

        if (seasonLinks.isNotEmpty()) {
            // Multi-season series
            val episodes = mutableListOf<Episode>()
            seasonLinks.forEachIndexed { index, seasonUrl ->
                val seasonNum = index + 1
                try {
                    val seasonEpisodes = loadSeasonEpisodes(seasonUrl, seasonNum, poster)
                    episodes.addAll(seasonEpisodes)
                } catch (_: Exception) { /* ignore broken season pages */ }
            }

            if (episodes.isNotEmpty()) {
                return newTvSeriesLoadResponse(title, url, type, episodes) {
                    this.posterUrl = poster
                    this.plot      = plot
                    this.tags      = tags
                }
            }
        }

        // ---- Case B: Direct episode links on the same page ----
        val epBlocks = parseInlineEpisodeBlocks(content)
        if (epBlocks.isNotEmpty()) {
            val episodes = epBlocks.map { (epNum, epLabel, links) ->
                newEpisode(links.joinToString(SEP)) {
                    name      = epLabel
                    episode   = epNum
                    season    = 1
                    posterUrl = poster
                }
            }
            return newTvSeriesLoadResponse(title, url, type, episodes) {
                this.posterUrl = poster
                this.plot      = plot
                this.tags      = tags
            }
        }

        // ---- Case C: store.animetoonhindi.com link directly on page ----
        val storeLink = content.selectFirst("a[href*='animetoonhindi.com/archives/']")
            ?.attr("href")?.trim()?.ifBlank { null }
        if (storeLink != null) {
            try {
                val storeEpisodes = loadStoreEpisodes(storeLink, 1, poster)
                if (storeEpisodes.isNotEmpty()) {
                    return newTvSeriesLoadResponse(title, url, type, storeEpisodes) {
                        this.posterUrl = poster
                        this.plot      = plot
                        this.tags      = tags
                    }
                }
            } catch (_: Exception) { }
        }

        // ---- Fallback: treat as single movie / episode ----
        val allLinks = collectStreamableLinks(content)
        val movieData = allLinks.joinToString(SEP).ifBlank { url }
        return newMovieLoadResponse(title, url, type, movieData) {
            this.posterUrl = poster
            this.plot      = plot
            this.tags      = tags
        }
    }

    // ------------------------------------------------------------------
    // Extract season links from detail page
    // ------------------------------------------------------------------
    private fun extractSeasonLinks(content: Element, pageUrl: String): List<String> {
        val results = mutableListOf<String>()

        // Pattern 1: Links whose text matches "Season N" or just "S N"
        content.select("a[href]").forEach { a ->
            val text = a.text().trim()
            val href = a.attr("href").trim()
            if (href.isBlank() || href == "#") return@forEach

            val isSeasonLink = Regex("""^(Season\s*\d+|S\d+|Part\s*\d+)$""", RegexOption.IGNORE_CASE)
                .matches(text)
            if (isSeasonLink && !results.contains(href)) {
                results.add(fixUrl(href))
            }
        }

        // Pattern 2: Look for headings containing "Season N" and collect
        // the following links until next heading
        if (results.isEmpty()) {
            val seasonHeadingRe = Regex("""(?i)season\s*(\d+)""")
            val headings = content.select("h2, h3, h4, p, div, strong")
            for (h in headings) {
                val match = seasonHeadingRe.find(h.text())
                if (match != null) {
                    // Try to find a link in this element or its next siblings
                    val link = h.selectFirst("a[href]")
                        ?: h.nextElementSibling()?.selectFirst("a[href]")
                    if (link != null) {
                        val href = link.attr("href").trim()
                        if (href.isNotBlank() && href != "#" && !results.contains(href)) {
                            results.add(fixUrl(href))
                        }
                    }
                }
            }
        }

        return results.distinct()
    }

    // ------------------------------------------------------------------
    // Load a season page → extract episodes via store.animetoonhindi
    // ------------------------------------------------------------------
    private suspend fun loadSeasonEpisodes(
        seasonUrl: String,
        seasonNum: Int,
        poster: String?,
    ): List<Episode> {
        val doc = fetchDoc(seasonUrl)
        val content = doc.selectFirst(".entry-content, .post-content") ?: doc.body()

        // Look for store.animetoonhindi.com archives link
        val storeLink = content.selectFirst("a[href*='animetoonhindi.com/archives/']")
            ?.attr("href")?.trim()?.ifBlank { null }
            ?: content.selectFirst("a[href*='watchmultiquality' i], a[href*='mega' i]")
                ?.attr("href")?.trim()?.ifBlank { null }

        if (storeLink != null) {
            return loadStoreEpisodes(storeLink, seasonNum, poster)
        }

        // Fallback: try inline blocks on the season page itself
        val blocks = parseInlineEpisodeBlocks(content)
        return blocks.map { (epNum, epLabel, links) ->
            newEpisode(links.joinToString(SEP)) {
                name      = epLabel
                episode   = epNum
                season    = seasonNum
                posterUrl = poster
            }
        }
    }

    // ------------------------------------------------------------------
    // Load store.animetoonhindi.com/archives/NNNN page → episodes
    // ------------------------------------------------------------------
    private suspend fun loadStoreEpisodes(
        storeUrl: String,
        seasonNum: Int,
        poster: String?,
    ): List<Episode> {
        val doc = fetchDoc(fixUrl(storeUrl))
        val links = doc.select("a[href*='codedew.com/zipper']")

        if (links.isEmpty()) return emptyList()

        return links.mapIndexed { index, a ->
            val text = a.text().trim()
            val epNum = extractEpisodeNumber(text).takeIf { it > 0 }
                ?: (index + 1)
            val name  = text.ifBlank { "Episode $epNum" }
            val href  = fixUrl(a.attr("href").trim())

            newEpisode(href) {
                this.name      = name
                this.episode   = epNum
                this.season    = seasonNum
                this.posterUrl = poster
            }
        }
    }

    // ------------------------------------------------------------------
    // Parse inline episode blocks (direct links on page)
    // ------------------------------------------------------------------
    private fun parseInlineEpisodeBlocks(
        content: Element,
    ): List<Triple<Int, String, List<String>>> {
        val blocks       = mutableListOf<Triple<Int, String, MutableList<String>>>()
        var currentNum   = 0
        var currentLabel = ""
        var currentLinks = mutableListOf<String>()

        val epRe = Regex("""(?i)\bepisode[.\s\-_:]*(\d+)|\bep[.\s\-_:]*(\d+)""")

        for (child in content.children()) {
            val text    = child.text().trim()
            val tagName = child.tagName().lowercase()
            val match   = epRe.find(text)

            // Episode heading detected
            if (match != null &&
                tagName in setOf("h1", "h2", "h3", "h4", "h5", "p", "div", "strong", "b") &&
                text.length < 120
            ) {
                if (currentLinks.isNotEmpty()) {
                    blocks.add(Triple(currentNum, currentLabel, currentLinks))
                }
                val num = (match.groupValues[1].ifBlank { match.groupValues[2] })
                    .toIntOrNull() ?: (blocks.size + 1)
                currentNum   = num
                currentLabel = "Episode $num"
                currentLinks = mutableListOf()
                continue
            }

            val found = collectStreamableLinks(child)
            if (found.isNotEmpty()) {
                if (currentNum == 0) {
                    currentNum = 1
                    currentLabel = "Episode 1"
                }
                currentLinks.addAll(found)
            }
        }

        if (currentLinks.isNotEmpty()) {
            blocks.add(Triple(currentNum, currentLabel, currentLinks))
        }

        return blocks.map { Triple(it.first, it.second, it.third.distinct()) }
    }

    // ------------------------------------------------------------------
    // Collect streamable / source links from an element
    // ------------------------------------------------------------------
    private fun collectStreamableLinks(el: Element): List<String> {
        val results = mutableListOf<String>()

        // Iframes
        el.select("iframe[src], iframe[data-src]").forEach { iframe ->
            val src = iframe.attr("src").ifBlank { iframe.attr("data-src") }.trim()
            if (src.isNotBlank()) results.add(src)
        }

        // Known video host or codedew links
        el.select("a[href]").forEach { a ->
            val href = a.attr("href").trim()
            if (href.isBlank() || href == "#" || href.startsWith("mailto:")) return@forEach
            if (isVideoHost(href) || isCodedewLink(href) || isRedirectLink(href)) {
                results.add(href)
            }
        }

        return results.distinct()
    }

    // ------------------------------------------------------------------
    // Video host detection
    // ------------------------------------------------------------------
    private val videoHosts = listOf(
        "dood", "ds2play", "dooood", "d000d", "doodstream",
        "streamtape",
        "filemoon", "fmoonembed",
        "mixdrop",
        "stream.sb", "sbplay", "embedrise",
        "ok.ru",
        "mp4upload",
        "rumble",
        "fembed",
        "upstream",
        "kwik",
        "vidcloud", "vidstream",
        "rapidvideo",
        "streamhub",
        "voe.sx",
        "vidhide", "vidhidepre", "vidhidevip",
        "streamwish", "dwish",
        "filelions", "lion",
    )

    private fun isVideoHost(url: String): Boolean =
        url.startsWith("http") && videoHosts.any { url.contains(it, ignoreCase = true) }

    private fun isCodedewLink(url: String): Boolean =
        url.contains("codedew.com", ignoreCase = true)

    private fun isRedirectLink(url: String): Boolean {
        if (url.isBlank() || url == "#") return false
        return url.contains("/redirect/") || url.contains("redirect/main.php")
    }

    // ------------------------------------------------------------------
    // Episode number extraction
    // ------------------------------------------------------------------
    private fun extractEpisodeNumber(text: String): Int {
        val patterns = listOf(
            Regex("""(?i)(?:episode|ep)[.\s\-_:]*(\d+)"""),
            Regex("""(?i)S\d{1,2}\s*E(\d+)"""),
            Regex("""(?i)[_\-](\d+)(?:[_\-]|\s*$)"""),
            Regex("""\b(\d{1,3})\s*(?:st|nd|rd|th)?\s*(?:episode)?\b""", RegexOption.IGNORE_CASE),
        )
        for (p in patterns) {
            p.find(text)?.groupValues?.get(1)?.toIntOrNull()?.let { return it }
        }
        return 0
    }

    // ------------------------------------------------------------------
    // loadLinks – resolve episode data → playable links
    // ------------------------------------------------------------------
    override suspend fun loadLinks(
        data: String,
        isCasting: Boolean,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit,
    ): Boolean {

        // 1. Multiple URLs joined with SEP
        if (data.contains(SEP)) {
            data.split(SEP).filter { it.isNotBlank() }.forEach { raw ->
                processOneLink(raw, subtitleCallback, callback)
            }
            return true
        }

        // 2. Direct codedew link → pass to extractor
        if (isCodedewLink(data)) {
            try {
                // codedew.com/zipper/?url=… loads an intermediate page that
                // eventually lands on codedew.com/multiquality/ where the
                // player lives. We follow it and let CloudStream try to
                // resolve the final video source.
                loadExtractor(data, mainUrl, subtitleCallback, callback)
            } catch (_: Exception) { }
            return true
        }

        // 3. Direct video host
        if (isVideoHost(data)) {
            try { loadExtractor(data, mainUrl, subtitleCallback, callback) }
            catch (_: Exception) { }
            return true
        }

        // 4. store.animetoonhindi.com page → grab codedew links
        if (data.contains("animetoonhindi.com")) {
            try {
                val doc = fetchDoc(data)
                val codedewLinks = doc.select("a[href*='codedew.com/zipper']")
                    .map { fixUrl(it.attr("href").trim()) }
                    .filter { it.isNotBlank() }
                    .distinct()

                codedewLinks.forEach { link ->
                    try {
                        loadExtractor(link, mainUrl, subtitleCallback, callback)
                    } catch (_: Exception) { }
                }

                // Also collect any direct video hosts on the store page
                val directLinks = doc.select("a[href]").map { it.attr("href").trim() }
                    .filter { isVideoHost(it) }
                    .distinct()

                directLinks.forEach { link ->
                    try {
                        loadExtractor(link, mainUrl, subtitleCallback, callback)
                    } catch (_: Exception) { }
                }
            } catch (_: Exception) { }
            return true
        }

        // 5. Generic HTTP page – scrape everything
        if (data.startsWith("http")) {
            try {
                val doc = fetchDoc(data)
                val content = doc.selectFirst(".entry-content, .post-content, #content, body")
                    ?: return true

                val links = collectStreamableLinks(content)
                links.forEach { raw ->
                    processOneLink(raw, subtitleCallback, callback)
                }
            } catch (_: Exception) { }
        }

        return true
    }

    // ------------------------------------------------------------------
    // Process a single raw URL
    // ------------------------------------------------------------------
    private suspend fun processOneLink(
        rawUrl: String,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit,
    ) {
        try {
            val target = when {
                isVideoHost(rawUrl) || isCodedewLink(rawUrl) -> rawUrl
                isRedirectLink(rawUrl)                       -> resolveRedirect(rawUrl)
                else                                         -> rawUrl
            } ?: return

            loadExtractor(target, mainUrl, subtitleCallback, callback)
        } catch (_: Exception) { }
    }

    // ------------------------------------------------------------------
    // Follow a local redirect to its final destination
    // ------------------------------------------------------------------
    private suspend fun resolveRedirect(linkUrl: String): String? {
        return try {
            val resp = app.get(linkUrl, headers = baseHeaders(linkUrl))
            val finalUrl = resp.url
            if (isVideoHost(finalUrl) || isCodedewLink(finalUrl)) finalUrl else null
        } catch (_: Exception) { null }
    }
}
