package com.wus.hdpornfull

import com.lagradost.cloudstream3.HomePageResponse
import com.lagradost.cloudstream3.LoadResponse
import com.lagradost.cloudstream3.MainAPI
import com.lagradost.cloudstream3.MainPageRequest
import com.lagradost.cloudstream3.SearchResponse
import com.lagradost.cloudstream3.SubtitleFile
import com.lagradost.cloudstream3.TvType
import com.lagradost.cloudstream3.app
import com.lagradost.cloudstream3.newHomePageResponse
import com.lagradost.cloudstream3.newMovieLoadResponse
import com.lagradost.cloudstream3.newMovieSearchResponse
import com.lagradost.cloudstream3.utils.ExtractorLink
import org.jsoup.nodes.Element
import java.net.URLEncoder

class HdpornfullProvider : MainAPI() {

    override var mainUrl = "https://hdpornfull.com"
    override var name = "HDPornFull"
    override var lang = "en"
    override val hasMainPage = true
    override val hasDownloadSupport = true
    override val supportedTypes = setOf(TvType.NSFW)
    override val hasQuickSearch = true

    // ------------------------------------------------------------------
    // SELECTORS — tune here when the theme shifts.
    // ------------------------------------------------------------------
    private object SEL {
        val CARD       = "div.thumb, div.item, article.post, div.video-block, div.card"
        val CARD_LINK  = "a[href]"
        val CARD_IMG   = "img"
        val CARD_TITLE = "h3, h2, .title"
        val CARD_DUR   = ".duration, .time, span.dur"
        val POSTER     = "meta[property=og:image]"
        val TITLE      = "meta[property=og:title]"
        val DESC       = "meta[property=og:description]"
        val VIDEO      = "video[src], video source[src], iframe[src]"
    }

    // ------------------------------------------------------------------
    // HOMEPAGE
    // ------------------------------------------------------------------
    override val mainPage = mainPageOf(
        "$mainUrl/?page={page}"          to "Latest",
        "$mainUrl/category/new/?page={page}" to "New",
        "$mainUrl/category/popular/?page={page}" to "Popular",
        "$mainUrl/category/trending/?page={page}" to "Trending"
    )

    override suspend fun getMainPage(page: Int, request: MainPageRequest): HomePageResponse {
        val url = request.data.replace("{page}", page.toString())
        val doc = app.get(url).document
        val items = doc.select(SEL.CARD).mapNotNull { it.toSearchResult() }.distinctBy { it.url }
        return newHomePageResponse(request.name, items)
    }

    // ------------------------------------------------------------------
    // CARD -> SEARCH RESULT
    // ------------------------------------------------------------------
    private fun Element.toSearchResult(): SearchResponse? {
        val a = if (tagName() == "a") this else selectFirst(SEL.CARD_LINK) ?: return null
        val href = a.attr("href").takeIf { it.isNotBlank() } ?: return null
        val fixedHref = if (href.startsWith("http")) href else mainUrl + href

        val img = selectFirst(SEL.CARD_IMG)
        val poster = img?.attr("data-src")?.takeIf { it.isNotBlank() }
            ?: img?.attr("data-original")?.takeIf { it.isNotBlank() }
            ?: img?.attr("src")

        val title = a.attr("title").takeIf { it.isNotBlank() }
            ?: selectFirst(SEL.CARD_TITLE)?.text()?.trim()?.takeIf { it.isNotBlank() }
            ?: img?.attr("alt")?.takeIf { it.isNotBlank() }
            ?: return null

        val quality = Regex("""(\d{3,4})p""").find(selectFirst(SEL.CARD_DUR)?.text() ?: "")?.groupValues?.get(1)?.toIntOrNull()

        return newMovieSearchResponse(title, fixedHref, TvType.NSFW) {
            this.posterUrl = poster?.let { if (it.startsWith("http")) it else mainUrl + it }
            this.quality = quality ?: com.lagradost.cloudstream3.utils.Qualities.Unknown.value
        }
    }

    // ------------------------------------------------------------------
    // SEARCH
    // ------------------------------------------------------------------
    override suspend fun search(query: String): List<SearchResponse> {
        val enc = URLEncoder.encode(query, "UTF-8")
        // try two common paths; first non-empty wins
        val paths = listOf("$mainUrl/?s=$enc", "$mainUrl/search/$enc")
        for (p in paths) {
            val doc = runCatching { app.get(p).document }.getOrNull() ?: continue
            val hits = doc.select(SEL.CARD).mapNotNull { it.toSearchResult() }.distinctBy { it.url }
            if (hits.isNotEmpty()) return hits
        }
        return emptyList()
    }

    // ------------------------------------------------------------------
    // LOAD (video page -> playable links)
    // ------------------------------------------------------------------
    override suspend fun load(url: String): LoadResponse {
        val doc = app.get(url).document

        val title = doc.selectFirst(SEL.TITLE)?.attr("content")?.trim()?.takeIf { it.isNotBlank() }
            ?: doc.selectFirst("h1")?.text()?.trim()
            ?: "Untitled"

        val poster = doc.selectFirst(SEL.POSTER)?.attr("content")?.let {
            if (it.startsWith("http")) it else mainUrl + it
        }

        val desc = doc.selectFirst(SEL.DESC)?.attr("content")
            ?: doc.selectFirst("div.description, div.desc, .entry-content")?.text()

        val tags = doc.select("a[href*=/category/], a[href*=/tag/]").map { it.text().trim() }
            .filter { it.isNotBlank() }.distinct().take(20)

        val year = Regex("""\b(19|20)\d{2}\b""").find(doc.text())?.value?.toIntOrNull()

        val durationMin = Regex("""(\d{1,2}):(\d{2})(?::(\d{2}))?""")
            .find(doc.text())
            ?.let { m ->
                val parts = m.groupValues.drop(1).filter { it.isNotEmpty() }.map { it.toIntOrNull() ?: 0 }
                when (parts.size) {
                    2 -> parts[0]  // mm:ss -> treat as ~1 min display; not critical
                    3 -> parts[0] * 60 + parts[1] // hh:mm:ss -> minutes
                    else -> null
                }
            }

        // Grab any direct source strings we can see without leaving the page.
        // The extractor re-scrapes if we hand it a page URL rather than a file URL.
        val dataUrl = url

        return newMovieLoadResponse(title, dataUrl, TvType.NSFW, dataUrl) {
            this.posterUrl = poster
            this.plot = desc
            this.tags = tags
            this.year = year
            this.duration = durationMin
        }
    }

    // ------------------------------------------------------------------
    // LIGHT BRIDGE — hands the extractor a URL, extractor hands back links
    // ------------------------------------------------------------------
    override suspend fun loadLinks(
        data: String,
        isCasting: Boolean,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        return try {
            HdpornfullExtractor().getUrl(data, mainUrl, subtitleCallback, callback)
            true
        } catch (t: Throwable) {
            false
        }
    }
}