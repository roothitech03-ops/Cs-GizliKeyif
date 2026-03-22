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
        "${mainUrl}/categories/step-siblings-caught/" to "Step Siblings Caught",
        "${mainUrl}/categories/moms-teach-sex/" to "Moms Teach Sex",
        "${mainUrl}/categories/black/"          to "Black",
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
        
        val home = document.select("div.video-block div.video-card, div.item, div.video-item, div.thumb-block, div.video").mapNotNull { it.toSearchResult() }
        
        // Alternative selectors if the first one doesn't work
        val homeAlt = if (home.isEmpty()) {
            document.select("a[href*=/video/]").mapNotNull { linkElem ->
                val parent = linkElem.parent() ?: return@mapNotNull null
                parent.toSearchResultFromLink(linkElem)
            }
        } else home

        return newHomePageResponse(
            list    = HomePageList(
                name               = request.name,
                list               = homeAlt,
                isHorizontalImages = true
            ),
            hasNext = homeAlt.isNotEmpty()
        )
    }

    private fun Element.toSearchResult(): SearchResponse? {
        // Try multiple selectors for the title
        val title = this.selectFirst("div.video-title a, div.video-card-body div.video-title a, a.video-link, a[title], span.title")?.text()
            ?: this.selectFirst("a")?.attr("title")
            ?: return null

        // Try multiple selectors for the href
        val href = fixUrlNull(
            this.selectFirst("div.video-title a, div.video-card-body div.video-title a, a.video-link")?.attr("href")
                ?: this.selectFirst("a")?.attr("href")
        ) ?: return null

        // Try multiple selectors for the poster
        val posterUrl = fixUrlNull(
            this.selectFirst("img")?.attr("data-src")
                ?: this.selectFirst("img")?.attr("src")
                ?: this.selectFirst("div.video-card-image img")?.attr("data-src")
                ?: this.selectFirst("div.video-thumb img")?.attr("src")
        )

        // Try to get duration
        val duration = this.selectFirst("span.duration, span.video-duration, div.duration")?.text()

        // Try to get quality label
        val quality = this.selectFirst("span.quality, span.hd-label")?.text()

        return newMovieSearchResponse(title, href, TvType.NSFW) {
            this.posterUrl = posterUrl
            addExtra(duration?.let { "⏱ $it" } ?: "")
            addExtra(quality?.let { "🎬 $it" } ?: "")
        }
    }

    private fun Element.toSearchResultFromLink(linkElem: Element): SearchResponse? {
        val title = linkElem.attr("title").takeIf { it.isNotBlank() } 
            ?: linkElem.text().takeIf { it.isNotBlank() } 
            ?: return null
        
        val href = fixUrlNull(linkElem.attr("href")) ?: return null
        
        val posterUrl = fixUrlNull(
            this.selectFirst("img")?.attr("data-src") 
                ?: this.selectFirst("img")?.attr("src")
        )

        return newMovieSearchResponse(title, href, TvType.NSFW) {
            this.posterUrl = posterUrl
        }
    }

    override suspend fun search(query: String, page: Int): SearchResponseList {
        val url = "${mainUrl}/search/${query.replace(" ", "-")}/"
        val pageUrl = if (page > 1) "${url}page/${page}/" else url
        
        val document = app.get(pageUrl, interceptor = interceptor, headers = mapOf(
            "User-Agent" to "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120.0.0.0 Safari/537.36",
            "Accept" to "text/html,application/xhtml+xml,application/xml;q=0.9,image/webp,*/*;q=0.8",
            "Accept-Language" to "en-US,en;q=0.5",
        )).document

        val results = document.select("div.video-block div.video-card, div.item, div.video-item, div.thumb-block, div.video").mapNotNull { it.toSearchResult() }
        
        // Alternative selectors
        val resultsAlt = if (results.isEmpty()) {
            document.select("a[href*=/video/]").mapNotNull { linkElem ->
                val parent = linkElem.parent() ?: return@mapNotNull null
                parent.toSearchResultFromLink(linkElem)
            }
        } else results

        return newSearchResponseList(resultsAlt, hasNext = resultsAlt.isNotEmpty())
    }

    override suspend fun load(url: String): LoadResponse? {
        val document = app.get(url, interceptor = interceptor, headers = mapOf(
            "User-Agent" to "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120.0.0.0 Safari/537.36",
            "Accept" to "text/html,application/xhtml+xml,application/xml;q=0.9,image/webp,*/*;q=0.8",
            "Accept-Language" to "en-US,en;q=0.5",
        )).document

        // Extract title
        val title = document.selectFirst("h1, div.single-video-title h2, div.video-title h1, h1.entry-title")?.text()?.trim()
            ?: return null

        // Extract poster from meta tags or video player area
        val posterUrl = fixUrlNull(
            document.selectFirst("meta[property=og:image]")?.attr("content")
                ?: document.selectFirst("div.single-video-left div.video-poster img")?.attr("src")
                ?: document.selectFirst("div.video-player img")?.attr("src")
                ?: document.selectFirst("div.player img")?.attr("src")
        )

        // Extract description
        val description = document.selectFirst("meta[name=description], meta[property=og:description]")?.attr("content")
            ?: document.selectFirst("div.video-description, div.single-video-info, div.description")?.text()?.trim()

        // Extract tags
        val tags = document.select("div.video-tags a, p.tag-link a, div.tags a, a[href*=/tags/]").map { it.text() }.filter { it.isNotBlank() }

        // Extract actors/models
        val actors = document.select("div.video-models a, div.models a, a[href*=/models/]").map { it.text() }.filter { it.isNotBlank() }.distinct()

        // Extract duration
        val durationText = document.selectFirst("span.duration, div.video-duration, li.icon.fa-clock-o")?.text()
        val duration = durationText?.let { text ->
            val parts = text.split(" ", ":", "h", "m", "s").filter { it.isNotBlank() }
            when {
                text.contains("h") && text.contains("m") -> {
                    val hours = text.substringBefore("h").trim().toIntOrNull() ?: 0
                    val minutes = text.substringAfter("h").substringBefore("m").trim().toIntOrNull() ?: 0
                    hours * 60 + minutes
                }
                text.contains(":") -> {
                    val timeParts = text.split(":")
                    when (timeParts.size) {
                        3 -> timeParts[0].toIntOrNull()?.times(60)?.plus(timeParts[1].toIntOrNull() ?: 0) ?: 0
                        2 -> timeParts[0].toIntOrNull() ?: 0
                        else -> 0
                    }
                }
                else -> text.toIntOrNull()
            }
        }

        // Extract recommendations/related videos
        val recommendations = document.select("div.related-videos div.video-card, div.video-recommendation div.video-card, div.related div.item").mapNotNull { it.toSearchResult() }

        return newMovieLoadResponse(title, url, TvType.NSFW, url) {
            this.posterUrl       = posterUrl
            this.plot            = description
            this.tags            = tags
            this.duration        = duration
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
            document.selectFirst("div.video-player iframe, div.player iframe, div.embed-responsive iframe")?.attr("src")
                ?: document.selectFirst("iframe[src*=embed], iframe[src*=player], iframe[src*=video]")?.attr("src")
                ?: document.selectFirst("iframe[src*=dood], iframe[src*=vidoza], iframe[src*=streamtape]")?.attr("src")
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

            // If loadExtractor didn't work, try to get direct video from iframe page
            val iframeResponse = app.get(iframeUrl, referer = data, headers = mapOf(
                "User-Agent" to "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120.0.0.0 Safari/537.36",
                "Accept" to "text/html,application/xhtml+xml,application/xml;q=0.9,image/webp,*/*;q=0.8",
                "Accept-Language" to "en-US,en;q=0.5",
                "Referer" to "$mainUrl/",
            ))
            
            val iframeDoc = iframeResponse.text

            // Try to find video sources in iframe
            val videoRegex = Regex("""(?:src|file|url)[:\s]*["'](https?://[^"']+\.(?:mp4|m3u8|mkv|avi))["']""")
            val matches = videoRegex.findAll(iframeDoc)
            
            matches.forEach { match ->
                val videoUrl = match.groupValues[1]
                val quality = when {
                    videoUrl.contains("1080") || videoUrl.contains("fhd") -> Qualities.P1080
                    videoUrl.contains("720") || videoUrl.contains("hd") -> Qualities.P720
                    videoUrl.contains("480") -> Qualities.P480
                    videoUrl.contains("360") -> Qualities.P360
                    videoUrl.contains("240") -> Qualities.P240
                    else -> Qualities.Unknown
                }
                
                callback.invoke(
                    newExtractorLink(
                        source = name,
                        name = name,
                        url = videoUrl,
                        type = ExtractorLinkType.VIDEO,
                    ) {
                        this.referer = iframeUrl
                        this.quality = quality
                    }
                )
            }

            // Try to find m3u8 sources
            val m3u8Regex = Regex("""["'](https?://[^"']+\.m3u8[^"']*)["']""")
            val m3u8Matches = m3u8Regex.findAll(iframeDoc)
            
            m3u8Matches.forEach { match ->
                val m3u8Url = match.groupValues[1]
                callback.invoke(
                    newExtractorLink(
                        source = name,
                        name = "$name (HLS)",
                        url = m3u8Url,
                        type = ExtractorLinkType.M3U8,
                    ) {
                        this.referer = iframeUrl
                    }
                )
            }
        }

        // Try to find direct video sources in the main page
        val videoRegex = Regex("""(?:src|file|url)[:\s]*["'](https?://[^"']+\.(?:mp4|m3u8|mkv|avi))["']""")
        val docText = document.html()
        val matches = videoRegex.findAll(docText)
        
        matches.forEach { match ->
            val videoUrl = match.groupValues[1]
            callback.invoke(
                newExtractorLink(
                    source = name,
                    name = name,
                    url = videoUrl,
                    type = if (videoUrl.contains(".m3u8")) ExtractorLinkType.M3U8 else ExtractorLinkType.VIDEO,
                ) {
                    this.referer = "$mainUrl/"
                }
            )
        }

        // Try to find video sources in script tags (JSON data)
        document.select("script").forEach { script ->
            val scriptContent = script.data()
            if (scriptContent.contains("sources") || scriptContent.contains("video")) {
                // Look for sources array with file URLs
                val sourcesRegex = Regex(""""sources"\s*:\s*(\[[^\]]+\])""")
                val sourcesMatch = sourcesRegex.find(scriptContent)
                if (sourcesMatch != null) {
                    try {
                        val sourcesJson = sourcesMatch.groupValues[1]
                        val fileRegex = Regex(""""file"\s*:\s*"([^"]+)"""")
                        val qualityRegex = Regex(""""label"\s*:\s*"([^"]+)""")
                        
                        val files = fileRegex.findAll(sourcesJson).map { it.groupValues[1] }.toList()
                        val labels = qualityRegex.findAll(sourcesJson).map { it.groupValues[1] }.toList()
                        
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
