import com.android.build.api.artifact.SingleArtifact
import javax.xml.parsers.DocumentBuilderFactory
import org.w3c.dom.Element

plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.compose)
    alias(libs.plugins.compose.screenshot)
}

/**
 * Fails the build when the merged manifest requests INTERNET. AkaStudio is on-device only. INTERNET arrives only through
 * transitive dependencies (MediaPipe's data-transport CCT backend), and the app manifest strips it.
 */
abstract class CheckNoInternetPermission : DefaultTask() {
    @get:InputFile
    @get:PathSensitive(PathSensitivity.NONE)
    abstract val mergedManifest: RegularFileProperty

    @TaskAction
    fun check() {
        val file = mergedManifest.get().asFile
        val document = DocumentBuilderFactory.newInstance().apply { isNamespaceAware = true }
            .newDocumentBuilder().parse(file)
        val requested = listOf("uses-permission", "uses-permission-sdk-23").flatMap { tag ->
            val nodes = document.getElementsByTagName(tag)
            (0 until nodes.length).map {
                (nodes.item(it) as Element).getAttributeNS("http://schemas.android.com/apk/res/android", "name")
            }
        }
        if ("android.permission.INTERNET" in requested) {
            throw GradleException(
                "$file requests android.permission.INTERNET. AkaStudio must stay offline: remove it in " +
                    "app/src/main/AndroidManifest.xml with tools:node=\"remove\" and find the dependency that added it " +
                    "(app/build/outputs/logs/manifest-merger-*-report.txt).",
            )
        }
    }
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
            // supportedAbis includes x86_64; lint only reads literal ABI strings, so it can't see that through the val.
            //noinspection ChromeOsAbiSupport
            ndk { abiFilters += supportedAbis }
        }
    }

    splits {
        abi {
            isEnable = abiSplitApks
            reset()
            //noinspection ChromeOsAbiSupport (supportedAbis includes x86_64, see defaultConfig)
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

// `check` runs the offline guard for every variant, and a release APK or bundle can't be built without passing it.
androidComponents {
    onVariants { variant ->
        val variantName = variant.name.replaceFirstChar(Char::uppercaseChar)
        val guard = tasks.register<CheckNoInternetPermission>("check${variantName}NoInternetPermission") {
            group = "verification"
            description = "Fails if the ${variant.name} merged manifest requests INTERNET."
            mergedManifest.set(variant.artifacts.get(SingleArtifact.MERGED_MANIFEST))
        }
        tasks.named("check") { dependsOn(guard) }
        if (variant.buildType == "release") {
            tasks.named { it == "assemble$variantName" || it == "bundle$variantName" }
                .configureEach { dependsOn(guard) }
        }
    }
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
    androidTestImplementation(libs.androidx.test.espresso.core)
    debugImplementation(libs.androidx.compose.ui.test.manifest)

    screenshotTestImplementation(libs.screenshot.validation.api)
    screenshotTestImplementation(libs.androidx.compose.ui.tooling)
}
