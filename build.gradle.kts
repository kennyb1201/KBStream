plugins {
    // AGP 9.4 is the first line that supports API 37, which is what the
    // androidx 1.19 / Compose 1.12 generation of dependencies requires
    // (AGP 9.0 through 9.3 cap at API 36.1). It needs Gradle 9.6.0 or newer;
    // see gradle/wrapper/gradle-wrapper.properties.
    alias(libs.plugins.android.application) apply false
    // NOTE: there is deliberately NO org.jetbrains.kotlin.android (or
    // kotlin-android) plugin here any more. AGP 9 builds Kotlin in by
    // default, and its new DSL is incompatible with that plugin - applying
    // both fails the build with "Cannot add extension with name 'kotlin', as
    // there is an extension already registered with that name". The Kotlin
    // compiler plugins below (compose, serialization) are still needed and
    // still bring their own matching Kotlin Gradle plugin.
    alias(libs.plugins.kotlin.compose) apply false
    // KSP moved to its own versioning scheme (2.3.x) instead of the old
    // "<kotlin>-<ksp>" pairing; 2.3.x is the line that pairs with Kotlin 2.4.
    alias(libs.plugins.ksp) apply false
    alias(libs.plugins.kotlin.serialization) apply false
    // Uploads the R8/ProGuard mapping file to Sentry on release builds so
    // minified stack traces (MainActivity.i etc.) arrive deobfuscated.
    alias(libs.plugins.sentry.android.gradle) apply false
    // Kotlin lint, configured down to a single rule: see .editorconfig. It
    // exists to stop dead imports accumulating, not to re-style the tree.
    alias(libs.plugins.ktlint) apply false
    // Wires the baseline profile generator (:baselineprofile) to the app, so
    // `./gradlew :app:generateBaselineProfile` captures a profile from a
    // connected device and writes it into app/src/release/generated/
    // baselineProfiles/ for the release build to consume.
    alias(libs.plugins.baseline.profile) apply false
}
