pluginManagement {
    repositories {
        google()
        mavenCentral()
        gradlePluginPortal()
    }
}
dependencyResolutionManagement {
    repositories {
        google()
        mavenCentral()
        maven { url = uri("https://jitpack.io") }
    }
}
rootProject.name = "kbstream"
include(":app")
// The baseline profile generator: a test-only module that targets :app, runs it
// on a connected device and writes the profile the release build consumes. It
// ships nothing (see baselineprofile/build.gradle.kts).
include(":baselineprofile")
