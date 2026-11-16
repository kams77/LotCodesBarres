plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

android {
    namespace = "cd.codesbarres.lot"
    compileSdk = 34

    defaultConfig {
        applicationId = "cd.codesbarres.lot"
        minSdk = 26          // Android 8.0 et plus
        targetSdk = 34
        versionCode = 1
        versionName = "1.0"
    }

    buildTypes {
        release {
            // Code optimisé et ressources inutiles retirées : APK plus léger.
            // La signature se fait hors du dépôt public (voir README).
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro"
            )
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
        buildConfig = true
    }
}

dependencies {
    implementation("com.google.zxing:core:3.5.3")
    implementation("androidx.core:core:1.13.1")
}
