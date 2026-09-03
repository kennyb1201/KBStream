# ---------------------------------------------------------------------------
# KBStream R8 / ProGuard rules
#
# Release builds run with isMinifyEnabled = true. These rules keep the
# reflection-based parts of the app working (JSON adapters, extractors, QR
# generation). Debug builds are unaffected (minification is off).
# ---------------------------------------------------------------------------

# --- Kotlin reflection metadata (used by Moshi's KotlinJsonAdapterFactory) ---
-keepattributes RuntimeVisibleAnnotations, RuntimeVisibleParameterAnnotations, AnnotationDefault
-keep class kotlin.Metadata { *; }
-dontwarn kotlin.reflect.jvm.internal.**

# --- App data models: adapted reflectively at runtime by Moshi ---
-keep class com.kennyb1201.kbstream.data.** { *; }
-keep class com.kennyb1201.kbstream.domain.** { *; }

# --- Moshi custom adapter methods ---
-keepclasseswithmembers class * {
    @com.squareup.moshi.FromJson <methods>;
    @com.squareup.moshi.ToJson <methods>;
}

# --- NewPipeExtractor (heavy reflection + XML parsing) ---
-keep class org.schabi.newpipeextractor.** { *; }
-dontwarn org.schabi.newpipeextractor.**

# --- ZXing QR generation ---
-keep class com.google.zxing.** { *; }

# --- Common optional/annotation noise ---
-dontwarn javax.annotation.**
-dontwarn org.conscrypt.**
-dontwarn org.bouncycastle.**
-dontwarn org.openjsse.**