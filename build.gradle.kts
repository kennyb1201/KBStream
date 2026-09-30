plugins {
    id("com.android.application") version "8.6.0" apply false
    id("org.jetbrains.kotlin.android") version "2.0.21" apply false
    id("org.jetbrains.kotlin.plugin.compose") version "2.0.21" apply false
    id("com.google.devtools.ksp") version "2.0.21-1.0.25" apply false
    id("org.jetbrains.kotlin.plugin.serialization") version "2.0.21" apply false
    // Uploads the R8/ProGuard mapping file to Sentry on release builds so
    // minified stack traces (MainActivity.i etc.) arrive deobfuscated.
    id("io.sentry.android.gradle") version "6.23.0" apply false
    // Kotlin lint, configured down to a single rule: see .editorconfig. It
    // exists to stop dead imports accumulating, not to re-style the tree.
    id("org.jlleitschuh.gradle.ktlint") version "12.1.1" apply false
    // Wires the baseline profile generator (:baselineprofile) to the app, so
    // `./gradlew :app:generateBaselineProfile` captures a profile from a
    // connected device and writes it into app/src/release/generated/
    // baselineProfiles/ for the release build to consume.
    id("androidx.baselineprofile") version "1.3.4" apply false
}
