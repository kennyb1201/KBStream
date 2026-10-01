import java.util.Properties

plugins {
    id("com.android.application")
    // Consumes the baseline profile the :baselineprofile generator writes:
    // `./gradlew :app:generateBaselineProfile` on a connected device.
    id("androidx.baselineprofile")
    id("org.jetbrains.kotlin.android")
    id("com.google.devtools.ksp")
    id("org.jetbrains.kotlin.plugin.compose")
    id("org.jetbrains.kotlin.plugin.serialization")
    id("io.sentry.android.gradle")
    id("org.jlleitschuh.gradle.ktlint")
}

// Dead-import check. The rule set is one rule wide (see .editorconfig), so
// every finding this reports means exactly one thing - an import nothing
// references - and `./gradlew ktlintFormat` removes it. It runs over the app
// and test sources alike: an unused import in a test is the same junk.
ktlint {
    ignoreFailures.set(false)
}

// Room's exported schemas. Every @Database with exportSchema = true writes its
// shape here, one JSON per released version, and those files are committed:
// that is what makes a Migration reviewable against the schema it has to carry
// a database to, and what lets the guard test in data/history assert that the
// version the code declares is the version on disk. The watch-history database
// is USER DATA - resume points, watched state - so its history has to be
// readable; the guide database keeps exportSchema = false because it is a
// cache that is deliberately rebuilt when its schema changes.
ksp {
    arg("room.schemaLocation", "$projectDir/schemas")
}

val localProps = Properties()
val localPropsFile = rootProject.file("local.properties")
if (localPropsFile.exists()) {
    localProps.load(localPropsFile.inputStream())
}

val tmdbApiKey = localProps.getProperty("TMDB_API_KEY")
    ?: System.getenv("TMDB_API_KEY")
    ?: ""

val simklClientId = localProps.getProperty("SIMKL_CLIENT_ID")
    ?: System.getenv("SIMKL_CLIENT_ID")
    ?: ""

// There is deliberately no SIMKL_CLIENT_SECRET here, and it must not come
// back. Simkl is reached through its PIN (device) flow: the app asks
// /oauth/pin for a user_code, shows it, and polls /oauth/pin/{user_code}
// until the user approves on simkl.com. That flow is a PUBLIC-client flow -
// only client_id (which is public: it is in every authorize URL) travels,
// and no request the app makes has ever carried the secret. Baking it in
// therefore bought nothing and cost plenty: buildConfigField put a live
// secret in a public GPL-3.0 repo's CI configuration and inside every
// distributed APK, where a `strings`-style scan recovers it in seconds.
// SimklRepository.isConfigured() used to require it, which is why its absence
// was invisible: the only effect of the secret was to gate the question
// "is Simkl set up?" on a value nothing consumed.

val sentryDsn = localProps.getProperty("SENTRY_DSN")
    ?: System.getenv("SENTRY_DSN")
    ?: ""

val mdbListApiKey = localProps.getProperty("MDBLIST_API_KEY")
    ?: System.getenv("MDBLIST_API_KEY")
    ?: ""

// Trim(): a pasted secret or local.properties value with a trailing
// newline/space silently corrupts the apikey header -> Supabase answers
// 401 "Invalid API key" even though the key itself is correct.
val supabaseUrl = (localProps.getProperty("SUPABASE_URL")
    ?: System.getenv("SUPABASE_URL")
    ?: "").trim()

val supabaseAnonKey = (localProps.getProperty("SUPABASE_ANON_KEY")
    ?: localProps.getProperty("SUPABASE_PUBLISHABLE_KEY")
    ?: System.getenv("SUPABASE_ANON_KEY")
    ?: System.getenv("SUPABASE_PUBLISHABLE_KEY")
    ?: "").trim()

// Catch malformed keys at BUILD time instead of as a runtime 401: a key
// that is blank, contains whitespace, or is implausibly short cannot be
// accepted by Supabase, so surface it in the build log immediately.
if (supabaseUrl.isNotBlank()) {
    if (supabaseAnonKey.isBlank()) {
        logger.warn(
            "SUPABASE_URL is set but SUPABASE_ANON_KEY is blank — " +
                "sync sign-in will be disabled in this build."
        )
    } else if (
        supabaseAnonKey.contains(' ') ||
        supabaseAnonKey.length < 30
    ) {
        logger.warn(
            "SUPABASE_ANON_KEY looks malformed (whitespace or < 30 chars) — " +
                "Supabase will reject it with 'Invalid API key'. Re-paste the " +
                "full key (starts with sb_publishable_ or eyJ)."
        )
    }
}

val releaseStoreFile = System.getenv("KBSTREAM_STORE_FILE")
val releaseStorePassword = System.getenv("KBSTREAM_STORE_PASSWORD")
val releaseKeyAlias = System.getenv("KBSTREAM_KEY_ALIAS")
val releaseKeyPassword = System.getenv("KBSTREAM_KEY_PASSWORD")

// Whether a release keystore is actually available (CI passes one through the
// KBSTREAM_* environment variables; a developer machine usually has none).
val releaseSigningConfigured =
    !releaseStoreFile.isNullOrBlank() &&
        !releaseStorePassword.isNullOrBlank() &&
        !releaseKeyAlias.isNullOrBlank() &&
        !releaseKeyPassword.isNullOrBlank()

// Release version stamping (set by CI; local builds fall back to dev values).
// VERSION_CODE=github.run_number makes every CI build strictly higher than the
// last, so `adb install -r` upgrades cleanly and bug reports identify builds.
val ciVersionCode = System.getenv("VERSION_CODE")?.toIntOrNull() ?: 1
val ciVersionName = System.getenv("VERSION_NAME")?.takeIf { it.isNotBlank() } ?: "0.1.0-dev"
val ciGitSha = System.getenv("GIT_SHA")?.takeIf { it.isNotBlank() } ?: "local"

android {
    namespace = "com.kennyb1201.kbstream"
    compileSdk = 35

    defaultConfig {
        applicationId = "com.kennyb1201.kbstream"
        minSdk = 23
        targetSdk = 35
        versionCode = ciVersionCode
        versionName = ciVersionName

        buildConfigField("String", "TMDB_API_KEY", "\"$tmdbApiKey\"")
        buildConfigField("String", "SIMKL_CLIENT_ID", "\"$simklClientId\"")
        buildConfigField("String", "SENTRY_DSN", "\"$sentryDsn\"")
        buildConfigField("String", "MDBLIST_API_KEY", "\"$mdbListApiKey\"")
        buildConfigField("String", "SUPABASE_URL", "\"$supabaseUrl\"")
        buildConfigField("String", "SUPABASE_ANON_KEY", "\"$supabaseAnonKey\"")
        buildConfigField("String", "GIT_SHA", "\"$ciGitSha\"")
    }
    buildFeatures {
        compose = true
        buildConfig = true
    }

    signingConfigs {
        create("release") {
            if (releaseSigningConfigured) {
                storeFile = file(releaseStoreFile)
                storePassword = releaseStorePassword
                keyAlias = releaseKeyAlias
                keyPassword = releaseKeyPassword
            }
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = true
            // Keep the generated profile in the source tree rather than in
            // build/: it is input to the build (and reviewable in a diff),
            // the way the exported Room schemas are. Without a generated file
            // this is a no-op.
            baselineProfile { saveInSrc = true }
            isShrinkResources = true
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro"
            )
            signingConfig = signingConfigs.getByName("release")
        }
    }

    // Sentry: upload the R8 mapping on release builds so crash reports
    // arrive with real class/method names instead of obfuscated ones
    // (MainActivity.i). Auth comes from SENTRY_AUTH_TOKEN (CI secret) or
    // sentryAuthToken in local.properties; without a token the build still
    // succeeds — mapping upload is skipped with a warning.
    sentry {
        includeProguardMapping.set(true)
        telemetry.set(false)
        // Default to the main sentry.io host. The org token is a sentry.io
        // token and that host performs the region redirect itself; a
        // hardcoded region endpoint (us.sentry.io) only helps upload-only
        // tokens, and mismatching the host is what made uploads fail.
        url.set(
            System.getenv("SENTRY_URL")
                ?: localProps.getProperty("SENTRY_URL")
                ?: "https://sentry.io"
        )
        org.set(
            System.getenv("SENTRY_ORG")
                ?: localProps.getProperty("SENTRY_ORG")
                ?: "kbstream"
        )
        projectName.set(
            System.getenv("SENTRY_PROJECT")
                ?: localProps.getProperty("SENTRY_PROJECT")
                ?: "android"
        )
        authToken.set(
            System.getenv("SENTRY_AUTH_TOKEN")
                ?: localProps.getProperty("SENTRY_AUTH_TOKEN")
        )
        tracingInstrumentation { enabled.set(false) }
    }

    compileOptions {
    isCoreLibraryDesugaringEnabled = true
    sourceCompatibility = JavaVersion.VERSION_17
    targetCompatibility = JavaVersion.VERSION_17
}

    kotlinOptions {
        jvmTarget = "17"
    }

    // Android Lint is the gate the audit found missing: nothing else in this
    // tree flags an API reached above minSdk without a guard, a resource used
    // as the wrong type, or a component that cannot be instantiated - and the
    // ktlint configuration above is deliberately one rule wide.
    //
    // It runs in CI (.github/workflows/build.yml), never locally on purpose: a
    // container small enough to need gradle.properties' 3 GiB daemon heap
    // cannot run lint's whole-source analysis without the daemon being
    // OOM-killed mid-run.
    //
    // Errors fail the build; warnings are reported and uploaded as an artifact.
    // That split is the point - the issues lint calls errors are the ones that
    // crash on a TV with no debugger attached, whereas its style opinions (and
    // its new-dependency-available nudges) must not be what blocks a release.
    lint {
        abortOnError = true
        warningsAsErrors = false
        // Test sources are not shipped in the APK.
        checkTestSources = false
        // Assemble-release already runs lintVitalRelease (the fatal subset) on
        // every release build; this keeps that in place and adds the full
        // analysis on the debug variant in CI.
        checkReleaseBuilds = true
        // Single-language app by design (res/values/strings.xml holds one entry
        // and there is no values-<locale>): "this string is not translated" and
        // "this translation is redundant" describe the intent here, not a bug.
        // Only these two, and only because they are warnings: a check that can
        // fail the build stays on.
        disable += "MissingTranslation"
        disable += "ExtraTranslation"
        // The one thing lint cannot infer on its own: media3's `@UnstableApi`
        // (an androidx.annotation.RequiresOptIn(ERROR) marker) is opted into
        // project-wide there, because the marker's granularity is the library
        // and this app uses that library throughout. See the file for why, and
        // note the check itself stays enabled for every other marker.
        lintConfig = file("lint.xml")
        htmlReport = true
        xmlReport = true
    }

    testOptions {
        // Unit tests build real media3 Formats (the audio track label reads
        // Format.language / codecs / sampleMimeType), and Format's constructor
        // normalizes the language through Util.normalizeLanguageCode, which
        // calls android.text.TextUtils. Without this the stub jar throws
        // "Method isEmpty in android.text.TextUtils not mocked" from every
        // such test.
        unitTests.isReturnDefaultValues = true
        // Robolectric tests resolve resources and assets through the merged
        // debug resources: without this the migration test cannot read the
        // exported schemas it is handed (see the assets wiring below), and
        // Room's helper reports them as missing.
        unitTests.isIncludeAndroidResources = true
    }

    // A note for `:app:generateBaselineProfile`, whose failures are confusing:
    // the profile-capturing variants the baseline profile plugin adds
    // (nonMinifiedRelease, benchmark) INHERIT the release signing config above,
    // because the generator installs the app on the device. On a machine where
    // the KBSTREAM_* variables are unset that config is empty, so the run stops
    // at the install step with INSTALL_PARSE_FAILED_NO_CERTIFICATES and nothing
    // in the message mentions signing. Point those four variables at the debug
    // keystore to capture a profile locally - see README, "Baseline profile".
    sourceSets {
        // MigrationTestHelper reads the exported schemas from ASSETS, not from
        // the filesystem: it looks for "<database class name>/<version>.json"
        // in the instrumentation context's assets, and refuses to run without
        // it. The committed app/schemas directory is exactly that tree, so it
        // is wired in as an asset root - the same directory the KSP
        // room.schemaLocation argument above writes.
        //
        // On the debug VARIANT rather than a test source set: local unit tests
        // resolve assets through the merged debug assets
        // (com/android/tools/test_config.properties -> android_merged_assets),
        // so anything added to the `test` source set is never seen. Keeping it
        // off `main` keeps the exported schemas out of the released APK, which
        // costs a `testReleaseUnitTest` run the same wiring if that is ever
        // added.
        getByName("debug") { assets.srcDirs(files("$projectDir/schemas")) }
    }
}



dependencies {
    implementation("androidx.core:core-ktx:1.13.1")
    implementation("androidx.activity:activity-compose:1.9.2")
    implementation("androidx.lifecycle:lifecycle-viewmodel-compose:2.8.4")
    implementation("androidx.lifecycle:lifecycle-runtime-compose:2.8.4")

    implementation("androidx.compose.runtime:runtime")

    implementation("androidx.tv:tv-material:1.0.0")
    implementation("androidx.tv:tv-foundation:1.0.0")
    // 1.7.x BOM: BringIntoViewSpec (streaming-app-style focus landing on the
    // Home rails) was finalized in Compose 1.7; 1.6 shipped only the old
    // BringIntoViewResponder API.
    implementation(platform("androidx.compose:compose-bom:2024.09.03"))
    implementation("androidx.compose.ui:ui")
    implementation("androidx.compose.ui:ui-tooling-preview")
    implementation("androidx.compose.material3:material3")
    implementation("androidx.compose.material:material-icons-extended")
    implementation("com.squareup.retrofit2:retrofit:2.11.0")
    implementation("com.squareup.retrofit2:converter-moshi:2.11.0")
    implementation("com.squareup.okhttp3:okhttp:5.5.0")
    implementation("com.squareup.okhttp3:logging-interceptor:5.5.0")
    // Moshi, the code-generation half. Every model in this app is annotated
    // @JsonClass(generateAdapter = true), and without this processor those
    // 129 annotations do nothing at all: Moshi falls back to kotlin-reflect
    // for every single type. KSP emits one *JsonAdapter per model, so JSON
    // parsing runs generated code instead of reflection.
    //
    // It is also what makes the R8 rules in app/proguard-rules.pro narrowable:
    // a generated adapter reads its model's fields directly, so the model no
    // longer has to be kept member-for-member, only name-for-name (Moshi finds
    // the adapter by name). Keep this version in lockstep with moshi-kotlin
    // below — the generated adapters and the runtime are the same artifact's
    // two halves.
    ksp("com.squareup.moshi:moshi-kotlin-codegen:1.15.1")
    // The reflective half, kept as the FALLBACK for a type with no annotation.
    // Every Moshi.Builder below adds it with addLast() rather than add():
    // Moshi consults factories in order, so adding it first would let
    // reflection claim every model before the generated adapter is ever
    // looked up, and this dependency would be dead weight.
    implementation("com.squareup.moshi:moshi-kotlin:1.15.1")
    implementation("com.google.zxing:core:3.5.3")

    // Baseline profiles, runtime half. The profile itself (a list of the
    // classes/methods startup touches) is captured by :baselineprofile on a
    // device and committed under app/src/release/generated/baselineProfiles/;
    // this dependency is what INSTALLS it. Android 12+ compiles a profile at
    // install time on its own, but an app that installs itself from a GitHub
    // release - which is how this one ships, see AppUpdater - is never
    // installed by Play, so on the API 23-30 boxes it also runs on, nothing
    // would apply the profile without this. Small, and it does nothing when no
    // profile is present.
    implementation("androidx.profileinstaller:profileinstaller:1.4.1")

    // Crash reporting (Sentry)
    implementation("io.sentry:sentry-android:7.19.0")

    // Supabase (cross-device sync): auth + Postgres REST + realtime channels.
    implementation(platform("io.github.jan-tennert.supabase:bom:2.6.1"))
    implementation("io.github.jan-tennert.supabase:gotrue-kt")
    implementation("io.github.jan-tennert.supabase:postgrest-kt")
    implementation("io.github.jan-tennert.supabase:realtime-kt")
    // MUST be the OkHttp engine, not ktor-client-android. Realtime needs a
    // WebSocket-capable Ktor engine; the Android engine is HttpURLConnection
    // based and exposes NO WebSocketCapability, so every channel join dies
    // with "Engine doesn't support WebSocketCapability" and the SDK retries
    // forever. That left live sync dead AND made the 15s realtime health
    // watcher rebuild three dead channels + run a full pullAll on every
    // cycle, i.e. permanent background network/DB churn during playback.
    // OkHttp is already a dependency here (REST datasource, Coil).
    implementation("io.ktor:ktor-client-okhttp:2.3.12")
    implementation("org.jetbrains.kotlinx:kotlinx-serialization-json:1.7.3")

    implementation("androidx.media3:media3-exoplayer:1.9.0")
    implementation("androidx.media3:media3-exoplayer-hls:1.9.0")
    implementation("androidx.media3:media3-exoplayer-dash:1.9.0")
    implementation("androidx.media3:media3-exoplayer-rtsp:1.9.0")
    // Microsoft Smooth Streaming (MSS), the Silverlight-era adaptive format:
    // ".../stream.ism/Manifest", still served by IIS Media Services and some
    // CDNs. Small module, and Media3's DefaultMediaSourceFactory picks it up by
    // class name (SsMediaSource$Factory) once it is on the classpath - see
    // resolveMimeType in NativePlayerActivity, which is what puts the mime on
    // the MediaItem for a URL that has no extension to key off.
    implementation("androidx.media3:media3-exoplayer-smoothstreaming:1.9.0")
    implementation("androidx.media3:media3-ui:1.9.0")
    implementation("androidx.media3:media3-session:1.9.0")
    implementation("androidx.media3:media3-datasource-okhttp:1.9.0")
    // FFmpeg decoder extension.
    //
    // Default: the published Jellyfin build of media3's own decoder_ffmpeg
    // extension, which carries AUDIO decoders only — so its video renderer can
    // never claim a track. Drop a video-enabled build at
    // libs/media3-ffmpeg-decoder.aar (scripts/build_ffmpeg_video.sh; the
    // build workflow runs it in a cached, non-fatal step) and it is used
    // instead. That is
    // the whole swap: SplitModeRenderersFactory already runs the video
    // extension renderer in EXTENSION_RENDERER_MODE_ON, so the software video
    // decoder joins behind MediaCodec with no code change once the library
    // actually has video decoders.
    //
    // Why this extension and not a prebuilt one: media3's decoder_ffmpeg
    // ships its FFmpeg as libffmpegJNI.so, which can sit next to libmpv.
    // NextLib and the other prebuilt video extensions bundle libavcodec.so /
    // libavutil.so / libswscale.so — the same sonames libmpv already
    // provides — and two dependencies shipping one native library name break
    // the build (or silently keep only one engine's copy).
    val localFfmpegAar = rootProject.file("libs/media3-ffmpeg-decoder.aar")
    if (localFfmpegAar.exists()) {
        logger.lifecycle("FFmpeg extension: using video-enabled ${localFfmpegAar.name}")
        implementation(files(localFfmpegAar))
    } else {
        logger.warn(
            "FFmpeg extension: libs/media3-ffmpeg-decoder.aar not found — " +
                "falling back to the published AUDIO-ONLY artifact. Software " +
                "VIDEO decoding (10-bit AVC/HEVC, VP9 profile 2, AV1, MPEG-2, " +
                "VC-1) will NOT be available in this build. Run " +
                "scripts/build_ffmpeg_video.sh, or the build workflow's " +
                "\"Build FFmpeg video extension\" step, to produce the AAR."
        )
        implementation("org.jellyfin.media3:media3-ffmpeg-decoder:1.9.0+1")
    }

    // Backup playback engine (MPV): what plays a title when ExoPlayer cannot
    // — decoder-resource exhaustion on Realtek/TCL boxes, containers/codecs
    // MediaCodec has no decoder for, and fansub ASS/SSA typesetting (mpv
    // renders it with libass). It bundles its own FFmpeg + mpv + libass as
    // native libraries for all four ABIs, so it needs no other dependency and
    // no app-side extraction.
    //
    // Pinned to 0.5.1 deliberately: that release is a plain Java artifact.
    // 1.0.0 is compiled with Kotlin 2.2.10, and this project builds with Kotlin
    // 2.0.21, which cannot read that metadata ("compiled with an incompatible
    // version of Kotlin"). 0.5.1 also carries the same static MPVLib API the
    // reference mpv-android player uses, which is what MpvPlayerView drives.
    //
    // NOTE: the artifact declares minSdk 26 while this app ships minSdk 23.
    // The manifest carries the matching tools:overrideLibrary, and
    // PlayerEngine refuses to select MPV below API 26 rather than loading the
    // native libraries on a device they were not built for.
    implementation("dev.jdtech.mpv:libmpv:0.5.1")
    implementation("androidx.recyclerview:recyclerview:1.3.2")
    // Pinned to the COMMIT behind tag v0.26.4 (43f8e6ebeef4…, full hash
    // 43f8e6ebeef469db7c5328714bc5f33c9f06f092), not the tag itself: tags are
    // mutable, so "v0.26.4" can silently resolve to different code than the
    // build that was reviewed and shipped. JitPack resolves the commit prefix;
    // NewPipeExtractor is GPLv3, see THIRD_PARTY_NOTICES.md.
    implementation("com.github.TeamNewPipe:NewPipeExtractor:43f8e6ebeef4")


    implementation("androidx.room:room-runtime:2.7.1")
    ksp("androidx.room:room-compiler:2.7.1")
    implementation("androidx.room:room-ktx:2.7.1")

    implementation("androidx.work:work-runtime-ktx:2.10.1")

    implementation("io.coil-kt.coil3:coil-compose:3.0.0")
    implementation("io.coil-kt.coil3:coil:3.0.0")
    implementation("io.coil-kt.coil3:coil-network-okhttp:3.0.0")
    // SVG badge art: several popular KB-compatible badge packs serve chips
    // as .svg, which base Coil cannot decode — without this those badges
    // render blank.
    implementation("io.coil-kt.coil3:coil-svg:3.0.0")
    // Animated focus GIFs on KB collection folder tiles (manifest
    // focusGifUrl / focusGifEnabled); base Coil shows only the first frame.
    implementation("io.coil-kt.coil3:coil-gif:3.0.0")

    coreLibraryDesugaring("com.android.tools:desugar_jdk_libs_nio:2.1.4")

    // JVM unit tests (KidsMode rating matrix, catalog invariants).
    testImplementation("junit:junit:4.13.2")
    // The real org.json, not android.jar's stub. isReturnDefaultValues makes the
    // stub return nulls, so the OpenSubtitles tests would assert the failure
    // path of every response instead of the parse they are there for. Test
    // scope only: the app itself still uses the platform's org.json.
    testImplementation("org.json:json:20250107")

    // Room migration tests (see data/history): MigrationTestHelper drives a
    // real SQLite database through a Migration and validates the result
    // against the exported schema - the one class of test the JVM-only suite
    // could not reach, and the gap that let the watch-history schema sit one
    // version bump away from being dropped unnoticed.
    testImplementation("androidx.room:room-testing:2.7.1")
    // ...and Robolectric is what supplies the Android runtime room-testing and
    // MigrationTestHelper's instrumentation need, so it stays a UNIT test: no
    // device, no emulator, no androidTest variant, run by the same
    // `./gradlew testDebugUnitTest` gate as everything else.
    // 4.14 is the floor here: it is the first release with API 35 support, and
    // this module compiles against 35 (Robolectric picks the target SDK).
    testImplementation("org.robolectric:robolectric:4.14.1")
    testImplementation("androidx.test.ext:junit:1.2.1")
    testImplementation("androidx.test:core-ktx:1.6.1")

    debugImplementation("androidx.compose.ui:ui-tooling")
}
