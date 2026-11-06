package com.roothitech

import org.jsoup.nodes.Document
import com.lagradost.cloudstream3.*

object HdPornFullExtractor {

    fun parseCards(doc: Document): List<SearchResponse> {
        val selectors = listOf(
            "article.item", "div.video-card", ".thumb-block", ".video-item", "article", ".post"
        )
        val cards = selectors.firstNotNullOfOrNull { sel ->
            doc.select(sel).takeIf { it.isNotEmpty() }
        } ?: return emptyList()

        return cards.mapNotNull { el ->
            val a = el.selectFirst("a[href]") ?: return@mapNotNull null
            val href = a.absUrl("href").takeIf { it.isNotBlank() } ?: return@mapNotNull null
            val title = el.selectFirst(".title, h2, h3, .entry-title")?.text()?.trim()
                ?: a.attr("title").takeIf { it.isNotBlank() }
                ?: return@mapNotNull null
            val img = el.selectFirst("img")
            val poster = img?.absUrl("data-src")?.takeIf { it.isNotBlank() }
                ?: img?.absUrl("src").orEmpty()
            newMovieSearchResponse(title, href, TvType.NSFW) {
                this.posterUrl = poster
            }
        }
    }
}