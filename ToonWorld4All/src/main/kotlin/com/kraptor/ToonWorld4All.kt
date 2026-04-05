package com.kraptor

import android.util.Base64
import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.utils.*
import com.lagradost.cloudstream3.utils.ExtractorLink
import kotlinx.coroutines.delay
import org.jsoup.nodes.Element
import java.net.URLDecoder

// ─────────────────────────────────────────────────────────────────────────────
// ToonWorld4All CloudStream Provider
// Site  : https://toonworld4all.me
// Theme : WordPress (Gutenberg) + Cloudflare
// Lang  : Hindi / Tamil / Telugu / English / Japanese (Multi Audio)
//
// Video chain
//   wp-block button → /redirect/main.php?url=<base64>
//     → HTTP 302 → adrinolinks.in/{code}
//     → AdLinkFly form bypass → /links/go?token=...
//     → POST /links/go → intermediate domain (gomob.xyz / gamechilly.online)
//     → final video host (DoodStream / StreamTape / FileMoon …)
//     → CloudStream loadExtractor
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

    private fun baseHeaders() = mapOf(
        "User-Agent" to ua,
        "Referer"    to mainUrl,
        "Accept"     to "text/html,application/xhtml+xml,application/xml;q=0.9,*/*;q=0.8",
    )

    private suspend fun fetchDoc(url: String) =
        app.get(url, headers = baseHeaders()).document

    // Separator used to bundle multiple redirect URLs per episode
    private val SEP = "|||"

    // ─── Categories ───────────────────────────────────────────────────────
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

    // ─── Pagination ───────────────────────────────────────────────────────
    override suspend fun getMainPage(page: Int, request: MainPageRequest): HomePageResponse {
        val base = request.data.trimEnd('/')
        val url  = "$base/page/$page/"
        val doc  = fetchDoc(url)
        val items = doc.select("article.post, article.type-post").mapNotNull { parseCard(it) }
        val hasNext = doc.selectFirst(
            "a.next, a[rel=next], .pagination .next, a[href*='/page/${page + 1}/']"
        ) != null
        return newHomePageResponse(request.name, items, hasNext)
    }

    // ─── Article card (category listing) ─────────────────────────────────
    private fun parseCard(el: Element): SearchResponse? {
        val titleEl = el.selectFirst("h2.entry-title a, .entry-title a") ?: return null
        val title   = titleEl.text().trim().ifBlank { return null }
        val href    = titleEl.attr("href").trim().ifBlank { return null }

        // Try every possible image attribute for lazy-loaded WordPress themes
        val img = el.selectFirst(
            "img.wp-post-image, .post-thumbnail img, figure img, a > img, img"
        )
        val thumb = img?.bestSrc()

        return newAnimeSearchResponse(title, href, TvType.Anime) {
            posterUrl = thumb
        }
    }

    // ─── Image helpers ────────────────────────────────────────────────────
    // WordPress + Cloudflare sites use data-src / data-lazy-src for lazy loading.

    private fun Element.bestSrc(): String? {
        val raw = attr("data-lazy-src").ifBlank {
            attr("data-src").ifBlank {
                attr("data-original").ifBlank {
                    attr("src")
                }
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

    // ─── Search ───────────────────────────────────────────────────────────
    override suspend fun search(query: String): List<SearchResponse> {
        val encoded = query.trim().replace(" ", "+")
        val doc = fetchDoc("$mainUrl/?s=$encoded")
        return doc.select("article.post, article.type-post").mapNotNull { parseCard(it) }
    }

    // ─── Series detail ────────────────────────────────────────────────────
    override suspend fun load(url: String): LoadResponse {
        val doc = fetchDoc(url)

        val title = doc.selectFirst("h1.entry-title, .entry-title h1, .post-title")
            ?.text()?.trim()
            ?: doc.title().substringBefore(" - ").substringBefore(" | ").trim()

        // ── Poster: og:image is the most reliable on WordPress sites ──────
        val poster = doc.selectFirst("meta[property='og:image']")?.attr("content")?.trim()
            ?.ifBlank { null }
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

        // ── Case A: Individual episode page links (/episode/show-1x01) ─────
        val episodePageLinks = content
            ?.select("a[href*='/episode/']")
            ?.map { it.attr("href").trim() }
            ?.filter { Regex("""\d+x\d+""").containsMatchIn(it) }
            ?.distinct()
            ?: emptyList()

        if (episodePageLinks.isNotEmpty()) {
            val episodes = episodePageLinks.mapNotNull { epUrl ->
                val slug    = epUrl.trimEnd('/').substringAfterLast('/')
                val match   = Regex("""(\d+)x(\d+)""").find(slug)
                val seasonN = match?.groupValues?.get(1)?.toIntOrNull() ?: 1
                val epN     = match?.groupValues?.get(2)?.toIntOrNull()
                newEpisode(epUrl) {
                    name    = "Episode ${epN ?: (episodePageLinks.indexOf(epUrl) + 1)}"
                    season  = seasonN
                    episode = epN
                    // OG image from the season page used as episode poster fallback
                    posterUrl = poster
                }
            }.sortedWith(compareBy({ it.season ?: 0 }, { it.episode ?: 0 }))

            return newTvSeriesLoadResponse(title, url, TvType.Anime, episodes) {
                posterUrl = poster
                this.plot = plot
                this.tags = tags
            }
        }

        // ── Case B: All redirect buttons inline, grouped by episode heading ─
        if (content == null) {
            return newMovieLoadResponse(title, url, TvType.AnimeMovie, url) {
                posterUrl = poster; this.plot = plot
            }
        }

        val episodeBlocks = parseEpisodeBlocks(content)

        if (episodeBlocks.isEmpty()) {
            val links = content
                .select("a[href*='redirect/main.php'], a.wp-block-button__link[href*='main.php']")
                .map { it.attr("href") }
                .filter { it.contains("redirect") }
                .distinct()
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
                name      = epLabel
                episode   = epNum
                season    = 1
                posterUrl = poster   // season poster as episode thumbnail
            }
        }

        return newTvSeriesLoadResponse(title, url, TvType.Anime, episodes) {
            posterUrl = poster
            this.plot = plot
            this.tags = tags
        }
    }

    // ─── Parse inline episode blocks from Gutenberg post content ─────────
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

            if (match != null &&
                tagName in setOf("h1","h2","h3","h4","h5","p","div","strong","b")
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

            val links = child
                .select("a[href*='redirect/main.php'], a.wp-block-button__link[href*='main.php']")
                .map { it.attr("href") }
                .filter { it.contains("redirect") }

            if (links.isNotEmpty()) {
                if (currentNum == 0) { currentNum = 1; currentLabel = "Episode 1" }
                currentLinks.addAll(links)
            }
        }
        if (currentLinks.isNotEmpty())
            blocks.add(Triple(currentNum, currentLabel, currentLinks))

        return blocks.map { Triple(it.first, it.second, it.third.distinct()) }
    }

    // ─── Step 1: Decode base64 URL from redirect/main.php?url=<encoded> ──
    // This lets us skip the HTTP redirect step and go straight to the shortener.
    private fun decodeMainPhpUrl(redirectUrl: String): String? {
        return try {
            val encoded = Regex("""[?&]url=([^&\s]+)""")
                .find(redirectUrl)?.groupValues?.get(1) ?: return null
            val urlDecoded = URLDecoder.decode(encoded, "UTF-8")
            val bytes = Base64.decode(urlDecoded, Base64.DEFAULT)
            String(bytes, Charsets.UTF_8).trim()
                .takeIf { it.startsWith("http") }
        } catch (_: Exception) { null }
    }

    // ─── Step 2: Adrinolinks bypass (AdLinkFly 2025) ─────────────────────
    //
    // Flow:
    //   GET adrinolinks.in/CODE → 302 → /links/go?token=XXX (follow redirect)
    //   GET /links/go?token=XXX → HTML form with CSRF token + type fields
    //   POST /links/go { token, type, … } + X-Requested-With: XMLHttpRequest
    //   → JSON {"url":"..."} OR 302 to intermediate domain (gomob.xyz etc.)
    //   → follow through to final video host URL

    private suspend fun bypassAdrinolinks(adrinoUrl: String): String? {
        return try {
            val domain = Regex("""^(https?://[^/]+)""")
                .find(adrinoUrl)?.groupValues?.get(1) ?: return null

            // GET the shortcode page — CloudStream follows the 302 to /links/go?token=...
            val pageResp = app.get(
                adrinoUrl,
                headers = mapOf(
                    "User-Agent" to ua,
                    "Referer"    to mainUrl,
                    "Accept"     to "text/html,application/xhtml+xml"
                )
            )
            val pageDoc  = pageResp.document
            val finalDomain = Regex("""^(https?://[^/]+)""")
                .find(pageResp.url)?.groupValues?.get(1) ?: domain

            // Collect ALL named inputs (CSRF token + hidden type fields)
            val formData = pageDoc.select("input[name]")
                .associate { it.attr("name") to it.attr("value") }
                .filter { (k, _) -> k.isNotBlank() }
                .toMutableMap()

            if (formData.isEmpty()) return null

            // AdLinkFly enforces a countdown timer — wait for it
            delay(4_000)

            // POST to /links/go
            val postResp = app.post(
                "$finalDomain/links/go",
                data    = formData,
                headers = mapOf(
                    "User-Agent"       to ua,
                    "X-Requested-With" to "XMLHttpRequest",
                    "Referer"          to pageResp.url,
                    "Accept"           to "application/json, text/javascript, */*; q=0.01",
                    "Content-Type"     to "application/x-www-form-urlencoded; charset=UTF-8"
                )
            )

            // ── Try 1: JSON response { "url": "..." } ─────────────────────
            val jsonUrl = Regex(""""url"\s*:\s*"([^"]+)"""")
                .find(postResp.text)
                ?.groupValues?.get(1)
                ?.replace("\\/", "/")
                ?.takeIf { it.startsWith("http") }

            if (!jsonUrl.isNullOrBlank()) return jsonUrl

            // ── Try 2: app.post followed all redirects — check final URL ──
            // In 2025, adrinolinks POSTs redirect to intermediate domains
            // (gomob.xyz, gamechilly.online) then to the video host.
            val landedUrl = postResp.url
            if (landedUrl.isNotBlank() && landedUrl != "$finalDomain/links/go") {
                return landedUrl
            }

            // ── Try 3: extract any href that looks like a video host ───────
            Regex("""https?://[^\s"'<>]+""")
                .findAll(postResp.text)
                .map { it.value }
                .firstOrNull { u ->
                    listOf("dood","streamtape","filemoon","mixdrop","stream.sb",
                           "drive.google","ok.ru","rumble","mp4upload")
                        .any { host -> u.contains(host) }
                }

        } catch (_: Exception) { null }
    }

    // ─── Resolve one redirect/main.php link to a playable URL ────────────
    private suspend fun resolveLink(redirectUrl: String): String? {
        // 1. Try decoding the base64 URL param directly (fastest path)
        val directUrl = decodeMainPhpUrl(redirectUrl)
        if (!directUrl.isNullOrBlank()) {
            return if (directUrl.contains("adrinolinks")) {
                bypassAdrinolinks(directUrl)
            } else {
                directUrl   // already a video host URL
            }
        }

        // 2. Fall back to following the HTTP redirect from main.php
        return try {
            val resp      = app.get(redirectUrl, headers = baseHeaders())
            val landedUrl = resp.url

            when {
                landedUrl.contains("adrinolinks") || landedUrl.contains("adrino") ->
                    bypassAdrinolinks(landedUrl)

                landedUrl == redirectUrl || landedUrl.contains("toonworld4all") ->
                    null   // redirect didn't work — skip

                else -> landedUrl
            }
        } catch (_: Exception) { null }
    }

    // ─── Video link resolution ────────────────────────────────────────────
    override suspend fun loadLinks(
        data: String,
        isCasting: Boolean,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ): Boolean {

        // Build the list of redirect/main.php URLs to resolve
        val redirectLinks: List<String> = when {
            // Inline-episode bundle from Case B
            data.contains(SEP) ->
                data.split(SEP).filter { it.isNotBlank() }

            // Single redirect URL
            data.contains("redirect/main.php") ->
                listOf(data)

            // Episode page URL from Case A — fetch the page and extract buttons
            data.startsWith("http") -> {
                try {
                    fetchDoc(data)
                        .select(
                            "a[href*='redirect/main.php'], " +
                            "a.wp-block-button__link[href*='main.php']"
                        )
                        .map { it.attr("href") }
                        .filter { it.contains("redirect") }
                        .distinct()
                } catch (_: Exception) { emptyList() }
            }

            else -> emptyList()
        }

        if (redirectLinks.isEmpty()) {
            try { loadExtractor(data, mainUrl, subtitleCallback, callback) } catch (_: Exception) {}
            return true
        }

        // Resolve every redirect link and hand the final URL to loadExtractor
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
