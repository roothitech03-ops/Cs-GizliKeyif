package com.kraptor

import com.lagradost.cloudstream3.BasePlugin

class WatchPornPlugin : BasePlugin() {
    override fun load() {
        registerMainAPI(WatchPorn())
    }
}
