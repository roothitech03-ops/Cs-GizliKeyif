package com.wus.hdpornfull

import android.content.Context
import com.lagradost.cloudstream3.plugins.CloudstreamPlugin
import com.lagradost.cloudstream3.plugins.Plugin

@CloudstreamPlugin
class HdpornfullPlugin : Plugin() {
    override fun load(context: Context) {
        registerMainAPI(HdpornfullProvider())
        registerExtractorAPI(HdpornfullExtractor())
    }
}