import java.util.Properties

version = 1

android {
    defaultConfig {
        val properties = Properties()
        val localPropsFile = project.rootProject.file("local.properties")
        if (localPropsFile.exists()) {
            properties.load(localPropsFile.inputStream())
        }

        buildFeatures.buildConfig = false

        
    
}

cloudstream {
    language = "hi"

    description = "NexFlixia - Multilingual all-rounder provider"

    authors = listOf(
        "Faisal"
    )

    status = 1

    tvTypes = listOf(
        "Movie",
        "TvSeries",
        "Anime",
        "AnimeMovie",
        "AsianDrama"
    )

    iconUrl = ""
}

dependencies {
    implementation(project(":StreamHubOne"))
}