package com.kraptor

import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.utils.*
import com.lagradost.cloudstream3.utils.ExtractorLink
import com.lagradost.cloudstream3.utils.ExtractorLinkType
import com.lagradost.cloudstream3.utils.Qualities
import org.jsoup.nodes.Element
import java.net.URLDecoder
import android.util.Base64
import org.json.JSONArray

class WatchAnimeWorldProvider : MainAPI() {

    override var mainUrl = "https://watchanimeworld.net"
    override var name = "WatchAnimeWorld"
    override var lang = "hi"
    override val hasMainPage = true
    override val supportedTypes = setOf(
        TvType.Anime,
        TvType.AnimeMovie,
        TvType.Cartoon
    )

    private fun String.cleanHtml(): String = this
        .replace(Regex("<[^>]*>"), "")
        .replace("&amp;", "&")
        .replace("&lt;",  "<")
        .replace("&gt;",  ">")
        .replace("&quot;", "\"")
        .replace("&#39;", "'")
        .trim()

    private fun fixImg(raw: String): String {
        val s = raw.trim()
        return when {
            s.startsWith("//")   -> "https:$s"
            s.startsWith("/")    -> "$mainUrl$s"
            s.startsWith("http") -> s
            else                 -> s
        }
    }

    private suspend fun getDoc(url: String, referer: String = mainUrl) =
        app.get(url, headers = mapOf(
            "User-Agent" to "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36",
            "Referer"    to referer
        )).document

    override val mainPage = mainPageOf(
        "$mainUrl/category/type/anime/?page="           to "Anime",
        "$mainUrl/category/type/cartoon/?page="         to "Cartoons",
        "$mainUrl/category/language/hindi/?page="       to "Hindi Dubbed",
        "$mainUrl/category/language/tamil/?page="       to "Tamil Dubbed",
        "$mainUrl/category/language/telugu/?page="      to "Telugu Dubbed",
        "$mainUrl/category/language/english/?page="     to "English",
        "$mainUrl/category/status/completed/?page="     to "Completed",
        "$mainUrl/category/genre/action/?page="         to "Action",
        "$mainUrl/category/genre/adventure/?page="      to "Adventure",
        "$mainUrl/category/genre/fantasy/?page="        to "Fantasy",
        "$mainUrl/category/genre/comedy/?page="         to "Comedy",
        "$mainUrl/category/genre/drama/?page="          to "Drama",
        "$mainUrl/category/genre/shounen/?page="        to "Shounen",
        "$mainUrl/category/genre/romance/?page="        to "Romance",
        "$mainUrl/category/genre/horror-genre/?page="   to "Horror",
        "$mainUrl/category/genre/historical/?page="     to "Historical",
        "$mainUrl/category/franchise/naruto/?page="     to "Naruto",
        "$mainUrl/category/franchise/dragon-ball/?page=" to "Dragon Ball",
        "$mainUrl/category/franchise/pokemon/?page="    to "Pokemon",
        "$mainUrl/category/franchise/doraemon/?page="   to "Doraemon",
        "$mainUrl/category/franchise/ben-10/?page="     to "Ben 10",
    )

    override suspend fun getMainPage(page: Int, request: MainPageRequest): HomePageResponse {
        val doc  = getDoc(request.data + page)
        val list = doc.select("ul.post-lst li").mapNotNull { parseListing(it) }
        val more = doc.selectFirst("a.next, .pagination .next") != null
        return newHomePageResponse(request.name, list, more)
    }

    private fun parseListing(el: Element): SearchResponse? {
        val href = el.selectFirst("a.lnk-blk")?.attr("href")
            ?: el.selectFirst("a[href*='/series/'], a[href*='/movies/']")?.attr("href")
            ?: return null
        if (href.isBlank()) return null
        val title = el.selectFirst("h2.entry-title")?.text()?.cleanHtml()
            ?: el.selectFirst("[class*='entry-title']")?.text()?.cleanHtml()
            ?: el.selectFirst("img[alt]")?.attr("alt")?.cleanHtml()
            ?: return null
        if (title.isBlank()) return null
        val rawImg = el.selectFirst("img[data-src]")?.attr("data-src")
            ?: el.selectFirst("img[src]")?.attr("src")
            ?: ""
        return newAnimeSearchResponse(title, href, TvType.Anime) {
            posterUrl = fixImg(rawImg)
        }
    }

    override suspend fun search(query: String): List<SearchResponse> {
        val doc = getDoc("$mainUrl/?s=${query.replace(" ", "+")}")
        return doc.select("ul.post-lst li").mapNotNull { parseListing(it) }
    }

    override suspend fun load(url: String): LoadResponse {
        val doc = getDoc(url)

        val title = doc.selectFirst("h1.entry-title")?.text()?.cleanHtml()
            ?: doc.title().substringBefore(" - ").trim()

        val rawPoster = doc.selectFirst("img[src*='tmdb'], img[src*='image.tmdb.org']")?.attr("src")
            ?: doc.selectFirst("header img, .post-thumbnail img")?.attr("src")
            ?: ""
        val poster = fixImg(rawPoster)

        val description = doc.selectFirst(".overview, .description, .entry-content > p")
            ?.text()?.cleanHtml()

        val genres = doc.select("a[href*='/category/genre/']").map { it.text().cleanHtml() }

        val seasonEls = doc.select(".sel-temp a[data-season]")
        val postId    = seasonEls.firstOrNull()?.attr("data-post") ?: ""
        val nonce     = Regex(""""nonce"\s*:\s*"([^"]+)"""").find(doc.html())
            ?.groupValues?.get(1) ?: ""

        val allEpisodes = mutableListOf<Episode>()

        fun Element.toEpisode(season: Int): Episode? {
            val epHref = selectFirst("a.lnk-blk, a[href*='/episode/']")?.attr("href") ?: return null
            val epNum  = Regex("-${season}x(\\d+)/?$").find(epHref)?.groupValues?.get(1)?.toIntOrNull()
            val epTitle = selectFirst("h2.entry-title, .num-epi")?.text()?.cleanHtml()
                ?: "Episode ${epNum ?: ""}"
            val epThumb = selectFirst("img[data-src]")?.attr("data-src")?.let { fixImg(it) }
                ?: selectFirst("img[src]")?.attr("src")?.let { fixImg(it) }
            return newEpisode(epHref) {
                name      = epTitle
                posterUrl = epThumb
                this.season  = season
                episode   = epNum
            }
        }

        if (seasonEls.isEmpty()) {
            doc.select("ul#episode_by_temp article, article.post.episodes")
                .mapNotNull { it.toEpisode(1) }.let { allEpisodes.addAll(it) }
        } else {
            val latestSeasonNum = doc.selectFirst(".aa-drp .n_s")?.text()?.toIntOrNull() ?: 1
            doc.select("ul#episode_by_temp article")
                .mapNotNull { it.toEpisode(latestSeasonNum) }.let { allEpisodes.addAll(it) }

            seasonEls.forEach { seasonEl ->
                val season = seasonEl.attr("data-season").toIntOrNull() ?: return@forEach
                if (season == latestSeasonNum || postId.isBlank()) return@forEach
                try {
                    val resp = app.post(
                        "$mainUrl/wp-admin/admin-ajax.php",
                        data = mapOf(
                            "action"  to "get_episodes_by_season",
                            "post_id" to postId,
                            "season"  to season.toString(),
                            "nonce"   to nonce
                        ),
                        headers = mapOf(
                            "X-Requested-With" to "XMLHttpRequest",
                            "Referer"          to url,
                            "Origin"           to mainUrl
                        )
                    )
                    val body = resp.text.trim()
                    if (body != "0" && body != "-1" && body.isNotBlank()) {
                        resp.document.select("article.post.episodes")
                            .mapNotNull { it.toEpisode(season) }.let { allEpisodes.addAll(it) }
                    } else {
                        val seriesSlug = url.trimEnd('/').substringAfterLast('/')
                        val probeResp  = app.get(
                            "$mainUrl/episode/$seriesSlug-${season}x1/",
                            headers = mapOf("User-Agent" to "Mozilla/5.0")
                        )
                        if (probeResp.code == 200) {
                            probeResp.document
                                .select("ul#episode_by_temp article, article.post.episodes")
                                .mapNotNull { it.toEpisode(season) }.let { allEpisodes.addAll(it) }
                        }
                    }
                } catch (e: Exception) { /* skip */ }
            }
        }

        allEpisodes.sortWith(compareBy({ it.season ?: 0 }, { it.episode ?: 0 }))

        return newTvSeriesLoadResponse(title, url, TvType.Anime, allEpisodes) {
            posterUrl = poster
            plot      = description
            tags      = genres
        }
    }

    override suspend fun loadLinks(
        data: String,
        isCasting: Boolean,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        val doc = getDoc(data)

        // Server 1 — zephyrflick: extract CDN hash → build master.m3u8
        doc.select("iframe[src*='play.zephyrflick.top'], iframe[data-src*='play.zephyrflick.top']")
            .forEach { iframe ->
                val src = iframe.attr("src").ifBlank { iframe.attr("data-src") }
                if (src.isBlank()) return@forEach
                try {
                    val playerHtml = app.get(
                        src,
                        referer = mainUrl,
                        headers = mapOf("User-Agent" to "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36")
                    ).text
                    val cdnHash = Regex("""as-cdn13\.top/cdn/down/([a-f0-9A-F]+)/""")
                        .find(playerHtml)?.groupValues?.get(1)
                    if (!cdnHash.isNullOrBlank()) {
                        callback.invoke(
                            newExtractorLink(
                                source = name,
                                name   = "$name - MultiCloud (Hindi/Tamil/Telugu/English)",
                                url    = "https://s7.as-cdn13.top/cdn/down/$cdnHash/master.m3u8",
                                type   = ExtractorLinkType.M3U8
                            ) {
                                referer = "https://play.zephyrflick.top/"
                                quality = Qualities.Unknown.value
                            }
                        )
                    } else {
                        loadExtractor(src, data, subtitleCallback, callback)
                    }
                } catch (e: Exception) {
                    try { loadExtractor(src, data, subtitleCallback, callback) } catch (ex: Exception) {}
                }
            }

        // Server 2 — player1.php: base64 → short.icu → abysscdn
        doc.select("iframe[data-src*='player1.php']").forEach { iframe ->
            val playerSrc = iframe.attr("data-src")
            val dataParam = Regex("""[?&]data=([^&]+)""").find(playerSrc)?.groupValues?.get(1)
                ?: return@forEach
            try {
                val decoded = String(Base64.decode(URLDecoder.decode(dataParam, "UTF-8"), Base64.DEFAULT))
                val arr = JSONArray(decoded)
                for (i in 0 until arr.length()) {
                    val obj  = arr.getJSONObject(i)
                    val lang = obj.optString("language", "Unknown")
                    val link = obj.optString("link", "").trim()
                    if (link.isBlank()) continue
                    try {
                        val resolved = resolveRedirect(link) ?: link
                        try {
                            loadExtractor(resolved, data, subtitleCallback, callback)
                        } catch (ex: Exception) {
                            callback.invoke(
                                newExtractorLink(source = name, name = "$name [$lang]",
                                    url = resolved, type = ExtractorLinkType.VIDEO) {
                                    referer = mainUrl; quality = Qualities.Unknown.value
                                }
                            )
                        }
                    } catch (e: Exception) {
                        callback.invoke(
                            newExtractorLink(source = name, name = "$name [$lang]",
                                url = link, type = ExtractorLinkType.VIDEO) {
                                referer = mainUrl; quality = Qualities.Unknown.value
                            }
                        )
                    }
                }
            } catch (e: Exception) {}
        }

        // Server 3 — other iframes
        doc.select(".video iframe[src]").forEach { iframe ->
            val src = iframe.attr("src").trim()
            if (src.isBlank() || src.contains("player1.php") || src.contains("zephyrflick")) return@forEach
            try { loadExtractor(src, data, subtitleCallback, callback) } catch (e: Exception) {}
        }

        return true
    }

    private suspend fun resolveRedirect(url: String): String? {
        return try {
            val resp = app.get(url, allowRedirects = true,
                headers = mapOf("User-Agent" to "Mozilla/5.0 (Android 13; Mobile; rv:109.0) Gecko/109.0 Firefox/109.0"))
            resp.url.takeIf { it != url }
                ?: resp.document.selectFirst("meta[http-equiv=refresh]")
                    ?.attr("content")?.substringAfter("url=", "")?.ifBlank { null }
        } catch (e: Exception) { null }
    }
}
