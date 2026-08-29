package com.ctg

import com.lagradost.cloudstream3.plugins.CloudstreamPlugin
import com.lagradost.cloudstream3.plugins.Plugin
import android.content.Context

@CloudstreamPlugin
class CtgstreamPlugin : Plugin() {
    override fun load(context: Context) {
        // Register your provider here
        registerMainAPI(CtgStreamProvider())
    }
}