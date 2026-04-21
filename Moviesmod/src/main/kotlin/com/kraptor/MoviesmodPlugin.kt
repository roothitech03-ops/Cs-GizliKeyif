package com.kraptor

import com.lagradost.cloudstream3.plugins.CloudstreamPlugin
import com.lagradost.cloudstream3.plugins.Plugin
import android.content.Context
import com.lagradost.cloudstream3.utils.ExtractorApi
import com.lagradost.cloudstream3.utils.extractorApis

@CloudstreamPlugin
class MoviesModPlugin : Plugin() {
    override fun load(context: Context) {
        registerMainAPI(MoviesMod())
        // Register extractors so loadExtractor() also works
        registerExtractorAPI(DriveseedExtractor())
        registerExtractorAPI(DriveleechExtractor())
    }
}
