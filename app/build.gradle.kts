plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
    id("org.jetbrains.kotlin.plugin.compose")
}

// Diisi otomatis oleh GitHub Actions
val ciVersionCode = System.getenv("VERSION_CODE")?.toIntOrNull() ?: 1
val ciVersionName = System.getenv("VERSION_NAME") ?: "1.0.0-dev"
// Kunci tanda tangan tetap (signing/release.jks) supaya update APK bisa langsung
// di-install menimpa versi lama. Bisa diganti lewat env/secrets SIGNING_*.
val keystorePath: String = System.getenv("SIGNING_KEYSTORE_PATH")
    ?: rootProject.file("signing/release.jks").path

android {
    namespace = "id.twsanalyzer"
    compileSdk = 35

    defaultConfig {
        applicationId = "id.twsanalyzer"
        minSdk = 29 // Android 10: minimal untuk AudioPlaybackCapture
        targetSdk = 35
        versionCode = ciVersionCode
        versionName = ciVersionName
    }

    signingConfigs {
        create("release") {
            storeFile = file(keystorePath)
            storePassword = System.getenv("SIGNING_STORE_PASSWORD") ?: "twsanalyzer"
            keyAlias = System.getenv("SIGNING_KEY_ALIAS") ?: "twsanalyzer"
            keyPassword = System.getenv("SIGNING_KEY_PASSWORD") ?: "twsanalyzer"
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro"
            )
            signingConfig = signingConfigs.getByName("release")
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions {
        jvmTarget = "17"
    }
    buildFeatures {
        compose = true
    }
}

dependencies {
    val composeBom = platform("androidx.compose:compose-bom:2024.12.01")
    implementation(composeBom)

    implementation("androidx.core:core-ktx:1.15.0")
    implementation("androidx.activity:activity-compose:1.9.3")
    implementation("androidx.lifecycle:lifecycle-runtime-ktx:2.8.7")
    implementation("androidx.compose.ui:ui")
    implementation("androidx.compose.ui:ui-graphics")
    implementation("androidx.compose.foundation:foundation")
    implementation("androidx.compose.material3:material3")
    implementation("androidx.compose.material:material-icons-core")
}
