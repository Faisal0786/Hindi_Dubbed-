plugins {
    id("com.android.library")
    id("com.lagradost.cloudstream3.gradle")
}

// use an integer for version numbers
version = 1

android {
    namespace = "com.anistream"
    compileSdk = 34

    defaultConfig {
        minSdk = 21
    }

    buildFeatures {
        buildConfig = true
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_1_8
        targetCompatibility = JavaVersion.VERSION_1_8
    }
}

dependencies {
    implementation("androidx.core:core:1.16.0")
    implementation("androidx.appcompat:appcompat:1.7.0")
    implementation("com.google.android.material:material:1.12.0")
}

cloudstream {
    language = "en"

    description = "Watch anime in HD with English Sub and Dub from AniStream"
    authors = listOf("Faisal")

    /**
     * Status int:
     * 0: Down
     * 1: Ok
     * 2: Slow
     * 3: Beta only
     */
    status = 1
    tvTypes = listOf(
        "Anime",
        "AnimeMovie"
    )

    requiresResources = false
    iconUrl = "https://anistream.one/og.png"
}
