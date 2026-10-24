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
        // Android only lets an update install over an older version if this number is higher.
        // GitHub numbers every build 1, 2, 3 ... so we use that number (plus 100, which is
        // higher than the versions you installed before).
        versionCode = 100 + (System.getenv("GITHUB_RUN_NUMBER")?.toIntOrNull() ?: 1)
        versionName = "0.9.0"

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

    // SIGNING. Android refuses to install an update unless it is signed with the same key
    // as the version already on the phone. GitHub's build servers normally invent a new
    // random key every time, which is why updates said "package conflicts with an existing
    // package". Here we always sign with the same key file (app/debug.keystore).
    // This is a personal development key: fine for your own phones and for testing. If the
    // app is ever published for other people, use a private key kept in GitHub Secrets instead.
    signingConfigs {
        getByName("debug") {
            storeFile = file("debug.keystore")
            storePassword = "android"
            keyAlias = "androiddebugkey"
            keyPassword = "android"
        }
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
