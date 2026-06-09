// The Android reader for Supernote Nomad/Manta. Classic Views + a software-layer
// custom ReaderView driven by the Onyx EpdController — NOT Compose (its retained
// scene graph is the compositor trap this port exists to escape). Depends on the
// pure-JVM :docx engine for all DOCX parse / anchor / write-back.
//
// Repositories are NOT declared here: the settings.gradle.kts
// dependencyResolutionManagement block (mavenCentral + google + boox) supplies
// them. A module-level repositories{} block would, under Gradle's default
// PREFER_PROJECT mode, REPLACE those for this module and drop google()/boox.
plugins {
    id("com.android.application")
    kotlin("android")
}

android {
    namespace = "com.afluffywaffle.layuv"
    compileSdk = 36

    defaultConfig {
        // .dev suffix so the native port installs ALONGSIDE the Flutter APK on
        // the Supernote for side-by-side comparison. Switch to
        // "com.afluffywaffle.layuv" to replace the Flutter build instead.
        applicationId = "com.afluffywaffle.layuv.dev"
        minSdk = 30 // Nomad/Manta are Android 11.
        targetSdk = 36
        versionCode = 1
        versionName = "0.1.0"
    }

    buildTypes {
        release {
            isMinifyEnabled = false
            signingConfig = signingConfigs.getByName("debug")
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    // Fonts are plain assets (loaded via Typeface.createFromAsset), not res/font,
    // so don't let aapt deflate the .ttf files.
    androidResources {
        noCompress.add("ttf")
    }
}

kotlin {
    compilerOptions {
        jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_17)
    }
}

dependencies {
    implementation(project(":docx"))
    // Onyx EpdController waveforms (no Google Play Services anywhere in this app).
    implementation("com.onyx.android.sdk:onyxsdk-device:1.2.28")
}
