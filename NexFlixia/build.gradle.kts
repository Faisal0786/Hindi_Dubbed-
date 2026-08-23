import java.util.Properties

version = 6

android {
    defaultConfig {
        val properties = Properties()
        val localPropsFile = project.rootProject.file("local.properties")
        if (localPropsFile.exists()) {
            properties.load(localPropsFile.inputStream())
        }

        buildFeatures.buildConfig = true

        buildConfigField(
            "String",
            "SIMKL_API",
            "\"${properties.getProperty("SIMKL_API", "")}\""
        )

        buildConfigField(
            "String",
            "TMDB_KEY",
            "\"${properties.getProperty("TMDB_KEY", "")}\""
        )

        buildConfigField(
            "String",
            "CC_COOKIE",
            "\"${properties.getProperty("CC_COOKIE", "")}\""
        )
    }
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
