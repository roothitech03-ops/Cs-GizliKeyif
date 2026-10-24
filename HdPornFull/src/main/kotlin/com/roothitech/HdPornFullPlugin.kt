package com.roothitech

import com.lagradost.cloudstream3.plugins.CloudstreamPlugin
import com.lagradost.cloudstream3.plugins.Plugin
import android.content.Context
import androidx.appcompat.app.AppCompatActivity
import com.lagradost.api.Log

@CloudstreamPlugin
class HdPornFullPlugin : Plugin() {
    private var activity: AppCompatActivity? = null

    override fun load(context: Context) {
        activity = context as? AppCompatActivity ?: run {
            Log.e("HdPornFullPlugin", "Context is not an AppCompatActivity")
            return
        }
        registerMainAPI(HdPornFull(activity!!))
    }
}
