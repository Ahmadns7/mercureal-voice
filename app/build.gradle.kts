plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

android {
    namespace = "com.mercureal.voice"
    compileSdk = 35

    defaultConfig {
        applicationId = "com.mercureal.voice"
        minSdk = 26
        targetSdk = 35
        versionCode = 13
        versionName = "2.0.0"
    }

    buildTypes {
        release {
            isMinifyEnabled = false
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
        }
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
    // Framework views only, plus the Wearable message client for the Wear OS bridge (raw build fetches the same AARs, see scripts/build-raw.sh).
    implementation("com.google.android.gms:play-services-wearable:18.2.0")
}
