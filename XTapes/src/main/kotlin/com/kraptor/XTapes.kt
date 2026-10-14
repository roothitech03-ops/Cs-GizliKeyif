package com.kraptor

import com.lagradost.api.Log
import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.LoadResponse.Companion.addActors
import com.lagradost.cloudstream3.utils.*
import kotlinx.coroutines.CancellationException
import org.jsoup.nodes.Document
import org.jsoup.nodes.Element
import java.net.URI
import java.net.URLEncoder

class XTapes : MainAPI() {
    // The site now serves its canonical pages from xtapes.la; the old .tw host redirects here.
    override var mainUrl = "https://xtapes.la"
    override var name = "XTapes"
    override val hasMainPage = true
    override var lang = "en"
    override val hasQuickSearch = true
    override val supportedTypes = setOf(TvType.NSFW)
    override val vpnStatus = VPNStatus.MightBeNeeded

    // The site rejects requests without browser-like headers (HTTP 403).
    private val browserHeaders = mapOf(
        "User-Agent" to "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/126.0.0.0 Safari/537.36",
        "Accept" to "text/html,application/xhtml+xml,application/xml;q=0.9,*/*;q=0.8",
        "Accept-Language" to "en-US,en;q=0.9"
    )

    // Entries are kept only where the current route was verified to resolve to that listing.
    override val mainPage = mainPageOf(
        "$mainUrl/?filtre=date&cat=0" to "Latest Videos",
        "$mainUrl/?filtre=views" to "Most Popular",
        "$mainUrl/?filtre=rate" to "Top Rated",
        "$mainUrl/porn-movies-hd/?filtre=date" to "Full Movies",
        "$mainUrl/4k-porn-104363/" to "4K",
        "$mainUrl/796584/" to "Anal",
        "$mainUrl/761395/" to "Asian",
        "$mainUrl/792684/" to "Big Tits",
        "$mainUrl/blowjob-oral-cok-sucking-pussy-licking-45105/" to "Blowjob",
        "$mainUrl/creampie-cum-inside-352895/" to "Creampie",
        "$mainUrl/lesbian-porn-videos-047434/" to "Lesbian",
        "$mainUrl/0647848/" to "Brazzers",
        "$mainUrl/476126/" to "BangBros",
        "$mainUrl/716908/" to "Blacked",
        "$mainUrl/584379/" to "MYLF",
    )

    override suspend fun getMainPage(page: Int, request: MainPageRequest): HomePageResponse {
        val url = if (page <= 1) {
            request.data
        } else {
            val baseUrl = request.data
            val queryIndex = baseUrl.indexOf('?')
            val path = if (queryIndex >= 0) baseUrl.substring(0, queryIndex) else baseUrl
            val query = if (queryIndex >= 0) baseUrl.substring(queryIndex) else ""
            "${path.trimEnd('/')}/page/$page/$query"
        }

        val document = app.get(url, headers = browserHeaders).document
        val blockedByChallenge = document.isSecurityChallenge()
        if (blockedByChallenge) {
            Log.e("XTapes", "Cloudflare security challenge blocked video listing: $url")
        }
        val home = if (blockedByChallenge) emptyList() else {
            document.select("ul.listing-tube li, ul.listing-videos li")
                .mapNotNull { it.toSearchResult() }
        }
        Log.d("XTapes", "Loaded ${home.size} video cards from $url")
        return newHomePageResponse(
            list = HomePageList(
                name = request.name,
                list = home,
                isHorizontalImages = true
            ),
            hasNext = home.isNotEmpty() && document.hasNextPage()
        )
    }

    private fun Document.isSecurityChallenge(): Boolean =
        title().contains("just a moment", ignoreCase = true) ||
            text().contains("verify you are human", ignoreCase = true) ||
            selectFirst("iframe[src*='challenges.cloudflare.com'], #challenge-form, #cf-challenge-running") != null

    private fun Document.hasNextPage(): Boolean = select("a[href]").any { anchor ->
        val rel = anchor.attr("rel").split(Regex("\\s+")).any { it.equals("next", ignoreCase = true) }
        val classes = anchor.classNames().any { it.equals("next", ignoreCase = true) }
        rel || classes || anchor.text().contains("next videos", ignoreCase = true)
    }

    private fun Element.toSearchResult(): SearchResponse? {
        val anchor = selectFirst("a[href][title]") ?: selectFirst("a[href]") ?: return null
        val image = selectFirst("img")
        val title = listOfNotNull(
            anchor.attr("title").takeIf { it.isNotBlank() },
            image?.attr("alt")?.takeIf { it.isNotBlank() },
            image?.attr("title")?.takeIf { it.isNotBlank() },
            selectFirst("h3")?.text()?.takeIf { it.isNotBlank() },
            anchor.text().takeIf { it.isNotBlank() }
        ).firstOrNull() ?: return null
        val href = fixUrlNull(anchor.attr("href")) ?: return null
        val posterCandidate = image?.let { poster ->
            listOf("data-src", "data-original", "data-lazy-src", "data-original-src", "src", "data-srcset", "srcset")
                .asSequence()
                .map { poster.attr(it) }
                .firstOrNull { it.isNotBlank() && !it.startsWith("data:image", ignoreCase = true) }
                ?.substringBefore(",")
                ?.substringBefore(" ")
        }
        val posterUrl = posterCandidate?.let { resolveUrl(mainUrl, it) }
        return newMovieSearchResponse(title.trim(), href, TvType.NSFW) {
            this.posterUrl = posterUrl
        }
    }

    override suspend fun search(query: String): List<SearchResponse> {
        val encodedQuery = URLEncoder.encode(query, Charsets.UTF_8.name())
        val document = app.get("$mainUrl/?s=$encodedQuery", headers = browserHeaders).document
        if (document.isSecurityChallenge()) {
            Log.e("XTapes", "Cloudflare security challenge blocked search results")
            return emptyList()
        }
        return document.select("ul.listing-tube li, ul.listing-videos li")
            .mapNotNull { it.toSearchResult() }
    }

    override suspend fun quickSearch(query: String): List<SearchResponse>? = search(query)

    override suspend fun load(url: String): LoadResponse? {
        val pageUrl = resolveUrl(mainUrl, url) ?: return null
        val document = app.get(pageUrl, headers = browserHeaders).document
        val title = document.selectFirst("h1")?.text()?.trim()
            ?.takeIf { it.isNotBlank() }
            ?: document.selectFirst("meta[property=og:title]")?.attr("content")?.trim()
                ?.takeIf { it.isNotBlank() }
            ?: return null

        // Listing and search pages also have h1 elements; do not expose them as playable movies.
        if (title.startsWith("All videos in category", ignoreCase = true) ||
            title.contains("Official Website", ignoreCase = true) ||
            title.startsWith("Search results for", ignoreCase = true)
        ) return null

        val hasDetailMarker = document.selectFirst(
            "video, iframe[src], iframe[data-src], [itemprop=contentUrl], [itemprop=thumbnailUrl], " +
                "meta[property=og:video], meta[property=og:video:url], meta[property=og:image], " +
                "meta[name=description], div.infotext, span[itemprop=description]"
        ) != null
        if (!hasDetailMarker) return null

        val poster = listOfNotNull(
            document.selectFirst("meta[itemprop=thumbnailUrl]")?.attr("content"),
            document.selectFirst("meta[property=og:image]")?.attr("content"),
            document.selectFirst("meta[name=twitter:image]")?.attr("content")
        ).firstNotNullOfOrNull { resolveUrl(pageUrl, it) }
        val description = document.selectFirst("div.infotext")?.text()?.trim()
            ?.takeIf { it.isNotBlank() }
            ?: document.selectFirst("span[itemprop=description]")?.attr("content")?.trim()
                ?.takeIf { it.isNotBlank() }
            ?: document.selectFirst("meta[property=og:description]")?.attr("content")?.trim()
                ?.takeIf { it.isNotBlank() }
            ?: document.selectFirst("meta[name=description]")?.attr("content")?.trim()
                ?.takeIf { it.isNotBlank() }

        val allTags = document.select("a[rel=tag]")
        val explicitActors = document.select("a[itemprop=actor], .actors a, .performers a, .performer a")
        val actors = (if (explicitActors.isNotEmpty()) explicitActors else {
            allTags.filter { it.attr("href").contains("hd-porn-", ignoreCase = true) }
        }).map { Actor(it.text().trim(), null) }
            .filter { it.name.isNotBlank() }
            .distinctBy { it.name }
        val tags = allTags
            .filterNot { it.attr("href").contains("hd-porn-", ignoreCase = true) }
            .map { it.text().trim() }
            .filter { it.isNotEmpty() }
            .distinct()

        return newMovieLoadResponse(title, pageUrl, TvType.NSFW, pageUrl) {
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
        val pageUrl = resolveUrl(mainUrl, data) ?: return false
        val document = app.get(pageUrl, headers = browserHeaders).document
        val pageHtml = document.html()
        var foundLink = false
        val reportLink: (ExtractorLink) -> Unit = { link ->
            if (link.url.isNotBlank()) {
                foundLink = true
                callback(link)
            }
        }

        // Sites commonly lazy-load embeds into data-src instead of src.
        val iframeUrls = document.select("iframe[src], iframe[data-src], iframe[data-lazy-src]")
            .mapNotNull { iframe ->
                val src = sequenceOf("src", "data-src", "data-lazy-src")
                    .map { iframe.attr(it).trim() }
                    .firstOrNull { it.isNotBlank() && !it.equals("javascript:false", ignoreCase = true) }
                src?.let { resolveUrl(pageUrl, it) }
            }
            .distinct()

        val embed74k = iframeUrls.firstOrNull { isHostOrSubdomain(it, "74k.io") }
        if (embed74k != null) {
            try {
                Log.d("XTapes", "Found 74k.io embed: $embed74k")
                val embedHtml = app.get(embed74k, referer = pageUrl, headers = browserHeaders).text
                val links = extract74kLinks(embedHtml, embed74k)
                if (links != null) {
                    links.hls4?.let { addHlsLink("XTapes - HLS4", it, embed74k, reportLink) }
                    links.hls2?.let { addHlsLink("XTapes - HLS2", it, embed74k, reportLink) }
                    links.hls3?.let { addHlsLink("XTapes - HLS3", it, embed74k, reportLink) }
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                Log.e("XTapes", "Error extracting from 74k.io: ${e.message}")
            }
        }

        // Always observe whether loadExtractor actually invokes the callback; its return value is not a link count.
        if (!foundLink) {
            for (iframeUrl in iframeUrls) {
                if (isHostOrSubdomain(iframeUrl, "74k.io") || isIgnoredIframe(iframeUrl)) continue
                try {
                    loadExtractor(iframeUrl, pageUrl, subtitleCallback, reportLink)
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    Log.e("XTapes", "Error loading extractor for $iframeUrl: ${e.message}")
                }
            }
        }

        // Last resort for pages that expose a direct source rather than an iframe.
        if (!foundLink) {
            val sourcePattern = Regex(
                """(?:video_url|source|file)\s*[:=]\s*['\"]([^'\"]+)['\"]""",
                RegexOption.IGNORE_CASE
            )
            val directUrl = sourcePattern.find(pageHtml)?.groupValues?.get(1)
                ?: Regex(
                    """(?:(?:https?:)?//|/)[^\s'\"<>;,]+?\.m3u8(?:\?[^\s'\"<>;,]*)?""",
                    RegexOption.IGNORE_CASE
                ).find(pageHtml)?.value
            val videoUrl = directUrl?.let { resolveUrl(pageUrl, it) }
            if (videoUrl != null && (videoUrl.contains(".mp4", ignoreCase = true) || videoUrl.contains(".m3u8", ignoreCase = true))) {
                val linkType = if (videoUrl.contains(".m3u8", ignoreCase = true)) {
                    ExtractorLinkType.M3U8
                } else {
                    ExtractorLinkType.VIDEO
                }
                reportLink(
                    newExtractorLink(this.name, this.name, videoUrl, linkType) {
                        this.referer = pageUrl
                        this.quality = Qualities.Unknown.value
                    }
                )
            }
        }

        return foundLink
    }

    private fun isIgnoredIframe(url: String): Boolean = listOf(
        "recaptcha", "twitter", "izerv", "timbuk", "facebook.com/plugins", "platform.twitter.com"
    ).any { url.contains(it, ignoreCase = true) }

    private fun isHostOrSubdomain(url: String, host: String): Boolean {
        val actualHost = try {
            URI(url).host?.lowercase()
        } catch (_: Exception) {
            null
        } ?: return false
        return actualHost == host || actualHost.endsWith(".$host")
    }

    private fun resolveUrl(baseUrl: String, rawUrl: String): String? {
        val candidate = rawUrl.trim()
            .trim('"', '\'')
            .replace("\\/", "/")
            .replace("&amp;", "&")
            .replace(" ", "%20")
        if (candidate.isBlank() || candidate.startsWith("javascript:", ignoreCase = true) ||
            candidate.startsWith("data:", ignoreCase = true)
        ) return null

        return try {
            val base = URI(baseUrl)
            val target = URI(candidate)
            val resolved = if (target.isAbsolute) target else base.resolve(target)
            if ((resolved.scheme == "http" || resolved.scheme == "https") && !resolved.host.isNullOrBlank()) {
                resolved.toASCIIString()
            } else {
                null
            }
        } catch (_: Exception) {
            null
        }
    }

    private suspend fun addHlsLink(
        label: String,
        rawUrl: String,
        embedUrl: String,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        val streamUrl = resolveUrl(embedUrl, rawUrl) ?: return false
        if (!streamUrl.contains(".m3u8", ignoreCase = true)) return false
        val origin = try {
            URI(embedUrl).let { "${it.scheme}://${it.rawAuthority}" }
        } catch (_: Exception) {
            "https://74k.io"
        }
        callback(
            newExtractorLink(
                source = name,
                name = label,
                url = streamUrl,
                type = ExtractorLinkType.M3U8
            ) {
                this.referer = embedUrl
                this.quality = Qualities.Unknown.value
                this.headers = mapOf(
                    "User-Agent" to browserHeaders.getValue("User-Agent"),
                    "Origin" to origin,
                    "Referer" to embedUrl
                )
            }
        )
        return true
    }

    private data class HlsLinks(
        val hls2: String? = null,
        val hls3: String? = null,
        val hls4: String? = null
    )

    /**
     * Use CloudStream's maintained packer implementation (including non-decimal radices)
     * instead of a local base-62 decoder. Only scripts that contain the packer are tried.
     */
    private fun extract74kLinks(html: String, embedUrl: String): HlsLinks? {
        val packerSignature = Regex(
            """function\s*\(\s*p\s*,\s*a\s*,\s*c\s*,\s*k\s*,\s*e\s*,\s*d\s*\)""",
            RegexOption.IGNORE_CASE
        )
        val scripts = org.jsoup.Jsoup.parse(html).select("script")
            .map { it.data().ifBlank { it.html() } }
            .filter { packerSignature.containsMatchIn(it) }
            .ifEmpty { listOf(html) }

        for (script in scripts) {
            try {
                val unpacked = JsUnpacker(script).unpack()
                if (!unpacked.isNullOrBlank()) {
                    extractLinksFromUnpacked(unpacked, embedUrl)?.let { return it }
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                Log.d("XTapes", "JS unpacker failed: ${e.message}")
            }
        }

        return extractLinksFromUnpacked(html, embedUrl) ?: extractLinksFromPackedDict(html, embedUrl)
    }

    private fun extractLinksFromUnpacked(unpacked: String, embedUrl: String): HlsLinks? {
        val linksContent = Regex("""links\s*=\s*\{([^}]*)}""")
            .find(unpacked)?.groupValues?.get(1) ?: unpacked

        fun urlFor(key: String): String? {
            val raw = Regex("""[\"']?$key[\"']?\s*:\s*[\"']([^\"']+)[\"']""")
                .find(linksContent)?.groupValues?.get(1)
            return raw?.let { resolveUrl(embedUrl, it) }
        }

        var hls2 = urlFor("hls2")
        var hls3 = urlFor("hls3")
        var hls4 = urlFor("hls4")

        if (hls2 == null && hls3 == null && hls4 == null) {
            val urlRegex = Regex(
                """(?:(?:https?:)?//|/)[^\s'\"<>;,]+?\.m3u8(?:\?[^\s'\"<>;,]*)?""",
                RegexOption.IGNORE_CASE
            )
            for (match in urlRegex.findAll(unpacked)) {
                val url = resolveUrl(embedUrl, match.value) ?: continue
                if (hls4 == null && url.contains("/stream/", ignoreCase = true)) hls4 = url
                else if (hls2 == null) hls2 = url
                else if (hls3 == null) hls3 = url
            }
        }

        if (hls2 == null && hls3 == null && hls4 == null) {
            val fileUrl = Regex("""file\s*:\s*[\"']([^\"']+)[\"']""", RegexOption.IGNORE_CASE)
                .find(unpacked)?.groupValues?.get(1)?.let { resolveUrl(embedUrl, it) }
            if (fileUrl?.contains(".m3u8", ignoreCase = true) == true) hls2 = fileUrl
        }

        return if (hls2 != null || hls3 != null || hls4 != null) {
            HlsLinks(hls2 = hls2, hls3 = hls3, hls4 = hls4)
        } else {
            null
        }
    }

    private fun extractLinksFromPackedDict(html: String, embedUrl: String): HlsLinks? {
        val dictMatch = Regex("""'([^']+)'\s*\.split\('\|'\)""").find(html) ?: return null
        val candidate = dictMatch.groupValues[1].split('|')
            .firstOrNull { it.contains("m3u8", ignoreCase = true) } ?: return null
        val url = resolveUrl(embedUrl, candidate) ?: return null
        return HlsLinks(hls2 = url)
    }
}
