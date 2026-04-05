package com.kraptor

import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.utils.*
import com.lagradost.cloudstream3.utils.ExtractorLink
import kotlinx.coroutines.delay
import org.jsoup.nodes.Element

// ─────────────────────────────────────────────────────────────────────────────
// ToonWorld4All CloudStream Provider
// Site : https://toonworld4all.me
// Type : WordPress (Gutenberg) + Cloudflare
// Lang : Hindi / Tamil / Telugu / English / Japanese (Multi Audio)
//
// URL Patterns
//   Category listing : /category/{slug}/page/{N}/
//   Series/Season    : /{series-slug}/            (full season as one WP post)
//   Episode page     : /episode/{show-NxEP}/      (individual download page)
//
// Video chain
//   wp-block button href → /redirect/main.php?url=<encoded>
//   → HTTP 302 → adrinolinks.in/{code}
//   → AdLinkFly API bypass → DoodStream / StreamTape / etc.
//   → CloudStream loadExtractor
// ─────────────────────────────────────────────────────────────────────────────

class ToonWorld4AllProvider : MainAPI() {

    override var mainUrl              = "https://toonworld4all.me"
    override var name                 = "ToonWorld4All"
    override var lang                 = "hi"
    override val hasMainPage          = true
    override val supportedTypes       = setOf(
        TvType.Anime,
        TvType.AnimeMovie,
        TvType.Cartoon,
    )

    private val ua = "Mozilla/5.0 (Windows NT 10.0; Win64; x64) " +
                     "AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120.0.0.0 Safari/537.36"

    private fun baseHeaders() = mapOf(
        "User-Agent" to ua,
        "Referer"    to mainUrl,
        "Accept"     to "text/html,application/xhtml+xml,application/xml;q=0.9,*/*;q=0.8",
    )

    private suspend fun fetchDoc(url: String) =
        app.get(url, headers = baseHeaders()).document

    // ─── Link separator (used to bundle multi-link episode data) ───────────
    private val SEP = "|||"

    // ─── Main Page Categories ─────────────────────────────────────────────
    // All real slugs from toonworld4all.me

    override val mainPage = mainPageOf(
        "$mainUrl/category/anime/"             to "Anime",
        "$mainUrl/category/cartoon/"           to "Cartoon",
        "$mainUrl/category/completed/"         to "Completed",
        "$mainUrl/category/anime-times/"       to "Anime Times",
        "$mainUrl/category/jio-cinema/"        to "JioCinema",
        "$mainUrl/category/amazon-prime-video/" to "Amazon Prime",
        "$mainUrl/category/crunchyroll/"       to "Crunchyroll",
        "$mainUrl/category/disney-plus/"       to "Disney+",
        "$mainUrl/category/apple-tv/"          to "Apple TV+",
        "$mainUrl/category/muse-india/"        to "Muse India",
    )

    // ─── Pagination ───────────────────────────────────────────────────────
    // Site uses /category/{slug}/page/{N}/ (NOT ?page=N)

    override suspend fun getMainPage(page: Int, request: MainPageRequest): HomePageResponse {
        val base = request.data.trimEnd('/')
        val url  = "$base/page/$page/"
        val doc  = fetchDoc(url)

        val items = doc.select("article.post, article.type-post").mapNotNull { parseCard(it) }

        // Detect next page
        val hasNext = doc.selectFirst(
            "a.next, a[rel=next], .nav-previous a, .pagination .next, a[href*='/page/${page + 1}/']"
        ) != null

        return newHomePageResponse(request.name, items, hasNext)
    }

    // ─── Article card parser (category page) ──────────────────────────────

    private fun parseCard(el: Element): SearchResponse? {
        val titleEl = el.selectFirst("h2.entry-title a, .entry-title a") ?: return null
        val title   = titleEl.text().trim().ifBlank { return null }
        val href    = titleEl.attr("href").trim().ifBlank { return null }

        // WordPress featured-image thumbnail selectors (try best first)
        val img = el.selectFirst(
            "img.wp-post-image, .post-thumbnail img, .wp-block-post-featured-image img, " +
            "figure img, a > img, img[src]"
        )
        val rawThumb = img?.let {
            it.attr("data-src").ifBlank { it.attr("src") }
        } ?: ""
        val thumb = fixUrl(rawThumb)

        return newAnimeSearchResponse(title, href, TvType.Anime) {
            posterUrl = thumb.ifBlank { null }
        }
    }

    private fun fixUrl(raw: String): String = when {
        raw.startsWith("//")   -> "https:$raw"
        raw.startsWith("/")    -> "$mainUrl$raw"
        raw.startsWith("http") -> raw
        else                   -> ""
    }

    // ─── Search ───────────────────────────────────────────────────────────

    override suspend fun search(query: String): List<SearchResponse> {
        val encoded = query.trim().replace(" ", "+")
        val doc = fetchDoc("$mainUrl/?s=$encoded")
        return doc.select("article.post, article.type-post").mapNotNull { parseCard(it) }
    }

    // ─── Series / Season Load ─────────────────────────────────────────────
    //
    // Each WordPress post on this site represents a full SEASON.
    // Two cases:
    //   A) Post links to individual episode pages (/episode/show-1x01)
    //      → create one Episode per link, episode page URL in data
    //   B) Post has all download buttons inline (smaller series)
    //      → group by episode heading, store redirect links in data

    override suspend fun load(url: String): LoadResponse {
        val doc = fetchDoc(url)

        val title = doc.selectFirst("h1.entry-title, .entry-title h1, .post-title")
            ?.text()?.trim()
            ?: doc.title().substringBefore(" - ").substringBefore(" | ").trim()

        // Poster: featured image
        val poster = doc.selectFirst(
            "img.wp-post-image, .post-thumbnail img, .wp-block-post-featured-image img"
        )?.let { fixUrl(it.attr("src").ifBlank { it.attr("data-src") }) }?.ifBlank { null }

        val plot = doc.selectFirst(".entry-content > p, .post-content > p")
            ?.text()?.trim()?.ifBlank { null }

        val tags = doc.select("a[href*='/category/'], .entry-footer a[rel=category]")
            .map { it.text().trim() }.filter { it.length > 1 }.distinct()

        val content = doc.selectFirst(".entry-content, .post-content")

        // ── Case A: individual episode page links (/episode/{show-NxEP}) ──
        val episodePageLinks = content?.select("a[href*='/episode/']")
            ?.map { it.attr("href").trim() }
            ?.filter { Regex("""\d+x\d+""").containsMatchIn(it) }
            ?.distinct()
            ?: emptyList()

        if (episodePageLinks.isNotEmpty()) {
            val episodes = episodePageLinks.mapNotNull { epUrl ->
                val slug  = epUrl.trimEnd('/').substringAfterLast('/')
                val match = Regex("""(\d+)x(\d+)""").find(slug)
                val seasonN = match?.groupValues?.get(1)?.toIntOrNull() ?: 1
                val epN     = match?.groupValues?.get(2)?.toIntOrNull()

                newEpisode(epUrl) {
                    name    = "Episode ${epN ?: ""}"
                    season  = seasonN
                    episode = epN
                }
            }.sortedWith(compareBy({ it.season ?: 0 }, { it.episode ?: 0 }))

            return newTvSeriesLoadResponse(title, url, TvType.Anime, episodes) {
                posterUrl = poster
                this.plot = plot
                this.tags = tags
            }
        }

        // ── Case B: all redirect links inline, grouped by episode heading ──
        if (content == null) {
            return newMovieLoadResponse(title, url, TvType.AnimeMovie, url) {
                posterUrl = poster; this.plot = plot
            }
        }

        val episodeBlocks = parseEpisodeBlocks(content)

        if (episodeBlocks.isEmpty()) {
            // Single episode / movie — collect all redirect links
            val links = content.select("a[href*='redirect/main.php'], a.wp-block-button__link[href*='main.php']")
                .map { it.attr("href") }.filter { it.contains("redirect") }.distinct()

            return if (links.isNotEmpty()) {
                newMovieLoadResponse(title, url, TvType.AnimeMovie, links.joinToString(SEP)) {
                    posterUrl = poster; this.plot = plot
                }
            } else {
                newMovieLoadResponse(title, url, TvType.AnimeMovie, url) {
                    posterUrl = poster; this.plot = plot
                }
            }
        }

        val episodes = episodeBlocks.map { (epNum, epLabel, links) ->
            newEpisode(links.joinToString(SEP)) {
                name    = epLabel
                episode = epNum
                season  = 1
            }
        }

        return newTvSeriesLoadResponse(title, url, TvType.Anime, episodes) {
            posterUrl = poster
            this.plot = plot
            this.tags = tags
        }
    }

    // ─── Parse inline episode blocks from post content ────────────────────
    // Groups redirect links under their nearest episode heading.

    private fun parseEpisodeBlocks(
        content: Element
    ): List<Triple<Int, String, List<String>>> {
        val blocks = mutableListOf<Triple<Int, String, MutableList<String>>>()
        var currentEpNum = 0
        var currentLabel = ""
        var currentLinks = mutableListOf<String>()

        val epHeadingRe = Regex("""(?i)\bepisode[.\s\-_:]*(\d+)|\bep[.\s\-_:]*(\d+)""")

        for (child in content.children()) {
            val text    = child.text().trim()
            val tagName = child.tagName().lowercase()

            // Detect episode heading
            val match = epHeadingRe.find(text)
            if (match != null && tagName in setOf("h1","h2","h3","h4","h5","p","div","strong","b")) {
                // Save previous block if it had links
                if (currentLinks.isNotEmpty()) {
                    blocks.add(Triple(currentEpNum, currentLabel, currentLinks))
                }
                val num = (match.groupValues[1].ifBlank { match.groupValues[2] }).toIntOrNull()
                    ?: (blocks.size + 1)
                currentEpNum = num
                currentLabel = "Episode $num"
                currentLinks = mutableListOf()
                continue
            }

            // Collect redirect links in this element
            val links = child
                .select("a[href*='redirect/main.php'], a.wp-block-button__link[href*='main.php']")
                .map { it.attr("href") }
                .filter { it.contains("redirect") }

            if (links.isNotEmpty()) {
                if (currentEpNum == 0) {
                    // Links before any heading — create implicit episode 1
                    currentEpNum = 1
                    currentLabel = "Episode 1"
                }
                currentLinks.addAll(links)
            }
        }

        // Flush last block
        if (currentLinks.isNotEmpty()) {
            blocks.add(Triple(currentEpNum, currentLabel, currentLinks))
        }

        return blocks.map { (n, l, links) -> Triple(n, l, links.distinct()) }
    }

    // ─── Adrinolinks Bypass (AdLinkFly engine) ────────────────────────────
    //
    // 1. GET the adrinolinks page (with a referer to pass bot check)
    // 2. Collect ALL hidden <input> form fields
    // 3. POST to {domain}/links/go with those fields + XHR header
    // 4. Extract "url" from JSON response

    private suspend fun bypassAdrinolinks(adrinoUrl: String): String? {
        return try {
            val domain = Regex("""^(https?://[^/]+)""")
                .find(adrinoUrl)?.groupValues?.get(1) ?: return null

            val doc = app.get(
                adrinoUrl,
                headers = mapOf(
                    "User-Agent" to ua,
                    "Referer"    to mainUrl,
                    "Accept"     to "text/html,application/xhtml+xml"
                )
            ).document

            // Collect all named input fields (includes CSRF token + type)
            val formData = doc.select("input[name]")
                .associate { it.attr("name") to it.attr("value") }
                .filter { (k, _) -> k.isNotBlank() }
                .toMutableMap()

            if (formData.isEmpty()) return null

            // AdLinkFly requires a short wait before the API call
            delay(4_000)

            val resp = app.post(
                "$domain/links/go",
                data    = formData,
                headers = mapOf(
                    "User-Agent"       to ua,
                    "X-Requested-With" to "XMLHttpRequest",
                    "Referer"          to adrinoUrl,
                    "Accept"           to "application/json, text/javascript, */*"
                )
            )

            // Extract URL from JSON response body
            Regex(""""url"\s*:\s*"([^"]+)"""").find(resp.text)?.groupValues?.get(1)
                ?.replace("\\/", "/")
        } catch (_: Exception) { null }
    }

    // ─── Follow /redirect/main.php and bypass shorteners ─────────────────

    private suspend fun resolveLink(redirectUrl: String): String? {
        return try {
            // Follow all HTTP redirects → land on shortener page
            val resp     = app.get(redirectUrl, headers = baseHeaders())
            val landedUrl = resp.url

            when {
                // Adrinolinks shortener
                landedUrl.contains("adrinolinks") ->
                    bypassAdrinolinks(landedUrl)

                // Already on a known video host — pass straight through
                landedUrl.contains("dood")       ||
                landedUrl.contains("doodstream") ||
                landedUrl.contains("streamtape") ||
                landedUrl.contains("stream.sb")  ||
                landedUrl.contains("mixdrop")    ||
                landedUrl.contains("drive.google") ||
                landedUrl.contains("filemoon")   ->
                    landedUrl

                // Unknown — return as-is and let loadExtractor decide
                else -> landedUrl
            }
        } catch (_: Exception) { null }
    }

    // ─── Video Links ──────────────────────────────────────────────────────

    override suspend fun loadLinks(
        data: String,
        isCasting: Boolean,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ): Boolean {

        // Determine the list of redirect links from `data`
        val redirectLinks: List<String> = when {

            // Inline links bundled with SEP (Case B from load())
            data.contains(SEP) ->
                data.split(SEP).filter { it.isNotBlank() }

            // Single direct redirect URL
            data.contains("redirect/main.php") ->
                listOf(data)

            // Episode page URL (Case A from load()) — fetch and extract buttons
            data.startsWith("http") -> {
                try {
                    val epDoc = fetchDoc(data)
                    epDoc.select(
                        "a[href*='redirect/main.php'], a.wp-block-button__link[href*='main.php']"
                    ).map { it.attr("href") }.filter { it.contains("redirect") }.distinct()
                } catch (_: Exception) { emptyList() }
            }

            else -> emptyList()
        }

        if (redirectLinks.isEmpty()) {
            // Last-resort: try data as a direct extractor URL
            try { loadExtractor(data, mainUrl, subtitleCallback, callback) } catch (_: Exception) {}
            return true
        }

        // Resolve each redirect link and pass to loadExtractor
        redirectLinks.forEach { rdUrl ->
            try {
                val finalUrl = resolveLink(rdUrl) ?: return@forEach
                if (finalUrl.isBlank()) return@forEach
                loadExtractor(finalUrl, mainUrl, subtitleCallback, callback)
            } catch (_: Exception) {}
        }

        return true
    }
}
