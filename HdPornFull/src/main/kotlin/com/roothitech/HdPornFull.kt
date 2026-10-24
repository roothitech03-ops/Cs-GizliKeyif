package com.roothitech

import android.annotation.SuppressLint
import android.content.Context
import android.os.Handler
import android.os.Looper
import android.webkit.WebView
import android.webkit.WebViewClient
import org.json.JSONObject
import org.jsoup.Jsoup
import org.jsoup.nodes.Document
import org.jsoup.nodes.Element
import com.lagradost.api.Log
import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.utils.*
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlin.coroutines.resume

class HdPornFull : MainAPI() {
    override var mainUrl = "https://www.hdpornfull.com"
    override var name = "HdPornFull"
    override val hasMainPage = true
    override var lang = "en"
    override val supportedTypes = setOf(TvType.NSFW)
    override val vpnStatus = VPNStatus.MightBeNeeded

    // Browser-like headers to bypass Cloudflare basic checks
    private val browserHeaders = mapOf(
        "User-Agent" to "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/126.0.0.0 Safari/537.36",
        "Accept" to "text/html,application/xhtml+xml,application/xml;q=0.9,*/*;q=0.8",
        "Accept-Language" to "en-US,en;q=0.9"
    )

    // Verified on the site: cat=110 is the "Latest Porn Movies" listing
    override val mainPage = mainPageOf(
        "$mainUrl/?filter=latest" to "Latest Porn Videos",
        "$mainUrl/?filter=latest&cat=110" to "Latest Porn Movies"
    )

    private fun Document.isChallenge(): Boolean =
        title().contains("just a moment", ignoreCase = true) ||
            text().contains("verify you are human", ignoreCase = true) ||
            selectFirst("iframe[src*='challenges.cloudflare.com'], #challenge-form, #cf-challenge-running, #cf-wrapper") != null ||
            selectFirst("script[src*='challenges.cloudflare.com'], script[nonce*='challenge']") != null ||
            text().contains("ray id", ignoreCase = true) ||
            text().contains("cf_chl_", ignoreCase = true)

    /**
     * Fetches [url] over HTTP first. If the response is a Cloudflare block
     * (403 / challenge page) or [usable] rejects the parsed document, the page
     * is rendered in a WebView and that HTML is parsed instead.
     */
    private suspend fun fetchDocument(url: String, usable: (Document) -> Boolean): Document? {
        val httpDoc = runCatching {
            val response = app.get(url, headers = browserHeaders)
            if (response.code == 403 || response.code == 503) null else response.document
        }.getOrNull()
        
        // Check if HTTP result is valid
        if (httpDoc != null) {
            val isChallenge = httpDoc.isChallenge()
            val isUsable = usable(httpDoc)
            Log.d("HdPornFull", "HTTP fetch for $url: code=${runCatching { app.get(url, headers = browserHeaders).code }.getOrNull()}, challenge=$isChallenge, usable=$isUsable, title='${httpDoc.title()}'")
            if (!isChallenge && isUsable) return httpDoc
        }

        Log.d("HdPornFull", "HTTP fetch blocked or unusable for $url; rendering via WebView")
        val html = getRenderedHtmlViaWebView(url) ?: return httpDoc
        val webDoc = runCatching { Jsoup.parse(html, url) }.getOrNull()
        if (webDoc != null) {
            val isChallenge = webDoc.isChallenge()
            val isUsable = usable(webDoc)
            Log.d("HdPornFull", "WebView fetch for $url: challenge=$isChallenge, usable=$isUsable, title='${webDoc.title()}'")
            if (!isChallenge && isUsable) return webDoc
        }
        return httpDoc
    }

    override suspend fun getMainPage(page: Int, request: MainPageRequest): HomePageResponse {
        // Verified pattern: https://www.hdpornfull.com/page/2/?filter=latest
        val url = if (page <= 1) request.data else {
            val q = request.data.indexOf('?')
            val path = if (q >= 0) request.data.substring(0, q) else request.data
            val query = if (q >= 0) request.data.substring(q) else ""
            "${path.trimEnd('/')}/page/$page/$query"
        }
        val document = fetchDocument(url) { it.select("article").isNotEmpty() }
        val home = document?.select("article")?.mapNotNull { it.toSearchResult() } ?: emptyList()
        return newHomePageResponse(request.name, home, hasNext = home.isNotEmpty())
    }

    /**
     * Extracts video info from article element on listing pages.
     * Key: uses data-main-thumb attribute for thumbnail.
     */
    private fun Element.toSearchResult(): SearchResponse? {
        val anchor = selectFirst("a[href][title]") ?: selectFirst("a[href]") ?: return null
        val href = fixUrlNull(anchor.attr("abs:href").ifBlank { anchor.attr("href") }) ?: return null

        // Title: prefer anchor title, then img alt, then h2/h3, then anchor text
        val img = selectFirst("img")
        val title = listOfNotNull(
            anchor.attr("title").takeIf { it.isNotBlank() },
            img?.attr("alt")?.takeIf { it.isNotBlank() },
            img?.attr("title")?.takeIf { it.isNotBlank() },
            selectFirst("h2, h3")?.text()?.takeIf { it.isNotBlank() },
            anchor.text().takeIf { it.isNotBlank() }
        ).firstOrNull() ?: return null

        // Poster: data-main-thumb is the primary source (verified on site)
        val poster = listOf(
            attr("data-main-thumb"),
            img?.attr("data-src"),
            img?.attr("data-original"),
            img?.attr("data-lazy-src"),
            img?.attr("src")
        ).firstOrNull { it?.isNotBlank() == true && !it!!.startsWith("data:image", ignoreCase = true) }
            ?.substringBefore(",")?.substringBefore(" ")

        Log.d("HdPornFull", "Search result: title='$title', href=$href, poster=${poster != null}")

        return newMovieSearchResponse(title.trim(), href, TvType.NSFW) {
            this.posterUrl = poster?.let { fixUrlNull(it) }
        }
    }

    override suspend fun search(query: String, page: Int): SearchResponseList {
        // Verified format: https://www.hdpornfull.com/?s={query}
        val q = query.trim().replace(" ", "+")
        val url = if (page <= 1) "$mainUrl/?s=$q" else "$mainUrl/page/$page/?s=$q"
        val document = fetchDocument(url) { it.select("article").isNotEmpty() }
        val results = document?.select("article")?.mapNotNull { it.toSearchResult() } ?: emptyList()
        return newSearchResponseList(results, hasNext = results.isNotEmpty())
    }

    override suspend fun load(url: String): LoadResponse? {
        val document = fetchDocument(url) { it.selectFirst("h1") != null } ?: return null

        Log.d("HdPornFull", "Loading video page: $url, title='${document.title()}'")

        // Title extraction with fallbacks
        val title = document.selectFirst("main article h1")?.text()?.trim()
            ?: document.selectFirst("h1")?.text()?.trim()
            ?: document.selectFirst("meta[property='og:title']")?.attr("content")?.trim()
            ?: "Untitled"

        // Poster: prefer article's data-main-thumb, then og:image
        val article = document.selectFirst("article")
        val poster = article?.attr("data-main-thumb")
            ?.takeIf { it.isNotBlank() }
            ?.substringBefore(",")?.substringBefore(" ")
            ?: document.selectFirst("meta[property='og:image']")?.attr("content")

        // Plot: h4 block with release info
        val plot = document.selectFirst("main article h4")?.text()?.trim()
            ?: document.selectFirst("article h4")?.text()?.trim()

        Log.d("HdPornFull", "Extracted: title='$title', poster=${poster != null}, plot=${plot != null}")

        return newMovieLoadResponse(title, url, TvType.NSFW, url) {
            this.posterUrl = poster?.let { fixUrlNull(it) }
            this.plot = plot
        }
    }

    // Streaming hosts verified on the site
    private val streamingHosts = listOf(
        "streamtape.to", "streamtape.com", "streamtape.net",
        "lulust.com",
        "send.now", "send.cm",
        "smoothpre.com", "earnvids.com",
        "doodstream.com", "dood.so", "dood.pm", "dood.to", "dood.watch", "dood.cx",
        "mixdrop.co", "mixdrop.to", "mixdrop.sx",
        "filemoon.sx", "filemoon.to",
        "vidhide.com", "vidhidepro.com",
        "streamwish.com", "streamwish.to",
        "voe.sx"
    )

    // Ad/non-video iframe patterns to skip
    private val adFramePatterns = listOf(
        "cloudflare", "google", "stripchat", "chaturbate",
        "bongacams", "livejasmin", "exoclick", "trafficjunky",
        "doubleclick", "googlesyndication", "amazon-adsystem"
    )

    /**
     * Extracts all streaming links from the video page.
     * The page has a "Download / Stream Links" section with anchor tags.
     */
    private fun extractStreamLinks(document: Document): Set<String> {
        val links = LinkedHashSet<String>()

        // Primary: anchors in the article/content area
        val article = document.selectFirst("article") ?: document.selectFirst("main") ?: document

        // Find all links in the content area that match streaming hosts
        article.select("a[href]").forEach { anchor ->
            val href = anchor.attr("abs:href").ifBlank { anchor.attr("href") }
            if (href.isNotBlank() && streamingHosts.any { href.contains(it, ignoreCase = true) }) {
                links.add(href)
                Log.d("HdPornFull", "Found streaming link: $href")
            }
        }

        // Also check iframes (fallback)
        article.select("iframe[src]").forEach { iframe ->
            val src = iframe.attr("abs:src").ifBlank { iframe.attr("src") }
            if (src.isNotBlank() && adFramePatterns.none { src.contains(it, ignoreCase = true) }) {
                links.add(src)
                Log.d("HdPornFull", "Found iframe link: $src")
            }
        }

        // Also check for direct links in button-like elements
        article.select("button[onclick], a.btn, .stream-link, .download-link").forEach { elem ->
            val href = when {
                elem.tagName() == "a" -> elem.attr("abs:href").ifBlank { elem.attr("href") }
                elem.hasAttr("onclick") -> {
                    val onclick = elem.attr("onclick")
                    val urlMatch = Regex("https?://[^\"'\\s)]+").find(onclick)
                    urlMatch?.value ?: ""
                }
                else -> ""
            }
            if (href.isNotBlank() && streamingHosts.any { href.contains(it, ignoreCase = true) }) {
                links.add(href)
                Log.d("HdPornFull", "Found button/link element: $href")
            }
        }

        Log.d("HdPornFull", "Total streaming links extracted: ${links.size}")
        return links
    }

    override suspend fun loadLinks(
        data: String,
        isCasting: Boolean,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        val document = fetchDocument(data) { extractStreamLinks(it).isNotEmpty() } ?: return false
        var found = false
        val links = extractStreamLinks(document)

        if (links.isEmpty()) {
            Log.e("HdPornFull", "No streaming links found for $data")
            return false
        }

        Log.d("HdPornFull", "Found ${links.size} streaming links for $data")
        links.forEach { link ->
            Log.d("HdPornFull", "Trying extractor for: $link")
            if (loadExtractor(link, "$mainUrl/", subtitleCallback, callback)) found = true
        }
        return found
    }

    /**
     * Renders [pageUrl] in a real Android WebView and returns its HTML once
     * the page content (articles or a player-host link) has appeared and the
     * Cloudflare check has cleared. A WebView uses Chrome's TLS fingerprint,
     * so it passes checks that block OkHttp. Returns null on timeout.
     */
    @SuppressLint("SetJavaScriptEnabled")
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
                    settings.loadsImagesAutomatically = false // Speed up loading
                    settings.blockNetworkLoads = false
                }
                val startTime = System.currentTimeMillis()
                val maxWaitMs = 30_000L
                var pollCount = 0
                fun poll() {
                    if (!cont.isActive) return
                    pollCount++
                    // Ready when: articles present OR player-host links present
                    webView.evaluateJavascript(
                        "(function(){" +
                            "var articles = document.querySelectorAll('article').length;" +
                            "var playerLinks = document.querySelectorAll(\"a[href*='streamtape'],a[href*='doodstream'],a[href*='lulust'],a[href*='smoothpre'],a[href*='send.now'],a[href*='filemoon'],a[href*='mixdrop'],a[href*='vidhide'],a[href*='streamwish'],a[href*='voe.']\").length;" +
                            "var buttons = document.querySelectorAll('button[onclick], .stream-link, .download-link').length;" +
                            "var ready = (articles >= 3 || playerLinks > 0 || buttons > 0);" +
                            "return ready ? 'ready' : 'wait';" +
                        "})()"
                    ) { state ->
                        if (!cont.isActive) return@evaluateJavascript
                        val elapsed = System.currentTimeMillis() - startTime
                        Log.d("HdPornFull", "WebView poll #$pollCount for $pageUrl: state=$state, elapsed=${elapsed}ms")
                        if (state?.contains("ready") == true || elapsed >= maxWaitMs) {
                            webView.evaluateJavascript("document.documentElement.outerHTML") { html ->
                                if (cont.isActive) {
                                    val decoded = runCatching {
                                        JSONObject("{\"v\":$html}").getString("v")
                                    }.getOrNull()
                                    if (decoded != null && decoded.length > 1000) {
                                        Log.d("HdPornFull", "WebView success for $pageUrl: ${decoded.length} chars")
                                        cont.resume(decoded)
                                    } else {
                                        Log.e("HdPornFull", "WebView returned empty/small HTML for $pageUrl")
                                        cont.resume(null)
                                    }
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
                        Log.d("HdPornFull", "WebView onPageFinished: $url")
                        view.evaluateJavascript(
                            "document.querySelectorAll('video,audio').forEach(function(e){try{e.muted=true;e.volume=0;}catch(x){}});",
                            null
                        )
                        view.postDelayed({ poll() }, 3000)
                    }
                    
                    override fun onReceivedError(view: WebView?, request: WebResourceRequest?, error: WebResourceError?) {
                        Log.e("HdPornFull", "WebView error: ${error?.description}")
                    }
                }
                webView.loadUrl(pageUrl, browserHeaders)
                Handler(Looper.getMainLooper()).postDelayed({
                    if (cont.isActive) {
                        Log.e("HdPornFull", "WebView timeout for $pageUrl after ${maxWaitMs}ms")
                        cont.resume(null)
                        webView.destroy()
                    }
                }, maxWaitMs + 5000)
            }
        }
    }
}
