plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

android {
    namespace = "com.natan.nexa"
    compileSdk = 35

    defaultConfig {
        applicationId = "com.natan.nexa"
        minSdk = 26          // Android 8.0
        targetSdk = 35
        versionCode = 2
        versionName = "2.0.0"
    }

    signingConfigs {
        // Kunci rilis opsional dari GitHub Secrets (lihat .github/workflows/build-apk.yml)
        create("release") {
            val ks = System.getenv("NEXA_KEYSTORE_FILE")
            if (ks != null && file(ks).exists()) {
                storeFile = file(ks)
                storePassword = System.getenv("NEXA_KEYSTORE_PASSWORD")
                keyAlias = System.getenv("NEXA_KEY_ALIAS")
                keyPassword = System.getenv("NEXA_KEY_PASSWORD")
            }
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = false
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"))
            val ks = System.getenv("NEXA_KEYSTORE_FILE")
            signingConfig = if (ks != null && file(ks).exists()) signingConfigs.getByName("release")
            else signingConfigs.getByName("debug")
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions {
        jvmTarget = "17"
    }
    lint {
        abortOnError = false
        checkReleaseBuilds = false
    }
}

dependencies {
    implementation("androidx.core:core-ktx:1.13.1")
    implementation("androidx.appcompat:appcompat:1.7.0")
    implementation("androidx.activity:activity-ktx:1.9.3")
    implementation("androidx.webkit:webkit:1.12.1")
    implementation("com.google.android.material:material:1.12.0")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.9.0")
    implementation("com.squareup.okhttp3:okhttp:4.12.0")   // WebSocket: Gemini Live & TikTok bridge
}
