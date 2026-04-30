package com.kraptor

import com.lagradost.cloudstream3.plugins.CloudstreamPlugin
import com.lagradost.cloudstream3.plugins.Plugin
import android.content.Context

@CloudstreamPlugin
class RareAnimesPlugin : Plugin() {
    override fun main(context: Context) {
        registerMainAPI(RareAnimes())
    }
}