package com.kraptor

import com.lagradost.cloudstream3.LoadResponse
import com.lagradost.cloudstream3.MainApi
import com.lagradost.cloudstream3.MainPageRequest
import com.lagradost.cloudstream3.MainPageResponse
import com.lagradost.cloudstream3.SearchResponse
import com.lagradost.cloudstream3.TvType
import com.lagradost.cloudstream3.providers.BasePlugin

class WatchPornPlugin : BasePlugin() {
    override fun load() {
        registerMainAPI(WatchPorn())
    }
}
