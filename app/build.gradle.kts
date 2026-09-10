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

    // See the note at the end of this block.
    implementation(libs.androidx.input.motionprediction)

    // Plain JVM unit tests, matching :engine's choice of framework so a test
    // moving across the boundary keeps its imports. This is the only place the
    // mirrored ToolType constants can be checked against the real
    // MotionEvent.TOOL_TYPE_* values: :engine cannot see them by construction,
    // which is the whole point of the module, and a mirror that drifts
    // compiles, passes every test there, and misroutes on the tablet.
    testImplementation(kotlin("test"))

    // Robolectric, for the one thing the mirror test cannot reach: a real
    // MotionEvent. `obtain` with pointer properties, `addBatch` history,
    // `findPointerIndex` and the API-34 nanosecond accessor all work here, so
    // collectSamples and the router shell are testable on the JVM after all —
    // and the router's worst failure, a palm landing mid-stroke, is not
    // something anyone reproduces reliably by hand on a tablet.
    //
    // Two costs, stated rather than discovered later. It is a JUnit 4 runner,
    // so junit:junit and the vintage engine come with it and the platform has
    // to be told to run both engines. And it downloads a ~150 MB instrumented
    // Android runtime into ~/.m2, outside Gradle's cache, on first use: --offline
    // does not cover it and a clean machine or CI needs network once.
    //
    // What it cannot do is worth knowing too: it no-ops requestUnbufferedDispatch,
    // it does not dispatch real ACTION_HOVER_*, and it fabricates eventTimeNanos
    // as milliseconds x 1e6. Anything about batching, hover delivery or
    // sub-millisecond timing has to come off the tablet.
    testImplementation(libs.robolectric)
    testImplementation(libs.junit4)
    testRuntimeOnly(libs.junit.vintage.engine)

    // W11's Predictor. Held back until there was something to hold it, so the
    // APK W1 and W9 measured carried no unreferenced library; now there is a
    // Predictor and the dependency comes with it. Prediction itself still
    // defaults off — see Predictor's header for why that is a shipping position
    // and not a placeholder.
}

// :app is an Android module, so its unit test tasks are testDebugUnitTest and
// testReleaseUnitTest rather than `test` — use the debug one; `:app:test` runs
// both variants and pays for everything twice. They are still Test tasks, so
// they need the same useJUnitPlatform() :engine needs, for the same reason:
// which variant kotlin("test") resolves to follows the configured framework.
//
// testOptions.unitTests.isReturnDefaultValues is deliberately NOT set. AGP's
// default makes an unmocked android.* method throw "not mocked"; turning it on
// makes MotionEvent.obtain() return null instead, and because Java factories
// are Kotlin platform types that null is assigned without an NPE — a test that
// goes green while exercising nothing.
tasks.withType<Test>().configureEach {
    // Both engines: kotlin("test") tests are Jupiter, Robolectric's runner is
    // JUnit 4 and reaches the platform through vintage. Naming them explicitly
    // rather than relying on discovery, so a missing engine fails loudly
    // instead of silently running half the suite.
    useJUnitPlatform {
        includeEngines("junit-jupiter", "junit-vintage")
    }
}

// Writes the two generated documents into docs/.
//
// catalogue.json is the published vocabulary — every tool, group, kind, fill
// order and anchor — as a file that can be read without building the app, which
// is half of what makes "hand it to a model and ask for a workspace" a real
// sentence. workspace-example.json is one real workspace written by the real
// encoder, which docs/workspace-format.md quotes: a worked example a person
// copies from has to be one the app would actually produce.
//
// It runs on the *unit test* classpath, because that is the one classpath in
// this module that is a plain JVM, and the entry point lives in the test source
// set so that a main() which writes a file to a path off the command line never
// reaches the APK.
//
// It deliberately does not depend on the test task. ToolCatalogueTest fails
// when the checked-in file is stale, which is the whole point of it — and a
// generator that could only run once its own staleness check was already
// passing would be a generator nobody could use.
tasks.register<JavaExec>("catalogueJson") {
    group = "documentation"
    description = "Writes the generated docs and the shipped workspace assets."
    dependsOn("compileDebugUnitTestKotlin")
    val unitTest = tasks.named<Test>("testDebugUnitTest")
    classpath = files(provider { unitTest.get().classpath })
    mainClass.set("be.thalos.artiest.ui.CatalogueMainKt")
    argumentProviders.add(
        CommandLineArgumentProvider {
            listOf(
                rootProject.file("docs").absolutePath,
                file("src/main/assets/workspaces").absolutePath,
            )
        }
    )
}
