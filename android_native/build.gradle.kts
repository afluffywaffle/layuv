// Root build for the native Android port of Léamh (Supernote e-ink).
// The :docx module is pure Kotlin/JVM; the :app module is the Android reader.
plugins {
    kotlin("jvm") version "2.1.0" apply false
    kotlin("android") version "2.1.0" apply false
    // AGP 8.13.x is the latest stable line and is compatible with the Gradle
    // 9.1 wrapper pinned in gradle/wrapper.
    id("com.android.application") version "8.13.2" apply false
}
