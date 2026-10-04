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

# --- Moshi: generated adapters are resolved BY NAME at runtime -----------
#
# Every model in this app is @JsonClass(generateAdapter = true) and KSP emits
# a *JsonAdapter for each one (see the ksp(...) entry in app/build.gradle.kts).
# Moshi finds that adapter by BUILDING ITS NAME:
#
#   Class.forName(modelClass.name.replace('$', '_') + "JsonAdapter")
#
# (Types.generatedJsonAdapterName + Util.generatedJsonAdapter), using the name
# the class has at RUNTIME. So in a minified build both halves of that string
# have to survive the optimizer, and R8 cannot work either one out for itself:
# the concatenation happens inside Moshi, not in the APK.
#
#  - the model keeps its NAME, so the name Moshi builds is the real one;
#  - the adapter is kept with its name, so that name resolves to something.
#
# Members are deliberately NOT kept. A generated adapter reads its model's
# fields directly, so field/property/method names are free to shrink and be
# obfuscated - which is the payoff, and the reason the two blanket
# `-keep class ...data.** { *; }` / `...domain.** { *; }` rules that used to
# live here (one for every model, kept for reflection) are gone.
#
# Anything that is NOT annotated still works: KotlinJsonAdapterFactory is added
# with addLast() by every Moshi.Builder in the app, so it only sees types with
# no generated adapter, and it reads its member names out of kotlin.Metadata
# (kept above) rather than out of the class name - obfuscation does not disturb
# it.
#
# Worth knowing: the failure mode here is NOT a crash, which is why it must be
# pinned by a rule rather than by review. KotlinJsonAdapterFactory swallows the
# failed lookup (it catches the ClassNotFoundException and carries on) and
# adapts the type reflectively instead - so a rule this file gets wrong costs
# the speed and the shrinking that made codegen worth adding, silently, in the
# release build only. That is also why adding codegen is safe to ship: if a
# name is ever not preserved, the app degrades to the behaviour it shipped
# with before rather than breaking.
-keepnames @com.squareup.moshi.JsonClass class *
-keep class **JsonAdapter {
    <init>(...);
}

# --- libass JNI bridge ---
# scripts/libass-jni/libassjni.cpp exports
# Java_com_kennyb1201_kbstream_ui_player_AssNative_native*, so the class and
# every native method name are the interface. The -keepnames on the player
# package below happens to cover this today; this rule states the contract so
# narrowing that one cannot break the bridge silently - a renamed native
# method throws UnsatisfiedLinkError at the first ASS sidecar and nowhere
# earlier.
-keepclasseswithmembernames class com.kennyb1201.kbstream.ui.player.AssNative {
    native <methods>;
}

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
# video surface at runtime (Media3 1.11 still has no public surface-type setter).
# R8 would otherwise rename the field in release builds and silently break the
# fallback.
-keepclassmembers class androidx.media3.ui.PlayerView {
    private final android.view.View surfaceView;
}

# --- FFmpeg decoder extension: the reflection-loaded renderers ---
# DefaultRenderersFactory instantiates these two by NAME
# (Class.forName("androidx.media3.decoder.ffmpeg....")), so R8 must not rename
# or remove them: media3-exoplayer's consumer rules keep the constructors, and
# these keep the classes themselves, which is what the name lookup actually
# needs. If they are renamed, the lookup silently falls through and the
# software video path (10-bit AVC/HEVC, VP9 profile 2, AV1, MPEG-2, VC-1) and
# the software audio path (DTS/TrueHD/E-AC3/FLAC) simply do not exist in a
# RELEASE build — while still working in debug, where minification is off.
-keep class androidx.media3.decoder.ffmpeg.ExperimentalFfmpegVideoRenderer { *; }
-keep class androidx.media3.decoder.ffmpeg.FfmpegAudioRenderer { *; }

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