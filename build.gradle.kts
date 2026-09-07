plugins {
    alias(libs.plugins.android.application) apply false
    alias(libs.plugins.kotlin.android) apply false
    alias(libs.plugins.kotlin.compose) apply false
    // Not optional, and not merely tidy. `kotlin.android` and `kotlin.jvm` ship
    // in the same artifact, so declaring the Android one here already puts the
    // Kotlin plugin on the root buildscript classpath at an unknown version. A
    // versioned `alias(libs.plugins.kotlin.jvm)` in :engine is then rejected
    // ("already on the classpath ... compatibility cannot be checked"), and the
    // error points at engine/build.gradle.kts rather than at this file.
    // Declaring it here pins the version so the subproject request agrees.
    alias(libs.plugins.kotlin.jvm) apply false
}
