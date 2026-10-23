plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

android {
    namespace = "com.mercureal.voice.wear"
    compileSdk = 35

    defaultConfig {
        // Wear OS app. Same versionName as the phone app so the pair stays in step.
        applicationId = "com.mercureal.voice.wear"
        minSdk = 30
        targetSdk = 35
        versionCode = 13
        versionName = "2.0.0"
    }

    buildTypes {
        release {
            isMinifyEnabled = false
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
    // Wearable message client: sends the recognized query to the phone and receives the spoken reply.
    implementation("com.google.android.gms:play-services-wearable:18.2.0")
}
