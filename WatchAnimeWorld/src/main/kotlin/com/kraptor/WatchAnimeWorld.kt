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
        "$mainUrl/category/type/anime/"            to "Anime",
        "$mainUrl/category/type/cartoon/"          to "Cartoons",
        "$mainUrl/category/language/hindi/"        to "Hindi Dubbed",
        "$mainUrl/category/language/tamil/"        to "Tamil Dubbed",
        "$mainUrl/category/language/telugu/"       to "Telugu Dubbed",
        "$mainUrl/category/language/english/"      to "English",
        "$mainUrl/category/status/completed/"      to "Completed",
        "$mainUrl/category/genre/action/"          to "Action",
        "$mainUrl/category/genre/adventure/"       to "Adventure",
        "$mainUrl/category/genre/fantasy/"         to "Fantasy",
        "$mainUrl/category/genre/comedy/"          to "Comedy",
        "$mainUrl/category/genre/drama/"           to "Drama",
        "$mainUrl/category/genre/shounen/"         to "Shounen",
        "$mainUrl/category/genre/romance/"         to "Romance",
        "$mainUrl/category/genre/horror-genre/"    to "Horror",
        "$mainUrl/category/genre/historical/"      to "Historical",
        "$mainUrl/category/franchise/naruto/"      to "Naruto",
        "$mainUrl/category/franchise/dragon-ball/" to "Dragon Ball",
        "$mainUrl/category/franchise/pokemon/"     to "Pokemon",
        "$mainUrl/category/franchise/doraemon/"    to "Doraemon",
        "$mainUrl/category/franchise/ben-10/"      to "Ben 10",
    )

    override suspend fun getMainPage(page: Int, request: MainPageRequest): HomePageResponse {
        val url = if (page == 1) request.data else "${request.data}page/$page/"
        val doc = getDoc(url)
        val list = doc.select("ul.post-lst li").mapNotNull { parseListing(it) }
        val more = doc.selectFirst("a.next, .pagination .next, a[href*='/page/${page + 1}/']") != null
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
            ?: el.selectFirst("img[src]")?.attr("src") ?: ""
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
            ?: doc.selectFirst("header img, .post-thumbnail img")?.attr("src") ?: ""
        val poster = fixImg(rawPoster)

        val description = doc.selectFirst(".overview, .description, .entry-content > p")
            ?.text()?.cleanHtml()
        val genres = doc.select("a[href*='/category/genre/']").map { it.text().cleanHtml() }

        val seasonEls = doc.select(".sel-temp a[data-season]")
        val postId = seasonEls.firstOrNull()?.attr("data-post")
            ?: Regex("""postid-(\d+)""")
                .find(doc.selectFirst("body")?.attr("class") ?: "")
                ?.groupValues?.get(1) ?: ""

        val nonce = Regex(""""nonce"\s*:\s*"([^"]+)"""")
            .find(doc.html())?.groupValues?.get(1) ?: ""

        val seasonNumbers: List<Int> = if (seasonEls.isNotEmpty()) {
            seasonEls.mapNotNull { it.attr("data-season").toIntOrNull() }.distinct().sorted()
        } else {
            listOf(1)
        }

        val allEpisodes = mutableListOf<Episode>()

        // CRITICAL FIX: param renamed to "seasonNum" — avoids clash with
        // Episode.season property inside newEpisode { } lambda (was causing null seasons)
        fun Element.toEpisode(seasonNum: Int): Episode? {
            val epHref = selectFirst("a.lnk-blk, a[href*='/episode/']")?.attr("href") ?: return null
            val match  = Regex("""-(\d+)x(\d+)/?$""").find(epHref)
            val urlSeason = match?.groupValues?.get(1)?.toIntOrNull() ?: seasonNum
            val epNum  = if (urlSeason == seasonNum) match?.groupValues?.get(2)?.toIntOrNull() else null
            val epTitle = selectFirst(".num-epi")?.text()?.cleanHtml()
                ?: selectFirst("h2.entry-title")?.text()?.cleanHtml()
                ?: "Episode ${epNum ?: ""}"
            val epThumb = selectFirst("img[data-src]")?.attr("data-src")?.let { fixImg(it) }
                ?: selectFirst("img[src]")?.attr("src")?.let { fixImg(it) }
            return newEpisode(epHref) {
                name      = epTitle
                posterUrl = epThumb
                season    = seasonNum   // no shadowing — "seasonNum" ≠ Episode.season
                episode   = epNum
            }
        }

        seasonNumbers.forEach { seasonNum ->
            if (postId.isBlank()) return@forEach
            try {
                val resp = app.post(
                    "$mainUrl/wp-admin/admin-ajax.php",
                    data = mapOf(
                        "action" to "action_select_season",
                        "season" to seasonNum.toString(),
                        "post"   to postId,
                        "nonce"  to nonce
                    ),
                    headers = mapOf(
                        "X-Requested-With" to "XMLHttpRequest",
                        "Referer"          to url,
                        "Origin"           to mainUrl,
                        "Content-Type"     to "application/x-www-form-urlencoded"
                    )
                )
                val body = resp.text.trim()
                if (body == "0" || body == "-1" || body.isBlank()) return@forEach

                val eps = resp.document.select("article.post, li article")
                    .mapNotNull { it.toEpisode(seasonNum) }
                if (eps.isEmpty()) return@forEach

                if (eps.all { it.episode == null }) {
                    eps.forEachIndexed { idx, ep ->
                        allEpisodes.add(newEpisode(ep.data) {
                            name      = ep.name
                            posterUrl = ep.posterUrl
                            season    = seasonNum
                            episode   = idx + 1
                        })
                    }
                } else {
                    allEpisodes.addAll(eps)
                }
            } catch (_: Exception) {}
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

        // Server 1 — zephyrflick: CDN hash → master.m3u8
        doc.select("iframe[src*='play.zephyrflick.top'], iframe[data-src*='play.zephyrflick.top']")
            .distinctBy { it.attr("src").ifBlank { it.attr("data-src") } }
            .forEach { iframe ->
                val src = iframe.attr("src").ifBlank { iframe.attr("data-src") }
                if (src.isBlank()) return@forEach
                try {
                    val playerHtml = app.get(src, referer = mainUrl,
                        headers = mapOf("User-Agent" to "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36")
                    ).text
                    val cdnHash = Regex("""as-cdn13\.top/cdn/down/([a-fA-F0-9]{30,})/""")
                        .find(playerHtml)?.groupValues?.get(1)
                    if (!cdnHash.isNullOrBlank()) {
                        callback.invoke(
                            newExtractorLink(
                                source = name,
                                name   = "$name - MultiCloud",
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
                } catch (_: Exception) {
                    try { loadExtractor(src, data, subtitleCallback, callback) } catch (_: Exception) {}
                }
            }

        // Server 2 — player1.php / Abyss
        doc.select("iframe[src*='player1.php'], iframe[data-src*='player1.php']")
            .distinctBy {
                (it.attr("src").ifBlank { it.attr("data-src") })
                    .substringAfter("data=").take(20)
            }
            .forEach { iframe ->
                val playerSrc = iframe.attr("src").ifBlank { iframe.attr("data-src") }
                val dataParam = Regex("""[?&]data=([^&\s]+)""")
                    .find(playerSrc)?.groupValues?.get(1) ?: return@forEach
                try {
                    val decoded = String(Base64.decode(URLDecoder.decode(dataParam, "UTF-8"), Base64.DEFAULT))
                    val arr = JSONArray(decoded)
                    for (i in 0 until arr.length()) {
                        val obj  = arr.getJSONObject(i)
                        val lang = obj.optString("language", "Unknown")
                        val link = obj.optString("link", "").trim()
                        if (link.isBlank()) continue
                        val abyssId = link.trimEnd('/').substringAfterLast('/')
                        val abyssUrl = "https://abysscdn.com/?v=$abyssId"
                        try {
                            extractAbyssCDN(abyssUrl, lang, callback)
                        } catch (_: Exception) {
                            try {
                                loadExtractor(abyssUrl, data, subtitleCallback, callback)
                            } catch (_: Exception) {
                                callback.invoke(
                                    newExtractorLink(
                                        source = name,
                                        name   = "$name [Abyss-$lang]",
                                        url    = abyssUrl,
                                        type   = ExtractorLinkType.VIDEO
                                    ) {
                                        referer = mainUrl
                                        quality = Qualities.Unknown.value
                                    }
                                )
                            }
                        }
                    }
                } catch (_: Exception) {}
            }

        // Server 3 — pixdrive.cfd
        doc.select("iframe[src*='pixdrive.cfd'], iframe[data-src*='pixdrive.cfd']")
            .distinctBy { it.attr("src").ifBlank { it.attr("data-src") } }
            .forEach { iframe ->
                val src = iframe.attr("src").ifBlank { iframe.attr("data-src") }
                if (src.isBlank()) return@forEach
                try {
                    val playerHtml = app.get(src, referer = mainUrl,
                        headers = mapOf("User-Agent" to "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36")
                    ).text
                    val m3u8 = Regex("""https?://[^\s"'<>]+\.m3u8[^\s"'<>]*""").find(playerHtml)?.value
                    val mp4  = Regex("""https?://[^\s"'<>]+\.mp4[^\s"'<>]*""").find(playerHtml)?.value
                    when {
                        m3u8 != null -> callback.invoke(newExtractorLink(source = name,
                            name = "$name - MultiCloud2", url = m3u8, type = ExtractorLinkType.M3U8) {
                            referer = src; quality = Qualities.Unknown.value })
                        mp4  != null -> callback.invoke(newExtractorLink(source = name,
                            name = "$name - MultiCloud2", url = mp4,  type = ExtractorLinkType.VIDEO) {
                            referer = src; quality = Qualities.Unknown.value })
                        else -> loadExtractor(src, data, subtitleCallback, callback)
                    }
                } catch (_: Exception) {
                    try { loadExtractor(src, data, subtitleCallback, callback) } catch (_: Exception) {}
                }
            }

        // Any other iframe servers
        doc.select("div[id^='options-'] iframe[data-src]").forEach { iframe ->
            val src = iframe.attr("data-src").trim()
            if (src.isBlank() || src.contains("player1.php")
                || src.contains("zephyrflick") || src.contains("pixdrive")) return@forEach
            try { loadExtractor(src, data, subtitleCallback, callback) } catch (_: Exception) {}
        }

        return true
    }

    private suspend fun extractAbyssCDN(abyssUrl: String, lang: String, callback: (ExtractorLink) -> Unit) {
        val html = app.get(abyssUrl, referer = mainUrl,
            headers = mapOf("User-Agent" to "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 Chrome/120")
        ).text
        val b64 = Regex("""datas\s*=\s*"([A-Za-z0-9+/=]{20,})"""").find(html)?.groupValues?.get(1) ?: return
        val json = try { String(Base64.decode(b64, Base64.DEFAULT)) } catch (_: Exception) { return }
        val slug = Regex(""""slug"\s*:\s*"([^"]+)"""").find(json)?.groupValues?.get(1) ?: return
        callback.invoke(
            newExtractorLink(
                source = name,
                name   = "$name [Abyss-$lang]",
                url    = "https://abysscdn.com/?v=$slug",
                type   = ExtractorLinkType.VIDEO
            ) {
                referer = mainUrl
                quality = Qualities.Unknown.value
            }
        )
    }
}