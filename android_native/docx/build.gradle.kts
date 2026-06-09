// Pure Kotlin/JVM DOCX engine — ZERO android.* imports so the whole
// compatibility contract (plain-text byte-identity, anchoring, write-back)
// is JUnit-testable on the desktop with no emulator.
plugins {
    kotlin("jvm")
}

repositories {
    mavenCentral()
}

dependencies {
    // org.json is part of the Android platform, so compile against it but don't
    // bundle it; the JVM test runtime gets a real impl via testImplementation.
    compileOnly("org.json:json:20240303")

    testImplementation(platform("org.junit:junit-bom:5.10.2"))
    testImplementation("org.junit.jupiter:junit-jupiter")
    testImplementation("org.json:json:20240303")
    // Gradle 9 no longer puts the JUnit Platform launcher on the test runtime
    // classpath automatically; declare it so the test executor can start.
    testRuntimeOnly("org.junit.platform:junit-platform-launcher")
}

// Pin the Java compile target to match Kotlin's JVM target. Without this, the
// `compileJava` task inherits the running JDK's version (21/26 here) while
// Kotlin targets 17, and Gradle aborts with an inconsistent-JVM-target error.
java {
    sourceCompatibility = JavaVersion.VERSION_17
    targetCompatibility = JavaVersion.VERSION_17
}

kotlin {
    compilerOptions {
        jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_17)
    }
}

tasks.test {
    useJUnitPlatform()
    testLogging { events("passed", "failed", "skipped") }
}
