package com.sdmovies

import android.content.Context
import com.lagradost.cloudstream3.plugins.CloudstreamPlugin
import com.lagradost.cloudstream3.plugins.Plugin

@CloudstreamPlugin
class Sdplugin : Plugin() {

    override fun load(context: Context) {
        registerMainAPI(SDMoviesProvider())
    }
}