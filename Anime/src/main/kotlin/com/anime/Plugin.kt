package com.anime

import android.content.Context
import com.lagradost.cloudstream3.plugins.CloudstreamPlugin
import com.lagradost.cloudstream3.plugins.Plugin

@CloudstreamPlugin
class KitsuProviderPlugin : Plugin() {
    override fun load(context: Context) {
        registerMainAPI(KitsuAnimeProvider())
registerMainAPI(JikanProvider())
registerMainAPI(TmdbProvider())
    }
}