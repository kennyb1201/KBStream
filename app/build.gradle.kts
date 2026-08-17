import java.util.Properties

plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
    id("com.google.devtools.ksp")
    id("org.jetbrains.kotlin.plugin.compose")
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

val simklClientSecret = localProps.getProperty("SIMKL_CLIENT_SECRET")
    ?: System.getenv("SIMKL_CLIENT_SECRET")
    ?: ""

val releaseStoreFile = System.getenv("KBSTREAM_STORE_FILE")
val releaseStorePassword = System.getenv("KBSTREAM_STORE_PASSWORD")
val releaseKeyAlias = System.getenv("KBSTREAM_KEY_ALIAS")
val releaseKeyPassword = System.getenv("KBSTREAM_KEY_PASSWORD")

android {
    namespace = "com.kennyb1201.kbstream"
    compileSdk = 35

    defaultConfig {
        applicationId = "com.kennyb1201.kbstream"
        minSdk = 23
        targetSdk = 35
        versionCode = 1
        versionName = "0.1.0"

        buildConfigField("String", "TMDB_API_KEY", "\"$tmdbApiKey\"")
        buildConfigField("String", "SIMKL_CLIENT_ID", "\"$simklClientId\"")
        buildConfigField("String", "SIMKL_CLIENT_SECRET", "\"$simklClientSecret\"")
    }
    buildFeatures {
        compose = true
        buildConfig = true
    }

    signingConfigs {
        create("release") {
            if (
                !releaseStoreFile.isNullOrBlank() &&
                !releaseStorePassword.isNullOrBlank() &&
                !releaseKeyAlias.isNullOrBlank() &&
                !releaseKeyPassword.isNullOrBlank()
            ) {
                storeFile = file(releaseStoreFile)
                storePassword = releaseStorePassword
                keyAlias = releaseKeyAlias
                keyPassword = releaseKeyPassword
            }
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = false
            signingConfig = signingConfigs.getByName("release")
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    kotlinOptions {
        jvmTarget = "17"
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
    implementation(platform("androidx.compose:compose-bom:2024.06.00"))
    implementation("androidx.compose.ui:ui")
    implementation("androidx.compose.ui:ui-tooling-preview")
    implementation("androidx.compose.material3:material3")
    implementation("androidx.compose.material:material-icons-extended")
    implementation("com.squareup.retrofit2:retrofit:2.11.0")
    implementation("com.squareup.retrofit2:converter-moshi:2.11.0")
    implementation("com.squareup.okhttp3:okhttp:4.12.0")
    implementation("com.squareup.okhttp3:logging-interceptor:4.12.0")
    implementation("com.squareup.moshi:moshi-kotlin:1.15.1")

    implementation("androidx.media3:media3-exoplayer:1.4.1")
    implementation("androidx.media3:media3-exoplayer-hls:1.4.1")
    implementation("androidx.media3:media3-ui:1.4.1")

    implementation("androidx.room:room-runtime:2.7.1")
    ksp("androidx.room:room-compiler:2.7.1")
    implementation("androidx.room:room-ktx:2.7.1")

    implementation("androidx.work:work-runtime-ktx:2.10.1")

    implementation("io.coil-kt.coil3:coil-compose:3.0.0")
    implementation("io.coil-kt.coil3:coil-network-okhttp:3.0.0")

    debugImplementation("androidx.compose.ui:ui-tooling")
}
