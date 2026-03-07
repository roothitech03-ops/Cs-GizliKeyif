package com.kraptor

import com.lagradost.api.Log
import org.jsoup.nodes.Element
import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.utils.*
import com.lagradost.cloudstream3.LoadResponse.Companion.addActors

class XTapes : MainAPI() {
    override var mainUrl              = "https://xtapes.tw"
    override var name                 = "XTapes"
    override val hasMainPage          = true
    override var lang                 = "en"
    override val hasQuickSearch       = false
    override val supportedTypes       = setOf(TvType.NSFW)
    override val vpnStatus            = VPNStatus.MightBeNeeded

    override val mainPage = mainPageOf(
        "${mainUrl}/?filtre=date&cat=0" to "Latest Videos",
        "${mainUrl}/?filtre=views" to "Most Popular",
        "${mainUrl}/?filtre=rate" to "Top Rated",
        "${mainUrl}/?filtre=date&cat=1766" to "Full Movies",
        "${mainUrl}/4k-porn-104363/" to "4K",
        "${mainUrl}/721584/" to "Anal",
        "${mainUrl}/761395/" to "Asian",
        "${mainUrl}/236413/" to "Big Tits",
        "${mainUrl}/blowjob-oral-cok-sucking-pussy-licking-45105/" to "Blowjob",
        "${mainUrl}/creampie-cum-inside-356185/" to "Creampie",
        "${mainUrl}/lesbian-porn-videos-047434/" to "Lesbian",
        "${mainUrl}/milf-mom-hd-porn-438706/" to "MILFs",
        "${mainUrl}/hd-teen-porn-videos-126957/" to "Teen",
        "${mainUrl}/0647848/" to "Brazzers",
        "${mainUrl}/476126/" to "BangBros",
        "${mainUrl}/716908/" to "Reality Kings",
        "${mainUrl}/584379/" to "Vixen",
        "${mainUrl}/716908/" to "Blacked",
        "${mainUrl}/495429/" to "Tushy",
    )

    override suspend fun getMainPage(page: Int, request: MainPageRequest): HomePageResponse {
        val url = if (page == 1) {
            request.data
        } else {
            val baseUrl = request.data
            if (baseUrl.contains("?")) {
                // For filter-based URLs like /?filtre=date&cat=0
                val parts = baseUrl.split("?", limit = 2)
                "${parts[0]}page/${page}/?${parts[1]}"
            } else {
                // For category/network URLs like /721584/
                if (baseUrl.endsWith("/")) {
                    "${baseUrl}page/${page}/"
                } else {
                    "${baseUrl}/page/${page}/"
                }
            }
        }
        val document = app.get(url).document
        val home = document.select("ul.listing-tube li.border-radius-5").mapNotNull { it.toSearchResult() }
        return newHomePageResponse(request.name, home, hasNext = home.isNotEmpty())
    }

    private fun Element.toSearchResult(): SearchResponse? {
        val anchor = this.selectFirst("a[title]") ?: return null
        val title = anchor.attr("title").ifEmpty { 
            this.selectFirst("h3")?.text() ?: this.selectFirst("strong")?.text()
        } ?: return null
        val href = fixUrlNull(anchor.attr("href")) ?: return null
        val posterUrl = fixUrlNull(
            this.selectFirst("img")?.attr("src")
        )
        return newMovieSearchResponse(title.trim(), href, TvType.NSFW) {
            this.posterUrl = posterUrl
        }
    }

    override suspend fun search(query: String): List<SearchResponse> {
        val searchUrl = "${mainUrl}/?s=${query}"
        val document = app.get(searchUrl).document
        return document.select("ul.listing-tube li.border-radius-5").mapNotNull { it.toSearchResult() }
    }

    override suspend fun quickSearch(query: String): List<SearchResponse>? = search(query)

    override suspend fun load(url: String): LoadResponse? {
        val document = app.get(url).document
        val title = document.selectFirst("h1")?.text()?.trim() ?: return null
        val poster = fixUrlNull(
            document.selectFirst("meta[itemprop=thumbnailUrl]")?.attr("content")
                ?: document.selectFirst("meta[property=og:image]")?.attr("content")
        )
        val description = document.selectFirst("div.infotext")?.text()?.trim()
            ?: document.selectFirst("span[itemprop=description]")?.attr("content")?.trim()
            ?: document.selectFirst("meta[property=og:description]")?.attr("content")?.trim()

        // Tags: a[rel=tag] without /hd-porn- in href are categories
        // Actors: a[rel=tag] with /hd-porn- in href are actors/models
        val allTags = document.select("a[rel=tag]")
        val tags = allTags.filter { !it.attr("href").contains("hd-porn-") }
            .map { it.text().trim() }
            .filter { it.isNotEmpty() }
        val actors = allTags.filter { it.attr("href").contains("hd-porn-") }
            .map { Actor(it.text().trim(), null) }

        return newMovieLoadResponse(title, url, TvType.NSFW, url) {
            this.posterUrl = poster
            this.plot = description
            this.tags = tags
            addActors(actors)
        }
    }

    override suspend fun loadLinks(
        data: String,
        isCasting: Boolean,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        val document = app.get(data).document

        // Extract video from iframes
        document.select("iframe[src]").forEach { iframe ->
            val src = iframe.attr("src")
            if (src.isNotEmpty() && !src.contains("recaptcha") && !src.contains("twitter") && !src.contains("izerv") && !src.contains("timbuk") && src != "javascript:false") {
                try {
                    loadExtractor(fixUrl(src), data, subtitleCallback, callback)
                } catch (e: Exception) {
                    Log.e("XTapes", "Error loading extractor for $src: ${e.message}")
                }
            }
        }

        // Also try to find direct video URLs in page source as fallback
        val pageHtml = document.html()
        val patterns = listOf(
            Regex("""video_url['"]?\s*[:=]\s*['"]([^'"]+)['"]"""),
            Regex("""source['"]?\s*[:=]\s*['"]([^'"]+\.mp4[^'"]*)['"]"""),
            Regex("""file['"]?\s*[:=]\s*['"]([^'"]+\.mp4[^'"]*)['"]"""),
            Regex("""(https?://[^\s'"<>]+\.m3u8[^\s'"<>]*)""")
        )

        for (pattern in patterns) {
            val match = pattern.find(pageHtml)
            if (match != null) {
                val videoUrl = match.groupValues[1].replace("\\/", "/")
                if (videoUrl.contains(".mp4") || videoUrl.contains(".m3u8")) {
                    val linkType = if (videoUrl.contains(".m3u8")) ExtractorLinkType.M3U8 else ExtractorLinkType.VIDEO
                    callback.invoke(
                        newExtractorLink(this.name, this.name, videoUrl, linkType) {
                            this.referer = "$mainUrl/"
                            this.quality = Qualities.Unknown.value
                        }
                    )
                    return true
                }
            }
        }

        return true
    }
}
