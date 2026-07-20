import org.jetbrains.kotlin.konan.properties.Properties

version = 17

android {
    defaultConfig {
        val properties = Properties()
        properties.load(project.rootProject.file("local.properties").inputStream())
        android.buildFeatures.buildConfig=true
        buildConfigField("String", "SIMKL_API", "\"${properties.getProperty("SIMKL_API")}\"")
        buildConfigField("String", "TMDB_KEY", "\"${properties.getProperty("TMDB_KEY")}\"")
        buildConfigField("String", "CC_COOKIE", "\"${properties.getProperty("CC_COOKIE")}\"")
        buildConfigField("String", "CASTLE_KEY", "\"${properties.getProperty("CASTLE_KEY")}\"")
        buildConfigField("String", "MOVIEBLAST_TOKEN", "\"${properties.getProperty("MOVIEBLAST_TOKEN")}\"")
        buildConfigField("String", "MOVIEBLAST_API", "\"${properties.getProperty("MOVIEBLAST_API")}\"")
        buildConfigField("String", "MOVIEBLAST_KEY", "\"${properties.getProperty("MOVIEBLAST_KEY")}\"")
    }
}

cloudstream {
    language = "hi"

    description =
        "StreamHub One - Multilingual provider"

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