plugins {
    id("com.android.application") version "8.6.0" apply false
    id("org.jetbrains.kotlin.android") version "2.0.21" apply false
    id("org.jetbrains.kotlin.plugin.compose") version "2.0.21" apply false
    id("com.google.devtools.ksp") version "2.0.21-1.0.25" apply false
    id("org.jetbrains.kotlin.plugin.serialization") version "2.0.21" apply false
    // Uploads the R8/ProGuard mapping file to Sentry on release builds so
    // minified stack traces (MainActivity.i etc.) arrive deobfuscated.
    id("io.sentry.android.gradle") version "5.12.1" apply false
}
