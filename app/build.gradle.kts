plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.android)
    alias(libs.plugins.kotlin.compose)
}

android {
    // Distinct from :spike's be.thalos.artiest.spike on purpose. The two APKs
    // have to sit on the tablet at the same time: :spike is the measured A/B
    // control for the render path, and a control you have to uninstall to run
    // the candidate is not a control.
    namespace = "be.thalos.artiest"
    compileSdk = 35

    defaultConfig {
        applicationId = "be.thalos.artiest"
        // API 29 is the floor for front-buffered rendering, so it is the floor
        // for the whole project.
        minSdk = 29
        targetSdk = 34
        versionCode = 1
        versionName = "0.1-phase1"
    }

    buildTypes {
        debug {
            isDebuggable = true
        }
        release {
            // Same shape as :spike's, and for the same reason: latency has to
            // stay measurable on this build, and a debuggable one carries
            // enough overhead to muddy the reading. Debug-signed because the
            // comparison is against :spike, not against a store build.
            isMinifyEnabled = false
            signingConfig = signingConfigs.getByName("debug")
        }
    }

    // Matched to :spike exactly, including the DSL used to say it. :app and
    // :spike are two arms of the same measurement, so anything that could make
    // their bytecode differ has to be identical here.
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
    // The whole point of the module split. Everything decidable without a
    // device lives behind this line, and the compiler keeps it there.
    implementation(project(":engine"))

    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.lifecycle.runtime.ktx)
    implementation(libs.androidx.activity.compose)

    // The Compose plugin is applied above and buildFeatures.compose is on, so
    // the runtime is not optional: without it :app:compileDebugKotlin fails as
    // an "Internal compiler error" whose real cause
    // (IncompatibleComposeRuntimeVersionException) is only visible under --info.
    implementation(platform(libs.androidx.compose.bom))
    implementation(libs.androidx.ui)
    implementation(libs.androidx.ui.graphics)
    implementation(libs.androidx.material3)

    // W8's render path. Present now because it is the decided architecture and
    // :spike already resolves it, so the dependency graph is the same on both
    // arms of the comparison.
    implementation(libs.androidx.graphics.core)

    // libs.androidx.input.motionprediction is deliberately absent until W11.
    // The spike carries it, but prediction in :app is a gated experiment and
    // adding the dependency before there is a Predictor to hold it would put an
    // unreferenced library in the APK being measured.
}
