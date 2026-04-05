package com.kraptor

import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.utils.*
import com.lagradost.cloudstream3.utils.ExtractorLink
import kotlinx.coroutines.delay
import org.jsoup.nodes.Element

// ─────────────────────────────────────────────────────────────────────────────
// ToonWorld4All CloudStream Provider
// Site     : https://toonworld4all.me  (episode pages: archive.toonworld4all.me)
//
// Watch Online structure (confirmed):
//   <p><strong>Watch Online</strong> –
//       <a href="https://archive.toonworld4all.me/redirect/HASH">DoodStream</a> |
//       <a href="https://archive.toonworld4all.me/redirect/HASH">StreamTape</a>
//   </p>
//
// The redirect/HASH URLs are site-local redirectors → HTTP 302 → final video host.
// Just following the redirect gives us the actual streamable URL.
//
// Some episode pages also embed iframes directly (dood.watch, streamtape, etc.).
// The series page links episodes via:
//   <a href="https://archive.toonworld4all.me/episode/show-3x1">Watch/Download</a>
// ─────────────────────────────────────────────────────────────────────────────

class ToonWorld4AllProvider : MainAPI() {

    override var mainUrl        = "https://toonworld4all.me"
    override var name           = "ToonWorld4All"
    override var lang           = "hi"
    override val hasMainPage    = true
    override val supportedTypes = setOf(
        TvType.Anime,
        TvType.AnimeMovie,
        TvType.Cartoon,
    )

    private val ua = "Mozilla/5.0 (Windows NT 10.0; Win64; x64) " +
                     "AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120.0.0.0 Safari/537.36"

    private fun baseHeaders(referer: String = mainUrl) = mapOf(
        "User-Agent" to ua,
        "Referer"    to referer,
        "Accept"     to "text/html,application/xhtml+xml,application/xml;q=0.9,*/*;q=0.8",
    )

    private suspend fun fetchDoc(url: String) =
        app.get(url, headers = baseHeaders()).document

    // Separator used to bundle multiple source URLs per episode
    private val SEP = "|||"

    // ─── Video host detection ─────────────────────────────────────────────
    private val videoHosts = listOf(
        "dood", "ds2play", "dooood", "d000d", "doodstream",
        "streamtape",
        "filemoon", "fmoonembed",
        "mixdrop",
        "stream.sb", "sbplay", "embedrise",
        "ok.ru",
        "mp4upload",
        "rumble",
        "drive.google",
        "fembed",
        "upstream",
        "kwik",
        "vidcloud", "vidstream",
        "rapidvideo",
        "streamhub",
        "voe.sx",
        "vidhide",
        "streamwish",
    )

    private fun isVideoHost(url: String) =
        url.startsWith("http") && videoHosts.any { url.contains(it, ignoreCase = true) }

    // ─── Is a site-local redirect link we should follow? ─────────────────
    // Matches patterns like:
    //   https://archive.toonworld4all.me/redirect/ca87c8f9…
    //   https://toonworld4all.me/redirect/main.php?url=…
    private fun isRedirectLink(url: String): Boolean {
        if (url.isBlank() || url == "#") return false
        return url.contains("/redirect/") || url.contains("redirect/main.php")
    }

    // ─── Thumbnail helpers ─────────────────────────────────────────────────
    private fun Element.bestSrc(): String? {
        val raw = attr("data-lazy-src").ifBlank {
            attr("data-src").ifBlank {
                attr("data-original").ifBlank { attr("src") }
            }
        }
        return fixUrl(raw).ifBlank { null }
    }

    private fun fixUrl(raw: String): String = when {
        raw.isBlank()          -> ""
        raw.startsWith("//")   -> "https:$raw"
        raw.startsWith("/")    -> "$mainUrl$raw"
        raw.startsWith("http") -> raw
        else                   -> ""
    }

    // ─── Category pages ────────────────────────────────────────────────────
    override val mainPage = mainPageOf(
        "$mainUrl/category/anime/"              to "Anime",
        "$mainUrl/category/cartoon/"            to "Cartoon",
        "$mainUrl/category/completed/"          to "Completed",
        "$mainUrl/category/anime-times/"        to "Anime Times",
        "$mainUrl/category/jio-cinema/"         to "JioCinema",
        "$mainUrl/category/amazon-prime-video/" to "Amazon Prime",
        "$mainUrl/category/crunchyroll/"        to "Crunchyroll",
        "$mainUrl/category/disney-plus/"        to "Disney+",
        "$mainUrl/category/apple-tv/"           to "Apple TV+",
        "$mainUrl/category/muse-india/"         to "Muse India",
    )

    override suspend fun getMainPage(page: Int, request: MainPageRequest): HomePageResponse {
        val base = request.data.trimEnd('/')
        val doc  = fetchDoc("$base/page/$page/")
        val items = doc.select("article.post, article.type-post").mapNotNull { parseCard(it) }
        val hasNext = doc.selectFirst(
            "a.next, a[rel=next], .pagination .next, a[href*='/page/${page + 1}/']"
        ) != null
        return newHomePageResponse(request.name, items, hasNext)
    }

    private fun parseCard(el: Element): SearchResponse? {
        val titleEl = el.selectFirst("h2.entry-title a, .entry-title a") ?: return null
        val title   = titleEl.text().trim().ifBlank { return null }
        val href    = titleEl.attr("href").trim().ifBlank { return null }
        val img     = el.selectFirst(
            "img.wp-post-image, .post-thumbnail img, figure img, a > img, img"
        )
        return newAnimeSearchResponse(title, href, TvType.Anime) {
            posterUrl = img?.bestSrc()
        }
    }

    // ─── Search ────────────────────────────────────────────────────────────
    override suspend fun search(query: String): List<SearchResponse> {
        val doc = fetchDoc("$mainUrl/?s=${query.trim().replace(" ", "+")}")
        return doc.select("article.post, article.type-post").mapNotNull { parseCard(it) }
    }

    // ─── Series / movie detail ─────────────────────────────────────────────
    override suspend fun load(url: String): LoadResponse {
        val doc = fetchDoc(url)

        val title = doc.selectFirst("h1.entry-title, .entry-title h1, .post-title")
            ?.text()?.trim()
            ?: doc.title().substringBefore(" - ").substringBefore(" | ").trim()

        // og:image is the most reliable poster on WordPress sites
        val poster = doc.selectFirst("meta[property='og:image']")?.attr("content")
            ?.trim()?.ifBlank { null }
            ?: doc.selectFirst(
                "img.wp-post-image, .post-thumbnail img, " +
                ".wp-block-post-featured-image img, .entry-thumbnail img"
            )?.bestSrc()

        val plot = doc.selectFirst(
            "meta[name='description'], meta[property='og:description']"
        )?.attr("content")?.trim()?.ifBlank { null }
            ?: doc.selectFirst(".entry-content > p, .post-content > p")
                ?.text()?.trim()?.ifBlank { null }

        val tags = doc.select("a[href*='/category/'], .entry-footer a[rel=category]")
            .map { it.text().trim() }.filter { it.length > 1 }.distinct()

        val content = doc.selectFirst(".entry-content, .post-content")

        // ── Case A: page links to individual episode pages ─────────────────
        // Matches both /episode/ and /episode/ on archive subdomain.
        // URL patterns like: archive.toonworld4all.me/episode/fire-force-3x1
        val episodePageLinks = content
            ?.select("a[href*='/episode/']")
            ?.map { it.attr("href").trim() }
            ?.filter { Regex("""\d+x\d+""").containsMatchIn(it) }
            ?.distinct()
            ?: emptyList()

        if (episodePageLinks.isNotEmpty()) {
            val episodes = episodePageLinks.mapNotNull { epUrl ->
                val slug  = epUrl.trimEnd('/').substringAfterLast('/')
                val match = Regex("""(\d+)x(\d+)""").find(slug)
                val sNum  = match?.groupValues?.get(1)?.toIntOrNull() ?: 1
                val eNum  = match?.groupValues?.get(2)?.toIntOrNull()
                newEpisode(epUrl) {
                    name      = "Episode ${eNum ?: (episodePageLinks.indexOf(epUrl) + 1)}"
                    season    = sNum
                    episode   = eNum
                    posterUrl = poster
                }
            }.sortedWith(compareBy({ it.season ?: 0 }, { it.episode ?: 0 }))

            return newTvSeriesLoadResponse(title, url, TvType.Anime, episodes) {
                posterUrl = poster
                this.plot = plot
                this.tags = tags
            }
        }

        // ── Case B: all links inline, grouped by episode headings ──────────
        if (content == null) {
            return newMovieLoadResponse(title, url, TvType.AnimeMovie, url) {
                posterUrl = poster; this.plot = plot
            }
        }

        val episodeBlocks = parseEpisodeBlocks(content)

        if (episodeBlocks.isEmpty()) {
            // No episode structure found — treat entire page as a movie
            val allLinks = collectStreamableLinks(content)
            val movieData = allLinks.joinToString(SEP).ifBlank { url }
            return newMovieLoadResponse(title, url, TvType.AnimeMovie, movieData) {
                posterUrl = poster; this.plot = plot
            }
        }

        val episodes = episodeBlocks.map { (epNum, epLabel, links) ->
            newEpisode(links.joinToString(SEP)) {
                name      = epLabel
                episode   = epNum
                season    = 1
                posterUrl = poster
            }
        }

        return newTvSeriesLoadResponse(title, url, TvType.Anime, episodes) {
            posterUrl = poster
            this.plot = plot
            this.tags = tags
        }
    }

    // ─── Collect streamable links from a content block ────────────────────
    // Gathers: iframes, direct video-host anchors, AND site-local redirect links.
    // Redirect links (archive.toonworld4all.me/redirect/HASH) are included as-is;
    // they are resolved to final video-host URLs later in loadLinks.
    private fun collectStreamableLinks(el: Element): List<String> {
        val results = mutableListOf<String>()

        // Direct iframes
        el.select("iframe[src], iframe[data-src]").forEach { iframe ->
            val src = iframe.attr("src").ifBlank { iframe.attr("data-src") }.trim()
            if (src.isNotBlank()) results.add(src)
        }

        // Anchors: either direct video hosts OR site redirect links
        el.select("a[href]").forEach { a ->
            val href = a.attr("href").trim()
            if (href.isBlank() || href == "#" || href.startsWith("mailto:")) return@forEach
            if (isVideoHost(href) || isRedirectLink(href)) results.add(href)
        }

        return results.distinct()
    }

    // ─── Parse inline episode blocks ──────────────────────────────────────
    private fun parseEpisodeBlocks(
        content: Element
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

            // Episode heading
            if (match != null &&
                tagName in setOf("h1", "h2", "h3", "h4", "h5", "p", "div", "strong", "b") &&
                text.length < 120
            ) {
                if (currentLinks.isNotEmpty())
                    blocks.add(Triple(currentNum, currentLabel, currentLinks))
                val num = (match.groupValues[1].ifBlank { match.groupValues[2] })
                    .toIntOrNull() ?: (blocks.size + 1)
                currentNum   = num
                currentLabel = "Episode $num"
                currentLinks = mutableListOf()
                continue
            }

            val found = collectStreamableLinks(child)
            if (found.isNotEmpty()) {
                if (currentNum == 0) { currentNum = 1; currentLabel = "Episode 1" }
                currentLinks.addAll(found)
            }
        }

        if (currentLinks.isNotEmpty())
            blocks.add(Triple(currentNum, currentLabel, currentLinks))

        return blocks.map { Triple(it.first, it.second, it.third.distinct()) }
    }

    // ─── Resolve a single redirect/link to a streamable video URL ─────────
    //
    // Handles:
    //   1. archive.toonworld4all.me/redirect/HASH → HTTP 302 → video host
    //   2. toonworld4all.me/redirect/main.php?url=BASE64 → HTTP 302 → adrinolinks → POST → video
    //   3. Already a video host → return as-is
    //   4. Unknown redirect → follow and check final URL
    private suspend fun resolveToVideoUrl(linkUrl: String): String? {
        if (isVideoHost(linkUrl)) return linkUrl

        return try {
            // Follow all redirects and capture the final URL
            val resp      = app.get(linkUrl, headers = baseHeaders(linkUrl))
            val finalUrl  = resp.url

            when {
                // Already landed on a video host
                isVideoHost(finalUrl) -> finalUrl

                // Landed on adrinolinks timer page
                finalUrl.contains("adrinolinks") || finalUrl.contains("adrino.") ->
                    bypassAdrinolinks(finalUrl)

                // Anything else (rocklinks → GDTot, etc.) — skip
                else -> null
            }
        } catch (_: Exception) { null }
    }

    // ─── Adrinolinks (AdLinkFly) bypass ───────────────────────────────────
    //
    // Fetches the adrinolinks page (which has already been redirected to
    // /links/go?token=...), extracts form inputs, waits, then POSTs.
    // 2025: response may be JSON {"url":"..."} OR redirect to the video host.
    private suspend fun bypassAdrinolinks(adrinoUrl: String): String? {
        return try {
            // Fetch adrinolinks – CloudStream follows 302 to /links/go?token=...
            val pageResp = app.get(
                adrinoUrl,
                headers = mapOf("User-Agent" to ua, "Referer" to mainUrl)
            )
            val respUrl     = pageResp.url
            val finalDomain = Regex("""^(https?://[^/]+)""")
                .find(respUrl)?.groupValues?.get(1) ?: return null

            val doc = pageResp.document

            // Use .key / .value instead of destructuring to avoid type-inference errors
            val formData: MutableMap<String, String> = doc.select("input[name]")
                .associate { it.attr("name") to it.attr("value") }
                .filter { it.key.isNotBlank() }
                .toMutableMap()

            if (formData.isEmpty()) return null

            // Wait out the countdown timer
            delay(4_000)

            val postResp = app.post(
                "$finalDomain/links/go",
                data    = formData,
                headers = mapOf(
                    "User-Agent"       to ua,
                    "X-Requested-With" to "XMLHttpRequest",
                    "Referer"          to respUrl,
                    "Accept"           to "application/json, text/javascript, */*; q=0.01",
                    "Content-Type"     to "application/x-www-form-urlencoded; charset=UTF-8",
                )
            )

            // Try JSON body first
            val jsonUrl = Regex(""""url"\s*:\s*"([^"]+)"""")
                .find(postResp.text)?.groupValues?.get(1)
                ?.replace("\\/", "/")
                ?.takeIf { it.startsWith("http") }

            if (!jsonUrl.isNullOrBlank()) return jsonUrl

            // Fallback: final URL after redirect chain
            val landed = postResp.url
            if (landed.isNotBlank() && landed != "$finalDomain/links/go" && isVideoHost(landed))
                return landed

            // Fallback 2: scan response text for known video host URLs
            Regex("""https?://[^\s"'<>]+""").findAll(postResp.text)
                .map { it.value }
                .firstOrNull { isVideoHost(it) }

        } catch (_: Exception) { null }
    }

    // ─── loadLinks ──────────────────────────────────────────────────────────
    override suspend fun loadLinks(
        data: String,
        isCasting: Boolean,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ): Boolean {

        // ── 1. SEP-joined URLs from parseEpisodeBlocks (Case B) ───────────
        if (data.contains(SEP)) {
            data.split(SEP).filter { it.isNotBlank() }.forEach { rawUrl ->
                processOneLink(rawUrl, subtitleCallback, callback)
            }
            return true
        }

        // ── 2. Single direct video host URL (rare fast path) ─────────────
        if (isVideoHost(data)) {
            try { loadExtractor(data, mainUrl, subtitleCallback, callback) }
            catch (_: Exception) {}
            return true
        }

        // ── 3. Episode page URL (Case A) or series page URL (movie fallback)
        //      → Fetch the page and extract ALL streamable/redirect links.
        if (data.startsWith("http")) {
            try {
                val doc = fetchDoc(data)
                val content = doc.selectFirst(".entry-content, .post-content, #content, body")
                    ?: return true

                val links = collectStreamableLinks(content)
                links.forEach { rawUrl ->
                    processOneLink(rawUrl, subtitleCallback, callback)
                }
            } catch (_: Exception) {}
        }

        return true
    }

    // ─── Process a single raw URL: resolve redirects then call loadExtractor
    private suspend fun processOneLink(
        rawUrl: String,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit,
    ) {
        try {
            val videoUrl = if (isVideoHost(rawUrl)) {
                rawUrl
            } else {
                resolveToVideoUrl(rawUrl)
            } ?: return

            loadExtractor(videoUrl, mainUrl, subtitleCallback, callback)
        } catch (_: Exception) {}
    }
}
