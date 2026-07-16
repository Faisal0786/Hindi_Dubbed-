import org.jetbrains.kotlin.konan.properties.Properties

version = 6

android {
    defaultConfig {

        val properties = Properties()
        properties.load(
            project.rootProject
                .file("local.properties")
                .inputStream()
        )

        buildFeatures.buildConfig = true

        buildConfigField(
            "String",
            "TMDB_KEY",
            "\"${properties.getProperty("TMDB_KEY")}\""
        )
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