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
import org.json.JSONObject
import java.security.MessageDigest
import javax.crypto.Cipher
import javax.crypto.spec.IvParameterSpec
import javax.crypto.spec.SecretKeySpec

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

    // ─── Helpers ──────────────────────────────────────────────────────────────

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

    // ─── AbyssCDN Decryption ─────────────────────────────────────────────────
    //
    // AbyssCDN player-v2 (iamcdn.net/player-v2/core.bundle.js) algorithm:
    //
    //   keyStr      = "${user_id}:${slug}:${md5_id}"
    //   md5Hex      = MD5(keyStr).toHexString()   → 32-char lowercase hex
    //   encodedKey  = md5Hex.toByteArray(UTF-8)   → 32 bytes  (AES-256 key)
    //   counter     = encodedKey[0..15]            → first 16 bytes = AES-CTR IV
    //   plaintext   = AES-256-CTR decrypt(key=encodedKey, iv=counter, data=mediaBytes)
    //   result      = JSON.parse(plaintext)        → {mp4:{sources:[{url,path,label}]}}
    //
    // Final source URL  =  source.url + "/" + source.path
    //
    // SPECIAL PARSING: The outer `datas` JSON is constructed server-side by
    // embedding raw binary bytes for the "media" field.  Some bytes in range
    // 0x00-0x1F are stored as \uXXXX escapes, but with a server bug where
    // nibble 0 is stored as binary 0x00 instead of ASCII '0' (0x30).
    // Standard JSON.parse() therefore REJECTS the blob.  We use a custom
    // scanner (decodeAbyssMediaBytes) that is lenient about that exact bug.

    /** MD5 of [input] → 32-char lowercase hex string. */
    private fun md5Hex(input: String): String {
        val digest = MessageDigest.getInstance("MD5")
        return digest.digest(input.toByteArray(Charsets.UTF_8))
            .joinToString("") { "%02x".format(it.toInt() and 0xFF) }
    }

    /**
     * Scan the raw bytes of the outer-JSON blob starting at [startPos] and
     * decode one JSON string value (the media ciphertext), stopping at the
     * first unescaped `"` (0x22).
     *
     * Rules (superset of JSON, lenient about the server-side null-byte bug):
     *   \\n → 0x0A,  \\r → 0x0D,  \\t → 0x09,  \\b → 0x08,  \\f → 0x0C
     *   \\\\ → 0x5C,  \\" → 0x22,  \\/ → 0x2F
     *   \\uXXXX → (codepoint & 0xFF) ; a hex nibble of 0x00 is treated as '0'
     *   any other byte → taken verbatim
     */
    private fun decodeAbyssMediaBytes(raw: ByteArray, startPos: Int): ByteArray {
        val out = ArrayList<Byte>(2400)
        var i   = startPos
        while (i < raw.size) {
            val b = raw[i].toInt() and 0xFF
            when {
                b == 0x22 -> break                         // closing "
                b == 0x5C -> {                             // backslash
                    val esc = if (i + 1 < raw.size) (raw[i + 1].toInt() and 0xFF) else 0
                    when (esc) {
                        0x22 -> { out.add(0x22.toByte()); i += 2 }
                        0x5C -> { out.add(0x5C.toByte()); i += 2 }
                        0x2F -> { out.add(0x2F.toByte()); i += 2 }
                        0x6E -> { out.add(0x0A.toByte()); i += 2 }
                        0x72 -> { out.add(0x0D.toByte()); i += 2 }
                        0x74 -> { out.add(0x09.toByte()); i += 2 }
                        0x62 -> { out.add(0x08.toByte()); i += 2 }
                        0x66 -> { out.add(0x0C.toByte()); i += 2 }
                        0x75 -> {                           // \uXXXX
                            if (i + 5 < raw.size) {
                                val n1 = hexNibble(raw[i + 2])
                                val n2 = hexNibble(raw[i + 3])
                                val n3 = hexNibble(raw[i + 4])
                                val n4 = hexNibble(raw[i + 5])
                                val cp = (n1 shl 12) or (n2 shl 8) or (n3 shl 4) or n4
                                out.add((cp and 0xFF).toByte())
                            }
                            i += 6
                        }
                        else -> i += 2                     // unknown escape – skip
                    }
                }
                else -> { out.add(b.toByte()); i++ }
            }
        }
        return out.toByteArray()
    }

    /** Convert one raw byte to its hex nibble value (0-15).
     *  Treats 0x00 as digit '0' to compensate for the server-side encoding bug. */
    private fun hexNibble(raw: Byte): Int {
        val b = raw.toInt() and 0xFF
        return when {
            b == 0x00            -> 0        // server bug: binary 0 instead of ASCII '0'
            b in 0x30..0x39      -> b - 0x30 // '0'-'9'
            b in 0x41..0x46      -> b - 0x41 + 10 // 'A'-'F'
            b in 0x61..0x66      -> b - 0x61 + 10 // 'a'-'f'
            else                 -> 0
        }
    }

    /**
     * AES-256-CTR decrypt the [mediaBytes] ciphertext.
     * Key and IV are both derived from the MD5 hex string of the key material.
     */
    private fun abyssDecrypt(mediaBytes: ByteArray, slug: String, md5Id: String, userId: String): String {
        val keyStr     = "$userId:$slug:$md5Id"
        val md5HexStr  = md5Hex(keyStr)                         // 32-char hex
        val encodedKey = md5HexStr.toByteArray(Charsets.UTF_8)  // 32 bytes → AES-256
        val counter    = encodedKey.copyOfRange(0, 16)          // first 16 = CTR IV

        val cipher = Cipher.getInstance("AES/CTR/NoPadding")
        cipher.init(
            Cipher.DECRYPT_MODE,
            SecretKeySpec(encodedKey, "AES"),
            IvParameterSpec(counter)
        )
        return String(cipher.doFinal(mediaBytes), Charsets.UTF_8)
    }

    /**
     * Find the byte-offset of [needle] (ASCII) inside [haystack], starting at [from].
     * Returns -1 if not found.
     */
    private fun indexOfBytes(haystack: ByteArray, needle: ByteArray, from: Int = 0): Int {
        outer@ for (i in from..(haystack.size - needle.size)) {
            for (j in needle.indices) {
                if (haystack[i + j] != needle[j]) continue@outer
            }
            return i
        }
        return -1
    }

    /**
     * Extract AbyssCDN video links from the page at `https://abysscdn.com/?v=[videoId]`.
     *
     * The page exposes:
     *   const datas = "BASE64_OF_BINARY_JSON";
     * where the binary JSON encodes:
     *   { slug, md5_id, user_id, media: <AES-256-CTR ciphertext> }
     * After decrypting `media` we get:
     *   { mp4: { sources: [ {url, path, label} ] } }
     * and each playable URL is  source.url + "/" + source.path.
     */
    private suspend fun extractAbyssLinks(
        videoId: String,
        referer: String,
        callback: (ExtractorLink) -> Unit
    ) {
        val pageHtml = try {
            app.get(
                "https://abysscdn.com/?v=$videoId",
                headers = mapOf(
                    "User-Agent" to "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36",
                    "Referer"    to referer
                )
            ).text
        } catch (_: Exception) { return }

        // 1. Extract the base64 string from:  const datas = "...";
        val datasB64 = Regex("""const\s+datas\s*=\s*"([A-Za-z0-9+/=]+)"""")
            .find(pageHtml)?.groupValues?.get(1) ?: return

        // 2. Base64 decode → raw binary blob
        val rawBytes = try {
            Base64.decode(datasB64, Base64.DEFAULT)
        } catch (_: Exception) { return }

        // 3. Extract slug / md5_id / user_id via simple ASCII regex on the raw bytes
        //    (safe because these fields are pure ASCII numbers/strings)
        val rawAscii = String(rawBytes, Charsets.ISO_8859_1)
        val slug   = Regex(""""slug"\s*:\s*"([^"]+)"""").find(rawAscii)?.groupValues?.get(1) ?: return
        val md5Id  = Regex(""""md5_id"\s*:\s*(\d+)""").find(rawAscii)?.groupValues?.get(1) ?: return
        val userId = Regex(""""user_id"\s*:\s*(\d+)""").find(rawAscii)?.groupValues?.get(1) ?: return

        // 4. Locate the start of the "media" value bytes inside the raw blob
        val mediaPfx    = """"media":"""".toByteArray(Charsets.US_ASCII)
        val mediaPfxPos = indexOfBytes(rawBytes, mediaPfx)
        if (mediaPfxPos < 0) return
        val mediaStart  = mediaPfxPos + mediaPfx.size

        // 5. Custom-decode the binary JSON string value (handles server-side \u-bug)
        val mediaBytes = decodeAbyssMediaBytes(rawBytes, mediaStart)
        if (mediaBytes.isEmpty()) return

        // 6. AES-256-CTR decrypt → inner JSON
        val decryptedJson = try {
            abyssDecrypt(mediaBytes, slug, md5Id, userId)
        } catch (_: Exception) { return }

        // 7. Parse inner JSON and emit ExtractorLinks
        val inner = try { JSONObject(decryptedJson) } catch (_: Exception) { return }
        val sources = inner.optJSONObject("mp4")?.optJSONArray("sources") ?: return

        for (i in 0 until sources.length()) {
            val src   = sources.optJSONObject(i) ?: continue
            val url   = src.optString("url").trim()
            val path  = src.optString("path").trim()
            val label = src.optString("label", "?").trim()
            if (url.isBlank() || path.isBlank()) continue

            val quality = when (label) {
                "360p"  -> Qualities.P360.value
                "480p"  -> Qualities.P480.value
                "720p"  -> Qualities.P720.value
                "1080p" -> Qualities.P1080.value
                else    -> Qualities.Unknown.value
            }

            callback.invoke(
                newExtractorLink(
                    source = name,
                    name   = "$name - Abyss $label",
                    url    = "$url/$path",
                    type   = ExtractorLinkType.VIDEO
                ) {
                    this.referer = "https://abysscdn.com/"
                    this.quality = quality
                }
            )
        }
    }

    // ─── Main Page ────────────────────────────────────────────────────────────
    // FIX: Site uses /page/N/ pagination — NOT ?page=N

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
        // Page 1 = base URL, Page 2+ = base/page/2/ etc.
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
            ?: el.selectFirst("img[src]")?.attr("src")
            ?: ""

        return newAnimeSearchResponse(title, href, TvType.Anime) {
            posterUrl = fixImg(rawImg)
        }
    }

    // ─── Search ───────────────────────────────────────────────────────────────

    override suspend fun search(query: String): List<SearchResponse> {
        val doc = getDoc("$mainUrl/?s=${query.replace(" ", "+")}")
        return doc.select("ul.post-lst li").mapNotNull { parseListing(it) }
    }

    // ─── Series Detail ────────────────────────────────────────────────────────
    // KEY FIX: Episodes are NEVER pre-loaded in HTML — always fetched via AJAX
    // KEY FIX: Correct action = "action_select_season", correct param = "post"
    // KEY FIX: Season lambda shadowing — renamed param to "seasonNum" to avoid
    //           clash with Episode.season property inside newEpisode { } lambda

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

        // WordPress post ID: from season button data-post OR body class "postid-NNN"
        val seasonEls = doc.select(".sel-temp a[data-season]")
        val postId = seasonEls.firstOrNull()?.attr("data-post")
            ?: Regex("""postid-(\d+)""")
                .find(doc.selectFirst("body")?.attr("class") ?: "")
                ?.groupValues?.get(1)
            ?: ""

        // Nonce from torofilm_Public JS object embedded in page
        val nonce = Regex(""""nonce"\s*:\s*"([^"]+)"""")
            .find(doc.html())?.groupValues?.get(1) ?: ""

        // Season list: from buttons or default to [1]
        val seasonNumbers: List<Int> = if (seasonEls.isNotEmpty()) {
            seasonEls.mapNotNull { it.attr("data-season").toIntOrNull() }.distinct().sorted()
        } else {
            listOf(1)
        }

        val allEpisodes = mutableListOf<Episode>()

        // BUG FIX EXPLANATION:
        // Inside newEpisode { } the receiver is Episode which has a property called "season".
        // If the outer function param is also called "season", then inside the lambda
        // writing "season" refers to Episode.season (null by default), NOT the outer param.
        // So "this.season = season" becomes "this.season = this.season" — a no-op!
        // FIX: Rename the param to "seasonNum" so there is no ambiguity at all.
        fun Element.toEpisode(seasonNum: Int): Episode? {
            val epHref = selectFirst("a.lnk-blk, a[href*='/episode/']")?.attr("href")
                ?: return null

            // Extract season & episode number from URL: …-{s}x{ep}/
            val match     = Regex("""-(\d+)x(\d+)/?$""").find(epHref)
            val urlSeason = match?.groupValues?.get(1)?.toIntOrNull() ?: seasonNum
            val epNum     = if (urlSeason == seasonNum)
                match?.groupValues?.get(2)?.toIntOrNull()
            else null

            val epTitle = selectFirst(".num-epi")?.text()?.cleanHtml()
                ?: selectFirst("h2.entry-title")?.text()?.cleanHtml()
                ?: "Episode ${epNum ?: ""}"

            val epThumb = selectFirst("img[data-src]")?.attr("data-src")?.let { fixImg(it) }
                ?: selectFirst("img[src]")?.attr("src")?.let { fixImg(it) }

            // FIX: season = seasonNum — no shadowing, "seasonNum" is not a property of Episode
            return newEpisode(epHref) {
                name      = epTitle
                posterUrl = epThumb
                season    = seasonNum
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

                // If episode numbers weren't parseable, assign sequential indices
                if (eps.all { it.episode == null }) {
                    eps.forEachIndexed { idx, ep ->
                        allEpisodes.add(newEpisode(ep.data) {
                            name      = ep.name
                            posterUrl = ep.posterUrl
                            season    = seasonNum   // FIX: no shadowing here either
                            episode   = idx + 1
                        })
                    }
                } else {
                    allEpisodes.addAll(eps)
                }
            } catch (_: Exception) {
                // AJAX failed for this season — skip silently
            }
        }

        allEpisodes.sortWith(compareBy({ it.season ?: 0 }, { it.episode ?: 0 }))

        return newTvSeriesLoadResponse(title, url, TvType.Anime, allEpisodes) {
            posterUrl = poster
            plot      = description
            tags      = genres
        }
    }

    // ─── Video Links ──────────────────────────────────────────────────────────
    // Server 1 (MultiCloud)  – zephyrflick : hash → master.m3u8 (HLS)
    // Server 2 (Abyss)       – player1.php → short.icu slug → abysscdn.com
    //                           custom AES-256-CTR decryption → sssrr.org MP4
    // Server 3 (MultiCloud2) – pixdrive.cfd (HLS or MP4 from page)

    override suspend fun loadLinks(
        data: String,
        isCasting: Boolean,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        val doc = getDoc(data)

        // ── Server 1: zephyrflick ─────────────────────────────────────────────
        doc.select("iframe[src*='play.zephyrflick.top'], iframe[data-src*='play.zephyrflick.top']")
            .distinctBy { it.attr("src").ifBlank { it.attr("data-src") } }
            .forEach { iframe ->
                val src = iframe.attr("src").ifBlank { iframe.attr("data-src") }
                if (src.isBlank()) return@forEach
                try {
                    val playerHtml = app.get(
                        src, referer = mainUrl,
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

        // ── Server 2: player1.php → AbyssCDN (custom AES-256-CTR decryption) ─
        // player1.php?data=BASE64 where base64 = [{"link":"https://short.icu/VIDEO_ID"}]
        // The short.icu slug = abysscdn.com video ID.
        // We decrypt the abysscdn.com page ourselves instead of using loadExtractor.
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
                    val decoded = String(
                        Base64.decode(URLDecoder.decode(dataParam, "UTF-8"), Base64.DEFAULT)
                    )
                    val arr = JSONArray(decoded)
                    for (i in 0 until arr.length()) {
                        val obj  = arr.getJSONObject(i)
                        val link = obj.optString("link", "").trim()
                        if (link.isBlank()) continue
                        // short.icu/SLUG — slug IS the abysscdn video ID
                        val videoId = link.trimEnd('/').substringAfterLast('/')
                        if (videoId.isBlank()) continue
                        extractAbyssLinks(videoId, data, callback)
                    }
                } catch (_: Exception) {}
            }

        // ── Server 3: pixdrive.cfd (MultiCloud2) ─────────────────────────────
        doc.select("iframe[src*='pixdrive.cfd'], iframe[data-src*='pixdrive.cfd']")
            .distinctBy { it.attr("src").ifBlank { it.attr("data-src") } }
            .forEach { iframe ->
                val src = iframe.attr("src").ifBlank { iframe.attr("data-src") }
                if (src.isBlank()) return@forEach
                try {
                    val playerHtml = app.get(
                        src, referer = mainUrl,
                        headers = mapOf("User-Agent" to "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36")
                    ).text
                    val m3u8 = Regex("""https?://[^\s"'<>]+\.m3u8[^\s"'<>]*""").find(playerHtml)?.value
                    val mp4  = Regex("""https?://[^\s"'<>]+\.mp4[^\s"'<>]*""").find(playerHtml)?.value
                    when {
                        m3u8 != null -> callback.invoke(
                            newExtractorLink(
                                source = name,
                                name   = "$name - MultiCloud2",
                                url    = m3u8,
                                type   = ExtractorLinkType.M3U8
                            ) {
                                referer = src
                                quality = Qualities.Unknown.value
                            }
                        )
                        mp4 != null -> callback.invoke(
                            newExtractorLink(
                                source = name,
                                name   = "$name - MultiCloud2",
                                url    = mp4,
                                type   = ExtractorLinkType.VIDEO
                            ) {
                                referer = src
                                quality = Qualities.Unknown.value
                            }
                        )
                        else -> loadExtractor(src, data, subtitleCallback, callback)
                    }
                } catch (_: Exception) {
                    try { loadExtractor(src, data, subtitleCallback, callback) } catch (_: Exception) {}
                }
            }

        // ── Any other iframe servers ──────────────────────────────────────────
        doc.select("div[id^='options-'] iframe[data-src]").forEach { iframe ->
            val src = iframe.attr("data-src").trim()
            if (src.isBlank()
                || src.contains("player1.php")
                || src.contains("zephyrflick")
                || src.contains("pixdrive")
            ) return@forEach
            try { loadExtractor(src, data, subtitleCallback, callback) } catch (_: Exception) {}
        }

        return true
    }
}
