package com.kraptor

import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.utils.*
import org.jsoup.nodes.Document
import java.net.URI
import java.util.Base64

/*
 * ============================================================
 *  MoviesMod CloudStream Plugin  —  Fixed v2
 * ============================================================
 *
 *  COMPILE FIXES applied over v1:
 *
 *  1. Package changed  → com.kraptor
 *     (matched from GitHub Actions file path in build log)
 *
 *  2. newHomePageResponse() API fixed:
 *       OLD (broken): newHomePageResponse(request.name, items, hasNextPage = ...)
 *       NEW (correct): newHomePageResponse(request, items, hasNext = ...)
 *       - First arg must be MainPageRequest, not String
 *       - Named param is 'hasNext', not 'hasNextPage'
 *
 *  3. ExtractorLink constructor deprecated:
 *       OLD: ExtractorLink(source, name, url, referer, quality, isM3u8)
 *       NEW: newExtractorLink(source, name, url, referer, quality, isM3u8)
 *
 *  4. app.head() removed — not available in this CloudStream version.
 *     Domain verification now uses a try-catch app.get() instead.
 *
 *  FUNCTIONAL FIXES (from v1, unchanged):
 *  - Full modrefer.in → base64 decode → timed-content chain
 *  - Full modpro.blog family handler
 *  - Full cinematickit.org handler
 *  - Driveseed JS window.location.replace() follower
 *  - Driveseed file page: Instant Download / Worker Bot /
 *    Direct Links / Resume Cloud buttons
 *  - Dynamic domain resolution with 4-hour cache + fallback
 *  - Correct Referer header at every request hop
 * ============================================================
 */

class MoviesMod : MainAPI() {

    override var mainUrl = "https://moviesmod.farm"
    override var name    = "MoviesMod"
    override val hasMainPage      = true
    override var lang             = "en"
    override val hasDownloadSupport = true
    override val supportedTypes   = setOf(
        TvType.Movie,
        TvType.TvSeries,
        TvType.Anime,
    )

    // ─── Domain Resolution ────────────────────────────────────────────────────

    private val fallbackDomains = listOf(
        "https://moviesmod.farm",
        "https://moviesmod.build",
        "https://moviesmod.bond",
        "https://moviesmod.uno",
    )

    private val domainJsonUrl =
        "https://raw.githubusercontent.com/phisher98/TVVVV/refs/heads/main/domains.json"

    private var resolvedDomain  = mainUrl
    private var domainFetchedAt = 0L
    private val domainCacheTtl  = 4 * 60 * 60 * 1000L // 4 hours

    private suspend fun getBaseUrl(): String {
        val now = System.currentTimeMillis()
        if (now - domainFetchedAt < domainCacheTtl) return resolvedDomain

        return try {
            val json = app.get(domainJsonUrl, headers = baseHeaders())
                .parsedSafe<Map<String, String>>()
            val candidate = json?.get("moviesmod")?.trimEnd('/')
            if (!candidate.isNullOrBlank()) {
                // Verify the domain is reachable (no app.head — use GET with timeout)
                try {
                    val check = app.get(candidate, headers = baseHeaders(), timeout = 10_000L)
                    if (check.isSuccessful) resolvedDomain = candidate
                } catch (_: Exception) { /* keep current */ }
            }
            domainFetchedAt = now
            resolvedDomain
        } catch (_: Exception) {
            resolvedDomain
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
        "/"            to "Latest",
        "/movies/"     to "Movies",
        "/web-series/" to "Web Series",
        "/anime/"      to "Anime",
    )

    override suspend fun getMainPage(page: Int, request: MainPageRequest): HomePageResponse {
        val base    = getBaseUrl()
        val pageUrl = if (page == 1) "$base${request.data}"
                      else "$base${request.data}page/$page/"
        val doc     = app.get(pageUrl, headers = baseHeaders(base)).document
        val items   = parseSearchResults(doc, base)
        // FIX #1: pass `request` (not request.name) + `hasNext` (not hasNextPage)
        return newHomePageResponse(request, items, hasNext = items.isNotEmpty())
    }

    // ─── Search ───────────────────────────────────────────────────────────────

    override suspend fun search(query: String): List<SearchResponse> {
        val base = getBaseUrl()
        val url  = "$base/?s=${query.replace(" ", "+")}"
        val doc  = app.get(url, headers = baseHeaders(base)).document
        return parseSearchResults(doc, base)
    }

    private fun parseSearchResults(doc: Document, base: String): List<SearchResponse> {
        val articles = doc.select(
            ".latestPost, article.gridlove-post, .post-box, .movies-list article"
        )
        return articles.mapNotNull { el ->
            val a     = el.selectFirst("a[href]") ?: return@mapNotNull null
            val href  = a.absUrl("href").ifBlank { "$base${a.attr("href")}" }
            val title = el.selectFirst("h2, h3, .post-title, .entry-title, img[alt]")
                ?.let { it.attr("alt").ifBlank { it.text() } }
                ?: a.attr("title").ifBlank { a.text() }
            val poster = el.selectFirst("img")?.attr("src")

            val isSeries = title.contains(
                Regex("(?i)season|series|s\\d{1,2}e\\d{1,2}|episode")
            )
            if (isSeries)
                newTvSeriesSearchResponse(title.trim(), href, TvType.TvSeries) {
                    this.posterUrl = poster
                }
            else
                newMovieSearchResponse(title.trim(), href, TvType.Movie) {
                    this.posterUrl = poster
                }
        }
    }

    // ─── Load ─────────────────────────────────────────────────────────────────

    override suspend fun load(url: String): LoadResponse {
        val base = getBaseUrl()
        val doc  = app.get(url, headers = baseHeaders(base)).document

        val title  = doc.selectFirst("h1.entry-title, h1.post-title, h1")
                        ?.text()?.trim() ?: "Unknown"
        val poster = doc.selectFirst(
            ".post-thumbnail img, .entry-image img, article img"
        )?.attr("src")
        val plot   = doc.selectFirst(".entry-content p, .post-content p")?.text()?.trim()
        val year   = Regex("""(\d{4})""").find(title)?.value?.toIntOrNull()

        val isSeries = doc.select("h3, h4").any {
            it.text().contains(Regex("(?i)season\\s*\\d"))
        }

        return if (isSeries) {
            val episodes = mutableListOf<Episode>()
            var autoSeason = 1

            doc.select("h3, h4").forEach { header ->
                val headerText = header.text()
                if (!headerText.contains(Regex("(?i)season\\s*\\d"))) return@forEach
                val sNum = Regex("""(?i)season\s*(\d+)""")
                    .find(headerText)?.groupValues?.get(1)?.toIntOrNull() ?: autoSeason++

                var sibling = header.nextElementSibling()
                while (sibling != null &&
                       !sibling.tagName().matches(Regex("h[2-4]"))) {
                    sibling.select(
                        "a.maxbutton-episode-links, a.maxbutton-episode, " +
                        "a[href*='modrefer.in'], a[href*='modpro.blog'], " +
                        "a[href*='episodes.modpro.blog'], " +
                        "a[href*='links.modpro.blog'], " +
                        "a[href*='posts.modpro.blog'], " +
                        "a[href*='cinematickit.org']"
                    ).forEachIndexed { idx, a ->
                        val epText = a.text().trim()
                        if (epText.contains(Regex("(?i)batch|zip|pack"))) return@forEachIndexed
                        val epNum = Regex("""(?i)ep(?:isode)?\s*(\d+)""")
                            .find(epText)?.groupValues?.get(1)?.toIntOrNull() ?: (idx + 1)
                        val epUrl = a.absUrl("href").ifBlank { a.attr("href") }
                        if (epUrl.isBlank()) return@forEachIndexed
                        episodes += newEpisode(epUrl) {
                            this.name    = "S${sNum.toString().padStart(2,'0')}" +
                                           "E${epNum.toString().padStart(2,'0')} $epText"
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
            val links = collectMovieLinks(doc)
            newMovieLoadResponse(title, url, TvType.Movie, links.joinToString("|||")) {
                this.posterUrl = poster
                this.plot      = plot
                this.year      = year
            }
        }
    }

    private fun collectMovieLinks(doc: Document): List<String> {
        val content = doc.selectFirst(
            ".entry-content, .post-content, article"
        ) ?: return emptyList()

        val links = mutableListOf<String>()
        content.select("h4, h3").forEach { header ->
            var sibling = header.nextElementSibling()
            while (sibling != null &&
                   !sibling.tagName().matches(Regex("h[2-4]"))) {
                sibling.select(INTERMEDIATE_SELECTOR).forEach { a ->
                    val href = a.absUrl("href").ifBlank { a.attr("href") }
                    if (href.isNotBlank()) links += href
                }
                sibling = sibling.nextElementSibling()
            }
        }
        if (links.isEmpty()) {
            content.select(INTERMEDIATE_SELECTOR).forEach { a ->
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
        val urlsToProcess: List<String> = if (data.contains("|||")) {
            data.split("|||").map { it.trim() }.filter { it.isNotBlank() }
        } else {
            // TV episode page — extract links from it
            val epDoc = try {
                app.get(data, headers = baseHeaders(base)).document
            } catch (_: Exception) { return false }
            extractLinksFromEpisodePage(epDoc)
        }

        var foundAny = false
        urlsToProcess.forEach { url ->
            try {
                resolveToVideoUrls(url, base).forEach { (videoUrl, quality) ->
                    // FIX #3: use newExtractorLink instead of ExtractorLink(...)
                    callback.invoke(
                        newExtractorLink(
                            source  = name,
                            name    = "$name • $quality",
                            url     = videoUrl,
                            referer = base,
                            quality = getQualityInt(quality),
                            isM3u8  = videoUrl.contains(".m3u8"),
                        )
                    )
                    foundAny = true
                }
            } catch (_: Exception) { }
        }
        return foundAny
    }

    private fun extractLinksFromEpisodePage(doc: Document): List<String> {
        val content = doc.selectFirst(
            ".entry-content, .post-content, article"
        ) ?: return emptyList()
        return content.select(INTERMEDIATE_SELECTOR).mapNotNull { a ->
            val href = a.absUrl("href").ifBlank { a.attr("href") }
            href.ifBlank { null }
        }.distinct()
    }

    // ─── Multi-stage Resolution ───────────────────────────────────────────────

    private suspend fun resolveToVideoUrls(
        initialUrl: String,
        referer: String
    ): List<Pair<String, String>> {
        val driveseedUrls = mutableListOf<String>()
        val results       = mutableListOf<Pair<String, String>>()

        val stage1 = resolveIntermediateLink(initialUrl, referer)

        for (url in stage1) {
            when {
                url.contains("driveseed.org") ||
                url.contains("driveleech.net") -> driveseedUrls += url

                url.contains("tech.") && (
                    url.contains("unblockedgames") ||
                    url.contains("examzculture")   ||
                    url.contains("creativeexpressionsblog")
                ) -> resolveSidLink(url)?.let { driveseedUrls += it }

                url.contains("workers.dev") ||
                url.contains(".r2.dev")     ||
                url.contains("cdn.video-leech.pro") ->
                    results += url to guessQualityFromUrl(url)

                else -> { /* unknown — skip */ }
            }
        }

        for (dsUrl in driveseedUrls) {
            try { results += resolveDriveseedLink(dsUrl, referer) }
            catch (_: Exception) { }
        }
        return results
    }

    private suspend fun resolveIntermediateLink(url: String, referer: String): List<String> {
        return when {
            url.contains("modrefer.in")       -> resolveModreferLink(url, referer)
            url.contains("modpro.blog")        -> resolveModproBlogLink(url, referer)
            url.contains("cinematickit.org")   -> resolveCinematickitLink(url, referer)
            url.contains("driveseed.org")      -> listOf(url)
            url.contains("driveleech.net")     -> listOf(url)
            url.contains("tech.")              -> listOf(url)
            else                               -> emptyList()
        }
    }

    /** modrefer.in: decode base64 ?url= param, fetch page, extract timed links */
    private suspend fun resolveModreferLink(url: String, referer: String): List<String> {
        return try {
            val encodedParam = URI(url).query
                ?.split("&")
                ?.firstOrNull { it.startsWith("url=") }
                ?.removePrefix("url=")
                ?: return emptyList()
            val decodedUrl = String(Base64.getDecoder().decode(encodedParam))
            val doc = app.get(decodedUrl, headers = baseHeaders(referer)).document
            doc.select(".timed-content-client_show_0_5_0 a, .timed-content a")
                .mapNotNull { a ->
                    val href = a.absUrl("href").ifBlank { a.attr("href") }
                    href.ifBlank { null }
                }.filter { isDriveOrSidUrl(it) }
        } catch (_: Exception) { emptyList() }
    }

    /** modpro.blog / episodes. / links. / posts.: scan .entry-content */
    private suspend fun resolveModproBlogLink(url: String, referer: String): List<String> {
        return try {
            val doc = app.get(url, headers = baseHeaders(referer)).document
            val content = doc.selectFirst(".entry-content, article") ?: return emptyList()
            content.select("a[href]").mapNotNull { a ->
                val href = a.absUrl("href").ifBlank { a.attr("href") }
                href.ifBlank { null }
            }.filter { isDriveOrSidUrl(it) && !it.contains("comment") && !it.contains("#") }
             .distinct()
        } catch (_: Exception) { emptyList() }
    }

    /** cinematickit.org: handle optional safelink= base64, extract driveseed links */
    private suspend fun resolveCinematickitLink(url: String, referer: String): List<String> {
        return try {
            val resolvedUrl = if (url.contains("safelink=")) {
                val encoded = URI(url).query
                    ?.split("&")
                    ?.firstOrNull { it.startsWith("safelink=") }
                    ?.removePrefix("safelink=")
                    ?: return emptyList()
                String(Base64.getDecoder().decode(encoded))
            } else url
            val doc = app.get(resolvedUrl, headers = baseHeaders(referer)).document
            doc.select(
                "a[href*='driveseed.org'], a[href*='driveleech.net']"
            ).mapNotNull { a ->
                val href = a.absUrl("href").ifBlank { a.attr("href") }
                href.ifBlank { null }
            }
        } catch (_: Exception) { emptyList() }
    }

    /** SID links: 3-step form + cookie flow → Driveleech URL */
    private suspend fun resolveSidLink(sidUrl: String): String? {
        return try {
            // Step 0: GET the SID page
            val r0   = app.get(sidUrl, headers = baseHeaders(sidUrl))
            val doc0 = r0.document
            val form0    = doc0.selectFirst("form") ?: return null
            val action0  = form0.absUrl("action").ifBlank { sidUrl }
            val fields0  = form0.select("input[type=hidden]")
                .associate { it.attr("name") to it.attr("value") }

            // Step 1: POST form
            val r1 = app.post(
                action0,
                headers        = baseHeaders(sidUrl),
                data           = fields0,
                allowRedirects = false
            )
            var redirectUrl = r1.headers["Location"]
                ?: r1.document.selectFirst("a[href]")?.absUrl("href")
                ?: return null
            if (!redirectUrl.startsWith("http")) {
                val base = URI(sidUrl)
                redirectUrl = "${base.scheme}://${base.host}$redirectUrl"
            }

            // Step 2: GET redirect page
            val r2   = app.get(redirectUrl, headers = baseHeaders(sidUrl))
            val doc2 = r2.document

            // Look for drive URL directly
            findDriveUrlInDoc(doc2) ?: run {
                val form2   = doc2.selectFirst("form") ?: return null
                val action2 = form2.absUrl("action").ifBlank { redirectUrl }
                val fields2 = form2.select("input[type=hidden]")
                    .associate { it.attr("name") to it.attr("value") }
                val r3 = app.post(
                    action2,
                    headers        = baseHeaders(redirectUrl),
                    data           = fields2,
                    allowRedirects = true
                )
                findDriveUrlInDoc(r3.document) ?: r3.headers["Location"]
            }
        } catch (_: Exception) { null }
    }

    private fun findDriveUrlInDoc(doc: Document): String? {
        return doc.select(
            "a[href*='driveleech.net'], a[href*='driveseed.org']"
        ).firstOrNull()?.let { a ->
            a.absUrl("href").ifBlank { a.attr("href") }.ifBlank { null }
        } ?: doc.body().html().let { html ->
            Regex("""https?://(?:www\.)?driveleech\.net[^\s"'<>]+""").find(html)?.value
                ?: Regex("""https?://(?:www\.)?driveseed\.org[^\s"'<>]+""").find(html)?.value
        }
    }

    // ─── Driveseed / Driveleech → Final URL ──────────────────────────────────

    private suspend fun resolveDriveseedLink(
        url: String,
        referer: String
    ): List<Pair<String, String>> {
        val results = mutableListOf<Pair<String, String>>()
        val quality = guessQualityFromUrl(url)

        val filePageUrl = followJsRedirect(url, referer) ?: url
        val filePage = try {
            app.get(filePageUrl, headers = baseHeaders(referer)).document
        } catch (_: Exception) { return results }
        val host = URI(filePageUrl).let { "${it.scheme}://${it.host}" }

        // ── 1. Instant Download ──────────────────────────────────────────────
        filePage.selectFirst(
            "a:containsOwn(Instant Download), " +
            "a[href*='workers.dev'], a[href*='.r2.dev'], " +
            "a[href*='cdn.video-leech.pro']"
        )?.let { btn ->
            val href = btn.absUrl("href").ifBlank { btn.attr("href") }
            if (href.contains("workers.dev") || href.contains(".r2.dev") ||
                href.contains("cdn.video-leech.pro")) {
                results += href to quality
                return results
            }
            val keysMatch = Regex("""keys\s*=\s*["']?([^"'\s,;]+)""")
                .find(filePage.html())?.groupValues?.get(1)
            if (keysMatch != null) {
                try {
                    val apiResp = app.post(
                        "$host/api",
                        headers = baseHeaders(filePageUrl) + mapOf(
                            "x-token" to URI(filePageUrl).host
                        ),
                        data = mapOf("keys" to keysMatch)
                    )
                    Regex("""https?://[^\s"'<>]+\.(?:mp4|mkv|m3u8)[^\s"'<>]*""")
                        .find(apiResp.text)?.value?.let { directUrl ->
                            results += directUrl to quality
                            return results
                        }
                } catch (_: Exception) { }
            }
        }

        // ── 2. Resume Worker Bot ─────────────────────────────────────────────
        filePage.selectFirst(
            "a:containsOwn(Resume Worker Bot), a:containsOwn(Worker Bot)"
        )?.let { btn ->
            val workerUrl = btn.absUrl("href").ifBlank { btn.attr("href") }
            if (workerUrl.isNotBlank()) {
                try {
                    val workerPage = app.get(workerUrl, headers = baseHeaders(filePageUrl)).text
                    val token = Regex("""formData\.append\(['"]token['"]\s*,\s*['"]([^'"]+)""")
                        .find(workerPage)?.groupValues?.get(1)
                    val id    = Regex("""fetch\(['"]/download\?id=([^'"&]+)""")
                        .find(workerPage)?.groupValues?.get(1)
                    if (token != null && id != null) {
                        val wHost = URI(workerUrl).let { "${it.scheme}://${it.host}" }
                        val dlResp = app.post(
                            "$wHost/download?id=$id",
                            headers = baseHeaders(workerUrl) + mapOf(
                                "x-requested-with" to "XMLHttpRequest"
                            ),
                            data = mapOf("token" to token)
                        )
                        Regex("""https?://[^\s"'<>]+\.(?:mp4|mkv|m3u8)[^\s"'<>]*""")
                            .find(dlResp.text)?.value?.let { directUrl ->
                                results += directUrl to quality
                                return results
                            }
                    }
                } catch (_: Exception) { }
            }
        }

        // ── 3. Direct Links (GET ?type=1) ────────────────────────────────────
        filePage.selectFirst(
            "a:containsOwn(Direct Links), a:containsOwn(Direct Download)"
        )?.let { _ ->
            try {
                val dlPage = app.get(
                    "$filePageUrl?type=1",
                    headers = baseHeaders(filePageUrl)
                ).document
                dlPage.select(
                    "a.btn-success, a[href*='workers.dev'], a[href*='.mp4'], a[href*='.mkv']"
                ).forEach { a ->
                    val href = a.absUrl("href").ifBlank { a.attr("href") }
                    if (href.isNotBlank()) results += href to quality
                }
                if (results.isNotEmpty()) return results
            } catch (_: Exception) { }
        }

        // ── 4. Resume Cloud ──────────────────────────────────────────────────
        filePage.selectFirst(
            "a:containsOwn(Resume Cloud), a:containsOwn(Cloud Resume Download)"
        )?.let { btn ->
            val cloudUrl = btn.absUrl("href").ifBlank { btn.attr("href") }
            if (cloudUrl.isNotBlank()) {
                try {
                    val cloudPage = app.get(cloudUrl, headers = baseHeaders(filePageUrl)).document
                    cloudPage.selectFirst(
                        "a.btn-success, a:containsOwn(Cloud Resume Download)"
                    )?.absUrl("href")?.ifBlank { null }?.let { dlUrl ->
                        results += dlUrl to quality
                        return results
                    }
                } catch (_: Exception) { }
            }
        }

        // ── 5. Fallback: iframe / video src on the page ──────────────────────
        filePage.select("iframe[src], video source[src]").forEach { el ->
            val src = el.absUrl("src").ifBlank { el.attr("src") }
            if (src.startsWith("http")) results += src to quality
        }

        return results
    }

    /** Follow JS window.location.replace("...") to real file page. */
    private suspend fun followJsRedirect(url: String, referer: String): String? {
        return try {
            val html = app.get(url, headers = baseHeaders(referer)).text
            Regex("""window\.location\.replace\(['"]([^'"]+)['"]""")
                .find(html)?.groupValues?.get(1)
                ?: Regex("""window\.location\s*=\s*['"]([^'"]+)['"]""")
                    .find(html)?.groupValues?.get(1)
        } catch (_: Exception) { null }
    }

    // ─── Helpers ──────────────────────────────────────────────────────────────

    private fun isDriveOrSidUrl(url: String): Boolean =
        url.contains("driveseed.org")  ||
        url.contains("driveleech.net") ||
        (url.contains("tech.") && (
            url.contains("unblockedgames")        ||
            url.contains("examzculture")           ||
            url.contains("creativeexpressionsblog")
        ))

    private fun guessQualityFromUrl(url: String): String = when {
        url.contains("2160") || url.contains("4K", ignoreCase = true) -> "4K"
        url.contains("1080") -> "1080p"
        url.contains("720")  -> "720p"
        url.contains("480")  -> "480p"
        url.contains("360")  -> "360p"
        else                 -> "HD"
    }

    private fun getQualityInt(q: String): Int = when {
        q.contains("4K") || q.contains("2160") -> Qualities.P2160.value
        q.contains("1080")                      -> Qualities.P1080.value
        q.contains("720")                       -> Qualities.P720.value
        q.contains("480")                       -> Qualities.P480.value
        q.contains("360")                       -> Qualities.P360.value
        else                                    -> Qualities.Unknown.value
    }

    companion object {
        private const val INTERMEDIATE_SELECTOR =
            "a[href*='modrefer.in'], a[href*='modpro.blog'], " +
            "a[href*='episodes.modpro.blog'], a[href*='links.modpro.blog'], " +
            "a[href*='posts.modpro.blog'], a[href*='cinematickit.org'], " +
            "a[href*='driveseed.org'], a[href*='driveleech.net']"
    }
}