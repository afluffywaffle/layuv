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

// ─── generators source set ────────────────────────────────────────────────────
// Generator code lives here so it never ends up in the production JAR (and thus
// the APK).  It can see all of main's public API but NOT internal symbols like
// JsonWriter / Json (which are only needed in main anyway).

val generators by sourceSets.creating {
    compileClasspath += sourceSets.main.get().output + sourceSets.main.get().compileClasspath
    runtimeClasspath += sourceSets.main.get().output + sourceSets.main.get().runtimeClasspath
}

// org.json is compileOnly in main; generators need it at runtime (DocxStore.load)
// would use it, but the generator avoids that path.  We still add it here so that
// any indirect pull from the engine (e.g. LegacyComments) resolves at runtime.
configurations[generators.runtimeClasspathConfigurationName].apply {
    extendsFrom(configurations["compileOnly"])
}

tasks.register<JavaExec>("generateGoldens") {
    group = "verification"
    description = "Regenerate all docx golden test fixtures. Run from the repo root."
    classpath = generators.runtimeClasspath
    mainClass.set("com.afluffywaffle.layuv.docx.GenerateGoldensKt")
    workingDir = rootProject.projectDir
    dependsOn(tasks.named("compileGeneratorsKotlin"))
}
