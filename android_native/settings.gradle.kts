pluginManagement {
    repositories {
        mavenCentral()
        gradlePluginPortal()
        google()
    }
}

dependencyResolutionManagement {
    repositories {
        mavenCentral()
        google()
    }
}

rootProject.name = "leamh-native"

// Pure-JVM DOCX engine (no android.* deps) — desktop unit-testable.
include(":docx")
// The Android reader: StaticLayout paginator, software-layer ReaderView,
// RattaEink EPD path. Depends on :docx.
include(":app")
