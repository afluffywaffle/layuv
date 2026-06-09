pluginManagement {
    repositories {
        mavenCentral()
        gradlePluginPortal()
        google()
        maven { url = uri("https://repo.boox.com/repository/maven-public/") }
    }
}

dependencyResolutionManagement {
    repositories {
        mavenCentral()
        google()
        maven { url = uri("https://repo.boox.com/repository/maven-public/") }
    }
}

rootProject.name = "leamh-native"

// Pure-JVM DOCX engine (no android.* deps) — desktop unit-testable.
include(":docx")
// The Android reader: StaticLayout paginator, software-layer ReaderView,
// Onyx EpdController. Depends on :docx.
include(":app")
