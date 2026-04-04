package com.kraptor

import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.utils.*
import com.lagradost.cloudstream3.utils.AppUtils.toJson
import com.lagradost.cloudstream3.utils.AppUtils.parseJson
import org.jsoup.nodes.Document
import org.jsoup.nodes.Element
import java.net.URLDecoder
import android.util.Base64
import org.json.JSONArray
import org.json.JSONObject

class WatchAnimeWorldProvider : MainAPI() {

    override var mainUrl = "https://watchanimeworld.net"
    override var name = "WatchAnimeWorld"
    override var lang = "hi"
    override val hasMainPage = true
    override val hasSearch = true
    override val supportedTypes = setOf(
        TvType.Anime,
        TvType.AnimeMovie,
        TvType.Cartoon
    )

    companion object {
        private const val TMDB_IMG = "https://image.tmdb.org/t/p/w500"
        private const val TMDB_IMG_ORIG = "https://image.tmdb.org/t/p/original"
    }

    // ─── Helper ────────────────────────────────────────────────────────────────

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
            s.startsWith("/") -> "$mainUrl$s"
            s.startsWith("http") -> s
            else -> s
        }
    }

    private suspend fun getDoc(url: String): Document =
        app.get(url, headers = mapOf("User-Agent" to "Mozilla/5.0")).document

    // ─── Main Page ─────────────────────────────────────────────────────────────

    override val mainPage = mainPageOf(
        "$mainUrl/category/type/anime/?page=" to "Anime",
        "$mainUrl/category/type/cartoon/?page=" to "Cartoons",
        "$mainUrl/category/language/hindi/?page=" to "Hindi Dubbed",
        "$mainUrl/category/language/tamil/?page=" to "Tamil Dubbed",
        "$mainUrl/category/language/telugu/?page=" to "Telugu Dubbed",
        "$mainUrl/category/language/english/?page=" to "English",
        "$mainUrl/category/status/completed/?page=" to "Completed",
        "$mainUrl/category/genre/action/?page=" to "Action",
        "$mainUrl/category/genre/adventure/?page=" to "Adventure",
        "$mainUrl/category/genre/fantasy/?page=" to "Fantasy",
        "$mainUrl/category/genre/comedy/?page=" to "Comedy",
        "$mainUrl/category/genre/drama/?page=" to "Drama",
        "$mainUrl/category/genre/shounen/?page=" to "Shounen",
        "$mainUrl/category/genre/romance/?page=" to "Romance",
        "$mainUrl/category/genre/horror-genre/?page=" to "Horror",
        "$mainUrl/category/genre/historical/?page=" to "Historical",
        "$mainUrl/category/franchise/naruto/?page=" to "Naruto Franchise",
        "$mainUrl/category/franchise/dragon-ball/?page=" to "Dragon Ball",
        "$mainUrl/category/franchise/pokemon/?page=" to "Pokemon",
        "$mainUrl/category/franchise/doraemon/?page=" to "Doraemon",
        "$mainUrl/category/franchise/ben-10/?page=" to "Ben 10",
    )

    override suspend fun getMainPage(page: Int, request: MainPageRequest): HomePageResponse {
        val url = request.data + page
        val doc = getDoc(url)
        val items = doc.select("ul.post-lst li, article.post").mapNotNull { el ->
            parseListing(el)
        }
        val hasNext = doc.select("a.next, .pagination .next").isNotEmpty()
        return newHomePageResponse(request.name, items, hasNext)
    }

    private fun parseListing(el: Element): SearchResponse? {
        val linkEl = el.selectFirst("a.lnk-blk, a[href*='/series/']") ?: return null
        val href = linkEl.attr("href").takeIf { it.isNotBlank() } ?: return null
        val title = el.selectFirst("h2.entry-title, .entry-title")?.text()?.cleanHtml()
            ?: el.selectFirst("img[alt]")?.attr("alt")?.cleanHtml()
            ?: return null
        val rawImg = el.selectFirst("img[src]")?.attr("src") ?: ""
        val poster = fixImageUrl(rawImg)
        return newAnimeSearchResponse(title, href, TvType.Anime) {
            this.posterUrl = poster
        }
    }

    // ─── Search ─────────────────────────────────────────────────────────────────

    override suspend fun search(query: String): List<SearchResponse> {
        val url = "$mainUrl/?s=${query.replace(" ", "+")}"
        val doc = getDoc(url)
        return doc.select("ul.post-lst li, .post-lst article").mapNotNull { el ->
            parseListing(el)
        }
    }

    // ─── Series Detail ──────────────────────────────────────────────────────────

    override suspend fun load(url: String): LoadResponse {
        val doc = getDoc(url)

        val title = doc.selectFirst("h1.entry-title")?.text()?.cleanHtml()
            ?: doc.title().split(" - ").firstOrNull() ?: "Unknown"

        val rawPoster = doc.selectFirst("img[alt*='Image'], .post-thumbnail img, header img[src*='tmdb']")
            ?.attr("src") ?: ""
        val poster = fixImageUrl(rawPoster)

        val description = doc.selectFirst(".description, .overview, .entry-content p")?.text()?.cleanHtml()

        val genres = doc.select("a[href*='/category/genre/']").map { it.text().cleanHtml() }

        val seasonEls = doc.select(".sel-temp a[data-season]")
        val postId = seasonEls.firstOrNull()?.attr("data-post") ?: ""

        val allEpisodes = mutableListOf<Episode>()

        if (seasonEls.isEmpty()) {
            // Single season — parse episodes directly from page
            doc.select("article.post.episodes, ul#episode_by_temp article").forEach { ep ->
                val epLink = ep.selectFirst("a.lnk-blk, a[href*='/episode/']")?.attr("href") ?: return@forEach
                val epTitle = ep.selectFirst("h2.entry-title, .num-epi")?.text()?.cleanHtml() ?: "Episode"
                val epThumb = ep.selectFirst("img[src]")?.attr("src")?.let { fixImageUrl(it) }
                allEpisodes.add(newEpisode(epLink) {
                    this.name = epTitle
                    this.posterUrl = epThumb
                    this.season = 1
                })
            }
        } else {
            // Multi-season — episodes already loaded for current season in page
            val currentSeason = doc.select("ul#episode_by_temp article")
            val currentSeasonNum = doc.selectFirst(".aa-drp .n_s")?.text()?.toIntOrNull() ?: 1

            currentSeason.forEach { ep ->
                val epLink = ep.selectFirst("a.lnk-blk, a[href*='/episode/']")?.attr("href") ?: return@forEach
                val epTitle = ep.selectFirst("h2.entry-title, .num-epi")?.text()?.cleanHtml() ?: "Episode"
                val epThumb = ep.selectFirst("img[src]")?.attr("src")?.let { fixImageUrl(it) }
                allEpisodes.add(newEpisode(epLink) {
                    this.name = epTitle
                    this.posterUrl = epThumb
                    this.season = currentSeasonNum
                })
            }

            // Fetch other seasons via AJAX
            seasonEls.forEach { seasonEl ->
                val season = seasonEl.attr("data-season").toIntOrNull() ?: return@forEach
                if (season == currentSeasonNum) return@forEach
                if (postId.isBlank()) return@forEach
                try {
                    val ajaxUrl = "$mainUrl/wp-admin/admin-ajax.php"
                    val nonce = doc.html()
                        .substringAfter("\"nonce\":\"")
                        .substringBefore("\"")
                    val ajaxResp = app.post(
                        ajaxUrl,
                        data = mapOf(
                            "action" to "get_episodes_by_season",
                            "post_id" to postId,
                            "season" to season.toString(),
                            "nonce" to nonce
                        ),
                        headers = mapOf(
                            "X-Requested-With" to "XMLHttpRequest",
                            "Referer" to url
                        )
                    )
                    val ajaxDoc = ajaxResp.document
                    ajaxDoc.select("article.post.episodes").forEach { ep ->
                        val epLink = ep.selectFirst("a.lnk-blk, a[href*='/episode/']")?.attr("href") ?: return@forEach
                        val epTitle = ep.selectFirst("h2.entry-title, .num-epi")?.text()?.cleanHtml() ?: "Episode"
                        val epThumb = ep.selectFirst("img[src]")?.attr("src")?.let { fixImageUrl(it) }
                        allEpisodes.add(newEpisode(epLink) {
                            this.name = epTitle
                            this.posterUrl = epThumb
                            this.season = season
                        })
                    }
                } catch (_: Exception) {
                    // If AJAX fails, skip that season gracefully
                }
            }
        }

        return newTvSeriesLoadResponse(title, url, TvType.Anime, allEpisodes) {
            this.posterUrl = poster
            this.plot = description
            this.tags = genres
        }
    }

    // ─── Episode Video Links ─────────────────────────────────────────────────

    override suspend fun loadLinks(
        data: String,
        isCasting: Boolean,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        val doc = getDoc(data)

        // Server 1: play.zephyrflick.top (primary auto-play server)
        doc.select("iframe[src*='play.zephyrflick.top'], iframe[data-src*='play.zephyrflick.top']")
            .forEach { iframe ->
                val src = iframe.attr("src").ifBlank { iframe.attr("data-src") }
                if (src.isNotBlank()) {
                    try {
                        loadExtractor(src, data, subtitleCallback, callback)
                    } catch (_: Exception) {
                        // Fallback: add as direct link
                        callback.invoke(
                            ExtractorLink(
                                source = name,
                                name = "$name - Server 1",
                                url = src,
                                referer = mainUrl,
                                quality = Qualities.Unknown.value,
                                isM3u8 = false
                            )
                        )
                    }
                }
            }

        // Server 2: player1.php multi-language links (base64 encoded)
        doc.select("iframe[data-src*='player1.php']").forEach { iframe ->
            val playerSrc = iframe.attr("data-src")
            val dataParam = Regex("[?&]data=([^&]+)").find(playerSrc)?.groupValues?.get(1)
            if (!dataParam.isNullOrBlank()) {
                try {
                    val decoded = String(Base64.decode(
                        URLDecoder.decode(dataParam, "UTF-8"),
                        Base64.DEFAULT
                    ))
                    val jsonArray = JSONArray(decoded)
                    for (i in 0 until jsonArray.length()) {
                        val obj = jsonArray.getJSONObject(i)
                        val language = obj.optString("language", "Unknown")
                        val link = obj.optString("link", "")
                        if (link.isBlank()) continue
                        // These are shortlinks — try to resolve them
                        try {
                            val resolved = resolveShortLink(link)
                            if (resolved != null && (resolved.contains(".m3u8") || resolved.contains(".mp4"))) {
                                callback.invoke(
                                    ExtractorLink(
                                        source = name,
                                        name = "$name [$language]",
                                        url = resolved,
                                        referer = mainUrl,
                                        quality = Qualities.Unknown.value,
                                        isM3u8 = resolved.contains(".m3u8")
                                    )
                                )
                            } else if (resolved != null) {
                                loadExtractor(resolved, data, subtitleCallback, callback)
                            }
                        } catch (_: Exception) {
                            // Add raw shortlink as fallback so user can open it
                            callback.invoke(
                                ExtractorLink(
                                    source = name,
                                    name = "$name [$language] (Short)",
                                    url = link,
                                    referer = mainUrl,
                                    quality = Qualities.Unknown.value,
                                    isM3u8 = false
                                )
                            )
                        }
                    }
                } catch (_: Exception) {
                    // JSON parse failed — skip
                }
            }
        }

        // Server 3: Any other embedded iframes
        doc.select(".video iframe[src], .video-player iframe[src]").forEach { iframe ->
            val src = iframe.attr("src").trim()
            if (src.isBlank() || src.contains("player1.php") || src.contains("zephyrflick")) return@forEach
            try {
                loadExtractor(src, data, subtitleCallback, callback)
            } catch (_: Exception) {}
        }

        return true
    }

    // Resolve short URL by following redirects
    private suspend fun resolveShortLink(url: String): String? {
        return try {
            val resp = app.get(
                url,
                allowRedirects = true,
                headers = mapOf(
                    "User-Agent" to "Mozilla/5.0 (Android 13; Mobile; rv:109.0) Gecko/109.0 Firefox/109.0"
                )
            )
            resp.url.takeIf { it != url }
                ?: resp.document.selectFirst("meta[http-equiv=refresh]")
                    ?.attr("content")
                    ?.substringAfter("url=", "")
                    ?.ifBlank { null }
        } catch (_: Exception) {
            null
        }
    }
}
