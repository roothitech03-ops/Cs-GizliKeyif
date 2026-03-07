package com.kraptor

import com.lagradost.api.Log
import org.jsoup.nodes.Element
import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.utils.*
import com.lagradost.cloudstream3.LoadResponse.Companion.addActors

class XTapes : MainAPI() {
    override var mainUrl              = "https://xtapes.tw"
    override var name                 = "XTapes"
    override val hasMainPage          = true
    override var lang                 = "en"
    override val hasQuickSearch       = false
    override val supportedTypes       = setOf(TvType.NSFW)
    override val vpnStatus            = VPNStatus.MightBeNeeded

    override val mainPage = mainPageOf(
        "${mainUrl}/?filtre=date&cat=0" to "Latest Videos",
        "${mainUrl}/?filtre=views" to "Most Popular",
        "${mainUrl}/?filtre=rate" to "Top Rated",
        "${mainUrl}/?filtre=date&cat=1766" to "Full Movies",
        "${mainUrl}/4k-porn-104363/" to "4K",
        "${mainUrl}/721584/" to "Anal",
        "${mainUrl}/761395/" to "Asian",
        "${mainUrl}/236413/" to "Big Tits",
        "${mainUrl}/blowjob-oral-cok-sucking-pussy-licking-45105/" to "Blowjob",
        "${mainUrl}/creampie-cum-inside-356185/" to "Creampie",
        "${mainUrl}/lesbian-porn-videos-047434/" to "Lesbian",
        "${mainUrl}/milf-mom-hd-porn-438706/" to "MILFs",
        "${mainUrl}/hd-teen-porn-videos-126957/" to "Teen",
        "${mainUrl}/0647848/" to "Brazzers",
        "${mainUrl}/476126/" to "BangBros",
        "${mainUrl}/716908/" to "Reality Kings",
        "${mainUrl}/584379/" to "Vixen",
        "${mainUrl}/716908/" to "Blacked",
        "${mainUrl}/495429/" to "Tushy",
    )

    override suspend fun getMainPage(page: Int, request: MainPageRequest): HomePageResponse {
        val url = if (page == 1) {
            request.data
        } else {
            val baseUrl = request.data
            if (baseUrl.contains("?")) {
                val parts = baseUrl.split("?", limit = 2)
                "${parts[0]}page/${page}/?${parts[1]}"
            } else {
                if (baseUrl.endsWith("/")) {
                    "${baseUrl}page/${page}/"
                } else {
                    "${baseUrl}/page/${page}/"
                }
            }
        }
        val document = app.get(url).document
        val home = document.select("ul.listing-tube li.border-radius-5").mapNotNull { it.toSearchResult() }
        return newHomePageResponse(request.name, home, hasNext = home.isNotEmpty())
    }

    private fun Element.toSearchResult(): SearchResponse? {
        val anchor = this.selectFirst("a[title]") ?: return null
        val title = anchor.attr("title").ifEmpty { 
            this.selectFirst("h3")?.text() ?: this.selectFirst("strong")?.text()
        } ?: return null
        val href = fixUrlNull(anchor.attr("href")) ?: return null
        val posterUrl = fixUrlNull(
            this.selectFirst("img")?.attr("src")
        )
        return newMovieSearchResponse(title.trim(), href, TvType.NSFW) {
            this.posterUrl = posterUrl
        }
    }

    override suspend fun search(query: String): List<SearchResponse> {
        val searchUrl = "${mainUrl}/?s=${query}"
        val document = app.get(searchUrl).document
        return document.select("ul.listing-tube li.border-radius-5").mapNotNull { it.toSearchResult() }
    }

    override suspend fun quickSearch(query: String): List<SearchResponse>? = search(query)

    override suspend fun load(url: String): LoadResponse? {
        val document = app.get(url).document
        val title = document.selectFirst("h1")?.text()?.trim() ?: return null
        val poster = fixUrlNull(
            document.selectFirst("meta[itemprop=thumbnailUrl]")?.attr("content")
                ?: document.selectFirst("meta[property=og:image]")?.attr("content")
        )
        val description = document.selectFirst("div.infotext")?.text()?.trim()
            ?: document.selectFirst("span[itemprop=description]")?.attr("content")?.trim()
            ?: document.selectFirst("meta[property=og:description]")?.attr("content")?.trim()

        val allTags = document.select("a[rel=tag]")
        val tags = allTags.filter { !it.attr("href").contains("hd-porn-") }
            .map { it.text().trim() }
            .filter { it.isNotEmpty() }
        val actors = allTags.filter { it.attr("href").contains("hd-porn-") }
            .map { Actor(it.text().trim(), null) }

        return newMovieLoadResponse(title, url, TvType.NSFW, url) {
            this.posterUrl = poster
            this.plot = description
            this.tags = tags
            addActors(actors)
        }
    }

    /**
     * Simple JS Unpacker for eval(function(p,a,c,k,e,d){...}) packed scripts.
     * This is a common JavaScript packer used by many video hosting sites.
     */
    private fun jsUnpack(packed: String): String? {
        // Match eval(function(p,a,c,k,e,d){...}('PACKED',BASE,COUNT,'DICT'.split('|')))
        // Using flexible regex to handle different backslash escaping variants
        val regex = Regex(
            """eval\(function\(p,a,c,k,e,d\)\{while\(c--\)if\(k\[c]\)p=p\.replace\(new RegExp\(.{3,30}c\.toString\(a\).{3,30},k\[c]\);return p\}\('([\s\S]*?)',(\d+),(\d+),'([\s\S]*?)'\.split\('\|'\)\)\)"""
        )
        val match = regex.find(packed) ?: return null

        var p = match.groupValues[1]
        val a = match.groupValues[2].toIntOrNull() ?: return null
        var c = match.groupValues[3].toIntOrNull() ?: return null
        val k = match.groupValues[4].split("|")

        while (c-- > 0) {
            if (c < k.size && k[c].isNotEmpty()) {
                val word = Integer.toString(c, a)
                p = p.replace(Regex("\\b${Regex.escape(word)}\\b"), k[c])
            }
        }
        return p
    }

    override suspend fun loadLinks(
        data: String,
        isCasting: Boolean,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        val document = app.get(data).document
        val pageHtml = document.html()
        var foundLink = false

        // ===== Method 1: Extract from 74k.io iframe (primary method) =====
        val iframeSrcs = document.select("iframe[src]").map { it.attr("src") }
        
        // Find 74k.io iframe
        val embed74k = iframeSrcs.firstOrNull { it.contains("74k.io") }
        if (embed74k != null) {
            try {
                val embedUrl = if (embed74k.startsWith("//")) "https:$embed74k"
                               else if (!embed74k.startsWith("http")) "https://$embed74k"
                               else embed74k
                Log.d("XTapes", "Found 74k.io embed: $embedUrl")
                
                val embedResponse = app.get(
                    embedUrl,
                    referer = "$mainUrl/",
                    headers = mapOf(
                        "User-Agent" to "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120.0.0.0 Safari/537.36"
                    )
                ).text

                // Try to extract video links from the packed JS
                val links = extract74kLinks(embedResponse, embedUrl)
                if (links != null) {
                    foundLink = true
                    
                    // Add hls4 (74k.io stream proxy) - highest priority
                    if (links.hls4 != null) {
                        val hls4Url = if (links.hls4.startsWith("/")) {
                            "https://74k.io${links.hls4}"
                        } else links.hls4
                        
                        callback.invoke(
                            newExtractorLink(
                                source = "XTapes",
                                name = "XTapes - HLS4",
                                url = hls4Url,
                                type = ExtractorLinkType.M3U8
                            ) {
                                this.referer = embedUrl
                                this.quality = Qualities.Unknown.value
                                this.headers = mapOf(
                                    "Origin" to "https://74k.io",
                                    "Referer" to embedUrl
                                )
                            }
                        )
                    }
                    
                    // Add hls2 (direct CDN) - fallback
                    if (links.hls2 != null) {
                        callback.invoke(
                            newExtractorLink(
                                source = "XTapes",
                                name = "XTapes - HLS2",
                                url = links.hls2,
                                type = ExtractorLinkType.M3U8
                            ) {
                                this.referer = embedUrl
                                this.quality = Qualities.Unknown.value
                                this.headers = mapOf(
                                    "Origin" to "https://74k.io",
                                    "Referer" to embedUrl
                                )
                            }
                        )
                    }
                    
                    // Add hls3 (alternative CDN) - second fallback
                    if (links.hls3 != null) {
                        callback.invoke(
                            newExtractorLink(
                                source = "XTapes",
                                name = "XTapes - HLS3",
                                url = links.hls3,
                                type = ExtractorLinkType.M3U8
                            ) {
                                this.referer = embedUrl
                                this.quality = Qualities.Unknown.value
                                this.headers = mapOf(
                                    "Origin" to "https://74k.io",
                                    "Referer" to embedUrl
                                )
                            }
                        )
                    }
                }
            } catch (e: Exception) {
                Log.e("XTapes", "Error extracting from 74k.io: ${e.message}")
            }
        }

        // ===== Method 2: Try other iframes with loadExtractor =====
        if (!foundLink) {
            for (iframe in iframeSrcs) {
                val src = iframe
                if (src.isNotEmpty() && !src.contains("recaptcha") && !src.contains("twitter") 
                    && !src.contains("izerv") && !src.contains("timbuk") 
                    && src != "javascript:false" && !src.contains("74k.io")) {
                    try {
                        loadExtractor(fixUrl(src), data, subtitleCallback, callback)
                    } catch (e: Exception) {
                        Log.e("XTapes", "Error loading extractor for $src: ${e.message}")
                    }
                }
            }
        }

        // ===== Method 3: Try direct video URLs in page source =====
        if (!foundLink) {
            val patterns = listOf(
                Regex("""video_url['"]?\s*[:=]\s*['"]([^'"]+)['"]"""),
                Regex("""source['"]?\s*[:=]\s*['"]([^'"]+\.mp4[^'"]*)['"]"""),
                Regex("""file['"]?\s*[:=]\s*['"]([^'"]+\.mp4[^'"]*)['"]"""),
                Regex("""(https?://[^\s'"<>]+\.m3u8[^\s'"<>]*)""")
            )

            for (pattern in patterns) {
                val match = pattern.find(pageHtml)
                if (match != null) {
                    val videoUrl = match.groupValues[1].replace("\\/", "/")
                    if (videoUrl.contains(".mp4") || videoUrl.contains(".m3u8")) {
                        val linkType = if (videoUrl.contains(".m3u8")) ExtractorLinkType.M3U8 else ExtractorLinkType.VIDEO
                        callback.invoke(
                            newExtractorLink(this.name, this.name, videoUrl, linkType) {
                                this.referer = "$mainUrl/"
                                this.quality = Qualities.Unknown.value
                            }
                        )
                        foundLink = true
                        break
                    }
                }
            }
        }

        return foundLink
    }

    /**
     * Data class to hold the extracted HLS links from 74k.io
     */
    private data class HlsLinks(
        val hls2: String? = null,
        val hls3: String? = null,
        val hls4: String? = null
    )

    /**
     * Extract video links from 74k.io embed page.
     * The page contains packed JavaScript (eval packer) with JWPlayer setup
     * that includes HLS stream URLs.
     */
    private fun extract74kLinks(html: String, embedUrl: String): HlsLinks? {
        try {
            // Method A: Unpack the eval-packed JavaScript
            val unpacked = jsUnpack(html)
            if (unpacked != null) {
                Log.d("XTapes", "Successfully unpacked JS from 74k.io")
                return extractLinksFromUnpacked(unpacked, embedUrl)
            }
            
            // Method B: Try using CloudStream's built-in JsUnpacker
            try {
                val packedScript = Regex("""eval\(function\(p,a,c,k,e,d\)[\s\S]*?\)\)""").find(html)?.value
                if (packedScript != null) {
                    val csUnpacked = JsUnpacker(packedScript).unpack()
                    if (csUnpacked != null) {
                        Log.d("XTapes", "Unpacked with CS JsUnpacker")
                        return extractLinksFromUnpacked(csUnpacked, embedUrl)
                    }
                }
            } catch (e: Exception) {
                Log.d("XTapes", "CS JsUnpacker failed: ${e.message}")
            }

            // Method C: Try to extract URLs directly from the packed dictionary
            val directLinks = extractLinksFromPackedDict(html)
            if (directLinks != null) return directLinks

        } catch (e: Exception) {
            Log.e("XTapes", "Error in extract74kLinks: ${e.message}")
        }
        return null
    }

    /**
     * Extract HLS links from unpacked JavaScript content.
     */
    private fun extractLinksFromUnpacked(unpacked: String, embedUrl: String): HlsLinks? {
        var hls2: String? = null
        var hls3: String? = null
        var hls4: String? = null

        // Extract the links object: links={"hls2":"URL","hls3":"URL","hls4":"URL"}
        val linksMatch = Regex("""links\s*=\s*\{([^}]+)\}""").find(unpacked)
        if (linksMatch != null) {
            val linksContent = linksMatch.groupValues[1]
            
            hls2 = Regex(""""hls2"\s*:\s*"([^"]+)"""").find(linksContent)?.groupValues?.get(1)
            hls3 = Regex(""""hls3"\s*:\s*"([^"]+)"""").find(linksContent)?.groupValues?.get(1)
            hls4 = Regex(""""hls4"\s*:\s*"([^"]+)"""").find(linksContent)?.groupValues?.get(1)
            
            Log.d("XTapes", "Extracted links - hls2: ${hls2 != null}, hls3: ${hls3 != null}, hls4: ${hls4 != null}")
        }

        // Fallback: try to find m3u8 URLs directly
        if (hls2 == null && hls4 == null) {
            val m3u8Urls = Regex("""(https?://[^\s"'<>]+\.m3u8[^\s"'<>]*)""").findAll(unpacked)
            for (match in m3u8Urls) {
                val url = match.groupValues[1]
                if (hls2 == null) hls2 = url
            }
            
            val streamUrls = Regex("""(/stream/[^\s"'<>]+master\.m3u8)""").findAll(unpacked)
            for (match in streamUrls) {
                if (hls4 == null) hls4 = match.groupValues[1]
            }
        }

        // Also try to find file URL from sources setup
        if (hls2 == null && hls4 == null) {
            val fileMatch = Regex("""file\s*:\s*["']([^"']+\.m3u8[^"']*)["']""").find(unpacked)
            if (fileMatch != null) {
                hls2 = fileMatch.groupValues[1]
            }
        }

        return if (hls2 != null || hls3 != null || hls4 != null) {
            HlsLinks(hls2 = hls2, hls3 = hls3, hls4 = hls4)
        } else null
    }

    /**
     * Try to extract URLs directly from the packed JS dictionary
     * without fully unpacking. This is a fallback method.
     */
    private fun extractLinksFromPackedDict(html: String): HlsLinks? {
        // The packed JS dictionary contains the URL parts separated by |
        // Try to find m3u8 URLs or CDN hostnames in the dictionary
        val dictMatch = Regex("""'([^']+)'\.split\('\|'\)\)\)""").find(html) ?: return null
        val dict = dictMatch.groupValues[1].split("|")
        
        var hls2Url: String? = null
        
        // Look for full m3u8 URLs in the dictionary
        for (word in dict) {
            if (word.contains("master.m3u8") || (word.contains("m3u8") && word.contains("http"))) {
                hls2Url = word
                break
            }
        }
        
        // If we found a direct URL in the dictionary
        if (hls2Url != null) {
            return HlsLinks(hls2 = hls2Url)
        }
        
        return null
    }
}
