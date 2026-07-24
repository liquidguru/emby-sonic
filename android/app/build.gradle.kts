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

// Set when a release build had to fall back to the debug signing config; drives
// the warning banner wired to assembleRelease/bundleRelease at the bottom.
var releaseIsDebugSigned = false

android {
    namespace = "guru.liquid.embysonic"
    compileSdk = 36
    buildToolsVersion = "36.1.0"

    defaultConfig {
        applicationId = "guru.liquid.embysonic"
        minSdk = 26
        targetSdk = 36
        versionCode = 27
        versionName = "0.1.0-beta.27"
        vectorDrawables { useSupportLibrary = true }
    }

    signingConfigs {
        create("release") {
            val storeFilePath = releaseSigningValue("LIQUIDWAVE_RELEASE_STORE_FILE")
            if (!storeFilePath.isNullOrBlank()) {
                val resolvedStore = file(storeFilePath)
                if (!resolvedStore.exists()) {
                    throw GradleException(
                        "LIQUIDWAVE_RELEASE_STORE_FILE points at $resolvedStore, which does " +
                            "not exist. Fix the path in ~/.gradle/gradle.properties.",
                    )
                }
                // A half-configured release key is worse than none: it would fail
                // deep inside the signing task, or silently fall through.
                val missing = listOf(
                    "LIQUIDWAVE_RELEASE_STORE_PASSWORD",
                    "LIQUIDWAVE_RELEASE_KEY_ALIAS",
                    "LIQUIDWAVE_RELEASE_KEY_PASSWORD",
                ).filter { releaseSigningValue(it).isNullOrBlank() }
                if (missing.isNotEmpty()) {
                    throw GradleException(
                        "LIQUIDWAVE_RELEASE_STORE_FILE is set but ${missing.joinToString(", ")} " +
                            "${if (missing.size == 1) "is" else "are"} missing. Set all four in " +
                            "~/.gradle/gradle.properties, or none of them for a debug-signed build.",
                    )
                }
                storeFile = resolvedStore
                storePassword = releaseSigningValue("LIQUIDWAVE_RELEASE_STORE_PASSWORD")
                keyAlias = releaseSigningValue("LIQUIDWAVE_RELEASE_KEY_ALIAS")
                keyPassword = releaseSigningValue("LIQUIDWAVE_RELEASE_KEY_PASSWORD")
            }
        }
    }

    buildTypes {
        debug {
            applicationIdSuffix = ".debug"
            versionNameSuffix = "-debug"
        }
        release {
            isMinifyEnabled = true
            isShrinkResources = true
            // Use the release key when the signing secrets are present (storeFile
            // gets set above only then); otherwise fall back to the debug signing
            // config so `assembleRelease` still produces an installable APK for
            // contributors without the keystore. The fallback is deliberate but
            // must never be silent — it shipped 14 debug-signed betas before
            // anyone noticed — so it sets releaseIsDebugSigned, which prints a
            // banner at the end of the build.
            val releaseKey = signingConfigs.getByName("release")
            releaseIsDebugSigned = releaseKey.storeFile == null
            signingConfig = releaseKey.takeIf { it.storeFile != null }
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

// Printed at the end of a release build that fell back to the debug key, so it
// lands next to BUILD SUCCESSFUL rather than scrolling past in the noise.
tasks.matching { it.name == "assembleRelease" || it.name == "bundleRelease" }.configureEach {
    // Read into locals here: a doLast lambda that touches script properties
    // captures the script object, which the configuration cache cannot
    // serialize (it fails the build outright).
    val debugSigned = releaseIsDebugSigned
    val banner =
        """
                |
                |========================================================================
                |  WARNING: this release build is signed with the ANDROID DEBUG KEY.
                |
                |  LIQUIDWAVE_RELEASE_STORE_FILE is not set, so signing fell back to the
                |  debug config. The debug key is shared by every Android SDK install
                |  and its password is public: the signature identifies nobody.
                |
                |  DO NOT DISTRIBUTE THIS BUILD. To sign properly, set the four
                |  LIQUIDWAVE_RELEASE_* properties in ~/.gradle/gradle.properties
                |  (see README -> Release build).
                |
                |  Verify any build before shipping it:
                |    apksigner verify --print-certs <apk>
                |========================================================================
                |
        """.trimMargin()
    doLast {
        if (debugSigned) {
            logger.warn(banner)
        }
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
