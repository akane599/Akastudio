plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.compose)
    alias(libs.plugins.compose.screenshot)
}

// 32-bit x86 has no API 31+ devices or system images, and LiteRT ships no x86 .so, so it is dropped.
val supportedAbis = listOf("arm64-v8a", "armeabi-v7a", "x86_64")

// Opt-in side-load fallback: `assembleRelease -Pakastudio.abiSplits=true` emits one APK per ABI instead of the
// universal APK. Leave it off for `bundleRelease`, which splits by ABI on its own.
val abiSplitApks = providers.gradleProperty("akastudio.abiSplits").map(String::toBoolean).getOrElse(false)

android {
    namespace = "com.akane.akastudio"
    compileSdk = 37

    defaultConfig {
        applicationId = "com.akane.akastudio"
        minSdk = 31
        targetSdk = 37
        versionCode = 1
        versionName = "0.1.0"
        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
        // AGP rejects ndk.abiFilters alongside splits.abi, so the split include list takes over when splits are on.
        if (!abiSplitApks) {
            ndk { abiFilters += supportedAbis }
        }
    }

    splits {
        abi {
            isEnable = abiSplitApks
            reset()
            include(*supportedAbis.toTypedArray())
            isUniversalApk = false
        }
    }

    // Release ships as an App Bundle (`bundleRelease`): Play serves each device only its own ABI's native libraries.
    bundle {
        abi { enableSplit = true }
    }

    buildTypes {
        release {
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
        }
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    buildFeatures {
        compose = true
    }
    // Library-level noCompress does not reach the app APK; keep ML models stored so MediaPipe/LiteRT can mmap them.
    androidResources {
        noCompress += listOf("tflite", "task")
    }
    experimentalProperties["android.experimental.enableScreenshotTest"] = true
}

dependencies {
    implementation(project(":core:ingestion"))
    implementation(project(":feature:studio"))
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.activity.compose)
    implementation(libs.androidx.lifecycle.runtime.compose)
    implementation(libs.androidx.lifecycle.viewmodel.compose)
    implementation(platform(libs.androidx.compose.bom))
    implementation(libs.androidx.compose.ui)
    implementation(libs.androidx.compose.ui.graphics)
    implementation(libs.androidx.compose.ui.tooling.preview)
    implementation(libs.androidx.compose.material3)
    implementation(libs.kotlinx.collections.immutable)
    debugImplementation(libs.androidx.compose.ui.tooling)

    testImplementation(libs.junit)
    testImplementation(libs.kotlinx.coroutines.test)

    androidTestImplementation(libs.bundles.androidx.test)
    androidTestImplementation(platform(libs.androidx.compose.bom))
    androidTestImplementation(libs.androidx.compose.ui.test.junit4)
    debugImplementation(libs.androidx.compose.ui.test.manifest)

    screenshotTestImplementation(libs.screenshot.validation.api)
    screenshotTestImplementation(libs.androidx.compose.ui.tooling)
}
