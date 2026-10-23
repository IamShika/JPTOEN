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
        versionCode = 3
        versionName = "0.3-milestone3"

        // Both your phones are 64-bit ARM, so we only ship that version of the
        // speech engine. It keeps the APK small.
        ndk {
            abiFilters.add("arm64-v8a")
        }

        // Where the app downloads the speech model from: the Releases page of
        // YOUR GitHub repo. On GitHub's build servers the repo name is read
        // automatically; the text after ?: is only a fallback.
        val repo = System.getenv("GITHUB_REPOSITORY") ?: "IamShika/JPTOEN"
        buildConfigField(
            "String",
            "MODEL_BASE_URL",
            "\"https://github.com/$repo/releases/download/model-v1/\""
        )
    }

    buildFeatures {
        buildConfig = true
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

    // Google's on-device translator (Milestone 3)
    implementation("com.google.mlkit:translate:17.0.3")
}
