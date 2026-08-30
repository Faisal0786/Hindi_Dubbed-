import org.jetbrains.kotlin.konan.properties.Properties

version = 2

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
    description = "TheMoviesFlix provider for Dual Audio, Bollywood and Hollywood Movies and TV Shows"
    authors = listOf("Faisal")
    status = 1
    tvTypes = listOf(
        "TvSeries",
        "Movie",
        "Anime"
    )
    iconUrl = "https://moviesflixi.com/favicon.ico"
}
