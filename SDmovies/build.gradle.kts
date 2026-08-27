import org.jetbrains.kotlin.konan.properties.Properties

version = 4

android {

    defaultConfig {

        val properties = Properties()

        properties.load(
            project.rootProject
                .file("local.properties")
                .inputStream()
        )

        android.buildFeatures.buildConfig = true

        buildConfigField(
            "String",
            "TMDB_KEY",
            "\"${properties.getProperty("TMDB_KEY")}\""
        )
    }
}

cloudstream {

    description = "Multi language Movies and Tv shows provider"

    authors = listOf(
        "Faisal"
    )

    status = 1

    tvTypes = listOf(
        "TvSeries",
        "Movie",
        "AsianDrama",
        "Anime",
        "Torrent"
    )

    iconUrl = "https://sdmoviespoint.cyou/favicon.ico"
}
