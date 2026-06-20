package OttSource

import android.content.Context
import com.lagradost.cloudstream3.plugins.CloudstreamPlugin
import com.lagradost.cloudstream3.plugins.Plugin

@CloudstreamPlugin
open class OttSourcePlugin : Plugin() {

    override fun load(context: Context) {

        // Initialize storage
        NetflixMirrorStorage.init(context.applicationContext)

        // Pass context to providers
       
        NetflixMirrorProvider.context = context
        PrimeVideoMirrorProvider.context = context
        HotStarMirrorProvider.context = context

        // Main providers
        registerMainAPI(NetflixMirrorProvider())
        registerMainAPI(PrimeVideoMirrorProvider())
        registerMainAPI(HotStarMirrorProvider())

        // Disney studio providers
        registerMainAPI(DisneyStudioProvider("disney", "Disney"))
        registerMainAPI(DisneyStudioProvider("marvel", "Marvel"))
        registerMainAPI(DisneyStudioProvider("starwars", "Star Wars"))
        registerMainAPI(DisneyStudioProvider("pixar", "Pixar"))
    }
}