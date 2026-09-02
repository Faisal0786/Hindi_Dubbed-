package com.hindi

import android.content.Context
import com.lagradost.cloudstream3.MainActivity
import com.lagradost.cloudstream3.plugins.CloudstreamPlugin
import com.lagradost.cloudstream3.plugins.Plugin
import com.hindi.providers.init
import com.hindi.providers.Settings
import kotlinx.coroutines.runBlocking

@CloudstreamPlugin
class StreamHubOnePlugin : Plugin() {

    override fun load(context: Context) {

        runBlocking { init() }

        Settings.initSeenProviders()

        registerMainAPI(StreamHubOneProvider())
        registerMainAPI(Cwunchyroll())
        registerMainAPI(NexFlixiaProvider())

        this.openSettings = { ctx: Context ->
            Settings.showSettingsDialog(ctx) {
                MainActivity.reloadHomeEvent.invoke(true)
            }
        }
    }
}