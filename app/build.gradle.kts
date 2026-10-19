import java.util.Properties

plugins {
    alias(libs.plugins.android.application)
    // Consumes the baseline profile the :baselineprofile generator writes:
    // `./gradlew :app:generateBaselineProfile` on a connected device.
    alias(libs.plugins.baseline.profile)
    // No org.jetbrains.kotlin.android: AGP 9's built-in Kotlin compiles this
    // module's Kotlin sources, and the old plugin is incompatible with AGP 9's
    // new DSL. See the note in the root build file.
    alias(libs.plugins.ksp)
    alias(libs.plugins.kotlin.compose)
    alias(libs.plugins.kotlin.serialization)
    alias(libs.plugins.sentry.android.gradle)
    alias(libs.plugins.ktlint)
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

// A Play-distributed build must not request REQUEST_INSTALL_PACKAGES: Play
// treats it as a sensitive permission and rejects a listing that carries it
// without a justification, and a Play build has no in-app self-update to run
// anyway. `-PplayBuild` overlays app/src/play/AndroidManifest.xml, which
// REMOVES the permission from the merged manifest; the default build (no
// property) is byte-for-byte unchanged, so the GitHub self-update release
// keeps it. The overlay is added through the variant API rather than a
// product flavor so the default task names (assembleRelease and friends) do
// not change for the release pipeline.
val playBuild = project.hasProperty("playBuild")

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

// Trakt, by client id only - the same rule as Simkl above, and for the same
// reason. Trakt's current authentication guide marks the client secret
// deprecated for user sign-in and points at PKCE for the browser flow; the
// device flow the app actually uses is a public-client flow too. A secret
// compiled in here would be recoverable from every published APK, so if the
// device-token endpoint ever rejects a secret-less request the answer is a
// server-side exchange, never a buildConfigField named *SECRET* (pinned by
// SimklClientConfigTest). The id itself is public by construction.
val traktClientId = localProps.getProperty("TRAKT_CLIENT_ID")
    ?: System.getenv("TRAKT_CLIENT_ID")
    ?: ""

val sentryDsn = localProps.getProperty("SENTRY_DSN")
    ?: System.getenv("SENTRY_DSN")
    ?: ""

val mdbListApiKey = localProps.getProperty("MDBLIST_API_KEY")
    ?: System.getenv("MDBLIST_API_KEY")
    ?: ""

// The YouTube InnerTube web API key is deliberately NOT hardcoded in the
// source. It is a shared, public-client key, but baking a live key into a
// public GPL-3.0 repo (and into every distributed APK, where a `strings` scan
// recovers it) invites third parties to burn its quota. The extractor already
// scrapes the key from the watch page it fetches at runtime
// (`INNERTUBE_API_KEY` in that page); this is only the optional build-time
// fallback for a scrape miss, supplied like every other key through
// local.properties / an environment variable and blank by default.
val innertubeApiKey = (localProps.getProperty("INNERTUBE_API_KEY")
    ?: System.getenv("INNERTUBE_API_KEY")
    ?: "").trim()

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

// ── The MPV engine's library ───────────────────────────────────────────────
//
// The engine has exactly ONE backend: fortunasxp/mpv-android-libdovi, the
// `mpv-android-lib-libdovi.aar` release AAR fetched into app/libs/ by
// scripts/fetch_mpv_aar.sh. It replaced the Riteshp2001/mpvlibAndroid (Mpv∞)
// `mpvlib-1.1.2.aar` build, which had no Dolby Vision decode at all: its
// libplacebo was built without `-Ddovi`, so a DV title could only ever be
// played as its HDR10 base layer. This one carries libplacebo with
// `-Ddovi=enabled` (DV reshape + HDR metadata read from the RPU), libdovi 3.3.2
// statically linked, FFmpeg n8.1.1 with `dovi_rpudec`, and mpv on `vo=gpu-next`
// - so P5, P7, 8.1 and 8.4 are decoded as Dolby Vision rather than flattened.
//
// The two libraries are NOT drop-in compatible at the source level, and that is
// the part worth knowing before touching this: the new AAR reuses the
// `io.github.abdallahmehiz:mpv-android-lib` shell (its `classes.jar`,
// `libplayer.so`, `libc++_shared.so` and `libxml2.so`) and only swaps the
// `libmpv.so`/FFmpeg natives, so the Java class it exposes is `is.xyz.mpv.MPV`
// (a class) rather than the old `is.xyz.mpv.MPVLib` (a Kotlin object). That is
// the same API the removed `src/mpvnuvio` arm wrapped, and it is why
// `MpvBackend` holds an instance now instead of delegating to a singleton.
//
// `MpvPlayerView` drives `ui/player/MpvEngineApi.kt` + `MpvBackend`, whose one
// implementation lives in a source directory of its own (src/mpvrites),
// deliberately OUTSIDE src/main. No reflection, no second player, no second
// engine.
val mpvEngineSourceDir = "src/mpvrites"

android {
    namespace = "com.kennyb1201.kbstream"
    // 37, not 35: the androidx 1.19 / Compose 1.12 generation requires it,
    // and AGP 9.4 is the first AGP that supports it.
    compileSdk = 37

    defaultConfig {
        applicationId = "com.kennyb1201.kbstream"
        // 26, not 24: this is the app's OWN floor now. PlayerEngine refuses the
        // MPV engine below 26, and without the FFmpeg extension ExoPlayer has
        // no software video, so an API 24-25 box was already
        // ExoPlayer-on-MediaCodec only. (The MPV AAR declares minSdk 21 - below
        // this - so no tools:overrideLibrary is needed either way; the previous
        // mpvlibAndroid AAR declared 24, also below.) work-runtime 2.12
        // (minSdk 24) is satisfied by 26 as well.
        minSdk = 26
        // 36, not 35: Play rejects updates from phone/tablet/foldable apps that
        // do not target API 36+, and this manifest also carries a plain LAUNCHER
        // category, so the same AAB is distributed to those form factors (the
        // leanback/TV entry only needs API 34 and is unaffected either way).
        // compileSdk 37 already satisfies the build requirement. The API-36
        // behavior changes (edge-to-edge enforcement, predictive back, tablet
        // orientation locks) still need a device pass.
        targetSdk = 36
        versionCode = ciVersionCode
        versionName = ciVersionName

        // The launcher label lives in a resource rather than in the manifest so
        // the debug build can present itself as "KBStream Debug" beside the
        // tagged release instead of as a second identical icon.
        resValue("string", "app_name", "KBStream")
        // The global-search provider's authority. The release value is the
        // historical one, unchanged; the debug build overrides it below, because
        // two installed packages cannot publish the same provider authority.
        resValue(
            "string",
            "search_suggest_authority",
            "com.kennyb1201.kbstream.search"
        )

        // The self-update flow is compiled into every build's UI, but a Play
        // build must not have it at all: `-PplayBuild` strips
        // REQUEST_INSTALL_PACKAGES from the merged manifest, so the offer could
        // only ever end in a failed install (and asking for it is a policy
        // risk). The flag is what lets the Kotlin side hide the Settings row and
        // no-op the check, exactly as the manifest overlay drops the permission
        // (see `playBuild` above).
        buildConfigField("Boolean", "PLAY_BUILD", playBuild.toString())
        buildConfigField("String", "TMDB_API_KEY", "\"$tmdbApiKey\"")
        buildConfigField("String", "SIMKL_CLIENT_ID", "\"$simklClientId\"")
        buildConfigField("String", "TRAKT_CLIENT_ID", "\"$traktClientId\"")
        buildConfigField("String", "SENTRY_DSN", "\"$sentryDsn\"")
        buildConfigField("String", "MDBLIST_API_KEY", "\"$mdbListApiKey\"")
        buildConfigField("String", "INNERTUBE_API_KEY", "\"$innertubeApiKey\"")
        buildConfigField("String", "SUPABASE_URL", "\"$supabaseUrl\"")
        buildConfigField("String", "SUPABASE_ANON_KEY", "\"$supabaseAnonKey\"")
        buildConfigField("String", "GIT_SHA", "\"$ciGitSha\"")
    }
    buildFeatures {
        compose = true
        buildConfig = true
        // resValue carries the launcher label and the per-variant search
        // authority (see defaultConfig / debug below). AGP 9 leaves the
        // feature off unless it is asked for.
        resValues = true
    }

    // English only. The app's own strings are English (see the strings.xml),
    // but its AndroidX / Material / Play dependencies ship dozens of
    // translations no user of this build will ever see. Dropping the
    // values-<locale> folders is a straight APK-size win, and the default
    // (unqualified) resources are always kept. This is AGP 9's replacement
    // for the removed `resConfigs`/`resourceConfigurations` DSL.
    androidResources {
        localeFilters += "en"
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
        // A debug build is a SIDE-BY-SIDE testing build, not a replacement for
        // the tagged install. Its own applicationId lets both APKs be installed
        // at once (Android keeps one install per package), which is also what
        // makes the debug signing key harmless to the release install; its own
        // search authority keeps the two providers from colliding; and its own
        // label distinguishes the launcher entries. Its data directory, profiles
        // and sign-in are its own - the two installs share nothing.
        debug {
            applicationIdSuffix = ".debug"
            versionNameSuffix = "-debug"
            resValue("string", "app_name", "KBStream Debug")
            resValue(
                "string",
                "search_suggest_authority",
                "com.kennyb1201.kbstream.debug.search"
            )
        }
        release {
            // A release built without the KBSTREAM_* variables is UNSIGNED, and
            // AGP says nothing about it: the task still succeeds, the output is
            // `app-release-unsigned.apk`, and a developer who then installs or
            // hands out that file finds out at the worst possible moment. The
            // warning is per CONFIGURATION and only when a release task is
            // actually being asked for, so a debug build stays quiet; CI always
            // passes the variables, so this never fires there.
            if (!releaseSigningConfigured &&
                gradle.startParameter.taskNames.any { it.contains("release", ignoreCase = true) }
            ) {
                logger.warn(
                    "KBSTREAM: this build asks for a release variant with no " +
                        "KBSTREAM_STORE_FILE / _STORE_PASSWORD / _KEY_ALIAS / " +
                        "_KEY_PASSWORD in the environment, so the APK will be " +
                        "UNSIGNED (app-release-unsigned.apk) and cannot be " +
                        "installed over a signed one. " +
                        "See README > Baseline profile for the debug-keystore " +
                        "values, or export the real keystore's."
                )
            }
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro"
            )
            signingConfig = signingConfigs.getByName("release")
            // Release ships to ARM televisions. The merged native set carries
            // FOUR ABIs - libmpv's FFmpeg stack and sentry-native each bring
            // their own - of which two are real targets: arm64-v8a on newer
            // boxes, armeabi-v7a on the older/Fire TV sticks this is built
            // for. The other two, x86 and x86_64, are emulator-only and are
            // pure dead weight in an APK no emulator will install.
            //
            // AGP applies abiFilters at PACKAGING, not at merge or strip (both
            // intermediates still list every ABI); confirmed by building a
            // debug APK with a filter and finding only that ABI's lib/ dir in
            // it. Debug is deliberately left unfiltered so an x86_64 emulator
            // can still run the app.
            ndk {
                abiFilters += listOf("arm64-v8a", "armeabi-v7a")
            }
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
        // MpvBackend's own directory (see the MPV library note above this
        // `android {}`): kept outside src/main so the seam file lives with the
        // library it wraps. `kotlin { srcDir }` rather than `java`, because that
        // is where AGP's built-in Kotlin looks for a source set's Kotlin files.
        getByName("main") { kotlin { srcDir("$projectDir/$mpvEngineSourceDir") } }

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
    implementation(libs.androidx.core.ktx)
    // EncryptedSharedPreferences backs the auth-token stores (Supabase session,
    // Simkl token, MDBList key). The key is held in the AndroidKeyStore and
    // never leaves the device, so a rooted box or a pulled prefs file yields
    // ciphertext instead of a live session that can be replayed off-device.
    implementation(libs.androidx.security.crypto)
    implementation(libs.androidx.activity.compose)
    implementation(libs.androidx.lifecycle.viewmodel.compose)
    implementation(libs.androidx.lifecycle.runtime.compose)
    // Paging 3, Compose half. The "Open in Grid" full-catalog screen pages a
    // single addon catalog by item offset, which is exactly what a PagingSource
    // models: the source owns the offset bookkeeping, de-dupe and end-of-list
    // detection, and the screen reads one LazyPagingItems instead of a hand-
    // rolled items/isLoadingMore/hasMore/error state machine. paging-compose
    // brings paging-common (Pager/PagingConfig/PagingSource); paging-runtime is
    // the LiveData/RxJava half and is not needed here.
    implementation(libs.androidx.paging.compose)

    implementation(libs.androidx.compose.runtime)

    implementation(libs.androidx.tv.material)
    // Still declared even though nothing imports androidx.tv.foundation: no
    // 1.1.0 exists to bump it to, and tv-material 1.1.0's POM dropped its
    // transitive dependence on it, so removing this line would take the
    // artifact off the classpath entirely.
    implementation(libs.androidx.tv.foundation)
    // Compose 1.12.1 / material3 1.4.0. This BOM sat at 2024.09.03 (Compose
    // 1.7) for as long as it did because nothing newer fit under AGP 8.6.0 +
    // compileSdk 35 - see the ceiling note in .github/dependabot.yml - and
    // because the Compose compiler used to move with it: on Kotlin 2.x the
    // compiler ships WITH the Kotlin version (2.4.20 here), so a BOM bump is a
    // version bump and not a compiler migration. tv-material 1.1.0 needs at
    // least 1.10.3, which is what pins the floor of this line.
    implementation(platform(libs.androidx.compose.bom))
    implementation(libs.androidx.compose.ui.base)
    implementation(libs.androidx.compose.material3)
    implementation(libs.androidx.compose.material.icons.extended)
    // Retrofit 3 is the line that pairs with OkHttp 5 (Retrofit 2.11 pins
    // OkHttp 4), and both stay in the retrofit2/okhttp3 packages, so this is
    // a version bump rather than an import rewrite. They move together:
    // bumping OkHttp alone would leave Retrofit compiled against OkHttp 4.
    implementation(libs.retrofit.core)
    implementation(libs.retrofit.converter.moshi)
    implementation(libs.okhttp.core)
    implementation(libs.okhttp.logging.interceptor)
    // Transparent Brotli (Content-Encoding: br) decoding, installed on the
    // shared base client (see BaseHttpClient). Addon catalogs, TMDB and the
    // tracker APIs are all JSON over HTTP, and Brotli shrinks those payloads
    // ~20% against gzip - on a 1.7 GB TV box the win is mostly in the JSON
    // parse and the time-to-first-rail, not just bytes.
    implementation(libs.okhttp.brotli)
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
    ksp(libs.moshi.codegen)
    // The reflective half, kept as the FALLBACK for a type with no annotation.
    // Every Moshi.Builder below adds it with addLast() rather than add():
    // Moshi consults factories in order, so adding it first would let
    // reflection claim every model before the generated adapter is ever
    // looked up, and this dependency would be dead weight.
    implementation(libs.moshi.kotlin)
    implementation(libs.zxing.core)

    // Baseline profiles, runtime half. The profile itself (a list of the
    // classes/methods startup touches) is captured by :baselineprofile on a
    // device and committed under app/src/release/generated/baselineProfiles/;
    // this dependency is what INSTALLS it. Android 12+ compiles a profile at
    // install time on its own, but an app that installs itself from a GitHub
    // release - which is how this one ships, see AppUpdater - is never
    // installed by Play, so on the API 23-30 boxes it also runs on, nothing
    // would apply the profile without this. Small, and it does nothing when no
    // profile is present.
    implementation(libs.androidx.profileinstaller)

    // Crash reporting (Sentry)
    // Sentry Android 8.x. The Gradle plugin stays at 6.23.0: it is versioned
    // independently of the SDK and 6.23.0 is the current release, so the SDK
    // major is the only thing that moves. 8.x rejects nothing the reporter
    // uses (SentryAndroid.init, dsn/release/dist/setTag, beforeSend,
    // beforeBreadcrumb, Sentry.captureException), so this is a version bump.
    implementation(libs.sentry.android)

    // Supabase (cross-device sync): auth + Postgres REST + realtime channels.
    //
    // supabase-kt 3.x renamed the auth module: gotrue-kt is no longer
    // published, and the package moved from io.github.jan.supabase.gotrue to
    // io.github.jan.supabase.auth (SessionStatus moved again, to
    // ...auth.status). 3.x also requires Ktor 3, so the engine below moved
    // with it.
    implementation(platform(libs.supabase.bom))
    implementation(libs.supabase.auth)
    implementation(libs.supabase.postgrest)
    implementation(libs.supabase.realtime)
    // MUST be the OkHttp engine, not ktor-client-android. Realtime needs a
    // WebSocket-capable Ktor engine; the Android engine is HttpURLConnection
    // based and exposes NO WebSocketCapability, so every channel join dies
    // with "Engine doesn't support WebSocketCapability" and the SDK retries
    // forever. That left live sync dead AND made the 15s realtime health
    // watcher rebuild three dead channels + run a full pullAll on every
    // cycle, i.e. permanent background network/DB churn during playback.
    // OkHttp is already a dependency here (REST datasource, Coil).
    implementation(libs.ktor.client.okhttp)
    // 1.11.0, the current stable. It started as the floor Ktor 3 / supabase-kt
    // 3 set when they moved to Kotlin 2.x (leaving 1.7.3 here forced a
    // downgrade of the runtime they are built against) and it matches the
    // Kotlin 2.4.20 serialization plugin compiling against it: the sync
    // payload, outbox queue, profile and hidden-title models all serialize
    // through this runtime.
    implementation(libs.kotlinx.serialization.json)

    implementation(libs.media3.exoplayer.core)
    implementation(libs.media3.exoplayer.hls)
    implementation(libs.media3.exoplayer.dash)
    implementation(libs.media3.exoplayer.rtsp)
    // Microsoft Smooth Streaming (MSS), the Silverlight-era adaptive format:
    // ".../stream.ism/Manifest", still served by IIS Media Services and some
    // CDNs. Small module, and Media3's DefaultMediaSourceFactory picks it up by
    // class name (SsMediaSource$Factory) once it is on the classpath - see
    // resolveMimeType in NativePlayerActivity, which is what puts the mime on
    // the MediaItem for a URL that has no extension to key off.
    implementation(libs.media3.exoplayer.smoothstreaming)
    implementation(libs.media3.ui)
    implementation(libs.media3.session)
    implementation(libs.media3.datasource.okhttp)
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
    // renders it with libass). It bundles its own FFmpeg + mpv + libass +
    // libplayer as native libraries for all four ABIs, so it needs no other
    // dependency and no app-side extraction.
    //
    // The backend is the fortunasxp/mpv-android-libdovi build: the mpv-android
    // library with a Dolby Vision-capable libplacebo and libdovi statically
    // linked, on `vo=gpu-next`. The library it replaced
    // (Riteshp2001/mpvlibAndroid) could not decode DV at all - no `-Ddovi` in
    // its libplacebo - so this is a capability change, not just a rebuild. It
    // reuses the `io.github.abdallahmehiz:mpv-android-lib` shell and therefore
    // exposes `is.xyz.mpv.MPV` as a class; `src/mpvrites/.../MpvBackend.kt` is
    // the only file that names it.
    //
    // It is not on Maven Central: it is a release AAR, consumed as a file
    // dependency. A ~155 MB binary is deliberately not vendored in git
    // (app/libs/ is gitignored, like libs/); instead scripts/fetch_mpv_aar.sh
    // downloads mpv-android-lib-libdovi.aar from the `mpv-libdovi-v1` GitHub
    // release, verifies it against a digest pinned in the script, and checks
    // every 64-bit .so it carries for 16 KB page alignment - and both CI
    // workflows run that script before building. The release workflow's
    // APK-level check re-verifies the packaged libraries on every release.
    //
    // Note ALSO that the AAR declares minSdk 21, below the app's floor of 26, so
    // the manifest needs no tools:overrideLibrary.
    //
    val mpvAar = file("libs/mpv-android-lib-libdovi.aar")
    if (!mpvAar.isFile) {
        throw GradleException(
            "The MPV engine AAR is missing at ${mpvAar.path}. Run " +
                "scripts/fetch_mpv_aar.sh (it downloads " +
                "mpv-android-lib-libdovi.aar and verifies its sha256), or drop " +
                "the file there from a previous build. The app cannot compile " +
                "without an MPV library: src/mpvrites/.../MpvBackend.kt imports " +
                "is.xyz.mpv.MPV from this AAR."
        )
    }
    implementation(files(mpvAar))
    implementation(libs.androidx.recyclerview)
    // Pinned to the COMMIT behind tag v0.26.4 (43f8e6ebeef4…, full hash
    // 43f8e6ebeef469db7c5328714bc5f33c9f06f092), not the tag itself: tags are
    // mutable, so "v0.26.4" can silently resolve to different code than the
    // build that was reviewed and shipped. JitPack resolves the commit prefix;
    // NewPipeExtractor is GPLv3, see THIRD_PARTY_NOTICES.md.
    implementation(libs.newpipe.extractor)


    implementation(libs.androidx.room.runtime)
    ksp(libs.androidx.room.compiler)
    implementation(libs.androidx.room.ktx)

    // 2.12.0 declares minSdk 24, which the app's floor (26) clears; below 24
    // the manifest merger rejects the whole build ("uses-sdk: minSdkVersion 23
    // cannot be smaller than version 24 declared in library
    // [androidx.work:work-runtime-ktx:2.12.0]").
    implementation(libs.androidx.work.runtime.ktx)

    implementation(libs.coil.compose)
    implementation(libs.coil.core)
    implementation(libs.coil.network.okhttp)
    // SVG badge art: several popular KB-compatible badge packs serve chips
    // as .svg, which base Coil cannot decode — without this those badges
    // render blank.
    implementation(libs.coil.svg)
    // Animated focus GIFs on KB collection folder tiles (manifest
    // focusGifUrl / focusGifEnabled); base Coil shows only the first frame.
    implementation(libs.coil.gif)

    coreLibraryDesugaring(libs.desugar.jdk.libs.nio)

    // JVM unit tests (KidsMode rating matrix, catalog invariants).
    testImplementation(libs.junit)
    // The real org.json, not android.jar's stub. isReturnDefaultValues makes the
    // stub return nulls, so the OpenSubtitles tests would assert the failure
    // path of every response instead of the parse they are there for. Test
    // scope only: the app itself still uses the platform's org.json.
    testImplementation(libs.json)

    // Room migration tests (see data/history): MigrationTestHelper drives a
    // real SQLite database through a Migration and validates the result
    // against the exported schema - the one class of test the JVM-only suite
    // could not reach, and the gap that let the watch-history schema sit one
    // version bump away from being dropped unnoticed.
    testImplementation(libs.androidx.room.testing)
    // ...and Robolectric is what supplies the Android runtime room-testing and
    // MigrationTestHelper's instrumentation need, so it stays a UNIT test: no
    // device, no emulator, no androidTest variant, run by the same
    // `./gradlew testDebugUnitTest` gate as everything else.
    // Robolectric picks the SDK it emulates from the app's target SDK (35), not
    // from compileSdk (37), so it needs a release that ships an android-all jar
    // for 35 - 4.14 was the floor for that, and 4.17 is the current line.
    testImplementation(libs.robolectric)
    testImplementation(libs.androidx.test.ext.junit)
    testImplementation(libs.androidx.test.core.ktx)

    debugImplementation(libs.androidx.compose.ui.tooling.debug)

    // LeakCanary, debug builds only. The two player activities are 3k-10k lines
    // with hand-rolled coroutine scopes and engine handoffs - the exact shape
    // that leaks - and it catches what a JVM unit-test suite structurally
    // cannot (a retained Activity/Context once the screen is gone). It
    // auto-installs from its own provider, so no app code changes, and being
    // debugImplementation means nothing ships in release.
    debugImplementation(libs.leakcanary.android)
}

// The Play-build manifest overlay (see `playBuild` above). Added last, so it
// has the highest priority in manifest merging: the `tools:node="remove"`
// lines in it drop REQUEST_INSTALL_PACKAGES from the merged manifest for the
// Play build only. Without `-PplayBuild` this block adds nothing and the
// release manifest is unchanged.
androidComponents {
    onVariants { variant ->
        if (playBuild) {
            variant.sources.manifests.addStaticManifestFile(
                "src/play/AndroidManifest.xml"
            )
        }
    }
}
