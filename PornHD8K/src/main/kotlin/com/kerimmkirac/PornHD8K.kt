// ! Bu araç @kerimmkirac tarafından | @Cs-GizliKeyif için yazılmıştır.

package com.kerimmkirac

import com.lagradost.api.Log
import org.jsoup.nodes.Element
import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.utils.*
import com.lagradost.cloudstream3.utils.ExtractorLink
import com.lagradost.cloudstream3.LoadResponse.Companion.addActors
import java.security.MessageDigest

class PornHD8K : MainAPI() {
    override var mainUrl              = "https://en16.pornhd8k.net"
    override var name                 = "PornHD8K"
    override val hasMainPage          = true
    override var lang                 = "en"
    override val hasQuickSearch       = false
    override val supportedTypes       = setOf(TvType.NSFW)

    companion object {
        // Cookie key parts extracted from fix.js (thfq6jcc6pj85tez)
        // Full key: n1sqcua67bcq9826avrbi6m49vd7shxkn985mhodk06twz87wwxtp3dqiicks2df...
        // Cookie name = key.substring(13,37) + episodeId + key.substring(40,64)
        private const val COOKIE_PREFIX = "826avrbi6m49vd7shxkn985m"
        private const val COOKIE_SUFFIX = "k06twz87wwxtp3dqiicks2df"
        private const val HASH_SECRET   = "98126avrbi6m49vd7shxkn985"
        private const val CHARS         = "abcdefghijklmnopqrstuvwxyz0123456789"
    }

    override val mainPage = mainPageOf(
        "$mainUrl/porn-hd-videos"            to "Latest Videos",
        "$mainUrl/studio/brazzers"           to "Brazzers",
        "$mainUrl/studio/naughtyamerica"     to "NaughtyAmerica",
        "$mainUrl/studio/realitykings"       to "RealityKings",
        "$mainUrl/studio/bang-bros"          to "BangBros",
        "$mainUrl/studio/mylf"               to "MYLF",
        "$mainUrl/studio/teamskeet"          to "TeamSkeet",
        "$mainUrl/studio/mofos"              to "Mofos",
        "$mainUrl/category/big-naturals"     to "Big Naturals",
        "$mainUrl/category/rk-prime"         to "RK Prime",
        "$mainUrl/tag/milf"                  to "MILF",
        "$mainUrl/tag/teen"                  to "Teen",
        "$mainUrl/tag/anal"                  to "Anal",
        "$mainUrl/tag/blowjob"               to "Blowjob",
        "$mainUrl/tag/big-tits"              to "Big Tits",
        "$mainUrl/tag/threesome"             to "Threesome"
    )

    override suspend fun getMainPage(page: Int, request: MainPageRequest): HomePageResponse {
        val url = if (page <= 1) request.data else "${request.data}/page-$page"
        val document = app.get(url).document

        val home = document.select("div.ml-item").mapNotNull {
            it.toSearchResult()
        }

        return newHomePageResponse(
            list = HomePageList(
                name = request.name,
                list = home,
                isHorizontalImages = true
            ),
            hasNext = true
        )
    }

    private fun Element.toSearchResult(): SearchResponse? {
        val titleElement = this.selectFirst("a.ml-mask") ?: return null
        val title = titleElement.attr("title").ifBlank { titleElement.text() }
        if (title.isBlank()) return null

        val href = fixUrl(titleElement.attr("href"))
        if (href.isBlank()) return null

        val img = this.selectFirst("img.mli-thumb, img.thumb, img")
        val posterUrl = img?.let {
            val dataOriginal = it.attr("data-original").trim()
            val src = it.attr("src").trim()
            val dataSrc = it.attr("data-src").trim()
            val rawUrl = when {
                dataOriginal.isNotBlank() && !dataOriginal.contains("data:image") -> dataOriginal
                dataSrc.isNotBlank() && !dataSrc.contains("data:image")           -> dataSrc
                src.isNotBlank() && !src.contains("data:image")                   -> src
                else -> null
            }
            rawUrl?.let { url -> fixUrlNull(url) }
        }

        return newMovieSearchResponse(title, href, TvType.NSFW) {
            this.posterUrl = posterUrl
        }
    }

    override suspend fun search(query: String, page: Int): SearchResponseList {
        val searchQuery = query.trim().replace("\\s+".toRegex(), "-").lowercase()
        val url = if (page <= 1) "$mainUrl/search/$searchQuery" else "$mainUrl/search/$searchQuery/page-$page"
        val document = app.get(url).document
        val searchResults = document.select("div.ml-item").mapNotNull {
            it.toSearchResult()
        }
        return newSearchResponseList(searchResults, hasNext = true)
    }

    override suspend fun load(url: String): LoadResponse {
        val document = app.get(url).document

        val title = document.selectFirst(".mvi-content h3")?.text()?.trim()
            ?: document.selectFirst("meta[property='og:title']")?.attr("content")?.trim()
            ?: document.selectFirst("h2")?.text()?.trim()
            ?: ""

        val poster = fixUrlNull(document.selectFirst("meta[property='og:image']")?.attr("content"))

        val description = document.selectFirst(".mvic-desc")?.ownText()?.trim()
            ?: document.selectFirst(".f-desc")?.text()?.trim()

        val categories = document.select(".mvic-info a[href*='/category/']").map { it.text().trim() }
        val actors     = document.select(".mvic-info a[href*='/pornstar/']").map { it.text().trim() }
        val tags       = document.select("#mv-keywords a").map { it.text().trim() }
        val allTags    = (categories + tags).distinct()

        val recommendations = document.select("div.ml-item").mapNotNull {
            it.toSearchResult()
        }

        return newMovieLoadResponse(title, url, TvType.NSFW, url) {
            this.posterUrl       = poster
            this.plot            = description
            this.tags            = allTags
            this.recommendations = recommendations
            addActors(actors)
        }
    }

    override suspend fun loadLinks(
        data: String,
        isCasting: Boolean,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        val response = app.get(data)
        val document = response.document
        val pageHtml = document.html()

        // Extract episode ID from the page's JavaScript: id: "XXXXX"
        val episodeId = Regex("""id:\s*"([^"]+)"""").find(pageHtml)?.groupValues?.get(1)
        if (episodeId.isNullOrBlank()) {
            Log.d("PornHD8K", "No episode ID found on page: $data")
            return false
        }

        Log.d("PornHD8K", "Found episode ID: $episodeId")

        // Generate random string (6 chars) for cookie verification
        val randomStr = (1..6).map { CHARS.random() }.joinToString("")

        // Build cookie name: COOKIE_PREFIX + episodeId + COOKIE_SUFFIX
        val cookieName = "$COOKIE_PREFIX$episodeId$COOKIE_SUFFIX"

        // Calculate MD5 hash: md5(episodeId + randomStr + HASH_SECRET)
        val hashInput = "$episodeId$randomStr$HASH_SECRET"
        val md5Hash = md5(hashInput)

        // Build the AJAX URL: /ajax/get_sources/{episodeId}/{md5Hash}
        val ajaxUrl = "$mainUrl/ajax/get_sources/$episodeId/$md5Hash?count=1&mobile=false"

        Log.d("PornHD8K", "AJAX URL: $ajaxUrl")

        // Make the AJAX request with the verification cookie
        val ajaxResponse = app.get(
            ajaxUrl,
            headers = mapOf(
                "X-Requested-With" to "XMLHttpRequest",
                "Accept" to "application/json, text/javascript, */*; q=0.01",
                "Referer" to data
            ),
            cookies = mapOf(cookieName to randomStr)
        )

        val jsonText = ajaxResponse.text.trim()
        Log.d("PornHD8K", "AJAX response length: ${jsonText.length}")

        if (jsonText.length <= 1) {
            Log.d("PornHD8K", "AJAX returned empty/invalid response: $jsonText")
            return false
        }

        try {
            // Parse JSON: {"playlist":[{"image":"...","sources":[{"file":"...","type":"...","label":"..."}]}]}
            val parsed = AppUtils.parseJson<AjaxResponse>(jsonText)
            parsed.playlist?.forEach { item ->
                item.sources?.forEach { source ->
                    val fileUrl = source.file
                    if (!fileUrl.isNullOrBlank()) {
                        val quality = source.label ?: "Unknown"
                        val isM3u8 = fileUrl.contains(".m3u8")

                        callback.invoke(
                            newExtractorLink(
                                source = name,
                                name = "$name - $quality",
                                url = fileUrl,
                                type = if (isM3u8) ExtractorLinkType.M3U8 else ExtractorLinkType.VIDEO
                            ) {
                                this.referer = mainUrl
                                this.quality = getQualityFromName(quality)
                            }
                        )
                    }
                }
            }
        } catch (e: Exception) {
            Log.d("PornHD8K", "Error parsing AJAX response: ${e.message}")

            // Fallback: try regex extraction from the JSON text
            val fileRegex = """"file"\s*:\s*"([^"]+)"""".toRegex()
            val labelRegex = """"label"\s*:\s*"([^"]+)"""".toRegex()

            val files = fileRegex.findAll(jsonText).map { it.groupValues[1] }.toList()
            val labels = labelRegex.findAll(jsonText).map { it.groupValues[1] }.toList()

            files.forEachIndexed { index, fileUrl ->
                val quality = labels.getOrNull(index) ?: "Unknown"
                val isM3u8 = fileUrl.contains(".m3u8")

                callback.invoke(
                    newExtractorLink(
                        source = name,
                        name = "$name - $quality",
                        url = fileUrl,
                        type = if (isM3u8) ExtractorLinkType.M3U8 else ExtractorLinkType.VIDEO
                    ) {
                        this.referer = mainUrl
                        this.quality = getQualityFromName(quality)
                    }
                )
            }
        }

        return true
    }

    private fun md5(input: String): String {
        val md = MessageDigest.getInstance("MD5")
        val digest = md.digest(input.toByteArray())
        return digest.joinToString("") { "%02x".format(it) }
    }

    // Data classes for JSON parsing
    data class AjaxResponse(
        val playlist: List<PlaylistItem>? = null,
        val embed: Boolean? = null
    )

    data class PlaylistItem(
        val image: String? = null,
        val sources: List<SourceItem>? = null,
        val tracks: Any? = null
    )

    data class SourceItem(
        val file: String? = null,
        val type: String? = null,
        val label: String? = null,
        val default: String? = null,
        val mimeType: String? = null
    )
}
