pluginManagement {
    repositories {
        google {
            content {
                includeGroupByRegex("com\\.android.*")
                includeGroupByRegex("com\\.google.*")
                includeGroupByRegex("androidx.*")
            }
        }
        mavenCentral()
        gradlePluginPortal()
    }
}

dependencyResolutionManagement {
    repositoriesMode.set(RepositoriesMode.FAIL_ON_PROJECT_REPOS)
    repositories {
        google()
        mavenCentral()
    }
}

rootProject.name = "artiest"

// Two new modules, and the split between them is one a compiler can enforce. :engine is
// kotlin("jvm"), so an `android.graphics` import in it does not compile — which
// is what makes the stroke pipeline testable with `./gradlew :engine:test`, no
// device and no SDK. That matters more here than it would elsewhere: Android
// builds on this machine go through a hand-bootstrapped SDK, so a test loop
// that never touches it is the fast one. Everything else (:canvas, :io, :ui,
// :brush, :document) stays a package named after its future module, so a
// Phase 2/3 split is a `git mv`.
include(":engine")
include(":app")

// :spike is the Phase 0 harness and stays. It is the measured A/B control for
// the render path — the only instrument that can still settle the hardware
// questions — so it is frozen after W3 rather than deleted or migrated. It is
// deletable once W17 has recorded a baseline against it, and not before.
// It carries applicationId be.thalos.artiest.spike, distinct from :app's, so
// both APKs sit on the tablet at once.
include(":spike")
