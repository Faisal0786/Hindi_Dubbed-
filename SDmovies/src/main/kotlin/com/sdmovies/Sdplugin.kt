package com.sdmovies

import android.content.context
import com.lagradost.cloudstream3.plugins.CloudstreamPlugin
import com.lagradost.cloudstream3.plugins.Plugin

@CloudstreamPlugin
class SDmoviesPlugin : Plugin() {
    override fun load()(context: Context) {
        registerMainAPI(SDMoviesProvider())
        
    }
}