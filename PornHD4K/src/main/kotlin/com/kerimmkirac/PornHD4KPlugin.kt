package com.kerimmkirac

import com.lagradost.cloudstream3.plugins.CloudstreamPlugin
import com.lagradost.cloudstream3.plugins.BasePlugin

@CloudstreamPlugin
class PornHD4KPlugin: BasePlugin() {
    override fun load() {
        registerMainAPI(PornHD4K())
    }
}
