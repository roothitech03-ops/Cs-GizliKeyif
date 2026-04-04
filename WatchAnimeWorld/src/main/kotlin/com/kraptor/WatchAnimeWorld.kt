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

    // ─── Helpers ───────────────────────────────────────────────────────────────

    private fun String.cleanHtml(): String = this
        .replace(Regex("<[^>]*>"), "")
        .replace("&amp;", "&")
        .replace("&lt;", "<")
        .replace("&gt;", ">")
        .replace("&quot;", "\"")
        .replace("&#39;", "'")
        .trim()

    private fun fixImageUrl(raw: String): String {
        val s = raw.trim()
        return when {
            s.startsWith("//") -> "https:$s"
            s.startsWith("/")  -> "$mainUrl$s"
            s.startsWith("http") -> s
            else -> s
        }
    }

    private suspend fun getDoc(url: String) =
        app.get(
            url,
            headers = mapOf("User-Agent" to "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36")
        ).document

    // ─── Main Page ─────────────────────────────────────────────────────────────

    override val mainPage = mainPageOf(
        "$mainUrl/category/type/anime/?page="          to "Anime",
        "$mainUrl/category/type/cartoon/?page="        to "Cartoons",
        "$mainUrl/category/language/hindi/?page="      to "Hindi Dubbed",
        "$mainUrl/category/language/tamil/?page="      to "Tamil Dubbed",
        "$mainUrl/category/language/telugu/?page="     to "Telugu Dubbed",
        "$mainUrl/category/language/english/?page="    to "English",
        "$mainUrl/category/status/completed/?page="    to "Completed",
        "$mainUrl/category/genre/action/?page="        to "Action",
        "$mainUrl/category/genre/adventure/?page="     to "Adventure",
        "$mainUrl/category/genre/fantasy/?page="       to "Fantasy",
        "$mainUrl/category/genre/comedy/?page="        to "Comedy",
        "$mainUrl/category/genre/drama/?page="         to "Drama",
        "$mainUrl/category/genre/shounen/?page="       to "Shounen",
        "$mainUrl/category/genre/romance/?page="       to "Romance",
        "$mainUrl/category/genre/horror-genre/?page="  to "Horror",
        "$mainUrl/category/genre/historical/?page="    to "Historical",
        "$mainUrl/category/franchise/naruto/?page="    to "Naruto",
        "$mainUrl/category/franchise/dragon-ball/?page=" to "Dragon Ball",
        "$mainUrl/category/franchise/pokemon/?page="   to "Pokemon",
        "$mainUrl/category/franchise/doraemon/?page="  to "Doraemon",
        "$mainUrl/category/franchise/ben-10/?page="    to "Ben 10",
    )

    override suspend fun getMainPage(page: Int, request: MainPageRequest): HomePageResponse {
        val doc  = getDoc(request.data + page)
        val list = doc.select("ul.post-lst li, article.post").mapNotNull { parseListing(it) }
        val more = doc.select("a.next, .pagination .next").isNotEmpty()
        return newHomePageResponse(request.name, list, more)
    }

    private fun parseListing(el: Element): SearchResponse? {
        val href  = el.selectFirst("a.lnk-blk, a[href*='/series/']")?.attr("href")
            ?.takeIf { it.isNotBlank() } ?: return null
        val title = el.selectFirst("h2.entry-title, .entry-title")?.text()?.cleanHtml()
            ?: el.selectFirst("img[alt]")?.attr("alt")?.cleanHtml()
            ?: return null
        val poster = fixImageUrl(el.selectFirst("img[src]")?.attr("src") ?: "")
        return newAnimeSearchResponse(title, href, TvType.Anime) {
            posterUrl = poster
        }
    }

    // ─── Search ─────────────────────────────────────────────────────────────────

    override suspend fun search(query: String): List<SearchResponse> {
        val doc = getDoc("$mainUrl/?s=${query.replace(" ", "+")}")
        return doc.select("ul.post-lst li, .post-lst article").mapNotNull { parseListing(it) }
    }

    // ─── Series Detail ──────────────────────────────────────────────────────────

    override suspend fun load(url: String): LoadResponse {
        val doc = getDoc(url)

        val title = doc.selectFirst("h1.entry-title")?.text()?.cleanHtml()
            ?: doc.title().split(" - ").firstOrNull()?.trim() ?: "Unknown"

        val poster = fixImageUrl(
            doc.selectFirst("img[alt*='Image'], .post-thumbnail img, header img[src*='tmdb']")
                ?.attr("src") ?: ""
        )

        val description = doc.selectFirst(".description, .overview, .entry-content p")
            ?.text()?.cleanHtml()

        val genres = doc.select("a[href*='/category/genre/']").map { it.text().cleanHtml() }

        val seasonEls      = doc.select(".sel-temp a[data-season]")
        val postId         = seasonEls.firstOrNull()?.attr("data-post") ?: ""
        val allEpisodes    = mutableListOf<Episode>()

        fun Element.toEpisode(season: Int): Episode? {
            val epLink  = selectFirst("a.lnk-blk, a[href*='/episode/']")?.attr("href") ?: return null
            val epTitle = selectFirst("h2.entry-title, .num-epi")?.text()?.cleanHtml() ?: "Episode"
            val epThumb = selectFirst("img[src]")?.attr("src")?.let { fixImageUrl(it) }
            return newEpisode(epLink) {
                name     = epTitle
                posterUrl = epThumb
                this.season = season
            }
        }

        if (seasonEls.isEmpty()) {
            doc.select("article.post.episodes, ul#episode_by_temp article")
                .mapNotNull { it.toEpisode(1) }.let { allEpisodes.addAll(it) }
        } else {
            val currentNum = doc.selectFirst(".aa-drp .n_s")?.text()?.toIntOrNull() ?: 1
            doc.select("ul#episode_by_temp article")
                .mapNotNull { it.toEpisode(currentNum) }.let { allEpisodes.addAll(it) }

            val nonce = doc.html().substringAfter("\"nonce\":\"").substringBefore("\"")

            seasonEls.forEach { seasonEl ->
                val season = seasonEl.attr("data-season").toIntOrNull() ?: return@forEach
                if (season == currentNum || postId.isBlank()) return@forEach
                try {
                    val ajaxDoc = app.post(
                        "$mainUrl/wp-admin/admin-ajax.php",
                        data = mapOf(
                            "action"  to "get_episodes_by_season",
                            "post_id" to postId,
                            "season"  to season.toString(),
                            "nonce"   to nonce
                        ),
                        headers = mapOf(
                            "X-Requested-With" to "XMLHttpRequest",
                            "Referer"          to url
                        )
                    ).document
                    ajaxDoc.select("article.post.episodes")
                        .mapNotNull { it.toEpisode(season) }.let { allEpisodes.addAll(it) }
                } catch (e: Exception) {
                    // season fetch failed — skip silently
                }
            }
        }

        return newTvSeriesLoadResponse(title, url, TvType.Anime, allEpisodes) {
            posterUrl = poster
            plot      = description
            tags      = genres
        }
    }

    // ─── Video Links ─────────────────────────────────────────────────────────

    override suspend fun loadLinks(
        data: String,
        isCasting: Boolean,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        val doc = getDoc(data)

        // Server 1 — play.zephyrflick.top (primary)
        doc.select("iframe[src*='play.zephyrflick.top'], iframe[data-src*='play.zephyrflick.top']")
            .forEach { iframe ->
                val src = iframe.attr("src").ifBlank { iframe.attr("data-src") }
                if (src.isBlank()) return@forEach
                try {
                    loadExtractor(src, data, subtitleCallback, callback)
                } catch (e: Exception) {
                    callback.invoke(
                        newExtractorLink(
                            source = name,
                            name   = "$name - Server 1",
                            url    = src,
                            type   = ExtractorLinkType.VIDEO
                        ) {
                            referer = mainUrl
                            quality = Qualities.Unknown.value
                        }
                    )
                }
            }

        // Server 2 — player1.php multi-language (base64 encoded)
        doc.select("iframe[data-src*='player1.php']").forEach { iframe ->
            val playerSrc = iframe.attr("data-src")
            val dataParam = Regex("[?&]data=([^&]+)").find(playerSrc)?.groupValues?.get(1)
                ?: return@forEach
            try {
                val decoded = String(
                    Base64.decode(URLDecoder.decode(dataParam, "UTF-8"), Base64.DEFAULT)
                )
                val jsonArray = JSONArray(decoded)
                for (i in 0 until jsonArray.length()) {
                    val obj      = jsonArray.getJSONObject(i)
                    val language = obj.optString("language", "Unknown")
                    val link     = obj.optString("link", "").trim()
                    if (link.isBlank()) continue
                    try {
                        val resolved = resolveShortLink(link)
                        when {
                            resolved == null -> Unit
                            resolved.contains(".m3u8") ->
                                callback.invoke(
                                    newExtractorLink(
                                        source = name,
                                        name   = "$name [$language]",
                                        url    = resolved,
                                        type   = ExtractorLinkType.M3U8
                                    ) {
                                        referer = mainUrl
                                        quality = Qualities.Unknown.value
                                    }
                                )
                            resolved.contains(".mp4") ->
                                callback.invoke(
                                    newExtractorLink(
                                        source = name,
                                        name   = "$name [$language]",
                                        url    = resolved,
                                        type   = ExtractorLinkType.VIDEO
                                    ) {
                                        referer = mainUrl
                                        quality = Qualities.Unknown.value
                                    }
                                )
                            else -> loadExtractor(resolved, data, subtitleCallback, callback)
                        }
                    } catch (e: Exception) {
                        // Resolved failed — provide short-link directly
                        callback.invoke(
                            newExtractorLink(
                                source = name,
                                name   = "$name [$language]",
                                url    = link,
                                type   = ExtractorLinkType.VIDEO
                            ) {
                                referer = mainUrl
                                quality = Qualities.Unknown.value
                            }
                        )
                    }
                }
            } catch (e: Exception) {
                // JSON decode failed — skip
            }
        }

        // Server 3 — any other iframes on the page
        doc.select(".video iframe[src], .video-player iframe[src]").forEach { iframe ->
            val src = iframe.attr("src").trim()
            if (src.isBlank()
                || src.contains("player1.php")
                || src.contains("zephyrflick")
            ) return@forEach
            try {
                loadExtractor(src, data, subtitleCallback, callback)
            } catch (e: Exception) {
                // skip
            }
        }

        return true
    }

    // Follow redirect chain and return final URL
    private suspend fun resolveShortLink(url: String): String? {
        return try {
            val resp = app.get(
                url,
                allowRedirects = true,
                headers = mapOf(
                    "User-Agent" to
                        "Mozilla/5.0 (Android 13; Mobile; rv:109.0) Gecko/109.0 Firefox/109.0"
                )
            )
            resp.url.takeIf { it != url }
                ?: resp.document
                    .selectFirst("meta[http-equiv=refresh]")
                    ?.attr("content")
                    ?.substringAfter("url=", "")
                    ?.ifBlank { null }
        } catch (e: Exception) {
            null
        }
    }
}
