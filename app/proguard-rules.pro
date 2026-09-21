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

# --- Player diagnostics: class names are printed into PLAYER_DV / PLAYER_VIDEO ---
# The Dolby Vision compat layer narrates itself through javaClass.simpleName
# ("Compat extractor configured=...", "Wrapping extractor=..."). R8 renames
# every one of those in a release build, so a field log reads
# "configured=x progressiveSource=W" exactly when the diagnosis depends on it.
# Names only — shrinking and optimization stay on, so the per-sample DV strip
# path keeps its inlining.
-keepnames class com.kennyb1201.kbstream.ui.player.**

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

# --- Media3 PlayerView internal surface swap (black-video TextureView fallback) ---
# NativePlayerActivity reflects into this private field to switch the PlayerView's
# video surface at runtime (Media3 1.9 has no public setSurfaceType). R8 would
# otherwise rename the field in release builds and silently break the fallback.
-keepclassmembers class androidx.media3.ui.PlayerView {
    private final android.view.View surfaceView;
}

# --- Jellyfin FFmpeg audio decoder ---
# FFmpeg audio decoding (DTS / DTS-HD / TrueHD / E-AC3 / FLAC) is the reason
# this package is bundled. Its video renderer ships with no video decoders
# compiled in and is never referenced (video is always hardware MediaCodec).
-dontwarn org.jellyfin.**

-dontwarn javax.annotation.**
-dontwarn org.conscrypt.**
-dontwarn org.bouncycastle.**
-dontwarn org.openjsse.**

# --- Ktor / SLF4J (HTTP layer pulled in by the Supabase sync SDK) ---
# slf4j-api's LoggerFactory.bind() references org.slf4j.impl.StaticLoggerBinder
# (and StaticMDCBinder / StaticMarkerBinder), which only exist in desktop
# SLF4J 1.x runtime bindings (logback etc.). No Android build ever ships
# them, and Ktor's Android engine never touches this code path at runtime.
-dontwarn org.slf4j.**

# --- Strip verbose/debug logs from release ---
# R8 removes Log.v/Log.d call sites entirely in minified builds: zero
# logcat noise and no viewing-behavior breadcrumbs in shipped APKs.
# Log.i/w/e stay — they carry genuine runtime diagnostics.
# Convention: anything that explains a user-visible watch-state outcome
# ("scrobble/start ok", "pushWatchedEpisode skipped: ...", "SIMKL marker sets
# refreshed: ...") logs at Log.i, so it survives into the release build a bug
# report actually comes from; Log.d is for tracing that only helps while
# developing.
-assumenosideeffects class android.util.Log {
    public static int v(java.lang.String, java.lang.String);
    public static int d(java.lang.String, java.lang.String);
    public static int v(java.lang.String, java.lang.String, java.lang.Throwable);
    public static int d(java.lang.String, java.lang.String, java.lang.Throwable);
}