package com.kraptor

import com.lagradost.cloudstream3.*
import org.jsoup.nodes.Document
import org.json.JSONObject
import android.util.Log

/**
 * Inspired by Vega App's stream extraction logic
 * This file contains advanced extraction utilities for multiple server types
 */

class StreamExtractor {
    
    // Headers for HTTP requests (similar to Vega's approach)
    companion object {
        val defaultHeaders = mapOf(
            "Accept" to "text/html,application/xhtml+xml,application/xml;q=0.9,*/*;q=0.8",
            "Accept-Language" to "en-US,en;q=0.9",
            "User-Agent" to "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36"
        )
    }

    /**
     * Extract streaming links from unblockedgames URLs
     * Uses form submission method (POST) similar to Vega
     */
    suspend fun extractUnblockedGames(url: String): List<String> {
        val links = mutableListOf<String>()
        try {
            // Step 1: Get initial page
            var currentUrl = url
            if (url.contains("sid=")) {
                val sid = url.substringAfter("sid=")
                Log.d("StreamExtractor", "Found SID: $sid")
            }

            val firstResponse = app.get(url, headers = defaultHeaders)
            val firstDoc = firstResponse.document
            
            // Step 2: Extract form data
            val wpHttp2Input = firstDoc.select("input[name=_wp_http2]").attr("value")
            val formAction = firstDoc.select("form").attr("action")
            
            if (wpHttp2Input.isNotEmpty()) {
                Log.d("StreamExtractor", "Found form data")
                
                // Step 3: Submit form with extracted data
                try {
                    val formBody = "{"
                    val secondResponse = app.post(
                        formAction.ifEmpty { url },
                        data = mapOf("_wp_http2" to wpHttp2Input),
                        headers = defaultHeaders + mapOf(
                            "Referer" to url,
                            "Content-Type" to "application/x-www-form-urlencoded"
                        )
                    )
                    
                    val secondDoc = secondResponse.document
                    
                    // Step 4: Extract redirect link
                    val redirectLink = secondDoc.select("meta[http-equiv=refresh]")
                        .attr("content")
                        .substringAfter("url=")
                        .ifEmpty {
                            secondDoc.select("script").text()
                                .substringAfter("href=\"")
                                .substringBefore("\"")
                        }
                    
                    if (redirectLink.isNotEmpty()) {
                        links.add(redirectLink)
                        Log.d("StreamExtractor", "Extracted redirect link: $redirectLink")
                    }
                } catch (e: Exception) {
                    Log.d("StreamExtractor", "Error in form submission: ${e.message}")
                }
            }
            
            // Fallback: Try direct link extraction
            val directLinks = firstDoc.select("a[href*=drive], a[href*=gdflix], a[href*=vcloud]")
                .mapNotNull { it.attr("href").takeIf { it.isNotEmpty() } }
            links.addAll(directLinks)
            
        } catch (e: Exception) {
            Log.d("StreamExtractor", "Error extracting unblockedgames: ${e.message}")
        }
        return links
    }

    /**
     * Extract from Driveleech-type pages with multiple download options
     * Similar to Vega's approach for handling multiple server types
     */
    suspend fun extractFromDrivePage(url: String): List<String> {
        val links = mutableListOf<String>()
        try {
            val response = app.get(url, headers = defaultHeaders)
            val doc = response.document
            
            // Method 1: Button-based links (Most common)
            val buttonLinks = doc.select("a.btn, a.maxbutton")
                .mapNotNull { 
                    val href = it.attr("href")
                    href.takeIf { it.isNotEmpty() && !it.contains("#") }
                }
            links.addAll(buttonLinks)
            Log.d("StreamExtractor", "Found ${buttonLinks.size} button links")
            
            // Method 2: Direct href links with specific patterns
            val patternLinks = doc.select("a[href*=unblockedgames], a[href*=driveleech], a[href*=driveseed]")
                .mapNotNull { 
                    val href = it.attr("href")
                    href.takeIf { it.isNotEmpty() }
                }
            links.addAll(patternLinks)
            Log.d("StreamExtractor", "Found ${patternLinks.size} pattern links")
            
            // Method 3: Redirect links (from meta tags)
            val redirectLink = doc.select("meta[http-equiv=refresh]")
                .attr("content")
                .substringAfter("url=")
                .takeIf { it.isNotEmpty() }
            if (redirectLink != null) {
                links.add(redirectLink)
            }
            
        } catch (e: Exception) {
            Log.d("StreamExtractor", "Error extracting from drive page: ${e.message}")
        }
        return links
    }

    /**
     * Extract episode links from series pages
     * Returns list of episode/season links to be processed
     */
    suspend fun extractEpisodeLinks(url: String): List<Pair<String, String>> {
        val episodeLinks = mutableListOf<Pair<String, String>>()
        try {
            val response = app.get(url, headers = defaultHeaders)
            val doc = response.document
            
            // Method 1: h3/h4 tags (episodes)
            doc.select("h3, h4").forEach { element ->
                val title = element.text().trim()
                val link = element.select("a").attr("href")
                if (link.isNotEmpty() && title.isNotEmpty()) {
                    episodeLinks.add(Pair(title, link))
                }
            }
            
            // Method 2: maxbutton elements
            doc.select("a.maxbutton").forEach { element ->
                val title = element.text().trim()
                val link = element.attr("href")
                if (link.isNotEmpty() && title.isNotEmpty()) {
                    episodeLinks.add(Pair(title, link))
                }
            }
            
            Log.d("StreamExtractor", "Found ${episodeLinks.size} episode links")
            
        } catch (e: Exception) {
            Log.d("StreamExtractor", "Error extracting episode links: ${e.message}")
        }
        return episodeLinks
    }

    /**
     * Validate if a link is accessible and extractable
     */
    suspend fun validateLink(url: String): Boolean {
        return try {
            val response = app.get(url, headers = defaultHeaders, timeout = 10)
            response.code in 200..299
        } catch (e: Exception) {
            Log.d("StreamExtractor", "Link validation failed: ${e.message}")
            false
        }
    }
}
