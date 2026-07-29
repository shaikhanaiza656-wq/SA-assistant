plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.android)
    alias(libs.plugins.kotlin.compose)
    alias(libs.plugins.kotlin.serialization)
    alias(libs.plugins.ksp)
    alias(libs.plugins.hilt)
}

android {
    namespace = "com.sa.assistant"
    compileSdk = 35

    defaultConfig {
        applicationId = "com.sa.assistant"
        minSdk = 26
        targetSdk = 35
        versionCode = 5
        versionName = "0.6.3-phase6-part3-wakeword-edgetts"

        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
    }

    buildTypes {
        debug {
            isMinifyEnabled = false
            applicationIdSuffix = ".debug"
        }
        release {
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
        compose = true
    }

    packaging {
        resources {
            excludes += "/META-INF/{AL2.0,LGPL2.1}"
        }
    }
}

dependencies {
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.lifecycle.runtime.ktx)
    implementation(libs.androidx.lifecycle.viewmodel.compose)
    implementation(libs.androidx.lifecycle.runtime.compose)
    implementation(libs.androidx.activity.compose)

    implementation(platform(libs.androidx.compose.bom))
    implementation(libs.androidx.ui)
    implementation(libs.androidx.ui.graphics)
    implementation(libs.androidx.ui.tooling.preview)
    implementation(libs.androidx.material3)
    implementation(libs.androidx.material.icons.extended)
    debugImplementation(libs.androidx.ui.tooling)

    implementation(libs.androidx.navigation.compose)
    implementation(libs.androidx.datastore.preferences)

    implementation(libs.androidx.room.runtime)
    implementation(libs.androidx.room.ktx)
    ksp(libs.androidx.room.compiler)

    implementation(libs.hilt.android)
    implementation(libs.hilt.navigation.compose)
    ksp(libs.hilt.compiler)

    implementation(libs.kotlinx.coroutines.android)
    implementation(libs.kotlinx.serialization.json)
    implementation(libs.accompanist.permissions)

    // Phase 3 Part 1: PDF Studio — real EXIF orientation reading for
    // scanned photos, and real thumbnail decoding/caching for the
    // pending-images tray and saved-PDF list.
    implementation(libs.androidx.exifinterface)
    implementation(libs.coil.compose)

    // Phase 6 Part 3: real always-on wake-word spotting. Porcupine is
    // Picovoice's actual published on-device engine (not a stub) — it needs
    // the user's own free AccessKey plus a custom "SA" keyword file trained
    // in Picovoice Console (see WakeWordPreferences kdoc / README). Until
    // those are supplied, WakeWordListener honestly falls back to the
    // existing SpeechRecognizer-loop approach instead of pretending to spot.
    implementation(libs.porcupine.android)

    // Real WebSocket client used to talk to Microsoft Edge's neural TTS
    // endpoint (Edge TTS). No bundled audio, no fake network calls.
    implementation(libs.okhttp)

    testImplementation(libs.junit)
    androidTestImplementation(libs.androidx.test.ext.junit)
    androidTestImplementation(libs.androidx.espresso.core)
    androidTestImplementation(platform(libs.androidx.compose.bom))
}
