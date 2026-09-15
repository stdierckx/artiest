import javax.inject.Inject
import org.gradle.process.ExecOperations

plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.android)
    alias(libs.plugins.kotlin.compose)
}

// Filament's material compiler, as a build dependency rather than a checked-in
// binary. `contour.mat` is source and belongs in the repository; the `.filamat`
// it turns into is a build product and does not -- the same rule this module
// already applies to `catalogue.json`.
//
// matc is a native executable published per host platform, so the classifier is
// chosen from the machine doing the build. An unsupported host fails here, with
// its own name in the message, rather than three tasks later with a missing
// asset.
val matc: Configuration by configurations.creating

val matcHost: String = run {
    val os = System.getProperty("os.name").lowercase()
    val arch = System.getProperty("os.arch").lowercase()
    when {
        os.contains("linux") -> "linux-x86_64"
        os.contains("mac") || os.contains("darwin") ->
            if (arch.contains("aarch64") || arch.contains("arm")) "osx-aarch_64" else "osx-x86_64"
        os.contains("windows") -> "windows-x86_64"
        else -> throw GradleException("no Filament matc build for $os/$arch")
    }
}

/**
 * Compiles every `.mat` in `src/main/materials` into `assets/materials`.
 *
 * `-p mobile` and `-a opengl`, because that is the only backend this app ever
 * asks Filament for and the other shader families are dead weight in the APK.
 *
 * A real task class and not a `doLast` on an anonymous one, because only a
 * typed task with a `DirectoryProperty` output can be handed to AGP's
 * `addGeneratedSourceDirectory` — see the wiring below for why that matters.
 */
abstract class CompileMaterials : DefaultTask() {
    @get:InputFiles
    @get:PathSensitive(PathSensitivity.RELATIVE)
    abstract val sources: ConfigurableFileCollection

    @get:InputFiles
    abstract val tool: ConfigurableFileCollection

    @get:OutputDirectory
    abstract val into: DirectoryProperty

    @get:Inject
    abstract val exec: ExecOperations

    @TaskAction
    fun compile() {
        val binary = tool.singleFile
        binary.setExecutable(true)
        val out = into.get().dir("materials").asFile
        out.mkdirs()
        for (source in sources.files) {
            val made = File(out, source.name.removeSuffix(".mat") + ".filamat")
            exec.exec {
                commandLine(
                    binary.absolutePath,
                    "-p", "mobile",
                    "-a", "opengl",
                    "-o", made.absolutePath,
                    source.absolutePath,
                )
            }.assertNormalExitValue()
        }
    }
}

val compileMaterials = tasks.register<CompileMaterials>("compileMaterials") {
    group = "build"
    description = "Compiles Filament .mat sources into .filamat."
    sources.from(fileTree("src/main/materials") { include("**/*.mat") })
    tool.from(matc)
    // `into` is deliberately not set here: the wiring below is what points it
    // at the directory AGP will merge, and a value set here would be quietly
    // replaced by that one.
}

/**
 * Put the compiled materials in the APK, and put the task in the graph.
 *
 * `assets.srcDir(theTask)` looks like it does this and does not: it adds the
 * directory, so a build on a machine where the task has run once produces a
 * working APK and a build on a clean checkout produces one whose 3D reference
 * has no shaders in it and silently loses the stone and the contour lines. It
 * is worse than that on an edit: a changed `.mat` and `assembleRelease` gives
 * an APK with yesterday's shader in it, which is a debugging session spent
 * looking at the wrong file.
 *
 * `addGeneratedSourceDirectory` is the AGP 8 way and is the one that both
 * registers the directory and makes every variant's asset merge depend on the
 * task that fills it.
 */
androidComponents {
    onVariants { variant ->
        variant.sources.assets?.addGeneratedSourceDirectory(
            compileMaterials,
            CompileMaterials::into,
        )
    }
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

        // Filament ships four ABIs and each one is about six megabytes of
        // native code. This tablet is arm64, every Android tablet anybody
        // draws on has been arm64 since 2019, and shipping the other three
        // would quadruple the cost of the 3D reference for nobody. It is a
        // filter on what is packaged, not a toolchain requirement: the NDK is
        // still not needed to build this module.
        ndk { abiFilters += "arm64-v8a" }
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

    testOptions {
        // The starter brushes ride in `assets/`, and `StarterBrushesTest` is
        // the only thing that checks the shipped files parse. Without this,
        // Robolectric sees no assets at all — an asset listing comes back empty
        // and every assertion about the set passes by describing nothing, which
        // is how the test first went green with sixteen files it never opened.
        unitTests.isIncludeAndroidResources = true
    }
}

dependencies {
    matc("com.google.android.filament:matc:${libs.versions.filament.get()}:$matcHost@exe")

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

    // Lr12. The 3D reference. Apache-2.0, which is why it can be a dependency
    // at all -- the alternatives a drawing app would reach for are GPL, and
    // this repository's rule for those is read-and-reimplement. Two AARs,
    // arm64 only: 3.0 MB of renderer and 3.1 MB of glTF loader.
    implementation(libs.filament.android)
    implementation(libs.filament.gltfio)

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
    // `-Dartiest.*` reaches the test JVM. Gradle forks its tests, so a property
    // set on the command line is otherwise invisible to them -- which matters
    // for the hand-run tools that live in the test source set because that is
    // where Robolectric's real graphics are. See `KritaSheetTool`.
    for ((key, value) in providers.systemPropertiesPrefixedBy("artiest.").get()) {
        systemProperty(key, value)
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
