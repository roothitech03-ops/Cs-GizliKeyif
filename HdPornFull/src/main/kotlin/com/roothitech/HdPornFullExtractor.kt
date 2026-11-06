package roothitech

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import org.jsoup.Jsoup
import org.jsoup.nodes.Document
import java.util.concurrent.TimeUnit

/**
 * HTML → structured data. Site-specific selectors live here only.
 * If the source site changes, you only touch this file.
 */
class ProviderExtractor(private var cfg: ProviderConfig) {

    private val http: OkHttpClient by lazy {
        OkHttpClient.Builder()
            .connectTimeout(cfg.timeoutMs, TimeUnit.MILLISECONDS)
            .readTimeout(cfg.timeoutMs, TimeUnit.MILLISECONDS)
            .addInterceptor { chain ->
                chain.proceed(
                    chain.request().newBuilder()
                        .header("User-Agent", cfg.userAgent)
                        .header("Accept-Language", "en-US,en;q=0.9")
                        .build()
                )
            }
            .build()
    }

    fun updateConfig(c: ProviderConfig) { cfg = c }

    suspend fun list(url: String): ProviderResult = withContext(Dispatchers.IO) {
        val doc = fetch(url)
        val items = doc.select("article.item, div.video-card, .thumb-block").mapNotNull { el ->
            val a = el.selectFirst("a[href]") ?: return@mapNotNull null
            val title = el.selectFirst(".title, h2, h3")?.text()?.trim() ?: return@mapNotNull null
            val img = el.selectFirst("img")
            val thumb = img?.absUrl("data-src").takeUnless { it.isNullOrBlank() }
                ?: img?.absUrl("src").orEmpty()
            val dur = el.selectFirst(".duration, .time")?.text()?.let(::parseDuration)
            VideoItem(
                id = a.absUrl("href").hashCode().toString(),
                title = title,
                thumb = thumb,
                pageUrl = a.absUrl("href"),
                durationSec = dur
            )
        }
        val hasNext = doc.selectFirst("a.next, .pagination a:last-child") != null
        ProviderResult(items, pageOf(url), hasNext)
    }

    suspend fun stream(pageUrl: String): StreamInfo = withContext(Dispatchers.IO) {
        val doc = fetch(pageUrl)
        val v = doc.selectFirst("video")
        val direct = v?.absUrl("src")?.takeUnless { it.isNullOrBlank() }
        val hls = v?.absUrl("data-hls")?.takeUnless { it.isNullOrBlank() }
            ?: doc.selectFirst("source[type=application/x-mpegURL]")?.absUrl("src")

        // Fallback: extract from inline JS — var video_url = "...";
        val jsUrl = Regex("""video_url\s*[:=]\s*["']([^"']+)["']""")
            .find(doc.html())?.groupValues?.get(1)

        StreamInfo(
            videoUrl = direct ?: jsUrl.takeIf { it?.endsWith(".mp4") == true },
            hlsUrl = hls ?: jsUrl.takeIf { it?.contains(".m3u8") == true }
        )
    }

    private fun fetch(url: String): Document {
        val req = Request.Builder().url(url).build()
        http.newCall(req).execute().use { r ->
            if (!r.isSuccessful) error("HTTP ${r.code} $url")
            return Jsoup.parse(r.body?.string().orEmpty(), url)
        }
    }

    private fun parseDuration(s: String): Int? {
        val p = s.trim().split(":").mapNotNull { it.toIntOrNull() }
        return when (p.size) {
            2 -> p[0] * 60 + p[1]
            3 -> p[0] * 3600 + p[1] * 60 + p[2]
            else -> null
        }
    }

    private fun pageOf(url: String): Int =
        Regex("/page/(\\d+)/?").find(url)?.groupValues?.get(1)?.toIntOrNull() ?: 1
}