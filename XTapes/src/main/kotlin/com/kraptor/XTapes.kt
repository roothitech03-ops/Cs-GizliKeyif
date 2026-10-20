package com.kraptor

import android.annotation.SuppressLint
import android.content.Context
import android.os.Handler
import android.os.Looper
import android.webkit.WebResourceRequest
import android.webkit.WebResourceResponse
import android.webkit.WebView
import android.webkit.WebViewClient
import org.json.JSONObject
import org.jsoup.Jsoup
import com.lagradost.api.Log
import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.LoadResponse.Companion.addActors
import com.lagradost.cloudstream3.utils.*
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.suspendCancellableCoroutine
import org.jsoup.nodes.Document
import org.jsoup.nodes.Element
import java.net.URI
import java.net.URLEncoder
import kotlin.coroutines.resume

class XTapes : MainAPI() {
    // Use each response's final URL below so .la/.tw canonical-host redirects do not corrupt embed resolution.
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

    // These are player hosts that CloudStream's extractor registry can resolve.
    // XTapes may expose them as links instead of iframe elements.
    private val playerHostHints = listOf("74k.io", "sextapes.to", "streamtape", "mixdrop", "dood", "bigwarp")

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

        val response = app.get(url, headers = browserHeaders, allowRedirects = true)
        val finalPageUrl = response.url
        val document = response.document
        val blockedByChallenge = document.isSecurityChallenge()
        if (blockedByChallenge) {
            Log.e("XTapes", "Cloudflare security challenge blocked video listing: $url")
        }
        val home = if (blockedByChallenge) emptyList() else {
            document.select("ul.listing-tube li, ul.listing-videos li")
                .mapNotNull { it.toSearchResult(finalPageUrl) }
        }
        Log.d("XTapes", "Loaded ${home.size} video cards from $finalPageUrl")
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

    private fun Element.toSearchResult(baseUrl: String): SearchResponse? {
        val anchor = selectFirst("a[href][title]") ?: selectFirst("a[href]") ?: return null
        val image = selectFirst("img")
        val title = listOfNotNull(
            anchor.attr("title").takeIf { it.isNotBlank() },
            image?.attr("alt")?.takeIf { it.isNotBlank() },
            image?.attr("title")?.takeIf { it.isNotBlank() },
            selectFirst("h3")?.text()?.takeIf { it.isNotBlank() },
            anchor.text().takeIf { it.isNotBlank() }
        ).firstOrNull() ?: return null
        val href = resolveUrl(baseUrl, anchor.attr("href")) ?: return null
        val posterCandidate = image?.let { poster ->
            listOf("data-src", "data-original", "data-lazy-src", "data-original-src", "src", "data-srcset", "srcset")
                .asSequence()
                .map { poster.attr(it) }
                .firstOrNull { it.isNotBlank() && !it.startsWith("data:image", ignoreCase = true) }
                ?.substringBefore(",")
                ?.substringBefore(" ")
        }
        val posterUrl = posterCandidate?.let { resolveUrl(baseUrl, it) }
        return newMovieSearchResponse(title.trim(), href, TvType.NSFW) {
            this.posterUrl = posterUrl
        }
    }

    override suspend fun search(query: String): List<SearchResponse> {
        val encodedQuery = URLEncoder.encode(query, Charsets.UTF_8.name())
        val response = app.get("$mainUrl/?s=$encodedQuery", headers = browserHeaders, allowRedirects = true)
        val document = response.document
        if (document.isSecurityChallenge()) {
            Log.e("XTapes", "Cloudflare security challenge blocked search results")
            return emptyList()
        }
        return document.select("ul.listing-tube li, ul.listing-videos li")
            .mapNotNull { it.toSearchResult(response.url) }
    }

    override suspend fun quickSearch(query: String): List<SearchResponse>? = search(query)

    override suspend fun load(url: String): LoadResponse? {
        val requestedPageUrl = resolveUrl(mainUrl, url) ?: return null
        val response = app.get(requestedPageUrl, headers = browserHeaders, allowRedirects = true)
        val pageUrl = response.url
        val document = response.document
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
            "video, iframe[src], iframe[data-src], iframe[data-lazy-src], iframe[data-embed], iframe[data-iframe], " +
                "iframe[data-url], iframe[data-player], [itemprop=contentUrl], [itemprop=thumbnailUrl], " +
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
        val requestedPageUrl = resolveUrl(mainUrl, data) ?: return false
        val response = app.get(requestedPageUrl, headers = browserHeaders, allowRedirects = true)
        val pageUrl = response.url
        var document = response.document
        // Cloudflare hard-blocks non-browser TLS fingerprints with HTTP 403 (tiny plain-text
        // body) which isSecurityChallenge() cannot detect. A real WebView uses Chrome's TLS
        // stack and passes, so go straight to the WebView fallback when blocked.
        if (response.code == 403 || document.isSecurityChallenge()) {
            val finalHost = runCatching { URI(pageUrl).host }.getOrNull()
            val requestedHost = runCatching { URI(requestedPageUrl).host }.getOrNull()
            Log.e("XTapes", "Playback page blocked by Cloudflare (HTTP ${response.code}); requested=$requestedHost, final=$finalHost; rendering via WebView to extract players")
            // Render the page in a real WebView (passes Cloudflare) and parse the result,
            // so the 74k.io / 88z.io player iframes are found and extracted normally.
            val renderedHtml = getRenderedHtmlViaWebView(pageUrl)
            if (renderedHtml != null) {
                document = Jsoup.parse(renderedHtml, pageUrl)
                Log.d("XTapes", "Got rendered HTML via WebView (${renderedHtml.length} chars); continuing extraction")
            } else {
                Log.e("XTapes", "Rendered HTML unavailable; using stream interception only")
                return extractViaWebView(pageUrl, callback)
            }
        }
        val pageHtml = document.html()
        var foundLink = false
        val reportLink: (ExtractorLink) -> Unit = { link ->
            if (link.url.isNotBlank()) {
                foundLink = true
                callback(link)
            }
        }

        // XMoviesForYou uses the site root as Referer; try that first, then the detail URL for hosts that require it.
        val siteRootReferer = runCatching {
            URI(pageUrl).let { "${it.scheme}://${it.rawAuthority}/" }
        }.getOrDefault(pageUrl)
        val extractorReferers = listOf(siteRootReferer, pageUrl).distinct()

        // Resolve each attribute before choosing it: src="about:blank" must not mask a real lazy URL.
        val iframeUrls = document.select(
            "iframe[src], iframe[data-src], iframe[data-lazy-src], iframe[data-embed], " +
                "iframe[data-iframe], iframe[data-url], iframe[data-player]"
        ).mapNotNull { iframe ->
            val iframeUrl = sequenceOf("src", "data-src", "data-lazy-src", "data-embed", "data-iframe", "data-url", "data-player")
                .map { iframe.attr(it).trim() }
                .filterNot { it.isBlank() || it.startsWith("javascript:", ignoreCase = true) }
                .mapNotNull { resolveUrl(pageUrl, it) }
                .firstOrNull()
            iframeUrl?.takeUnless {
                isIgnoredIframe(it) ||
                    // Never drop a known player host as an "ad": the video iframes on this
                    // site live inside ad-heavy containers and can carry ad-like attributes.
                    (!isKnownPlayerHost(it) && iframe.isAdvertisementFrame())
            }
        }.distinct()

        // Some versions expose the player as a provider anchor, not an iframe.
        // Restrict this fallback to known player hosts to avoid following page ads/social links.
        val playerUrls = (iframeUrls.filterNot { isIgnoredIframe(it) } + document.select("a[href]").mapNotNull { anchor ->
            val candidate = resolveUrl(pageUrl, anchor.attr("href")) ?: return@mapNotNull null
            candidate.takeIf { isKnownPlayerHost(it) && !isIgnoredIframe(it) }
        }).distinct()
        Log.d("XTapes", "Found ${playerUrls.size} player URL candidates")

        val embed74k = playerUrls.firstOrNull { isHostOrSubdomain(it, "74k.io") }
        var embed74kBlockedByChallenge = false
        if (embed74k != null) {
            var got74kDirectLinks = false
            try {
                Log.d("XTapes", "Found 74k.io player host: ${URI(embed74k).host}")
                val embedDocument = app.get(embed74k, referer = pageUrl, headers = browserHeaders).document
                val embedBlockedByChallenge = embedDocument.isSecurityChallenge()
                if (embedBlockedByChallenge) {
                    embed74kBlockedByChallenge = true
                    Log.e("XTapes", "74k.io returned a security challenge; trying WebView fallback")
                }
                val links = if (embedBlockedByChallenge) null else {
                    extract74kLinks(embedDocument.html(), embed74k)
                }
                if (links != null) {
                    links.hls4?.let { addHlsLink("XTapes - HLS4", it, embed74k, reportLink); got74kDirectLinks = true }
                    links.hls2?.let { addHlsLink("XTapes - HLS2", it, embed74k, reportLink); got74kDirectLinks = true }
                    links.hls3?.let { addHlsLink("XTapes - HLS3", it, embed74k, reportLink); got74kDirectLinks = true }
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                Log.e("XTapes", "Error extracting from 74k.io: ${e.message}")
            }
            // FALLBACK: If direct 74k.io extraction failed, load the embed in WebView
            // and intercept the stream. This ensures the fast 74k.io source appears
            // even when direct HTTP is blocked.
            if (!got74kDirectLinks) {
                try {
                    Log.d("XTapes", "74k.io direct failed; trying WebView fallback")
                    if (extractViaWebView(embed74k, reportLink)) {
                        foundLink = true
                        Log.d("XTapes", "Got 74k.io stream via WebView")
                    }
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    Log.e("XTapes", "74k.io WebView fallback failed: ${e.message}")
                }
            }
        }

        // Try every player so the user gets sources from all of them and can pick the fastest.
        for (playerUrl in playerUrls) {
            if (embed74kBlockedByChallenge && isHostOrSubdomain(playerUrl, "74k.io")) continue
            if (isHostOrSubdomain(playerUrl, "sextapes.to")) {
                // Real player (StreamHG). Try the registered extractor first, then a
                // WebView render of the embed which intercepts the premilkyway.com file.
                var sextapesFound = false
                try {
                    loadExtractor(playerUrl, pageUrl, subtitleCallback) { link ->
                        if (link.url.isNotBlank()) {
                            sextapesFound = true
                            reportLink(link)
                        }
                    }
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    Log.e("XTapes", "sextapes.to extractor failed: ${e.message}")
                }
                if (!sextapesFound) {
                    try {
                        Log.d("XTapes", "sextapes.to: trying WebView on embed")
                        if (extractViaWebView(playerUrl, reportLink)) foundLink = true
                    } catch (e: CancellationException) {
                        throw e
                    } catch (e: Exception) {
                        Log.e("XTapes", "Error extracting from sextapes.to: ${e.message}")
                    }
                }
                continue
            }
            var playerFoundLink = false
            val playerReportLink: (ExtractorLink) -> Unit = { link ->
                if (link.url.isNotBlank()) {
                    playerFoundLink = true
                    reportLink(link)
                }
            }
            for (referer in extractorReferers) {
                if (playerFoundLink) break
                try {
                    loadExtractor(playerUrl, referer, subtitleCallback, playerReportLink)
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    val host = try { URI(playerUrl).host } catch (_: Exception) { "unknown" }
                    Log.e("XTapes", "Error loading extractor for $host: ${e.message}")
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

        // Last resort: the HTML extraction found nothing (page layout changed or embeds moved).
        // Render in a real WebView and intercept the stream URL from network traffic.
        if (!foundLink) {
            Log.d("XTapes", "No links via HTML extraction; trying WebView fallback for $pageUrl")
            foundLink = extractViaWebView(pageUrl, callback)
        }

        if (!foundLink) Log.e("XTapes", "No playable links emitted from detail page; player candidates=${playerUrls.size}")

        return foundLink
    }

    /**
     * Renders [pageUrl] in a real Android WebView and captures the stream URL from network
     * traffic. A WebView uses Chrome's TLS fingerprint, so it passes Cloudflare's bot checks
     * that block OkHttp-based requests (HTTP 403). Same pattern as the 18EU provider.
     */
    @SuppressLint("SetJavaScriptEnabled")
    /**
     * Renders a Cloudflare-blocked page in a real WebView and returns its HTML once the
     * video iframes have appeared. Returns null on timeout/failure.
     */
    private suspend fun getRenderedHtmlViaWebView(pageUrl: String): String? {
        val context = runCatching {
            Class.forName("android.app.ActivityThread")
                .getMethod("currentApplication")
                .invoke(null) as? Context
        }.getOrNull() ?: return null

        return suspendCancellableCoroutine { cont ->
            Handler(Looper.getMainLooper()).post {
                val webView = WebView(context).apply {
                    settings.javaScriptEnabled = true
                    settings.domStorageEnabled = true
                    settings.userAgentString = browserHeaders.getValue("User-Agent")
                }
                val startTime = System.currentTimeMillis()
                val maxWaitMs = 25_000L
                fun poll() {
                    if (!cont.isActive) return
                    // Ready when a video iframe (any src/data-src) is present in the DOM.
                    webView.evaluateJavascript(
                        "(function(){var fs=document.querySelectorAll('iframe');for(var i=0;i<fs.length;i++){var s=(fs[i].getAttribute('src')||'')+(fs[i].getAttribute('data-src')||'');if(s.indexOf('74k.io')>=0||s.indexOf('88z.io')>=0||s.indexOf('sextapes.to')>=0){return 'ready';}}return fs.length>0?'some':'none';})()"
                    ) { state ->
                        if (!cont.isActive) return@evaluateJavascript
                        val elapsed = System.currentTimeMillis() - startTime
                        val ready = state?.contains("ready") == true
                        if (ready || elapsed >= maxWaitMs) {
                            webView.evaluateJavascript("document.documentElement.outerHTML") { html ->
                                if (cont.isActive) {
                                    val decoded = runCatching {
                                        JSONObject("{\"v\":$html}").getString("v")
                                    }.getOrNull()
                                    cont.resume(decoded)
                                }
                                webView.destroy()
                            }
                        } else {
                            webView.postDelayed({ poll() }, 2000)
                        }
                    }
                }
                webView.webViewClient = object : WebViewClient() {
                    override fun onPageFinished(view: WebView, url: String?) {
                        view.postDelayed({ poll() }, 3000)
                    }
                }
                webView.loadUrl(pageUrl)
                Handler(Looper.getMainLooper()).postDelayed({
                    if (cont.isActive) {
                        cont.resume(null)
                        webView.destroy()
                    }
                }, maxWaitMs + 5000)
            }
        }
    }

    private suspend fun extractViaWebView(
        pageUrl: String,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        val context = runCatching {
            Class.forName("android.app.ActivityThread")
                .getMethod("currentApplication")
                .invoke(null) as? Context
        }.getOrNull() ?: run {
            Log.e("XTapes", "WebView fallback: no application context available")
            return false
        }

        val capturedUrl = suspendCancellableCoroutine { cont ->
            Handler(Looper.getMainLooper()).post {
                val webView = WebView(context).apply {
                    settings.javaScriptEnabled = true
                    settings.domStorageEnabled = true
                    settings.mediaPlaybackRequiresUserGesture = false
                    settings.userAgentString = browserHeaders.getValue("User-Agent")
                }
                webView.webViewClient = object : WebViewClient() {
                    override fun shouldInterceptRequest(
                        view: WebView,
                        request: WebResourceRequest
                    ): WebResourceResponse? {
                        val url = request.url.toString()
                        val isStream = url.contains(".m3u8", ignoreCase = true) ||
                            url.contains(".mp4", ignoreCase = true)
                        // STRICT: only accept real video streams, never ads.
                        // Real video on this site comes from the StreamHG CDN (premilkyway.com)
                        // or as HLS (.m3u8). Video ads come from IMA/ad-networks as .mp4.
                        val lowerUrl = url.lowercase()
                        val isHls = lowerUrl.contains(".m3u8")
                        val isKnownVideoCdn = lowerUrl.contains("premilkyway.com")
                        val isAdNetwork = lowerUrl.contains("izerv") ||
                            lowerUrl.contains("doubleclick") ||
                            lowerUrl.contains("googlesyndication") ||
                            lowerUrl.contains("googleads") ||
                            lowerUrl.contains("imasdk") ||
                            lowerUrl.contains("redirectthisclick") ||
                            lowerUrl.contains("exoclick") ||
                            lowerUrl.contains("adnxs") ||
                            lowerUrl.contains("pubmatic") ||
                            lowerUrl.contains("rubicon") ||
                            lowerUrl.contains("criteo") ||
                            lowerUrl.contains("outbrain") ||
                            lowerUrl.contains("taboola") ||
                            lowerUrl.contains("preroll") ||
                            lowerUrl.contains("midroll") ||
                            lowerUrl.contains("vast") ||
                            lowerUrl.contains("vpaid")
                        // Accept: known video CDN, or HLS from non-ad source.
                        // Reject: everything from ad networks, and .mp4 from unknown hosts (likely ads).
                        val accept = isKnownVideoCdn || (isHls && !isAdNetwork)
                        if (cont.isActive && isStream && accept) {
                            cont.resume(url)
                            view.post { view.destroy() }
                        }
                        return super.shouldInterceptRequest(view, request)
                    }

                    override fun onPageFinished(view: WebView, url: String?) {
                        // Nudge lazy players to start so the stream request fires.
                        view.evaluateJavascript(
                            "(function(){var v=document.querySelector('video');if(v&&v.play){try{v.play()}catch(e){}}var b=document.querySelector('.jw-display-icon-display');if(b){b.click();}})();",
                            null
                        )
                    }
                }
                webView.loadUrl(pageUrl)
                Handler(Looper.getMainLooper()).postDelayed({
                    if (cont.isActive) {
                        cont.resume(null)
                        webView.destroy()
                    }
                }, 25_000)
            }
        }

        val streamUrl = capturedUrl ?: run {
            Log.e("XTapes", "WebView fallback: no stream URL captured for $pageUrl")
            return false
        }
        val isHls = streamUrl.contains(".m3u8", ignoreCase = true)
        callback(
            newExtractorLink(this.name, "XTapes - WebView", streamUrl, if (isHls) ExtractorLinkType.M3U8 else ExtractorLinkType.VIDEO) {
                this.referer = pageUrl
                this.quality = Qualities.Unknown.value
                this.headers = mapOf(
                    "User-Agent" to browserHeaders.getValue("User-Agent"),
                    "Referer" to pageUrl
                )
            }
        )
        Log.d("XTapes", "WebView fallback captured: $streamUrl")
        return true
    }

    private fun Element.isAdvertisementFrame(): Boolean {
        val metadata = listOf(attr("title"), attr("aria-label"), attr("name"), attr("id"), attr("class")).joinToString(" ")
        return Regex(
            """(?:^|[\s_-])(?:ad|ads|advert|advertisement)(?:$|[\s_-])""",
            RegexOption.IGNORE_CASE
        ).containsMatchIn(metadata)
    }

    private fun isIgnoredIframe(url: String): Boolean = listOf(
        "recaptcha", "twitter", "izerv", "timbuk", "facebook.com/plugins", "platform.twitter.com",
        // 88z.io is a fake-player ad wrapper on this site, not a real video source.
        "88z.io"
    ).any { url.contains(it, ignoreCase = true) }

    private fun isHostOrSubdomain(url: String, host: String): Boolean {
        val actualHost = try {
            URI(url).host?.lowercase()
        } catch (_: Exception) {
            null
        } ?: return false
        return actualHost == host || actualHost.endsWith(".$host")
    }

    private fun isKnownPlayerHost(url: String): Boolean {
        val host = try {
            URI(url).host?.lowercase()
        } catch (_: Exception) {
            null
        } ?: return false
        return playerHostHints.any { host.contains(it) }
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
