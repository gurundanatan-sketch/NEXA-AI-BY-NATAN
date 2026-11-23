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
        // Nomor versi naik otomatis di setiap build GitHub Actions →
        // APK baru selalu bisa menimpa versi lama (dengan tanda tangan yang sama).
        val run = System.getenv("GITHUB_RUN_NUMBER")?.toIntOrNull() ?: 0
        versionCode = 100 + run
        versionName = "6.0.$run"

        // Alamat server NEXA dari GitHub Secrets (tools/setup_server.sh).
        fun secret(name: String) = "\"" + (System.getenv(name) ?: "").trim()
            .replace("\\", "\\\\").replace("\"", "\\\"") + "\""
        // Hanya alamat server & token aplikasi yang masuk APK — kunci API tetap di server.
        buildConfigField("String", "SERVER_URL", secret("NEXA_SERVER_URL"))
        buildConfigField("String", "APP_TOKEN", secret("NEXA_APP_TOKEN"))
    }

    buildFeatures {
        buildConfig = true
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
            // Tanda tangan pribadi dari GitHub Secrets. Tanpa itu dipakai kunci debug
            // (APK tidak bisa menimpa versi bertanda tangan pribadi).
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
    implementation("com.squareup.okhttp3:okhttp:4.12.0")   // WebSocket: Gemini Live & TikTok
    // Perakit video di HP (pengganti FFmpeg)
    implementation("androidx.media3:media3-transformer:1.5.1")
    implementation("androidx.media3:media3-effect:1.5.1")
    implementation("androidx.media3:media3-common:1.5.1")
    implementation("com.google.guava:guava:33.3.1-android")
}
