package com.hindi

import com.lagradost.cloudstream3.plugins.CloudstreamPlugin
import com.lagradost.cloudstream3.plugins.Plugin

@CloudstreamPlugin
class StreamHubOnePlugin : Plugin() {
    override fun load() {
        registerMainAPI(StreamHubOneProvider())
    }
}