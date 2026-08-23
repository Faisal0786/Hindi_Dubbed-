import org.jetbrains.kotlin.konan.properties.Properties

version = 5

android {
    defaultConfig {
        val properties = Properties()
        properties.load(project.rootProject.file("local.properties").inputStream())

        buildFeatures.buildConfig = true // "android." yahan zaroori nahi hai andar

        buildConfigField(
            "String",
            "SIMKL_API",
            "\"${properties.getProperty("SIMKL_API")}\""
        )

        buildConfigField(
            "String",
            "TMDB_KEY",
            "\"${properties.getProperty("TMDB_KEY")}\""
        )

        buildConfigField(
            "String",
            "CC_COOKIE",
            "\"${properties.getProperty("CC_COOKIE")}\""
        )
    }

    // Bridge: Yahan SourceSets merge kar diya gaya hai
    sourceSets {
        getByName("main") {
            java.srcDir(project(":StreamHubOne").file("src/main/kotlin"))
        }
    }
}

cloudstream {
    language = "hi"

    description =
        "NexFlixia - Multilingual all-rounder provider"

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
    compileOnly(project(":StreamHubOne"))
}
