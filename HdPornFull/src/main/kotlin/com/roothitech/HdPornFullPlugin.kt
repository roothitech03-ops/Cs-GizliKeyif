package com.roothitech

import com.lagradost.cloudstream3.plugins.CloudstreamPlugin
import com.lagradost.cloudstream3.plugins.Plugin

@CloudstreamPlugin
class HdPornFullPlugin : Plugin() {
    override fun load() {
        registerMainAPI(HdPornFull())
    }
}
