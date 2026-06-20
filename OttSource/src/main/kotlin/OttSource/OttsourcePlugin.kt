package OttSource

import android.content.Context
import com.lagradost.cloudstream3.plugins.CloudstreamPlugin
import com.lagradost.cloudstream3.plugins.Plugin

@CloudstreamPlugin
open class OttSourcePlugin : Plugin() {

    

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