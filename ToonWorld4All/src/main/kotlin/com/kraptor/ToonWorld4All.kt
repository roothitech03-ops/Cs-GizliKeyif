package com.kraptor

import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.utils.*
import com.lagradost.cloudstream3.utils.ExtractorLink
import org.jsoup.nodes.Element

// ─────────────────────────────────────────────────────────────────────────────
// ToonWorld4All CloudStream Provider
// Site  : https://toonworld4all.me
// Theme : WordPress (Gutenberg) + Cloudflare
// Lang  : Hindi / Tamil / Telugu / English / Japanese (Multi Audio)
//
// Watch Online section → <iframe src="dood.wf/e/..."> or similar
// Download section     → redirect/main.php → rocklinks → GDTot  (download only, not streaming)
//
// Key insight: iframes are the streamable links; redirect buttons are downloads.
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

    // Separator for bundling multiple video source URLs per episode
    private val SEP = "|||"

    // Known video hosts that loadExtractor can handle
    private val videoHosts = listOf(
        "dood", "ds2play", "dooood", "d000d",
        "streamtape", "filemoon", "mixdrop",
        "stream.sb", "sbplay", "embedrise",
        "ok.ru", "mp4upload", "rumble",
        "drive.google", "fembed", "streamlare",
        "upstream", "kwik", "vidcloud",
        "rapidvideo", "streamhub"
    )

    private fun isVideoHost(url: String) =
        url.startsWith("http") && videoHosts.any { url.contains(it) }

    // ─── Thumbnail helpers ─────────────────────────────────────────────────
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
        val url  = "$base/page/$page/"
        val doc  = fetchDoc(url)
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

        // ── Case A: page links to individual episode URLs (/episode/show-1x01) ─
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

        // ── Case B: all iframes + buttons inline, grouped by episode headings ─
        if (content == null) {
            return newMovieLoadResponse(title, url, TvType.AnimeMovie, url) {
                posterUrl = poster; this.plot = plot
            }
        }

        val episodeBlocks = parseEpisodeBlocks(content)

        if (episodeBlocks.isEmpty()) {
            // No episode structure — treat as movie/single entry
            // Collect all Watch Online iframes from the whole page
            val allIframes = content.select("iframe[src]")
                .map { it.attr("src").trim() }
                .filter { it.isNotBlank() }
                .distinct()

            val movieData = allIframes.joinToString(SEP).ifBlank { url }
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

    // ─── Parse inline episode blocks ───────────────────────────────────────
    // Scans Gutenberg content children for episode headings and the iframes /
    // links that follow each heading.
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

            // Episode heading detected
            if (match != null &&
                tagName in setOf("h1", "h2", "h3", "h4", "h5", "p", "div", "strong", "b")
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

            // Collect Watch Online iframes (primary streaming source)
            val iframes = child.select("iframe[src]")
                .map { it.attr("src").trim() }
                .filter { it.isNotBlank() }

            // Collect direct video host links in buttons
            val directVideoLinks = child
                .select("a.wp-block-button__link, .wp-block-button a, a[href]")
                .map { it.attr("href").trim() }
                .filter { isVideoHost(it) }

            val allFound = (iframes + directVideoLinks).distinct()

            if (allFound.isNotEmpty()) {
                if (currentNum == 0) { currentNum = 1; currentLabel = "Episode 1" }
                currentLinks.addAll(allFound)
            }
        }

        if (currentLinks.isNotEmpty())
            blocks.add(Triple(currentNum, currentLabel, currentLinks))

        return blocks.map { Triple(it.first, it.second, it.third.distinct()) }
    }

    // ─── loadLinks ──────────────────────────────────────────────────────────
    // data can be:
    //   1. SEP-joined iframe/video URLs   → from Case B inline blocks
    //   2. An episode page URL            → from Case A (fetch and find iframes)
    //   3. A direct series URL            → single movie fallback
    //
    // Strategy:
    //   a) Direct video host URL → loadExtractor
    //   b) Episode page URL      → fetch → find iframes → loadExtractor each
    //   c) Series page URL       → fetch → find iframes in whole page
    override suspend fun loadLinks(
        data: String,
        isCasting: Boolean,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ): Boolean {

        // ── Case 1: SEP-joined direct video URLs (stored from Case B) ─────
        if (data.contains(SEP)) {
            data.split(SEP).filter { it.isNotBlank() }.forEach { videoUrl ->
                if (isVideoHost(videoUrl)) {
                    try { loadExtractor(videoUrl, mainUrl, subtitleCallback, callback) }
                    catch (_: Exception) {}
                }
            }
            return true
        }

        // ── Case 2: Single direct video URL ──────────────────────────────
        if (isVideoHost(data)) {
            try { loadExtractor(data, mainUrl, subtitleCallback, callback) }
            catch (_: Exception) {}
            return true
        }

        // ── Case 3: Episode page URL or series page URL ───────────────────
        // Fetch the page and extract all iframe sources
        if (data.startsWith("http")) {
            try {
                val doc = fetchDoc(data)

                // Grab every iframe on the page
                val iframesFound = doc.select("iframe[src], iframe[data-src]")
                    .map { el ->
                        el.attr("src").ifBlank { el.attr("data-src") }.trim()
                    }
                    .filter { it.isNotBlank() }
                    .distinct()

                // Also grab any direct video host anchor links
                val directLinks = doc.select("a[href]")
                    .map { it.attr("href").trim() }
                    .filter { isVideoHost(it) }
                    .distinct()

                val allVideoSrcs = (iframesFound + directLinks).distinct()

                allVideoSrcs.forEach { src ->
                    try { loadExtractor(src, mainUrl, subtitleCallback, callback) }
                    catch (_: Exception) {}
                }

            } catch (_: Exception) {}
        }

        return true
    }
}
