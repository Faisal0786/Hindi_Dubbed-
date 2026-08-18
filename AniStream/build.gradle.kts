import org.jetbrains.kotlin.konan.properties.Properties

plugins {
    id("com.android.library")
    id("com.lagradost.cloudstream3.gradle")
}

version = 1

android {
    namespace = "com.anistream"
    compileSdk = 34

    defaultConfig {
        minSdk = 21

        val properties = Properties()
        val localPropertiesFile = project.rootProject.file("local.properties")
        if (localPropertiesFile.exists()) {
            properties.load(localPropertiesFile.inputStream())
        }

        android.buildFeatures.buildConfig = true

        buildConfigField(
            "String",
            "TMDB_KEY",
            "\"${properties.getProperty("TMDB_KEY", "")}\""
        )
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_1_8
        targetCompatibility = JavaVersion.VERSION_1_8
    }

    kotlinOptions {
        jvmTarget = "1.8"
        freeCompilerArgs = freeCompilerArgs + listOf(
            "-opt-in=kotlin.RequiresOptIn"
        )
    }
}

cloudstream {
    description = "Watch anime in HD with English Sub and Dub from AniStream"

    authors = listOf(
        "Faisal"
    )

    status = 1

    tvTypes = listOf(
        "Anime",
        "AnimeMovie"
    )

    iconUrl = "https://anistream.one/og.png"
}
