package com.wus.hdpornfull

import com.lagradost.cloudstream3.SubtitleFile
import com.lagradost.cloudstream3.app
import com.lagradost.cloudstream3.utils.ExtractorApi
import com.lagradost.cloudstream3.utils.ExtractorLink
import com.lagradost.cloudstream3.utils.ExtractorLinkType
import com.lagradost.cloudstream3.utils.Qualities
import com.lagradost.cloudstream3.utils.newExtractorLink

class HdpornfullExtractor : ExtractorApi() {

    override var name = "Hdpornfull"
    override var mainUrl = "https://hdpornfull.com"
    override val requiresReferer = false

    private val direct = Regex(
        """https?://[^\s"']+?\.(?:mp4|m3u8)(?:\?[^\s"']*)?""",
        RegexOption.IGNORE_CASE
    )
    private val playerJson = Regex(
        """["'](?:file|src|url|source)["']\s*:\s*["']([^"']+?\.(?:mp4|m3u8)[^"']*)["']""",
        RegexOption.IGNORE_CASE
    )

    override suspend fun getUrl(
        url: String,
        referer: String?,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ) {
        val html = app.get(url, referer = referer ?: mainUrl).text

        val candidates = LinkedHashSet<String>()
        playerJson.findAll(html).forEach { m -> candidates.add(m.groupValues[1]) }
        direct.findAll(html).forEach { m -> candidates.add(m.value) }

        if (candidates.isEmpty()) {
            // fall back to an iframe hop
            val iframe = Regex(
                """<iframe[^>]+?(?:data-)?(?:src|data-src)\s*=\s*["']([^"']+)["']""",
                RegexOption.IGNORE_CASE
            ).find(html)?.groupValues?.getOrNull(1)
            if (iframe != null && !iframe.startsWith("data:")) {
                val absolute = if (iframe.startsWith("http")) iframe else mainUrl + iframe
                val inner = app.get(absolute, referer = url).text
                playerJson.findAll(inner).forEach { m -> candidates.add(m.groupValues[1]) }
                direct.findAll(inner).forEach { m -> candidates.add(m.value) }
            }
        }

        candidates.forEach { raw ->
            val link = if (raw.startsWith("http")) raw else mainUrl + raw
            val type = if (link.contains(".m3u8")) ExtractorLinkType.M3U8 else ExtractorLinkType.VIDEO
            callback(
                newExtractorLink(name, name, link, type) {
                    this.referer = url
                    this.quality = when {
                        link.contains("1080") -> Qualities.P1080.value
                        link.contains("720")  -> Qualities.P720.value
                        link.contains("480")  -> Qualities.P480.value
                        else -> Qualities.Unknown.value
                    }
                }
            )
        }
    }
}