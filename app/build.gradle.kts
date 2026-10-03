import java.util.Properties

plugins {
    id("com.android.application")
    // Consumes the baseline profile the :baselineprofile generator writes:
    // `./gradlew :app:generateBaselineProfile` on a connected device.
    id("androidx.baselineprofile")
    // No org.jetbrains.kotlin.android: AGP 9's built-in Kotlin compiles this
    // module's Kotlin sources, and the old plugin is incompatible with AGP 9's
    // new DSL. See the note in the root build file.
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
    // 37, not 35: the androidx 1.19 / Compose 1.12 generation requires it,
    // and AGP 9.4 is the first AGP that supports it.
    compileSdk = 37

    defaultConfig {
        applicationId = "com.kennyb1201.kbstream"
        // 24, not 23: work-runtime 2.12 declares minSdk 24 and is the last piece
        // of the 2026 androidx line this app could not take (the manifest merger
        // refuses it outright below 24). The floor costs Android 6.0 only, and
        // buys little there anyway - libmpv declares minSdk 26 and PlayerEngine
        // refuses the MPV engine below it, so an API 23-25 box already had no
        // backup engine and no software video.
        minSdk = 24
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

    // No kotlinOptions block: AGP 9's built-in Kotlin takes the Kotlin
    // jvmTarget from compileOptions.targetCompatibility (17 above), so setting
    // it here again would be redundant - and the DSL is gone in AGP 9 anyway.

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
        // No htmlReport/xmlReport here: AGP 9 removed both flags because lint
        // now always writes its HTML and XML reports (the CI upload still finds
        // them at app/build/reports/lint-results-debug.{html,xml}).
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
        getByName("debug") { assets.directories.add("$projectDir/schemas") }
    }
}

// Keep the generated baseline profile in the source tree rather than in build/:
// it is input to the build (and reviewable in a diff), the way the exported Room
// schemas are. Without a generated file this is a no-op.
//
// This used to live inside buildTypes.release as `baselineProfile { saveInSrc =
// true }`. AGP 9 dropped saveInSrc from the build-type DSL (the build-type
// BaselineProfile interface now only carries ignoreFrom/
// ignoreFromAllExternalDependencies); the androidx baseline-profile plugin's own
// top-level `baselineProfile` extension still exposes it, so the setting moved
// here. Set once for every variant, which is what the property always did.
baselineProfile {
    saveInSrc = true
}


dependencies {
    implementation("androidx.core:core-ktx:1.19.1")
    // EncryptedSharedPreferences backs the auth-token stores (Supabase session,
    // Simkl token, MDBList key). The key is held in the AndroidKeyStore and
    // never leaves the device, so a rooted box or a pulled prefs file yields
    // ciphertext instead of a live session that can be replayed off-device.
    implementation("androidx.security:security-crypto:1.1.0-alpha06")
    implementation("androidx.activity:activity-compose:1.13.0")
    implementation("androidx.lifecycle:lifecycle-viewmodel-compose:2.11.0")
    implementation("androidx.lifecycle:lifecycle-runtime-compose:2.11.0")
    // Paging 3, Compose half. The "Open in Grid" full-catalog screen pages a
    // single addon catalog by item offset, which is exactly what a PagingSource
    // models: the source owns the offset bookkeeping, de-dupe and end-of-list
    // detection, and the screen reads one LazyPagingItems instead of a hand-
    // rolled items/isLoadingMore/hasMore/error state machine. paging-compose
    // brings paging-common (Pager/PagingConfig/PagingSource); paging-runtime is
    // the LiveData/RxJava half and is not needed here.
    implementation("androidx.paging:paging-compose:3.5.1")

    implementation("androidx.compose.runtime:runtime")

    implementation("androidx.tv:tv-material:1.1.0")
    // Still declared even though nothing imports androidx.tv.foundation: no
    // 1.1.0 exists to bump it to, and tv-material 1.1.0's POM dropped its
    // transitive dependence on it, so removing this line would take the
    // artifact off the classpath entirely.
    implementation("androidx.tv:tv-foundation:1.0.0")
    // Compose 1.12.1 / material3 1.4.0. This BOM sat at 2024.09.03 (Compose
    // 1.7) for as long as it did because nothing newer fit under AGP 8.6.0 +
    // compileSdk 35 - see the ceiling note in .github/dependabot.yml - and
    // because the Compose compiler used to move with it: on Kotlin 2.x the
    // compiler ships WITH the Kotlin version (2.4.20 here), so a BOM bump is a
    // version bump and not a compiler migration. tv-material 1.1.0 needs at
    // least 1.10.3, which is what pins the floor of this line.
    implementation(platform("androidx.compose:compose-bom:2026.09.00"))
    implementation("androidx.compose.ui:ui")
    implementation("androidx.compose.ui:ui-tooling-preview")
    implementation("androidx.compose.material3:material3")
    implementation("androidx.compose.material:material-icons-extended")
    // Retrofit 3 is the line that pairs with OkHttp 5 (Retrofit 2.11 pins
    // OkHttp 4), and both stay in the retrofit2/okhttp3 packages, so this is
    // a version bump rather than an import rewrite. They move together:
    // bumping OkHttp alone would leave Retrofit compiled against OkHttp 4.
    implementation("com.squareup.retrofit2:retrofit:3.0.0")
    implementation("com.squareup.retrofit2:converter-moshi:3.0.0")
    implementation("com.squareup.okhttp3:okhttp:5.5.0")
    implementation("com.squareup.okhttp3:logging-interceptor:5.5.0")
    // Transparent Brotli (Content-Encoding: br) decoding, installed on the
    // shared base client (see BaseHttpClient). Addon catalogs, TMDB and the
    // tracker APIs are all JSON over HTTP, and Brotli shrinks those payloads
    // ~20% against gzip - on a 1.7 GB TV box the win is mostly in the JSON
    // parse and the time-to-first-rail, not just bytes.
    implementation("com.squareup.okhttp3:okhttp-brotli:5.5.0")
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
    ksp("com.squareup.moshi:moshi-kotlin-codegen:1.15.2")
    // The reflective half, kept as the FALLBACK for a type with no annotation.
    // Every Moshi.Builder below adds it with addLast() rather than add():
    // Moshi consults factories in order, so adding it first would let
    // reflection claim every model before the generated adapter is ever
    // looked up, and this dependency would be dead weight.
    implementation("com.squareup.moshi:moshi-kotlin:1.15.2")
    implementation("com.google.zxing:core:3.5.4")

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
    // Sentry Android 8.x. The Gradle plugin stays at 6.23.0: it is versioned
    // independently of the SDK and 6.23.0 is the current release, so the SDK
    // major is the only thing that moves. 8.x rejects nothing the reporter
    // uses (SentryAndroid.init, dsn/release/dist/setTag, beforeSend,
    // beforeBreadcrumb, Sentry.captureException), so this is a version bump.
    implementation("io.sentry:sentry-android:8.59.0")

    // Supabase (cross-device sync): auth + Postgres REST + realtime channels.
    //
    // supabase-kt 3.x renamed the auth module: gotrue-kt is no longer
    // published, and the package moved from io.github.jan.supabase.gotrue to
    // io.github.jan.supabase.auth (SessionStatus moved again, to
    // ...auth.status). 3.x also requires Ktor 3, so the engine below moved
    // with it.
    implementation(platform("io.github.jan-tennert.supabase:bom:3.8.0"))
    implementation("io.github.jan-tennert.supabase:auth-kt")
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
    implementation("io.ktor:ktor-client-okhttp:3.6.0")
    // 1.11.0, the current stable. It started as the floor Ktor 3 / supabase-kt
    // 3 set when they moved to Kotlin 2.x (leaving 1.7.3 here forced a
    // downgrade of the runtime they are built against) and it matches the
    // Kotlin 2.4.20 serialization plugin compiling against it: the sync
    // payload, outbox queue, profile and hidden-title models all serialize
    // through this runtime.
    implementation("org.jetbrains.kotlinx:kotlinx-serialization-json:1.11.0")

    implementation("androidx.media3:media3-exoplayer:1.11.1")
    implementation("androidx.media3:media3-exoplayer-hls:1.11.1")
    implementation("androidx.media3:media3-exoplayer-dash:1.11.1")
    implementation("androidx.media3:media3-exoplayer-rtsp:1.11.1")
    // Microsoft Smooth Streaming (MSS), the Silverlight-era adaptive format:
    // ".../stream.ism/Manifest", still served by IIS Media Services and some
    // CDNs. Small module, and Media3's DefaultMediaSourceFactory picks it up by
    // class name (SsMediaSource$Factory) once it is on the classpath - see
    // resolveMimeType in NativePlayerActivity, which is what puts the mime on
    // the MediaItem for a URL that has no extension to key off.
    implementation("androidx.media3:media3-exoplayer-smoothstreaming:1.11.1")
    implementation("androidx.media3:media3-ui:1.11.1")
    implementation("androidx.media3:media3-session:1.11.1")
    implementation("androidx.media3:media3-datasource-okhttp:1.11.1")
    // FFmpeg decoder extension.
    //
    // There is no published build of media3's decoder_ffmpeg we can use: the
    // extension subclasses media3's decoder internals, so it only works when it
    // is compiled against the exact media3 release on the classpath, and the
    // one prebuilt available (org.jellyfin.media3:media3-ffmpeg-decoder) stopped
    // at 1.9.0+1 — it has no 1.10/1.11 build. It also carries AUDIO decoders
    // only, so its video renderer answers UNSUPPORTED for every video mime and
    // never claims a track. Pinning media3 at 1.9.0 to keep that fallback alive
    // would freeze three media3 releases of decoder and container fixes, so the
    // fallback is gone and our own video-enabled AAR is the only source.
    //
    // scripts/build_ffmpeg_video.sh builds that AAR from the matching
    // androidx/media git tag into libs/media3-ffmpeg-decoder.aar (gitignored,
    // so it survives across builds once produced), and the build workflow runs
    // it in a cached step whose "Verify FFmpeg extension was produced" check
    // fails the release unless the AAR exists. Every published APK therefore
    // ships a matching video-enabled FFmpeg. Locally, either run the script
    // (JDK 17, NDK r28c, ~10 GB disk, tens of minutes the first time) or drop
    // the AAR from a previous build run's "media3-ffmpeg-decoder-video"
    // artifact into libs/.
    //
    // Once it is there this is the whole swap: SplitModeRenderersFactory
    // already runs the video extension renderer in EXTENSION_RENDERER_MODE_ON,
    // so the software video decoder joins behind MediaCodec with no code change
    // once the library actually has video decoders.
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
            "FFmpeg extension: libs/media3-ffmpeg-decoder.aar not found — this " +
                "build has NO FFmpeg extension in ExoPlayer, so software VIDEO " +
                "decoding (10-bit AVC/HEVC, VP9 profile 2, AV1, MPEG-2, VC-1) " +
                "and the audio codecs MediaCodec does not guarantee (AC-3/E-AC-3, " +
                "DTS, TrueHD, MP2, WMA, AC-4) are only available through the MPV " +
                "engine. Run scripts/build_ffmpeg_video.sh, or drop the " +
                "\"media3-ffmpeg-decoder-video\" artifact from a previous build " +
                "workflow run into libs/, to produce the AAR."
        )
    }

    // Backup playback engine (MPV): what plays a title when ExoPlayer cannot
    // — decoder-resource exhaustion on Realtek/TCL boxes, containers/codecs
    // MediaCodec has no decoder for, and fansub ASS/SSA typesetting (mpv
    // renders it with libass). It bundles its own FFmpeg + mpv + libass as
    // native libraries for all four ABIs, so it needs no other dependency and
    // no app-side extraction.
    //
    // Pinned to 0.5.1 deliberately: that release is a plain Java artifact and
    // carries the static MPVLib API the reference mpv-android player uses,
    // which is what MpvPlayerView drives. (The other reason the pin used to
    // carry - 1.0.0 is Kotlin-compiled and the old 2.0.21 toolchain could not
    // read its metadata - no longer applies now that the project builds on
    // Kotlin 2.4.20; bumping mpv is still a separate, deliberate change.)
    //
    // NOTE: the artifact declares minSdk 26 while this app ships minSdk 23.
    // The manifest carries the matching tools:overrideLibrary, and
    // PlayerEngine refuses to select MPV below API 26 rather than loading the
    // native libraries on a device they were not built for.
    implementation("dev.jdtech.mpv:libmpv:0.5.1")
    implementation("androidx.recyclerview:recyclerview:1.4.0")
    // Pinned to the COMMIT behind tag v0.26.4 (43f8e6ebeef4…, full hash
    // 43f8e6ebeef469db7c5328714bc5f33c9f06f092), not the tag itself: tags are
    // mutable, so "v0.26.4" can silently resolve to different code than the
    // build that was reviewed and shipped. JitPack resolves the commit prefix;
    // NewPipeExtractor is GPLv3, see THIRD_PARTY_NOTICES.md.
    implementation("com.github.TeamNewPipe:NewPipeExtractor:43f8e6ebeef4")


    implementation("androidx.room:room-runtime:2.8.5")
    ksp("androidx.room:room-compiler:2.8.5")
    implementation("androidx.room:room-ktx:2.8.5")

    // 2.12.0 is why the app's floor moved to minSdk 24: it declares 24 itself,
    // and below that the manifest merger rejects the whole build ("uses-sdk:
    // minSdkVersion 23 cannot be smaller than version 24 declared in library
    // [androidx.work:work-runtime-ktx:2.12.0]").
    implementation("androidx.work:work-runtime-ktx:2.12.0")

    implementation("io.coil-kt.coil3:coil-compose:3.6.3")
    implementation("io.coil-kt.coil3:coil:3.6.3")
    implementation("io.coil-kt.coil3:coil-network-okhttp:3.6.3")
    // SVG badge art: several popular KB-compatible badge packs serve chips
    // as .svg, which base Coil cannot decode — without this those badges
    // render blank.
    implementation("io.coil-kt.coil3:coil-svg:3.6.3")
    // Animated focus GIFs on KB collection folder tiles (manifest
    // focusGifUrl / focusGifEnabled); base Coil shows only the first frame.
    implementation("io.coil-kt.coil3:coil-gif:3.6.3")

    coreLibraryDesugaring("com.android.tools:desugar_jdk_libs_nio:2.1.5")

    // JVM unit tests (KidsMode rating matrix, catalog invariants).
    testImplementation("junit:junit:4.13.2")
    // The real org.json, not android.jar's stub. isReturnDefaultValues makes the
    // stub return nulls, so the OpenSubtitles tests would assert the failure
    // path of every response instead of the parse they are there for. Test
    // scope only: the app itself still uses the platform's org.json.
    testImplementation("org.json:json:20260814")

    // Room migration tests (see data/history): MigrationTestHelper drives a
    // real SQLite database through a Migration and validates the result
    // against the exported schema - the one class of test the JVM-only suite
    // could not reach, and the gap that let the watch-history schema sit one
    // version bump away from being dropped unnoticed.
    testImplementation("androidx.room:room-testing:2.8.5")
    // ...and Robolectric is what supplies the Android runtime room-testing and
    // MigrationTestHelper's instrumentation need, so it stays a UNIT test: no
    // device, no emulator, no androidTest variant, run by the same
    // `./gradlew testDebugUnitTest` gate as everything else.
    // Robolectric picks the SDK it emulates from the app's target SDK (35), not
    // from compileSdk (37), so it needs a release that ships an android-all jar
    // for 35 - 4.14 was the floor for that, and 4.17 is the current line.
    testImplementation("org.robolectric:robolectric:4.17")
    testImplementation("androidx.test.ext:junit:1.3.0")
    testImplementation("androidx.test:core-ktx:1.7.0")

    debugImplementation("androidx.compose.ui:ui-tooling")
}
