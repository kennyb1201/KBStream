// Baseline Profile GENERATOR - not part of the app.
//
// A baseline profile is a list of the classes and methods the app touches during
// startup and first-frame work, which ART then compiles ahead of time instead of
// interpreting. On a TV that is the difference between a Home rail that appears
// and a Home rail that appears a second later; on this app it is worth having
// because 130k lines of Compose and a cold Coil/WorkManager start mean the first
// frames are mostly interpreted.
//
// This module is how the profile is PRODUCED: it targets :app, launches it on a
// connected device or emulator, and writes the profile out. Nothing here ships.
// See baselineprofile/src/main/java/.../BaselineProfileGenerator.kt for how to
// run it, and the profileinstaller dependency in app/build.gradle.kts for the
// half that installs the result on devices.
plugins {
    // No version and no catalog alias: com.android.test comes from the AGP
    // already on the classpath (declared in the root build), and requesting it
    // through an alias makes Gradle try to resolve it a second time with a
    // version, which fails with "already on the classpath with an unknown
    // version".
    id("com.android.test")
    // AGP 9's built-in Kotlin replaces org.jetbrains.kotlin.android here too;
    // see the root build file.
    alias(libs.plugins.baseline.profile)
}

android {
    namespace = "com.kennyb1201.kbstream.baselineprofile"
    compileSdk = 37

    defaultConfig {
        // The generator runs on the device the profile is captured from - a TV
        // box or an emulator, both far above this. It does NOT constrain the
        // app's own minSdk (26).
        minSdk = 28
        targetSdk = 35
        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    // No kotlinOptions block: AGP 9's built-in Kotlin takes the jvmTarget from
    // compileOptions.targetCompatibility (17 above); the DSL is gone in AGP 9.

    // The profile is captured from the app itself, not from a copy of its code.
    targetProjectPath = ":app"
    experimentalProperties["android.experimental.self-instrumenting"] = true
}

baselineProfile {
    // Generate against a CONNECTED device (or an emulator started by hand)
    // rather than a Gradle Managed Device: this is a TV app whose focus
    // behavior is part of what the profile should cover, and a managed phone
    // device would capture the wrong traversal. `./gradlew
    // :app:generateBaselineProfile` with a device attached is the intended path.
    useConnectedDevices = true
}

dependencies {
    implementation(libs.androidx.test.ext.junit)
    implementation(libs.uiautomator)
    implementation(libs.benchmark.macro.junit4)
}
