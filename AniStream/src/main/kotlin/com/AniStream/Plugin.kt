package com.anistream

import android.content.Context
import com.lagradost.cloudstream3.plugins.CloudstreamPlugin
import com.lagradost.cloudstream3.plugins.Plugin

@CloudstreamPlugin
class AniStreamPlugin : Plugin() {
    override fun load(context: Context) {
        registerMainAPI(AniStreamProvider())
    }
}
