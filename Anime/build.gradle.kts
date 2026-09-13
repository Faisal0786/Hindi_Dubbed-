plugins {
    id("com.android.library")
    id("com.lagradost.cloudstream3.gradle")
}

// use an integer for version numbers
version = 1

android {
    // Dhyan rahe, Anime module ke liye yahan "com.anime" hoga aur AniStream ke liye "com.anistream"
    namespace = "com.anime" 
    compileSdk = 34

    defaultConfig {
        minSdk = 21
    }

    buildFeatures {
        buildConfig = true
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_11
        targetCompatibility = JavaVersion.VERSION_11
    }
    
    // 🔴 YAHAN SE kotlinOptions HATA DIYA GAYA HAI 🔴
}

dependencies {
    implementation("androidx.core:core:1.16.0")
    implementation("androidx.appcompat:appcompat:1.7.0")
    implementation("com.google.android.material:material:1.12.0")
}

cloudstream {
    language = "en"

    description = "Watch anime in HD"
    authors = listOf("Faisal")

    status = 1
    tvTypes = listOf(
        "Anime",
        "AnimeMovie"
    )

    requiresResources = false
    iconUrl = " "
}

// 🟢 FIX: KOTLIN JVM TARGET KO FILE KE SABSE NEECHE (BAHAR) AISE LIKHNA HAI 🟢
tasks.withType<org.jetbrains.kotlin.gradle.tasks.KotlinCompile>().configureEach {
    kotlinOptions {
        jvmTarget = "11"
    }
}
