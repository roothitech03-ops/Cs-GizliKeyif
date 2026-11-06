package com.roothitech

import com.lagradost.cloudstream3.plugins.CloudstreamPlugin
import com.lagradost.cloudstream3.plugins.BasePlugin

@CloudstreamPlugin
class HdPornFullPlugin : BasePlugin() {
    override fun load() {
        registerMainAPI(HdPornFull())
    }
}