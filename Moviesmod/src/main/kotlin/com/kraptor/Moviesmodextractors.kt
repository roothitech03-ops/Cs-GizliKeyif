package com.kraptor

import com.lagradost.cloudstream3.app
import com.lagradost.cloudstream3.utils.ExtractorApi
import com.lagradost.cloudstream3.utils.ExtractorLink
import com.lagradost.cloudstream3.utils.Qualities
import java.net.URI
import java.util.Base64

/*
 * ============================================================
 *  Driveseed / Driveleech Extractor
 * ============================================================
 *
 *  This extractor is registered with CloudStream's extractor
 *  system so that loadExtractor() also works if the embed URL
 *  ever gets passed through the standard extractors pipeline.
 *
 *  It mirrors the full resolution logic in MoviesMod.kt so
 *  that both code paths produce video links.
 * ============================================================
 */

open class DriveseedExtractor : ExtractorApi() {
    override val name            = "Driveseed"
    override val mainUrl         = "https://driveseed.org"
    override val requiresReferer = true

    private val userAgent =
        "Mozilla/5.0 (Windows NT 10.0; Win64; x64) " +
        "AppleWebKit/537.36 (KHTML, like Gecko) " +
        "Chrome/124.0.0.0 Safari/537.36"

    private fun hdr(referer: String) = mapOf(
        "User-Agent" to userAgent,
        "Referer"    to referer,
    )

    override suspend fun getUrl(
        url: String,
        referer: String?,
        subtitleCallback: (com.lagradost.cloudstream3.SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ) {
        val ref = referer ?: mainUrl
        val html = try { app.get(url, headers = hdr(ref)).text } catch (_: Exception) { return }

        // Follow JS window.location.replace() redirect
        val filePageUrl = Regex("""window\.location\.replace\(['"]([^'"]+)['"]""")
            .find(html)?.groupValues?.get(1) ?: url

        val filePage = try {
            app.get(filePageUrl, headers = hdr(ref)).document
        } catch (_: Exception) { return }

        val host    = URI(filePageUrl).let { "${it.scheme}://${it.host}" }
        val quality = when {
            url.contains("1080") -> Qualities.P1080.value
            url.contains("720")  -> Qualities.P720.value
            url.contains("480")  -> Qualities.P480.value
            else                 -> Qualities.Unknown.value
        }

        // ── Instant Download ─────────────────────────────────────────────────
        val keysMatch = Regex("""keys\s*=\s*["']?([^"'\s,;]+)""")
            .find(filePage.html())?.groupValues?.get(1)
        if (keysMatch != null) {
            try {
                val apiResp = app.post(
                    "$host/api",
                    headers = hdr(filePageUrl) + mapOf("x-token" to URI(filePageUrl).host),
                    data    = mapOf("keys" to keysMatch)
                )
                Regex("""https?://[^\s"'<>]+\.(?:mp4|mkv|m3u8)[^\s"'<>]*""")
                    .find(apiResp.text)?.value?.let { directUrl ->
                        callback(
                            ExtractorLink(
                                source  = name,
                                name    = "$name Instant",
                                url     = directUrl,
                                referer = filePageUrl,
                                quality = quality,
                                isM3u8  = directUrl.contains(".m3u8"),
                            )
                        )
                        return
                    }
            } catch (_: Exception) { }
        }

        // ── Worker Bot ────────────────────────────────────────────────────────
        filePage.selectFirst("a:containsOwn(Resume Worker Bot), a:containsOwn(Worker Bot)")
            ?.absUrl("href")?.ifBlank { null }?.let { workerUrl ->
                try {
                    val workerPage = app.get(workerUrl, headers = hdr(filePageUrl)).text
                    val token = Regex("""formData\.append\(['"]token['"]\s*,\s*['"]([^'"]+)""")
                        .find(workerPage)?.groupValues?.get(1)
                    val id    = Regex("""fetch\(['"]/download\?id=([^'"&]+)""")
                        .find(workerPage)?.groupValues?.get(1)
                    if (token != null && id != null) {
                        val wHost = URI(workerUrl).let { "${it.scheme}://${it.host}" }
                        val dlResp = app.post(
                            "$wHost/download?id=$id",
                            headers = hdr(workerUrl) + mapOf("x-requested-with" to "XMLHttpRequest"),
                            data    = mapOf("token" to token)
                        )
                        Regex("""https?://[^\s"'<>]+\.(?:mp4|mkv|m3u8)[^\s"'<>]*""")
                            .find(dlResp.text)?.value?.let { directUrl ->
                                callback(
                                    ExtractorLink(
                                        source  = name,
                                        name    = "$name Worker",
                                        url     = directUrl,
                                        referer = workerUrl,
                                        quality = quality,
                                        isM3u8  = directUrl.contains(".m3u8"),
                                    )
                                )
                                return
                            }
                    }
                } catch (_: Exception) { }
            }

        // ── Resume Cloud ──────────────────────────────────────────────────────
        filePage.selectFirst("a:containsOwn(Resume Cloud), a:containsOwn(Cloud Resume Download)")
            ?.absUrl("href")?.ifBlank { null }?.let { cloudUrl ->
                try {
                    val cloudDoc = app.get(cloudUrl, headers = hdr(filePageUrl)).document
                    cloudDoc.selectFirst("a.btn-success, a:containsOwn(Cloud Resume Download)")
                        ?.absUrl("href")?.ifBlank { null }?.let { finalUrl ->
                            callback(
                                ExtractorLink(
                                    source  = name,
                                    name    = "$name Cloud",
                                    url     = finalUrl,
                                    referer = cloudUrl,
                                    quality = quality,
                                    isM3u8  = finalUrl.contains(".m3u8"),
                                )
                            )
                        }
                } catch (_: Exception) { }
            }
    }
}

/** Handles driveleech.net — same logic as Driveseed. */
class DriveleechExtractor : DriveseedExtractor() {
    override val name    = "Driveleech"
    override val mainUrl = "https://driveleech.net"
}