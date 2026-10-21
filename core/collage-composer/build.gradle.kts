plugins {
    alias(libs.plugins.android.library)
}

android {
    namespace = "com.akane.akastudio.core.composer"
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
        }
    }
    // The androidTest APK bundles :core:vision-curator's models, whose noCompress does not carry over;
    // keep them stored so MediaPipe/LiteRT can mmap them in instrumented tests.
    androidResources {
        noCompress += listOf("tflite", "task")
    }
}

dependencies {
    api(project(":core:color-engine"))
    api(project(":core:vision-curator"))
    api(project(":core:ingestion"))

    implementation(libs.kotlinx.coroutines.core)
    implementation(libs.kotlinx.coroutines.android)

    testImplementation(libs.junit)
    testImplementation(libs.kotlinx.coroutines.test)

    androidTestImplementation(libs.bundles.androidx.test)
}
