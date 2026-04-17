package com.kraptor

import com.lagradost.cloudstream3.plugins.CloudstreamPlugin
import com.lagradost.cloudstream3.plugins.Plugin
import android.content.Context

@CloudstreamPlugin
class WatchAnimeWorldPlugin : Plugin() {
    override fun load(context: Context) {
        // Register the main API
        registerMainAPI(WatchAnimeWorld())
        // Register any extractors if needed
        // registerExtractorAPI(YOUR_EXTRACTOR_NAME)
    }
}