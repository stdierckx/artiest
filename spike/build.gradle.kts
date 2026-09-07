plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.android)
    alias(libs.plugins.kotlin.compose)
}

android {
    namespace = "eu.torqa.artiest.spike"
    compileSdk = 35

    defaultConfig {
        applicationId = "eu.torqa.artiest.spike"
        // API 29 is the floor for front-buffered rendering, so it is the floor
        // for the whole project.
        minSdk = 29
        targetSdk = 34
        versionCode = 1
        versionName = "0.1-phase0"
    }

    buildTypes {
        debug {
            isDebuggable = true
        }
        release {
            // Measure the release build. A debuggable build carries enough
            // overhead to muddy a latency reading.
            isMinifyEnabled = false
            signingConfig = signingConfigs.getByName("debug")
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
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.lifecycle.runtime.ktx)
    implementation(libs.androidx.activity.compose)

    implementation(platform(libs.androidx.compose.bom))
    implementation(libs.androidx.ui)
    implementation(libs.androidx.ui.graphics)
    implementation(libs.androidx.material3)

    implementation(libs.androidx.graphics.core)
    implementation(libs.androidx.input.motionprediction)
}
