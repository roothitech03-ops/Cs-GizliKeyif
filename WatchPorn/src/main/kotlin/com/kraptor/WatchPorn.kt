package com.kraptor // Using the package name from your working file

import android.content.Context
import android.webkit.WebView
import android.webkit.WebViewClient
import android.os.Handler
import android.os.Looper
import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.utils.*
import com.lagradost.cloudstream3.LoadResponse.Companion.addActors
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.jsoup.nodes.Element
import kotlin.coroutines.resume
import kotlin.coroutines.suspendCoroutine

// Main provider class, taking Context for WebView operations
class WatchPornProvider(context: Context) : MainAPI() {
    override var mainUrl = "https://watchporn.to"
    override var name = "WatchPorn"
    override val hasMainPage = true
    override var lang = "en"
    override val hasQuickSearch = false // Set to true if quick search is implemented
    override val supportedTypes = setOf(TvType.NSFW) // Using NSFW as per your original file

    private val appContext = context.applicationContext // Use application context to prevent memory leaks

    // Define main page categories as in your original file
    override val mainPage = mainPageOf(
        "${mainUrl}/latest-updates/" to "Latest",
        "${mainUrl}/top-rated/" to "Top Rated",
        "${mainUrl}/most-popular/" to "Most Popular",
        "${mainUrl}/categories/manyvids/" to "ManyVids",
        "${mainUrl}/categories/onlyfans/" to "OnlyFans",
        "${mainUrl}/categories/xvideosred/" to "XVideosRed",
        "${mainUrl}/categories/primalfetish/" to "PrimalFetish",
        "${mainUrl}/categories/brazzersexxtra/" to "BrazzersExxtra",
        "${mainUrl}/categories/julesjordan/" to "JulesJordan",
        "${mainUrl}/categories/pascalssubsluts/" to "PascalsSubSluts",
        "${mainUrl}/categories/tabooheat/" to "TabooHeat",
        "${mainUrl}/categories/evilangel/" to "Evilangel",
        "${mainUrl}/categories/outofthefamily/" to "OutOfTheFamily",
        "${mainUrl}/categories/missax/" to "MissaX",
        "${mainUrl}/categories/loveherfeet/" to "LoveHerFeet",
        "${mainUrl}/categories/mommyblowsbest/" to "MommyBlowsBest",
        "${mainUrl}/categories/alexlegend/" to "AlexLegend",
        "${mainUrl}/categories/analized/" to "Analized",
        "${mainUrl}/categories/analintroductions/" to "AnalIntroductions",
        "${mainUrl}/categories/blackedraw/" to "BlackedRaw",
        "${mainUrl}/categories/immeganlive/" to "ImMeganLive",
        "${mainUrl}/categories/vixen/" to "Vixen",
        "${mainUrl}/categories/rkprime/" to "RKPrime",
        "${mainUrl}/categories/puretaboo/" to "PureTaboo",
        "${mainUrl}/categories/deeper/" to "Deeper",
        "${mainUrl}/categories/tushy/" to "Tushy",
        "${mainUrl}/categories/mypervyfamily/" to "MyPervyFamily",
        "${mainUrl}/categories/familytherapy/" to "FamilyTherapy",
        "${mainUrl}/categories/hotwifexxx/" to "HotwifeXXX",
        "${mainUrl}/categories/sislovesme/" to "SisLovesMe",
        "${mainUrl}/categories/wcaproductions/" to "WCAProductions",
        "${mainUrl}/categories/jamieyoung/" to "JamieYoung",
        "${mainUrl}/categories/familystrokes/" to "FamilyStrokes",
        "${mainUrl}/categories/allherluv/" to "AllHerLuv",
        "${mainUrl}/categories/blacked/" to "Blacked",
        "${mainUrl}/categories/tightandteen/" to "TightAndTeen",
        "${mainUrl}/categories/nubiles/" to "Nubiles",
        "${mainUrl}/categories/tushyraw/" to "TushyRaw",
        "${mainUrl}/categories/dadcrush/" to "DadCrush",
        "${mainUrl}/categories/meana-wolf/" to "Meana Wolf",
        "${mainUrl}/categories/cosplay/" to "Cosplay",
        "${mainUrl}/categories/pervmom/" to "PervMom",
        "${mainUrl}/categories/willtilexxx/" to "WillTileXXX",
        "${mainUrl}/categories/bangbus/" to "BangBus",
        "${mainUrl}/categories/mylifeinmiami/" to "MyLifeInMiami",
        "${mainUrl}/categories/analvids/" to "AnalVids",
        "${mainUrl}/categories/pornworld/" to "PornWorld",
        "${mainUrl}/categories/brattysis/" to "BrattySis"
    )

    // Fetches content for the main page categories
    override suspend fun getMainPage(page: Int, request: MainPageRequest): HomePageResponse {
        val url = if (page <= 1) request.data else "${request.data}$page/"
        val document = app.get(url).document
        val home = document.select("div.thumb.item").mapNotNull { it.toSearchResponse() }

        return newHomePageResponse(request.name, home)
    }

    // Converts a Jsoup Element to a SearchResponse object
    private fun Element.toSearchResponse(): SearchResponse? {
        val title = this.selectFirst("span.thumb__title")?.text()?.trim() ?: return null
        val href = fixUrlNull(this.selectFirst("a")?.attr("href")) ?: return null
        val posterUrl = fixUrlNull(this.selectFirst("img")?.attr("data-webp") ?: this.selectFirst("img")?.attr("src"))
        val rating = this.select("span.thumb__meta-item").lastOrNull()?.text()?.replace("%", "")?.trim()

        // Store URL and poster together for the load function
        return newMovieSearchResponse(title, "$href|$posterUrl", TvType.NSFW) {
            this.posterUrl = posterUrl
            this.score = Score.from100(rating)
        }
    }

    // Handles search queries
    override suspend fun search(query: String): List<SearchResponse> {
        // The search URL from your original file, handles pagination implicitly
        val url = "${mainUrl}/search/?q=$query&mode=async&function=get_block&block_id=list_videos_videos_list_search_result&category_ids=&sort_by=&from_videos=1"
        val document = app.get(url).document

        return document.select("div.thumb.item").mapNotNull { it.toSearchResponse() }
    }

    // Loads detailed information for a selected video
    override suspend fun load(data: String): LoadResponse? {
        // Split the data string to get the actual URL and stored poster URL
        val (url, storedPoster) = data.split("|").let {
            it[0] to it.getOrNull(1)
        }

        val response = app.get(url)
        val document = response.document
        val cookies = response.cookies.toString()

        val title = document.selectFirst("h1.single__content-title")?.text()?.trim() ?: return null
        val poster = storedPoster ?: document.selectFirst("meta[property=og:image]")?.attr("content")

        val tags = document.select("div.single__info-row:contains(Tags:) a").map { it.text().trim() }
        val actors = document.select("div.single__info-row:contains(Models:) a").map { Actor(it.text().trim()) }

        // Extract duration from the video player if available
        val durationText = document.selectFirst("div.fp-time-duration")?.text()?.trim()
        val totalMinutes = durationText?.toDurationMinutes()

        val recommendations = document.select("div.related-videos div.thumb.item").mapNotNull {
            it.toSearchResponse()
        }

        return newMovieLoadResponse(title, url, TvType.NSFW, url) {
            this.posterUrl = poster
            // Add headers for poster if needed, as in your original file
            this.posterHeaders = mapOf(
                "User-Agent" to "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/131.0.0.0 Safari/537.36",
                "Referer" to "$mainUrl/",
                "Cookie" to cookies
            )
            this.tags = tags
            this.duration = totalMinutes
            this.recommendations = recommendations
            addActors(actors)
        }
    }

    // Helper function to convert duration string to minutes
    private fun String.toDurationMinutes(): Int? {
        val parts = this.split(":").mapNotNull { it.toIntOrNull() }
        return when (parts.size) {
            3 -> (parts[0] * 60) + parts[1] // Hours:Minutes:Seconds
            2 -> parts[0] // Minutes:Seconds
            else -> null
        }
    }

    // Extracts video URLs using WebView, similar to your original file
    // This is crucial for sites that load video sources dynamically with JavaScript
    @SuppressLint("SetJavaScriptEnabled")
    override suspend fun loadLinks(data: String, isCasting: Boolean, subtitleCallback: (SubtitleFile) -> Unit, callback: (ExtractorLink) -> Unit): Boolean {
        val document = app.get(data).text // Get the HTML content of the page

        // Use WebView to execute JavaScript and extract video URLs
        val videoUrls = withContext(Dispatchers.Main) {
            suspendCoroutine<List<String>> { continuation ->
                val wv = WebView(appContext).apply {
                    settings.javaScriptEnabled = true
                    settings.domStorageEnabled = true

                    webViewClient = object : WebViewClient() {
                        override fun onPageFinished(view: WebView?, url: String?) {
                            super.onPageFinished(view, url)
                            // Delay to ensure all dynamic content is loaded
                            Handler(Looper.getMainLooper()).postDelayed({
                                view?.evaluateJavascript("""
                                (function() {
                                    var videos = [];
                                    // Try to get video URLs from flashvars object
                                    if (typeof flashvars !== 'undefined') {
                                        if (flashvars.video_url && flashvars.video_url.indexOf('https://') !== -1) {
                                            videos.push(flashvars.video_url);
                                        }
                                        if (flashvars.video_alt_url && flashvars.video_alt_url.indexOf('https://') !== -1) {
                                            videos.push(flashvars.video_alt_url);
                                        }
                                    }
                                    // If not found in flashvars, scan script tags for .mp4 links
                                    if (videos.length === 0) {
                                        var scripts = document.getElementsByTagName('script');
                                        for (var i = 0; i < scripts.length; i++) {
                                            var text = scripts[i].textContent;
                                            var matches = text.match(/https:\/\/watchporn\.to\/get_file\/[^\s\'\"]+\.mp4[^\s\'\"]*/g);
                                            if (matches) {
                                                for (var j = 0; j < matches.length; j++) {
                                                    videos.push(matches[j]);
                                                }
                                            }
                                        }
                                    }
                                    // Remove duplicates
                                    videos = videos.filter(function(item, pos) {
                                        return videos.indexOf(item) === pos;
                                    });
                                    return JSON.stringify(videos);
                                })();
                                """) { result ->
                                    try {
                                        val cleanResult = result.trim('"').replace("\\", "")
                                        val jsonArray = JSONArray(cleanResult)
                                        val urls = mutableListOf<String>()
                                        for (i in 0 until jsonArray.length()) {
                                            urls.add(jsonArray.getString(i))
                                        }
                                        continuation.resume(urls)
                                    } catch (e: Exception) {
                                        // Log.e("VideoExtractor", "Error: ${e.message}") // Use Cloudstream's Log if available
                                        continuation.resume(emptyList())
                                    } finally {
                                        // Clean up WebView to prevent memory leaks
                                        Handler(Looper.getMainLooper()).post {
                                            try {
                                                this@apply.stopLoading()
                                                this@apply.clearHistory()
                                                this@apply.destroy()
                                            } catch (ignored: Throwable) {}
                                        }
                                    }
                                }
                            }, 100) // Small delay to ensure JS execution
                        }
                    }
                    loadDataWithBaseURL(mainUrl, document, "text/html", "UTF-8", null)
                }
            }
        }

        // Process extracted video URLs
        videoUrls.forEach { url ->
            // Attempt to extract quality from the URL, e.g., /149927_720p.mp4
            val qualityText = url.substringBeforeLast("/").substringAfterLast("/").substringBefore(".mp4").substringAfterLast("_")
            val quality = getQualityFromName(qualityText)

            callback.invoke(
                newExtractorLink(
                    name,
                    name,
                    url,
                    referer = "$mainUrl/", // Referer is important for some sites
                    quality = quality
                )
            )
        }

        return videoUrls.isNotEmpty()
    }

    // Helper function to map quality text to Cloudstream's Qualities enum
    private fun getQualityFromName(name: String): Int {
        return when (name.lowercase()) {
            "240p" -> Qualities.P240.value
            "360p" -> Qualities.P360.value
            "480p" -> Qualities.P480.value
            "720p" -> Qualities.P720.value
            "1080p" -> Qualities.P1080.value
            "1440p" -> Qualities.P1440.value
            "2160p" -> Qualities.P2160.value
            else -> Qualities.Unknown.value
        }
    }
}
