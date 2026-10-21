plugins {
    alias(libs.plugins.android.library)
}

android {
    namespace = "com.akane.akastudio.core.curator"
    compileSdk = 37

    defaultConfig {
        minSdk = 31
        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
        consumerProguardFiles("consumer-rules.pro")
    }

    buildTypes {
        release {
            isMinifyEnabled = false
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
        }
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    testOptions {
        unitTests {
            isReturnDefaultValues = true
            all { test ->
                // ModelAssetsTest re-hashes the committed model files straight from the source tree.
                test.systemProperty("akastudio.modelsDir", file("src/main/assets/models").absolutePath)
            }
        }
    }
    androidResources {
        // MediaPipe and LiteRT memory-map models from the APK, so they must be stored, not deflated.
        noCompress += listOf("tflite", "task")
    }
}

dependencies {
    api(project(":core:ingestion"))
    api(project(":core:color-engine"))

    implementation(libs.kotlinx.coroutines.core)
    implementation(libs.kotlinx.coroutines.android)
    implementation(libs.mediapipe.tasks.vision)
    implementation(libs.litert)

    testImplementation(libs.junit)
    testImplementation(libs.kotlinx.coroutines.test)

    androidTestImplementation(libs.bundles.androidx.test)
}
