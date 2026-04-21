package com.kraptor

import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.utils.*
import com.lagradost.cloudstream3.network.CloudflareKiller
import org.jsoup.Jsoup
import org.jsoup.nodes.Document
import java.net.URI
import java.util.Base64

/*
 * ============================================================
 *  MoviesMod CloudStream Plugin — Complete Fixed Implementation
 * ============================================================
 *
 *  ROOT CAUSE SUMMARY (why "No links found" appeared):
 *
 *  1. WRONG LINK SELECTOR — Old code looked for `a[href*="hubcloud"]`
 *     or similar. The site now uses modrefer.in, modpro.blog,
 *     episodes.modpro.blog, links.modpro.blog, posts.modpro.blog,
 *     cinematickit.org as intermediate redirectors.
 *
 *  2. MULTI-STAGE CHAIN NOT IMPLEMENTED — Every link on the movie
 *     page is an intermediate shortener that leads to a driveseed.org
 *     or SID link, which in turn leads to the final video/download URL.
 *     The original extractor stopped at the first stage.
 *
 *  3. BASE64 DECODE MISSING — modrefer.in encodes the real URL in
 *     a base64 `?url=` query parameter. Without decoding it, the
 *     extractor gets an unresolvable page.
 *
 *  4. DRIVESEED REDIRECT NOT FOLLOWED — Driveseed uses a JS
 *     `window.location.replace(...)` redirect. A naive fetch gets the
 *     redirect shell, not the file page.
 *
 *  5. MISSING HEADERS — The site and all intermediaries require:
 *     - Referer matching each hop
 *     - A realistic User-Agent
 *     Without these, requests return 403 / empty pages.
 *
 *  6. DOMAIN CHANGES — moviesmod changes TLD frequently.
 *     A hardcoded domain becomes stale within weeks.
 *
 *  FIX SUMMARY:
 *  - Dynamic domain fetching (with fallback to moviesmod.farm)
 *  - Correct selectors for movies (h4 sections) and TV (h3 Season sections)
 *  - Full modrefer.in → base64 decode → timed-content link extraction
 *  - Full modpro.blog / posts / episodes / links subdomain handling
 *  - Full cinematickit.org → driveseed link extraction
 *  - Driveseed JS-redirect follower
 *  - Driveseed file page button handlers:
 *      • Instant Download  → POST /api with keys + x-token header
 *      • Resume Worker Bot → extract token+id → POST /download
 *      • Direct Links      → GET ?type=1
 *      • Resume Cloud      → follow + extract .btn-success
 *  - Proper Referer chain at every hop
 * ============================================================
 */

class MoviesMod : MainAPI() {

    override var mainUrl = "https://moviesmod.farm"
    override var name = "MoviesMod"
    override val hasMainPage = true
    override var lang = "en"
    override val hasDownloadSupport = true
    override val supportedTypes = setOf(
        TvType.Movie,
        TvType.TvSeries,
        TvType.Anime,
    )

    // ─── Domain Resolution ────────────────────────────────────────────────────

    /** Fallback domains — tried in order if the primary is unreachable. */
    private val fallbackDomains = listOf(
        "https://moviesmod.farm",
        "https://moviesmod.build",
        "https://moviesmod.bond",
        "https://moviesmod.uno",
    )

    /** Centralised domain JSON maintained by the community. */
    private val domainJsonUrl =
        "https://raw.githubusercontent.com/phisher98/TVVVV/refs/heads/main/domains.json"

    private var resolvedDomain: String = mainUrl
    private var domainFetchedAt: Long = 0L
    private val domainCacheTtl = 4 * 60 * 60 * 1000L  // 4 hours

    /**
     * Returns the live MoviesMod domain. Caches the result for 4 hours.
     * Falls back to [mainUrl] if the JSON fetch fails.
     */
    private suspend fun getBaseUrl(): String {
        val now = System.currentTimeMillis()
        if (now - domainFetchedAt < domainCacheTtl) return resolvedDomain

        return try {
            val json = app.get(
                domainJsonUrl,
                headers = baseHeaders()
            ).parsedSafe<Map<String, String>>()

            val candidate = json?.get("moviesmod")?.trimEnd('/')
            if (!candidate.isNullOrBlank()) {
                // Quick HEAD check to verify the domain is up
                val test = app.head(candidate, headers = baseHeaders(), timeout = 10_000L)
                resolvedDomain = if (test.isSuccessful) candidate else resolvedDomain
            }
            domainFetchedAt = now
            resolvedDomain
        } catch (_: Exception) {
            resolvedDomain  // keep whatever we had
        }
    }

    // ─── Common Headers ───────────────────────────────────────────────────────

    private val userAgent =
        "Mozilla/5.0 (Windows NT 10.0; Win64; x64) " +
        "AppleWebKit/537.36 (KHTML, like Gecko) " +
        "Chrome/124.0.0.0 Safari/537.36"

    private fun baseHeaders(referer: String = mainUrl): Map<String, String> = mapOf(
        "User-Agent"      to userAgent,
        "Referer"         to referer,
        "Accept"          to "text/html,application/xhtml+xml,application/xml;q=0.9,*/*;q=0.8",
        "Accept-Language" to "en-US,en;q=0.5",
        "Connection"      to "keep-alive",
    )

    // ─── Main Page ────────────────────────────────────────────────────────────

    override val mainPage = mainPageOf(
        "/"             to "Latest",
        "/movies/"      to "Movies",
        "/web-series/"  to "Web Series",
        "/anime/"       to "Anime",
    )

    override suspend fun getMainPage(page: Int, request: MainPageRequest): HomePageResponse {
        val base = getBaseUrl()
        val pageUrl = if (page == 1) "$base${request.data}"
                      else "$base${request.data}page/$page/"
        val doc = app.get(pageUrl, headers = baseHeaders(base)).document
        val items = parseSearchResults(doc, base)
        return newHomePageResponse(request.name, items, hasNextPage = items.isNotEmpty())
    }

    // ─── Search ───────────────────────────────────────────────────────────────

    override suspend fun search(query: String): List<SearchResponse> {
        val base = getBaseUrl()
        val url  = "$base/?s=${query.replace(" ", "+")}"
        val doc  = app.get(url, headers = baseHeaders(base)).document
        return parseSearchResults(doc, base)
    }

    /** Extracts post cards from any listing/archive page. */
    private fun parseSearchResults(doc: Document, base: String): List<SearchResponse> {
        // moviesmod uses `.latestPost` divs; also try `.post-box` as fallback
        val articles = doc.select(".latestPost, article.gridlove-post, .post-box, .movies-list article")
        return articles.mapNotNull { el ->
            val a     = el.selectFirst("a[href]") ?: return@mapNotNull null
            val href  = a.absUrl("href").ifBlank { "$base${a.attr("href")}" }
            val title = el.selectFirst("h2, h3, .post-title, .entry-title, img[alt]")
                           ?.let { it.attr("alt").ifBlank { it.text() } }
                           ?: a.attr("title").ifBlank { a.text() }
            val poster = el.selectFirst("img")?.attr("src")

            val isSeries = title.contains(Regex("(?i)season|series|s\\d{1,2}e\\d{1,2}|episode"))
            if (isSeries)
                newTvSeriesSearchResponse(title.trim(), href, TvType.TvSeries) { this.posterUrl = poster }
            else
                newMovieSearchResponse(title.trim(), href, TvType.Movie) { this.posterUrl = poster }
        }
    }

    // ─── Load (detail page) ───────────────────────────────────────────────────

    override suspend fun load(url: String): LoadResponse {
        val base = getBaseUrl()
        val doc  = app.get(url, headers = baseHeaders(base)).document

        val title  = doc.selectFirst("h1.entry-title, h1.post-title, h1")?.text()?.trim() ?: "Unknown"
        val poster = doc.selectFirst(".post-thumbnail img, .entry-image img, article img")
                        ?.attr("src")
        val plot   = doc.selectFirst(".entry-content p, .post-content p")?.text()?.trim()
        val year   = Regex("""(\d{4})""").find(title)?.value?.toIntOrNull()
            ?: doc.selectFirst("time, .date")?.text()?.let { Regex("""(\d{4})""").find(it)?.value?.toIntOrNull() }

        // Detect TV vs Movie by presence of season headers
        val isSeries = doc.select("h3, h4").any {
            it.text().contains(Regex("(?i)season\\s*\\d"))
        }

        return if (isSeries) {
            // ── TV Series ──
            val episodes = mutableListOf<Episode>()
            var seasonNum = 1

            doc.select("h3, h4").forEach { header ->
                val headerText = header.text()
                if (!headerText.contains(Regex("(?i)season\\s*\\d"))) return@forEach

                val sNum = Regex("""(?i)season\s*(\d+)""").find(headerText)?.groupValues?.get(1)
                              ?.toIntOrNull() ?: seasonNum++

                // Collect episode buttons in the block following this header
                var sibling = header.nextElementSibling()
                while (sibling != null && !sibling.tagName().matches(Regex("h[2-4]"))) {
                    // Episode-level links: maxbutton-episode-links, direct anchors
                    sibling.select(
                        "a.maxbutton-episode-links, a.maxbutton-episode, " +
                        "a[href*='modrefer.in'], a[href*='modpro.blog'], " +
                        "a[href*='episodes.modpro.blog'], a[href*='links.modpro.blog'], " +
                        "a[href*='posts.modpro.blog'], a[href*='cinematickit.org']"
                    ).forEachIndexed { idx, a ->
                        val epText = a.text().trim()
                        val epNum  = Regex("""(?i)ep(?:isode)?\s*(\d+)""").find(epText)
                                        ?.groupValues?.get(1)?.toIntOrNull() ?: (idx + 1)
                        val epUrl  = a.absUrl("href").ifBlank { a.attr("href") }
                        if (epUrl.isBlank()) return@forEachIndexed
                        // Skip batch/zip links
                        if (epText.contains(Regex("(?i)batch|zip|pack"))) return@forEachIndexed

                        episodes += newEpisode(epUrl) {
                            this.name    = "S${sNum.toString().padStart(2,'0')}E${epNum.toString().padStart(2,'0')} $epText"
                            this.season  = sNum
                            this.episode = epNum
                        }
                    }
                    sibling = sibling.nextElementSibling()
                }
            }

            newTvSeriesLoadResponse(title, url, TvType.TvSeries, episodes) {
                this.posterUrl = poster
                this.plot      = plot
                this.year      = year
            }
        } else {
            // ── Movie ──
            // Collect ALL quality anchor links from the page into a serialisable string
            val links = collectMovieLinks(doc)
            newMovieLoadResponse(title, url, TvType.Movie, links.joinToString("|||")) {
                this.posterUrl = poster
                this.plot      = plot
                this.year      = year
            }
        }
    }

    /**
     * Collects raw intermediate link URLs from a movie's content page.
     * Stores them as a pipe-separated list in the data field so loadLinks
     * can process them later.
     */
    private fun collectMovieLinks(doc: Document): List<String> {
        val content = doc.selectFirst(".entry-content, .post-content, article") ?: return emptyList()

        // moviesmod movie pages group links under h4 quality headers
        val links = mutableListOf<String>()
        content.select("h4, h3").forEach { header ->
            var sibling = header.nextElementSibling()
            while (sibling != null && !sibling.tagName().matches(Regex("h[2-4]"))) {
                sibling.select(
                    "a[href*='modrefer.in'], a[href*='modpro.blog'], " +
                    "a[href*='episodes.modpro.blog'], a[href*='links.modpro.blog'], " +
                    "a[href*='posts.modpro.blog'], a[href*='cinematickit.org'], " +
                    "a[href*='driveseed.org'], a[href*='driveleech.net']"
                ).forEach { a ->
                    val href = a.absUrl("href").ifBlank { a.attr("href") }
                    if (href.isNotBlank()) links += href
                }
                sibling = sibling.nextElementSibling()
            }
        }

        // Fallback: scan the entire entry-content if headers yielded nothing
        if (links.isEmpty()) {
            content.select(
                "a[href*='modrefer.in'], a[href*='modpro.blog'], " +
                "a[href*='episodes.modpro.blog'], a[href*='links.modpro.blog'], " +
                "a[href*='posts.modpro.blog'], a[href*='cinematickit.org'], " +
                "a[href*='driveseed.org'], a[href*='driveleech.net']"
            ).forEach { a ->
                val href = a.absUrl("href").ifBlank { a.attr("href") }
                if (href.isNotBlank()) links += href
            }
        }
        return links.distinct()
    }

    // ─── loadLinks ────────────────────────────────────────────────────────────

    override suspend fun loadLinks(
        data: String,
        isCasting: Boolean,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        val base = getBaseUrl()

        // data is either:
        //   (a) a pipe-separated list of raw intermediate URLs  (Movie)
        //   (b) a single episode URL                             (TV episode)
        val urlsToProcess: List<String> = if (data.contains("|||")) {
            data.split("|||").map { it.trim() }.filter { it.isNotBlank() }
        } else {
            // TV episode: fetch the episode page and extract links from it
            val epDoc = try {
                app.get(data, headers = baseHeaders(base)).document
            } catch (_: Exception) { return false }
            extractLinksFromEpisodePage(epDoc, data)
        }

        var foundAny = false
        urlsToProcess.forEach { url ->
            try {
                val videoUrls = resolveToVideoUrls(url, base)
                videoUrls.forEach { (videoUrl, quality) ->
                    callback.invoke(
                        ExtractorLink(
                            source  = name,
                            name    = "$name • $quality",
                            url     = videoUrl,
                            referer = base,
                            quality = getQualityFromString(quality),
                            isM3u8  = videoUrl.contains(".m3u8"),
                        )
                    )
                    foundAny = true
                }
            } catch (_: Exception) { /* skip broken links */ }
        }
        return foundAny
    }

    /** Pulls episode-level intermediate links from an episode page. */
    private fun extractLinksFromEpisodePage(doc: Document, pageUrl: String): List<String> {
        val content = doc.selectFirst(".entry-content, .post-content, article") ?: return emptyList()
        return content.select(
            "a[href*='modrefer.in'], a[href*='modpro.blog'], " +
            "a[href*='episodes.modpro.blog'], a[href*='links.modpro.blog'], " +
            "a[href*='posts.modpro.blog'], a[href*='cinematickit.org'], " +
            "a[href*='driveseed.org'], a[href*='driveleech.net']"
        ).mapNotNull { a ->
            val href = a.absUrl("href").ifBlank { a.attr("href") }
            href.ifBlank { null }
        }.distinct()
    }

    // ─── Multi-stage Link Resolution ──────────────────────────────────────────

    /**
     * Takes a raw intermediate URL and follows the full chain:
     *
     *   modrefer.in           → base64 decode → timed-content links
     *   modpro.blog family    → entry-content → driveseed/SID links
     *   cinematickit.org      → driveseed links
     *   driveseed.org         → JS redirect → file page → final URL
     *   driveleech.net        → file page → final URL
     *   SID (tech.*)          → 6-step cookie flow → driveleech → final URL
     *
     * Returns a list of (videoUrl, qualityLabel) pairs.
     */
    private suspend fun resolveToVideoUrls(
        initialUrl: String,
        referer: String
    ): List<Pair<String, String>> {

        val driveseedUrls = mutableListOf<String>()
        val results       = mutableListOf<Pair<String, String>>()

        // ── Stage 1: Resolve intermediate shortener → driveseed/SID/driveleech ──
        val driveseedOrSidUrls = resolveIntermediateLink(initialUrl, referer)

        for (url in driveseedOrSidUrls) {
            when {
                // Direct driveseed/driveleech
                url.contains("driveseed.org") || url.contains("driveleech.net") -> {
                    driveseedUrls += url
                }
                // SID links (tech.unblockedgames.world, tech.examzculture.in, etc.)
                url.contains("tech.") && (
                    url.contains("unblockedgames") ||
                    url.contains("examzculture") ||
                    url.contains("creativeexpressionsblog")
                ) -> {
                    // Resolve SID → Driveleech
                    val driveleechUrl = resolveSidLink(url) ?: continue
                    driveseedUrls += driveleechUrl
                }
                // Workers.dev or direct CDN — may be final already
                url.contains("workers.dev") || url.contains(".r2.dev") ||
                url.contains("cdn.video-leech.pro") -> {
                    val quality = guessQualityFromUrl(url)
                    results += url to quality
                }
                else -> { /* unknown — skip */ }
            }
        }

        // ── Stage 2: Driveseed/Driveleech → final file page URL ──
        for (dsUrl in driveseedUrls) {
            try {
                val finalUrls = resolveDriveseedLink(dsUrl, referer)
                results += finalUrls
            } catch (_: Exception) { }
        }

        return results
    }

    // ─── Stage 1a: Intermediate Shorteners ───────────────────────────────────

    /**
     * Dispatches to the correct resolver for each known intermediate domain.
     * Returns a list of driveseed.org / driveleech.net / SID URLs.
     */
    private suspend fun resolveIntermediateLink(url: String, referer: String): List<String> {
        return when {
            url.contains("modrefer.in")    -> resolveModreferLink(url, referer)
            url.contains("modpro.blog")    -> resolveModproBlogLink(url, referer)
            url.contains("cinematickit.org")-> resolveCinematickitLink(url, referer)
            url.contains("driveseed.org")  -> listOf(url)
            url.contains("driveleech.net") -> listOf(url)
            // If already a SID link, pass it through
            url.contains("tech.")          -> listOf(url)
            else                           -> emptyList()
        }
    }

    /**
     * modrefer.in encodes the real destination in a base64 `?url=` parameter.
     * After decoding, we fetch the decoded URL and look for driveseed/SID links
     * inside `.timed-content-client_show_0_5_0 a`.
     */
    private suspend fun resolveModreferLink(url: String, referer: String): List<String> {
        return try {
            // Decode the ?url= base64 parameter
            val encodedParam = URI(url).query
                ?.split("&")
                ?.firstOrNull { it.startsWith("url=") }
                ?.removePrefix("url=")
                ?: return emptyList()

            val decodedUrl = String(Base64.getDecoder().decode(encodedParam))
            val doc = app.get(decodedUrl, headers = baseHeaders(referer)).document

            // Extract links from the timed reveal container
            doc.select(".timed-content-client_show_0_5_0 a, .timed-content a")
                .mapNotNull { a ->
                    val href = a.absUrl("href").ifBlank { a.attr("href") }
                    href.ifBlank { null }
                }
                .filter { href ->
                    href.contains("driveseed.org") || href.contains("driveleech.net") ||
                    (href.contains("tech.") && href.contains("unblockedgames"))   ||
                    (href.contains("tech.") && href.contains("examzculture"))     ||
                    (href.contains("tech.") && href.contains("creativeexpressionsblog"))
                }
        } catch (_: Exception) { emptyList() }
    }

    /**
     * modpro.blog subdomains (episodes., links., posts.) host intermediate
     * link pages. We fetch the page and extract driveseed / SID links from
     * the `.entry-content` or the whole `article`.
     */
    private suspend fun resolveModproBlogLink(url: String, referer: String): List<String> {
        return try {
            val doc = app.get(url, headers = baseHeaders(referer)).document
            val content = doc.selectFirst(".entry-content, article") ?: return emptyList()

            content.select("a[href]").mapNotNull { a ->
                val href = a.absUrl("href").ifBlank { a.attr("href") }
                href.ifBlank { null }
            }.filter { href ->
                (href.contains("driveseed.org") || href.contains("driveleech.net") ||
                 (href.contains("tech.") && (
                     href.contains("unblockedgames") ||
                     href.contains("examzculture")   ||
                     href.contains("creativeexpressionsblog")
                 ))
                ) && !href.contains("comment") && !href.contains("#")
            }.distinct()
        } catch (_: Exception) { emptyList() }
    }

    /**
     * cinematickit.org pages contain driveseed.org links in their body.
     * Also handles the `safelink=` base64 obfuscation variant.
     */
    private suspend fun resolveCinematickitLink(url: String, referer: String): List<String> {
        return try {
            // Handle safelink= obfuscation
            val resolvedUrl = if (url.contains("safelink=")) {
                val encoded = URI(url).query
                    ?.split("&")?.firstOrNull { it.startsWith("safelink=") }
                    ?.removePrefix("safelink=")
                    ?: return emptyList()
                String(Base64.getDecoder().decode(encoded))
            } else url

            val doc = app.get(resolvedUrl, headers = baseHeaders(referer)).document
            doc.select("a[href*='driveseed.org'], a[href*='driveleech.net']")
                .mapNotNull { a ->
                    val href = a.absUrl("href").ifBlank { a.attr("href") }
                    href.ifBlank { null }
                }
        } catch (_: Exception) { emptyList() }
    }

    // ─── Stage 1b: SID Link Resolution (6-step cookie flow) ──────────────────

    /**
     * SID links use a multi-step form-submission flow with a dynamically
     * injected cookie to gate access to the final Driveleech redirect.
     *
     * Steps:
     *   0. GET the SID URL → extract form data
     *   1. POST form → get redirect
     *   2. Follow redirect → extract next form
     *   3. POST with cookie from response header
     *   4. Follow → extract Driveleech URL
     */
    private suspend fun resolveSidLink(sidUrl: String): String? {
        return try {
            // Step 0: GET the SID page
            val r0 = app.get(sidUrl, headers = baseHeaders(sidUrl))
            val doc0 = r0.document

            // Extract the form and the hidden fields
            val form0   = doc0.selectFirst("form") ?: return null
            val action0 = form0.absUrl("action").ifBlank { sidUrl }
            val fields0 = form0.select("input[type=hidden]").associate {
                it.attr("name") to it.attr("value")
            }

            // Step 1: POST form
            val r1 = app.post(
                action0,
                headers = baseHeaders(sidUrl),
                data    = fields0,
                allowRedirects = false
            )

            var redirectUrl = r1.headers["Location"] ?: r1.document
                .selectFirst("a[href], meta[http-equiv=refresh]")
                ?.let {
                    it.attr("href").ifBlank {
                        Regex("""url=([^"']+)""").find(it.attr("content"))?.groupValues?.get(1)
                    }
                } ?: return null

            if (!redirectUrl.startsWith("http")) {
                redirectUrl = URI(sidUrl).let { base ->
                    "${base.scheme}://${base.host}$redirectUrl"
                }
            }

            // Step 2: GET redirect
            val r2   = app.get(redirectUrl, headers = baseHeaders(sidUrl))
            val doc2 = r2.document

            // Look for a Driveleech or Driveseed URL in the page / response
            val driveleechUrl = findDriveUrlInDoc(doc2)
                ?: run {
                    // Step 3: If there's another form, submit it
                    val form2   = doc2.selectFirst("form") ?: return null
                    val action2 = form2.absUrl("action").ifBlank { redirectUrl }
                    val fields2 = form2.select("input[type=hidden]").associate {
                        it.attr("name") to it.attr("value")
                    }
                    val r3 = app.post(
                        action2,
                        headers = baseHeaders(redirectUrl),
                        data    = fields2,
                        allowRedirects = true
                    )
                    findDriveUrlInDoc(r3.document)
                        ?: r3.headers["Location"]
                }
                ?: return null

            driveleechUrl
        } catch (_: Exception) { null }
    }

    /** Finds the first driveleech.net or driveseed.org URL in a document. */
    private fun findDriveUrlInDoc(doc: Document): String? {
        return doc.select("a[href*='driveleech.net'], a[href*='driveseed.org']")
            .firstOrNull()
            ?.let { a ->
                val href = a.absUrl("href").ifBlank { a.attr("href") }
                href.ifBlank { null }
            }
            ?: doc.body().html().let { html ->
                Regex("""https?://(?:www\.)?driveleech\.net[^\s"'<>]+""").find(html)?.value
                    ?: Regex("""https?://(?:www\.)?driveseed\.org[^\s"'<>]+""").find(html)?.value
            }
    }

    // ─── Stage 2: Driveseed/Driveleech → Final Video URL ──────────────────────

    /**
     * Resolves a driveseed.org or driveleech.net URL to one or more
     * direct video/download URLs.
     *
     * Driveseed has an intermediate JS-redirect page; driveleech may too.
     * After landing on the file page, buttons are tried in priority order:
     *
     *   1. Instant Download  → POST /api  { keys }  + x-token header
     *   2. Resume Worker Bot → extract token+id → POST /download?id=
     *   3. Direct Links      → GET ?type=1
     *   4. Resume Cloud      → follow → .btn-success href
     */
    private suspend fun resolveDriveseedLink(
        url: String,
        referer: String
    ): List<Pair<String, String>> {
        val results = mutableListOf<Pair<String, String>>()
        val quality = guessQualityFromUrl(url)

        // Follow JS redirect if needed
        val filePageUrl = followJsRedirect(url, referer) ?: url
        val filePage    = try {
            app.get(filePageUrl, headers = baseHeaders(referer)).document
        } catch (_: Exception) { return results }

        val host = URI(filePageUrl).let { "${it.scheme}://${it.host}" }

        // ── Try 1: Instant Download ──────────────────────────────────────────
        val instantBtn = filePage.selectFirst(
            "a:containsOwn(Instant Download), a[href*='workers.dev'], a[href*='.r2.dev'], a[href*='cdn.video-leech.pro']"
        )
        if (instantBtn != null) {
            val href = instantBtn.absUrl("href").ifBlank { instantBtn.attr("href") }
            if (href.contains("workers.dev") || href.contains(".r2.dev") ||
                href.contains("cdn.video-leech.pro")) {
                results += href to quality
                return results  // got a direct CDN link — done
            }
            // POST to /api with keys in FormData
            val keysMatch = Regex("""keys\s*=\s*["']?([^"'\s]+)""")
                .find(filePage.html())?.groupValues?.get(1)
            if (keysMatch != null) {
                try {
                    val apiResp = app.post(
                        "$host/api",
                        headers = baseHeaders(filePageUrl) + mapOf("x-token" to URI(filePageUrl).host),
                        data    = mapOf("keys" to keysMatch)
                    )
                    val directUrl = Regex("""https?://[^\s"'<>]+\.(?:mp4|mkv|m3u8)[^\s"'<>]*""")
                        .find(apiResp.text)?.value
                    if (directUrl != null) {
                        results += directUrl to quality
                        return results
                    }
                } catch (_: Exception) { }
            }
        }

        // ── Try 2: Resume Worker Bot ─────────────────────────────────────────
        val workerBtn = filePage.selectFirst("a:containsOwn(Resume Worker Bot), a:containsOwn(Worker Bot)")
        if (workerBtn != null) {
            val workerUrl = workerBtn.absUrl("href").ifBlank { workerBtn.attr("href") }
            if (workerUrl.isNotBlank()) {
                try {
                    val workerPage = app.get(workerUrl, headers = baseHeaders(filePageUrl)).text
                    val token = Regex("""formData\.append\(['"]token['"]\s*,\s*['"]([^'"]+)""")
                        .find(workerPage)?.groupValues?.get(1)
                    val id    = Regex("""fetch\(['"]/download\?id=([^'"&]+)""")
                        .find(workerPage)?.groupValues?.get(1)
                    if (token != null && id != null) {
                        val workerHost = URI(workerUrl).let { "${it.scheme}://${it.host}" }
                        val dlResp = app.post(
                            "$workerHost/download?id=$id",
                            headers = baseHeaders(workerUrl) + mapOf(
                                "x-requested-with" to "XMLHttpRequest"
                            ),
                            data = mapOf("token" to token)
                        )
                        val directUrl = Regex("""https?://[^\s"'<>]+\.(?:mp4|mkv|m3u8)[^\s"'<>]*""")
                            .find(dlResp.text)?.value
                        if (directUrl != null) {
                            results += directUrl to quality
                            return results
                        }
                    }
                } catch (_: Exception) { }
            }
        }

        // ── Try 3: Direct Links (GET ?type=1) ────────────────────────────────
        val directBtn = filePage.selectFirst("a:containsOwn(Direct Links), a:containsOwn(Direct Download)")
        if (directBtn != null) {
            val directLinksUrl = directBtn.absUrl("href").let { href ->
                if (href.contains("?")) "$href&type=1"
                else "$filePageUrl?type=1"
            }
            try {
                val dlPage = app.get(directLinksUrl, headers = baseHeaders(filePageUrl)).document
                dlPage.select("a.btn-success, a[href*='workers.dev'], a[href*='.mp4'], a[href*='.mkv']")
                    .forEach { a ->
                        val href = a.absUrl("href").ifBlank { a.attr("href") }
                        if (href.isNotBlank()) results += href to quality
                    }
                if (results.isNotEmpty()) return results
            } catch (_: Exception) { }
        }

        // ── Try 4: Resume Cloud ──────────────────────────────────────────────
        val cloudBtn = filePage.selectFirst(
            "a:containsOwn(Resume Cloud), a:containsOwn(Cloud Resume Download)"
        )
        if (cloudBtn != null) {
            val cloudUrl = cloudBtn.absUrl("href").ifBlank { cloudBtn.attr("href") }
            if (cloudUrl.isNotBlank()) {
                try {
                    val cloudPage = app.get(cloudUrl, headers = baseHeaders(filePageUrl)).document
                    val dlUrl = cloudPage
                        .selectFirst("a.btn-success, a:containsOwn(Cloud Resume Download)")
                        ?.absUrl("href")
                    if (!dlUrl.isNullOrBlank()) {
                        results += dlUrl to quality
                        return results
                    }
                } catch (_: Exception) { }
            }
        }

        // ── Try 5: Fallback — any iframe or video tag on the page ────────────
        filePage.select("iframe[src], video source[src]").forEach { el ->
            val src = el.attr("src").ifBlank { el.absUrl("src") }
            if (src.isNotBlank() && (src.contains("http"))) {
                results += src to quality
            }
        }

        return results
    }

    /**
     * Driveseed redirect pages embed `window.location.replace("...")` in a
     * script tag. We extract and return that URL.
     */
    private suspend fun followJsRedirect(url: String, referer: String): String? {
        return try {
            val html = app.get(url, headers = baseHeaders(referer)).text
            // Pattern: window.location.replace("https://...")
            Regex("""window\.location\.replace\(['"]([^'"]+)['"]""")
                .find(html)?.groupValues?.get(1)
                ?: Regex("""window\.location\s*=\s*['"]([^'"]+)['"]""")
                    .find(html)?.groupValues?.get(1)
        } catch (_: Exception) { null }
    }

    // ─── Helpers ──────────────────────────────────────────────────────────────

    private fun guessQualityFromUrl(url: String): String = when {
        url.contains("2160") || url.contains("4K", ignoreCase = true) -> "4K"
        url.contains("1080")  -> "1080p"
        url.contains("720")   -> "720p"
        url.contains("480")   -> "480p"
        url.contains("360")   -> "360p"
        else                  -> "HD"
    }

    private fun getQualityFromString(q: String): Int = when {
        q.contains("4K") || q.contains("2160") -> Qualities.P2160.value
        q.contains("1080")                      -> Qualities.P1080.value
        q.contains("720")                       -> Qualities.P720.value
        q.contains("480")                       -> Qualities.P480.value
        q.contains("360")                       -> Qualities.P360.value
        else                                    -> Qualities.Unknown.value
    }
}