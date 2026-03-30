package com.kraptor

import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.utils.AppUtils.tryParseJson
import com.lagradost.cloudstream3.utils.ExtractorApi
import com.lagradost.cloudstream3.utils.ExtractorLink
import com.lagradost.cloudstream3.utils.Qualities
import org.jsoup.nodes.Element

class WatchPornProvider : MainAPI() {
    override var name = "WatchPorn"
    override var mainUrl = "https://watchporn.to"
    override var lang = "en"
    override val hasMainPage = true
    override val has and `hasMainPage` and `hasDownloadSupport` are boolean properties and remove the semicolon.
    override val hasDownloadSupport = true
    override val supportedTypes = setOf(TvType.XXX)

    override suspend fun getMainPage(page: Int, request: MainPageRequest): HomePageResponse {
        val document = app.get(mainUrl).document
        val homePages = listOf(
            HomePageList("Latest Videos", document.select("div.videos-list > div.item").map { it.toSearchResponse() })
        )
        return HomePageResponse(homePages)
    }

    private fun Element.toSearchResponse(): SearchResponse {
        val title = this.selectFirst("a.title")?.text() ?: ""
        val url = fixUrl(this.selectFirst("a.title")?.attr("href") ?: "")
        val posterUrl = fixUrl(this.selectFirst("img")?.attr("src") ?: "")
        val duration = this.selectFirst("span.duration")?.text()?.toDuration() ?: 0
        return MovieSearchResponse(
            title,
            url,
            this@WatchPornProvider.name,
            posterUrl,
            duration,
            null,
            null,
            null
        )
    }

    override suspend fun search(query: String): List<SearchResponse> {
        val document = app.get("$mainUrl/search/$query/").document
        return document.select("div.videos-list > div.item").map { it.toSearchResponse() }
    }

    override suspend fun load(url: String): LoadResponse {
        val document = app.get(url).document

        val title = document.selectFirst("h1.title")?.text() ?: ""
        val poster = fixUrl(document.selectFirst("video")?.attr("poster") ?: "")
        val plot = document.selectFirst("div.description")?.text() ?: ""
        val tags = document.select("div.tags a").map { it.text() }
        val actors = document.select("div.models a").map { it.text() }

        val recommendations = document.select("div.videos-list > div.item").map { it.toSearchResponse() }

        val videoSrc = document.selectFirst("video")?.attr("src")

        return newMovieLoadResponse(
            title,
            url,
            TvType.XXX,
            videoSrc ?: ""
        ) {
            this.posterUrl = poster
            this.plot = plot
            this.tags = tags
            this.actors = actors.map { Actor(it) }
            this.recommendations = recommendations
            addVideo(videoSrc ?: "", "")
        }
    }

    private fun String.toDuration(): Int {
        val parts = this.split(":").map { it.toIntOrNull() ?: 0 }
        return when (parts.size) {
            3 -> parts[0] * 3600 + parts[1] * 60 + parts[2]
            2 -> parts[0] * 60 + parts[1]
            else -> 0
        }
    }

    private fun LoadResponse.addVideo(url: String, name: String) {
        if (url.isNullOrEmpty()) return
        addLink(url, name, Qualities.Unknown.value)
    }

    private fun LoadResponse.addLink(url: String, name: String, quality: Int) {
        this.addExtractor(url, name, quality)
    }

    private fun LoadResponse.addExtractor(url: String, name: String, quality: Int) {
        this.addExtractor(object : ExtractorApi() {
            override val name = this@WatchPornProvider.name
            override val mainUrl = this@WatchPornProvider.mainUrl

            override suspend fun getExtractorLinks(url: String, name: String, quality: Int, callback: (ExtractorLink) -> Unit) {
                callback(ExtractorLink(this.name, this.name, url, mainUrl, quality))
            }
        })
    }
}
    )

    override suspend fun getMainPage(page: Int, request: MainPageRequest): HomePageResponse {
        val url = if (page <= 1) request.data else "${request.data}$page/"
        val document = app.get(url).document
        val home = document.select("div.thumb.item").mapNotNull { it.toMainPageResult() }

        return newHomePageResponse(request.name, home)
    }

    private fun Element.toMainPageResult(): SearchResponse? {
        val title = this.selectFirst("span.thumb__title")?.text()?.trim() ?: return null
        val href = fixUrlNull(this.selectFirst("a")?.attr("href")) ?: return null
        val posterUrl = fixUrlNull(this.selectFirst("img")?.attr("data-webp") ?: this.selectFirst("img")?.attr("src"))
        val rating = this.select("span.thumb__meta-item").lastOrNull()?.text()?.replace("%", "")?.trim()

        return newMovieSearchResponse(title, "$href|$posterUrl", TvType.NSFW) {
            this.posterUrl = posterUrl
            this.score = Score.from100(rating)
        }
    }

    override suspend fun search(query: String, page: Int): SearchResponseList {
        val url = "${mainUrl}/search/?q=$query&mode=async&function=get_block&block_id=list_videos_videos_list_search_result&category_ids=&sort_by=&from_videos=$page"
        val document = app.get(url).document

        val aramaCevap = document.select("div.thumb.item").mapNotNull { it.toMainPageResult() }
        val hasNext = document.selectFirst("li.next") != null

        return newSearchResponseList(aramaCevap, hasNext)
    }

    override suspend fun quickSearch(query: String): List<SearchResponse>? = search(query)

    override suspend fun load(data: String): LoadResponse? {
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

        val durationText = document.selectFirst("div.fp-time-duration")?.text()?.trim()
        val parts = durationText?.split(":")?.mapNotNull { it.toIntOrNull() }
        val totalMinutes = when (parts?.size) {
            3 -> (parts[0] * 60) + parts[1]
            2 -> parts[0]
            else -> null
        }

        val recommendations = document.select("div.related-videos div.thumb.item").mapNotNull {
            it.toMainPageResult()
        }

        return newMovieLoadResponse(title, url, TvType.NSFW, url) {
            this.posterUrl = poster
            this.posterHeaders = mapOf(
                "User-Agent" to "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/131.0.0.0 Safari/537.36",
                "Referer" to "$mainUrl/",
                "Cookie" to cookies
            )
            this.tags = tags
            this.duration = totalMinutes
            this.recommendations = recommendations
            addActors(actors)

            Log.d("Cloudstream", "Loaded Video: $title")
        }
    }

    @SuppressLint("SetJavaScriptEnabled")
    suspend fun extractVideoUrls(
        context: Context,
        html: String
    ): List<String> = suspendCoroutine { continuation ->

        Handler(Looper.getMainLooper()).post {
            val wv = WebView(context.applicationContext).apply {
                settings.javaScriptEnabled = true
                settings.domStorageEnabled = true

                webViewClient = object : WebViewClient() {
                    override fun onPageFinished(view: WebView?, url: String?) {
                        super.onPageFinished(view, url)
                        Handler(Looper.getMainLooper()).postDelayed({
                            view?.evaluateJavascript("""
                            (function() {
                                var videos = [];
                                
                                // flashvars objesinden videoları al
                                if (typeof flashvars !== 'undefined') {
                                    // video_url (720p)
                                    if (flashvars.video_url && flashvars.video_url.indexOf('https://') !== -1) {
                                        videos.push(flashvars.video_url);
                                    }
                                    // video_alt_url (1080p)
                                    if (flashvars.video_alt_url && flashvars.video_alt_url.indexOf('https://') !== -1) {
                                        videos.push(flashvars.video_alt_url);
                                    }
                                }
                                
                                // Eğer flashvars'dan bulamadıysa, script'leri tara
                                if (videos.length === 0) {
                                    var scripts = document.getElementsByTagName('script');
                                    for (var i = 0; i < scripts.length; i++) {
                                        var text = scripts[i].textContent;
                                        
                                        // https://watchporn.to/get_file/ ile başlayan .mp4 linklerini bul
                                        var matches = text.match(/https:\/\/watchporn\.to\/get_file\/[^\s'"]+\.mp4[^\s'"]*/g);
                                        
                                        if (matches) {
                                            for (var j = 0; j < matches.length; j++) {
                                                videos.push(matches[j]);
                                            }
                                        }
                                    }
                                }
                                
                                // Tekrarları temizle
                                videos = videos.filter(function(item, pos) {
                                    return videos.indexOf(item) === pos;
                                });
                                
                                return JSON.stringify(videos);
                            })();
                        """) { result ->
                                try {
                                    val cleanResult = result.trim('"').replace("\\", "")
                                    val videoUrls = JSONArray(cleanResult)

                                    val urls = mutableListOf<String>()
                                    for (i in 0 until videoUrls.length()) {
                                        urls.add(videoUrls.getString(i))
                                    }

                                    continuation.resume(urls)

                                    Handler(Looper.getMainLooper()).post {
                                        try {
                                            this@apply.stopLoading()
                                            this@apply.clearHistory()
                                            this@apply.destroy()
                                        } catch (ignored: Throwable) {}
                                    }
                                } catch (e: Exception) {
                                    Log.e("VideoExtractor", "Error: ${e.message}")
                                    continuation.resume(emptyList())
                                }
                            }
                        }, 100)
                    }
                }

                loadDataWithBaseURL(mainUrl, html, "text/html", "UTF-8", null)
            }
        }
    }

    override suspend fun loadLinks(data: String, isCasting: Boolean, subtitleCallback: (SubtitleFile) -> Unit, callback: (ExtractorLink) -> Unit): Boolean {
        Log.d("kraptor_$name", "data = ${data}")
        val document = app.get(data).text

        val videoUrls = extractVideoUrls(context, document)

        videoUrls.forEach { url ->
            Log.d("kraptor_$name", "url = ${url}")
            val quality = url.substringBeforeLast("/").substringAfterLast("/").substringBefore(".").substringAfter("_")
            callback.invoke(
                newExtractorLink(
                    name,
                    name,
                    url,
                    type = ExtractorLinkType.VIDEO,
                    {
                        this.referer = "$mainUrl/"
                        this.quality = getQualityFromName(quality)
                    }
                )
            )
        }

        return videoUrls.isNotEmpty()
    }
}
