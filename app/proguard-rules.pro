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

# --- Rhino JS engine (pulled in by NewPipeExtractor) ---
# Rhino references desktop-only JDK classes (java.beans / javax.script) that
# do not exist on Android. The code paths NewPipe uses (evaluating player
# response JS) never touch them, so R8 can safely ignore the missing classes.
-dontwarn org.mozilla.javascript.**
-dontwarn java.beans.**
-dontwarn javax.script.**

# --- ZXing QR generation ---
-keep class com.google.zxing.** { *; }

# --- Common optional/annotation noise ---
-dontwarn javax.annotation.**
-dontwarn org.conscrypt.**
-dontwarn org.bouncycastle.**
-dontwarn org.openjsse.**