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

    // The site sits behind a Cloudflare "Verify you are human" check that
    // blocks plain HTTP clients (the app received the challenge page, so v1
    // showed the section names but zero videos). Requests use browser-like
    // headers, and anything blocked or empty is re-fetched through a real
    // WebView, whose Chrome TLS fingerprint passes the check (XTapes pattern).
    private val browserHeaders = mapOf(
        "User-Agent" to "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/126.0.0.0 Safari/537.36",
        "Accept" to "text/html,application/xhtml+xml,application/xml;q=0.9,*/*;q=0.8",
        "Accept-Language" to "en-US,en;q=0.9"
    )

    // Verified on the site: cat=110 is the "Latest Porn Movies" listing.
    override val mainPage = mainPageOf(
        "$mainUrl/?filter=latest" to "Latest Porn Videos",
        "$mainUrl/?filter=latest&cat=110" to "Latest Porn Movies"
    )

    private fun Document.isChallenge(): Boolean =
        title().contains("just a moment", ignoreCase = true) ||
            text().contains("verify you are human", ignoreCase = true) ||
            selectFirst("iframe[src*='challenges.cloudflare.com'], #challenge-form, #cf-challenge-running") != null

    /**
     * Fetches [url] over HTTP first. If the response is a Cloudflare block
     * (403 / challenge page) or [usable] rejects the parsed document, the page
     * is rendered in a WebView and that HTML is parsed instead.
     */
    private suspend fun fetchDocument(url: String, usable: (Document) -> Boolean): Document? {
        val httpDoc = runCatching {
            val response = app.get(url, headers = browserHeaders)
            if (response.code == 403) null else response.document
        }.getOrNull()
        if (httpDoc != null && !httpDoc.isChallenge() && usable(httpDoc)) return httpDoc

        Log.d("HdPornFull", "HTTP fetch blocked or unusable for $url; rendering via WebView")
        val html = getRenderedHtmlViaWebView(url) ?: return httpDoc
        return runCatching { Jsoup.parse(html, url) }.getOrNull() ?: httpDoc
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

    // Listing cards are article elements whose single anchor wraps the
    // thumbnail and metadata; the title is the thumbnail's alt text.
    private fun Element.toSearchResult(): SearchResponse? {
        val anchor = selectFirst("a[href][title]") ?: selectFirst("a[href]") ?: return null
        val href = fixUrlNull(anchor.attr("abs:href").ifBlank { anchor.attr("href") }) ?: return null
        val img = selectFirst("img")
        val title = listOfNotNull(
            anchor.attr("title").takeIf { it.isNotBlank() },
            img?.attr("alt")?.takeIf { it.isNotBlank() },
            img?.attr("title")?.takeIf { it.isNotBlank() },
            selectFirst("h2, h3")?.text()?.takeIf { it.isNotBlank() },
            anchor.text().takeIf { it.isNotBlank() }
        ).firstOrNull() ?: return null
        val poster = img?.let { image ->
            listOf("data-src", "data-original", "data-lazy-src", "src", "data-srcset", "srcset")
                .asSequence()
                .map { image.attr(it) }
                .firstOrNull { it.isNotBlank() && !it.startsWith("data:image", ignoreCase = true) }
                ?.substringBefore(",")?.substringBefore(" ")
        }
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
        val title = document.selectFirst("main article h1")?.text()?.trim()
            ?: document.selectFirst("h1")?.text()?.trim()
            ?: document.selectFirst("meta[property=og:title]")?.attr("content")?.trim()
            ?: return null
        return newMovieLoadResponse(title, url, TvType.NSFW, url) {
            posterUrl = fixUrlNull(document.selectFirst("meta[property=og:image]")?.attr("content"))
            // The page has no prose description; its h4 block carries the
            // release info (file name, size, duration, codec).
            plot = document.selectFirst("main article h4")?.text()?.trim()
                ?: document.selectFirst("article h4")?.text()?.trim()
        }
    }

    // Every player source the video page exposes: the in-page player iframe
    // plus the stream links listed on the page.
    // shortcut: RapidGator links are skipped on purpose, it is a
    // download-only host with no streaming extractor.
    private val hostHints = listOf(
        "streamtape", "lulust", "lulu", "smoothpre", "earnvids", "send.now",
        "dood", "mixdrop", "bigwarp", "filemoon", "streamhide",
        "vidhide", "filelions", "streamwish", "voe."
    )

    // Iframes that are never the video player (verified on the site: the
    // article also carries live-cam and gambling ad frames).
    private val adFrameHints = listOf(
        "cloudflare", "google", "stripchat", "chaturbate",
        "bongacams", "livejasmin", "exoclick", "trafficjunky"
    )

    private fun extractLinks(document: Document): Set<String> {
        val links = LinkedHashSet<String>()
        document.select("article iframe[src]").forEach { iframe ->
            val src = iframe.attr("abs:src").ifBlank { iframe.attr("src") }
            if (src.isNotBlank() && adFrameHints.none { src.contains(it) }) links.add(src)
        }
        document.select("article a[href]").forEach { anchor ->
            val href = anchor.attr("abs:href").ifBlank { anchor.attr("href") }
            if (hostHints.any { href.contains(it) }) links.add(href)
        }
        return links
    }

    override suspend fun loadLinks(
        data: String,
        isCasting: Boolean,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        val document = fetchDocument(data) { extractLinks(it).isNotEmpty() } ?: return false
        var found = false
        extractLinks(document).forEach { link ->
            if (loadExtractor(link, "$mainUrl/", subtitleCallback, callback)) found = true
        }
        return found
    }

    /**
     * Renders [pageUrl] in a real Android WebView and returns its HTML once
     * the page content (articles or a player iframe) has appeared and the
     * Cloudflare check has cleared. A WebView uses Chrome's TLS fingerprint,
     * so it passes checks that block OkHttp. Returns null on timeout.
     * Same pattern as the XTapes provider.
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
                }
                val startTime = System.currentTimeMillis()
                val maxWaitMs = 25_000L
                fun poll() {
                    if (!cont.isActive) return
                    webView.evaluateJavascript(
                        "(function(){if(document.title.toLowerCase().indexOf('just a moment')>=0){return 'cf';}var a=document.querySelectorAll('article').length;var f=document.querySelectorAll('iframe').length;return (a>=3||f>0)?'ready':'wait';})()"
                    ) { state ->
                        if (!cont.isActive) return@evaluateJavascript
                        val elapsed = System.currentTimeMillis() - startTime
                        if (state?.contains("ready") == true || elapsed >= maxWaitMs) {
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
                        // Mute any media in the rendered page so no audio leaks
                        // while the hidden WebView renders a video page.
                        view.evaluateJavascript(
                            "document.querySelectorAll('video,audio').forEach(function(e){try{e.muted=true;e.volume=0;}catch(x){}});",
                            null
                        )
                        view.postDelayed({ poll() }, 3000)
                    }
                }
                webView.loadUrl(pageUrl, browserHeaders)
                Handler(Looper.getMainLooper()).postDelayed({
                    if (cont.isActive) {
                        cont.resume(null)
                        webView.destroy()
                    }
                }, maxWaitMs + 5000)
            }
        }
    }
}
