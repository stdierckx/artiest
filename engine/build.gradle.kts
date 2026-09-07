// The invariant this module exists for: NOTHING here may see android.*.
//
// It is enforced by the plugin, not by convention. kotlin("jvm") never puts
// android.jar on the compile classpath, and Gradle's variant matching rejects
// any Android producer (project or .aar) on the platform.type attribute before
// compilation is even reached. So `import android.graphics.Path` fails to
// compile, and so does a dependency on :app.
//
// Two things it does not stop, both of which compile silently. A raw file/jar
// dependency carrying android.* classes — files(".../android.jar"),
// org.robolectric:android-all, com.google.android:android — bypasses both
// checks. And the compile classpath here is the whole JDK, so java.awt.geom,
// javax.swing and java.beans build fine, pass :engine:test, get dexed, and
// then throw NoClassDefFoundError on the tablet. That second one is not
// hypothetical: java.awt.geom.Area and Path2D are exactly what a stroke engine
// reaches for at lasso/eraser time. Geometry in this module is hand-written
// float math. Keep the dependencies to the Kotlin stdlib and test frameworks.
//
// No `repositories { }` block: settings.gradle.kts sets FAIL_ON_PROJECT_REPOS.
plugins {
    alias(libs.plugins.kotlin.jvm)
}

// 17, matching :spike and :app, and pinned rather than inherited. Left to
// itself this module compiles to the JDK's own level — 21 on this machine —
// and AGP 8.7.3 packages the 21-bytecode result into the APK without a word.
// The skew stays invisible until something changes (a CI JDK, --release,
// core-library desugaring), so it is worth stating explicitly here.
//
// Configured directly, NOT via `kotlin { jvmToolchain(17) }`: only JDK 21 is
// installed and no toolchain download repository is configured, so a toolchain
// request fails the build outright.
java {
    sourceCompatibility = JavaVersion.VERSION_17
    targetCompatibility = JavaVersion.VERSION_17
}

kotlin {
    // Fully qualified deliberately. A top-level `import` above the `plugins`
    // block silently breaks the libs.plugins.* accessors, and the resulting
    // error names a Gradle internal (ToolchainManagement.jvm) rather than the
    // import.
    compilerOptions {
        jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_17)
    }
}

dependencies {
    // kotlin-test rather than JUnit 4: with useJUnitPlatform() below it resolves
    // to kotlin-test-junit5, and that whole stack (jupiter 5.10.1, platform
    // 1.10.1) is already in the Gradle cache on this machine while junit:junit
    // is not. One less coordinate to keep in sync with the Kotlin version, too.
    testImplementation(kotlin("test"))
}

// Paired with kotlin("test") above, not independent of it: the variant
// kotlin-test resolves to follows whatever test framework is configured. Drop
// this line and it picks kotlin-test-junit, which needs junit:junit — absent
// from this machine's Gradle cache, so the build dies at dependency
// resolution, for a reason with nothing to do with the code being changed.
tasks.test {
    useJUnitPlatform()
}
