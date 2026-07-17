package com.hindi

import android.content.Context
import com.lagradost.cloudstream3.plugins.CloudstreamPlugin
import com.lagradost.cloudstream3.plugins.Plugin

@CloudstreamPlugin
class StreamHubOnePlugin : Plugin() {

    override fun load(context: Context) {

        AnimeCacheStorage.init(context.applicationContext)

        registerMainAPI(StreamHubOneProvider())
        registerMainAPI(Cwunchyroll())
    }
}