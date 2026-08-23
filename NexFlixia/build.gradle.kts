import org.jetbrains.kotlin.konan.properties.Properties

version = 5

android {
    defaultConfig {
        val properties = Properties()
        properties.load(project.rootProject.file("local.properties").inputStream())

        android.buildFeatures.buildConfig = true

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

//Bridge
dependencies {
    // 1. Isko implementation se hata kar compileOnly karein taaki IDE ko pata chale
    compileOnly(project(":StreamHubOne"))
}

// 2. Ye block add karein taaki StreamHubOne ka code NexFlixia ke andar bundle ho jaye
sourceSets {
    getByName("main") {
        java.srcDir(project(":StreamHubOne").file("src/main/kotlin"))
        // Agar java files bhi hain toh ye line bhi add karein
        // java.srcDir(project(":StreamHubOne").file("src/main/java"))
    }
}