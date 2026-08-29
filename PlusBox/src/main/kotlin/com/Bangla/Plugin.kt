package com.Bangla

import com.lagradost.cloudstream3.plugins.CloudstreamPlugin
import com.lagradost.cloudstream3.plugins.Plugin
import android.content.Context

@CloudstreamPlugin
class PlusboxPlugin : Plugin() {
    override fun load(context: Context) {
        // Register your provider here
        registerMainAPI(PlusboxProvider())
    }
}
