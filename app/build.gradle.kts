// Settings for the app itself.

plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

android {
    namespace = "com.example.jpsubs"
    compileSdk = 35

    defaultConfig {
        applicationId = "com.example.jpsubs"
        // Android 10 (API 29) is the first version that can capture playback audio.
        minSdk = 29
        targetSdk = 35
        versionCode = 1
        versionName = "0.1-milestone1"
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    kotlinOptions {
        jvmTarget = "17"
    }
}

dependencies {
    implementation("androidx.core:core-ktx:1.13.1")
    implementation("androidx.activity:activity-ktx:1.9.3")
}
