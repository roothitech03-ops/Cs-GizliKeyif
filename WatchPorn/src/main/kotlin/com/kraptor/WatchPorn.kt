// ! Bu araç @Kraptor123 tarafından | @Cs-GizliKeyif için yazılmıştır.

package com.kraptor

import com.lagradost.api.Log
import org.jsoup.nodes.Element
import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.utils.*
import com.lagradost.cloudstream3.LoadResponse.Companion.addActors
import com.lagradost.cloudstream3.network.CloudflareKiller
import okhttp3.Interceptor
import okhttp3.Response
import org.jsoup.Jsoup

class WatchPorn : MainAPI() {
    override var mainUrl              = "https://watchporn.to"
    override var name                 = "WatchPorn"
    override val hasMainPage          = true
    override var lang                 = "en"
    override val hasQuickSearch       = false
    override val supportedTypes       = setOf(TvType.NSFW)
    override val vpnStatus            = VPNStatus.MightBeNeeded

    override val mainPage = mainPageOf(
        "${mainUrl}/"                           to "Home",
        "${mainUrl}/latest-updates/"            to "Latest Videos",
        "${mainUrl}/top-rated/"                 to "Top Rated",
        "${mainUrl}/most-popular/"              to "Most Viewed",
        "${mainUrl}/categories/manyvids/"       to "ManyVids",
        "${mainUrl}/categories/onlyfans/"       to "OnlyFans",
        "${mainUrl}/categories/brazzers/"       to "Brazzers",
        "${mainUrl}/categories/reality-kings/"  to "Reality Kings",
        "${mainUrl}/categories/naughty-america/" to "Naughty America",
        "${mainUrl}/categories/bangbros/"       to "BangBros",
        "${mainUrl}/categories/mylf/"           to "MYLF",
        "${mainUrl}/categories/pure-taboo/"     to "Pure Taboo",
        "${mainUrl}/categories/family-strokes/" to "Family Strokes",
        "${mainUrl}/categories/blacked/"        to "Blacked",
        "${mainUrl}/categories/tushy/"          to "Tushy",
        "${mainUrl}/categories/vixen/"          to "Vixen",
        "${mainUrl}/categories/deeper/"         to "Deeper",
    )

    private val cloudflareKiller by lazy { CloudflareKiller() }
    private val interceptor      by lazy { CloudflareInterceptor(cloudflareKiller) }

    class CloudflareInterceptor(private val cloudflareKiller: CloudflareKiller): Interceptor {
        override fun intercept(chain: Interceptor.Chain): Response {
            val request  = chain.request()
            val response = chain.proceed(request)
            val doc      = Jsoup.parse(response.peekBody(1024 * 1024).string())

            if (doc.html().contains("Just a moment") || doc.html().contains("cf-browser-verification")) {
                return cloudflareKiller.intercept(chain)
            }

            return response
        }
    }

    override suspend fun getMainPage(page: Int, request: MainPageRequest): HomePageResponse {
        val url = if (page == 1) request.data else "${request.data}page/${page}/"
        val document = app.get(url, interceptor = interceptor, headers = mapOf(
            "User-Agent" to "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120.0.0.0 Safari/537.36",
            "Accept" to "text/html,application/xhtml+xml,application/xml;q=0.9,image/webp,*/*;q=0.8",
            "Accept-Language" to "en-US,en;q=0.5",
        )).document
        
        val home = document.select("div.video-block div.video-card").mapNotNull { it.toSearchResult() }
        
        return newHomePageResponse(
            list    = HomePageList(
                name               = request.name,
                list               = home,
                isHorizontalImages = true
            ),
            hasNext = home.isNotEmpty()
        )
    }

    private fun Element.toSearchResult(): SearchResponse? {
        val titleElement = this.selectFirst("div.video-title a") 
            ?: this.selectFirst("a[href*=/video/]")
            ?: return null
            
        val title = titleElement.text().trim()
            .ifEmpty { titleElement.attr("title") }
            .ifEmpty { return null }

        val href = fixUrlNull(titleElement.attr("href")) ?: return null

        val posterUrl = fixUrlNull(
            this.selectFirst("div.video-card-image img")?.attr("data-src")
                ?: this.selectFirst("div.video-card-image img")?.attr("src")
                ?: this.selectFirst("img")?.attr("data-src")
                ?: this.selectFirst("img")?.attr("src")
        )

        val duration = this.selectFirst("span.duration")?.text()
            ?: this.selectFirst("span.video-duration")?.text()

        return newMovieSearchResponse(title, href, TvType.NSFW) {
            this.posterUrl = posterUrl
            addExtra(duration?.let { "⏱ $it" } ?: "")
        }
    }

    override suspend fun search(query: String, page: Int): SearchResponseList {
        val searchQuery = query.replace(" ", "-")
        val url = if (page == 1) "${mainUrl}/search/${searchQuery}/" else "${mainUrl}/search/${searchQuery}/page/${page}/"
        
        val document = app.get(url, interceptor = interceptor, headers = mapOf(
            "User-Agent" to "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120.0.0.0 Safari/537.36",
            "Accept" to "text/html,application/xhtml+xml,application/xml;q=0.9,image/webp,*/*;q=0.8",
            "Accept-Language" to "en-US,en;q=0.5",
        )).document

        val results = document.select("div.video-block div.video-card").mapNotNull { it.toSearchResult() }

        return newSearchResponseList(results, hasNext = results.isNotEmpty())
    }

    override suspend fun load(url: String): LoadResponse? {
        val document = app.get(url, interceptor = interceptor, headers = mapOf(
            "User-Agent" to "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120.0.0.0 Safari/537.36",
            "Accept" to "text/html,application/xhtml+xml,application/xml;q=0.9,image/webp,*/*;q=0.8",
            "Accept-Language" to "en-US,en;q=0.5",
        )).document

        val title = document.selectFirst("h1")?.text()?.trim()
            ?: document.selectFirst("div.single-video-title h2")?.text()?.trim()
            ?: return null

        val posterUrl = fixUrlNull(
            document.selectFirst("meta[property=og:image]")?.attr("content")
                ?: document.selectFirst("meta[name=twitter:image]")?.attr("content")
        )

        val description = document.selectFirst("meta[name=description]")?.attr("content")
            ?: document.selectFirst("meta[property=og:description]")?.attr("content")

        val tags = document.select("p.tag-link a[href*=/tags/], div.video-tags a[href*=/tags/]").map { it.text() }.filter { it.isNotBlank() }

        val actors = document.select("div.video-models a[href*=/models/], div.models a[href*=/models/]").map { it.text() }.filter { it.isNotBlank() }.distinct()

        val recommendations = document.select("div.related-videos div.video-card, div.video-recommendation div.video-card").mapNotNull { it.toSearchResult() }

        return newMovieLoadResponse(title, url, TvType.NSFW, url) {
            this.posterUrl       = posterUrl
            this.plot            = description
            this.tags            = tags
            this.recommendations = recommendations
            addActors(actors.map { Actor(it) })
        }
    }

    override suspend fun loadLinks(
        data: String,
        isCasting: Boolean,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        Log.d("kraptor_$name", "loadLinks data = $data")
        
        val document = app.get(data, interceptor = interceptor, headers = mapOf(
            "User-Agent" to "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120.0.0.0 Safari/537.36",
            "Accept" to "text/html,application/xhtml+xml,application/xml;q=0.9,image/webp,*/*;q=0.8",
            "Accept-Language" to "en-US,en;q=0.5",
        )).document

        // Try to find iframe embed
        val iframeUrl = fixUrlNull(
            document.selectFirst("div.video-player iframe")?.attr("src")
                ?: document.selectFirst("div.player iframe")?.attr("src")
                ?: document.selectFirst("iframe[src*=" +
                    "embed,player,video,dood,vidoza,streamtape,tape,fembed,mixdrop,upstream" +
                    "]")?.attr("src")
                ?: document.selectFirst("iframe")?.attr("src")
        )

        Log.d("kraptor_$name", "iframeUrl = $iframeUrl")

        if (!iframeUrl.isNullOrBlank()) {
            // Try to load via extractors for known hosts
            val extracted = loadExtractor(iframeUrl, "$mainUrl/", subtitleCallback, callback)
            if (extracted) {
                Log.d("kraptor_$name", "Successfully extracted via loadExtractor")
                return true
            }
        }

        // Try to find video sources in script tags
        document.select("script").forEach { script ->
            val scriptContent = script.data()
            if (scriptContent.contains("sources") || scriptContent.contains("file")) {
                val sourcesRegex = Regex(""""sources"\s*:\s*(\[[^\]]+\])""")
                val sourcesMatch = sourcesRegex.find(scriptContent)
                if (sourcesMatch != null) {
                    try {
                        val sourcesJson = sourcesMatch.groupValues[1]
                        val fileRegex = Regex(""""file"\s*:\s*"([^"]+)"""")
                        val labelRegex = Regex(""""label"\s*:\s*"([^"]+)"""")
                        
                        val files = fileRegex.findAll(sourcesJson).map { it.groupValues[1] }.toList()
                        val labels = labelRegex.findAll(sourcesJson).map { it.groupValues[1] }.toList()
                        
                        files.forEachIndexed { index, fileUrl ->
                            val quality = labels.getOrNull(index)?.let { getQualityFromName(it) } ?: Qualities.Unknown
                            callback.invoke(
                                newExtractorLink(
                                    source = name,
                                    name = name,
                                    url = fileUrl,
                                    type = if (fileUrl.contains(".m3u8")) ExtractorLinkType.M3U8 else ExtractorLinkType.VIDEO,
                                ) {
                                    this.referer = "$mainUrl/"
                                    this.quality = quality
                                }
                            )
                        }
                    } catch (e: Exception) {
                        Log.d("kraptor_$name", "Error parsing sources JSON: ${e.message}")
                    }
                }
            }
        }

        return true
    }
}
