plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.android)
    alias(libs.plugins.kotlin.compose)
    alias(libs.plugins.kotlin.serialization)
    alias(libs.plugins.ksp)
    alias(libs.plugins.hilt)
}

fun releaseSigningValue(name: String): String? =
    providers.gradleProperty(name).orElse(providers.environmentVariable(name)).orNull

android {
    namespace = "guru.liquid.embysonic"
    compileSdk = 36
    buildToolsVersion = "36.1.0"

    defaultConfig {
        applicationId = "guru.liquid.embysonic"
        minSdk = 26
        targetSdk = 36
        versionCode = 6
        versionName = "0.1.0-beta.7"
        vectorDrawables { useSupportLibrary = true }
    }

    signingConfigs {
        create("release") {
            val storeFilePath = releaseSigningValue("LIQUIDWAVE_RELEASE_STORE_FILE")
            if (!storeFilePath.isNullOrBlank()) {
                storeFile = file(storeFilePath)
                storePassword = releaseSigningValue("LIQUIDWAVE_RELEASE_STORE_PASSWORD")
                keyAlias = releaseSigningValue("LIQUIDWAVE_RELEASE_KEY_ALIAS")
                keyPassword = releaseSigningValue("LIQUIDWAVE_RELEASE_KEY_PASSWORD")
            }
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = true
            isShrinkResources = true
            // Use the release key when the signing secrets are present
            // (storeFile gets set above only then); otherwise fall back to the
            // debug signing config so `assembleRelease` still produces an
            // installable, signed APK for contributors/CI without the keystore —
            // rather than failing on a half-configured release SigningConfig or
            // emitting an unsigned APK.
            signingConfig = signingConfigs.getByName("release")
                .takeIf { it.storeFile != null }
                ?: signingConfigs.getByName("debug")
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro",
            )
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions {
        jvmTarget = "17"
    }
    buildFeatures {
        compose = true
        buildConfig = true
    }
}

dependencies {
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.appcompat)
    implementation(libs.androidx.lifecycle.runtime.ktx)
    implementation(libs.androidx.lifecycle.runtime.compose)
    implementation(libs.androidx.lifecycle.viewmodel.compose)
    implementation(libs.androidx.activity.compose)

    implementation(platform(libs.androidx.compose.bom))
    implementation(libs.androidx.ui)
    implementation(libs.androidx.ui.graphics)
    implementation(libs.androidx.ui.tooling.preview)
    implementation(libs.androidx.material3)
    implementation(libs.androidx.material.icons.extended)
    debugImplementation(libs.androidx.ui.tooling)

    implementation(libs.androidx.navigation.compose)
    implementation(libs.androidx.datastore.preferences)

    implementation(libs.hilt.android)
    ksp(libs.hilt.compiler)
    implementation(libs.hilt.navigation.compose)

    implementation(libs.retrofit)
    implementation(libs.retrofit.kotlinx.serialization)
    implementation(libs.okhttp)
    implementation(libs.okhttp.logging)
    implementation(libs.kotlinx.serialization.json)

    implementation(libs.coil.compose)

    // Wired in M3 (playback); declared now so the module graph is stable.
    implementation(libs.media3.exoplayer)
    implementation(libs.media3.session)
    implementation(libs.media3.ui)

    // Casting (Google Cast / Material You receiver). media3-cast is for the
    // Phase 1 CastPlayer; the framework provides the Cast button + sessions.
    implementation(libs.media3.cast)
    implementation(libs.play.services.cast.framework)
}
