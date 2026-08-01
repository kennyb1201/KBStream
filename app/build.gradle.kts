import java.util.Properties

plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
    id("com.google.devtools.ksp")
    id("org.jetbrains.kotlin.plugin.compose")
}

val localProps = Properties()
val localPropsFile = rootProject.file("local.properties")
if (localPropsFile.exists()) localProps.load(localPropsFile.inputStream())

buildConfigField("String", "TMDB_API_KEY", ""$tmdbApiKey"")
buildConfigField("String", "SIMKL_CLIENT_ID", ""$simklClientId"")
buildConfigField("String", "SIMKL_CLIENT_SECRET", ""$simklClientSecret"")

android {
    namespace = "com.kennyb1201.kbstream"
    compileSdk = 35

    defaultConfig {
        applicationId = "com.kennyb1201.kbstream"
        minSdk = 23
        targetSdk = 35
        versionCode = 1
        versionName = "0.1.0"

        buildConfigField("String", "TMDB_API_KEY", ""$tmdbApiKey"")
        buildConfigField("String", "SIMKL_CLIENT_ID", ""$simklClientId"")
        buildConfigField("String", "SIMKL_CLIENT_SECRET", ""$simklClientSecret"")
    }

    buildFeatures {
        compose = true
        buildConfig = true
    }

    buildTypes {
        release {
            isMinifyEnabled = false
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
