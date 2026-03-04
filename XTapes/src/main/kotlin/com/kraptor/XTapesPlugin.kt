package com.kraptor

import com.lagradost.cloudstream3.plugins.CloudstreamPlugin
import com.lagradost.cloudstream3.plugins.Plugin

@CloudstreamPlugin
class XTapesPlugin: Plugin() {
    override fun load() {
        registerMainAPI(XTapes())
    }
}
