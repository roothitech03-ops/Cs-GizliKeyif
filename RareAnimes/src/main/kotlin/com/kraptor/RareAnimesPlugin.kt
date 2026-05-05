package com.kraptor

import com.lagradost.cloudstream3.plugins.CloudstreamPlugin
import com.lagradost.cloudstream3.plugins.Plugin
import android.content.Context

/**
 * RareAnimes CloudStream Plugin entry-point.
 *
 * Registers the RareAnimes provider so the app can list,
 * search, and stream content from Rare Toons India.
 */
@CloudstreamPlugin
class RareAnimesPlugin : Plugin() {
    override fun main(context: Context) {
        registerMainAPI(RareAnimes())
    }
}
