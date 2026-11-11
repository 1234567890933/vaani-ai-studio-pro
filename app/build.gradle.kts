plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
    id("org.jetbrains.kotlin.plugin.compose")
}

android {
    namespace = "ai.hindidub"
    compileSdk = 35
    ndkVersion = "27.0.12077973"

    defaultConfig {
        applicationId = "ai.hindidub"
        minSdk = 26
        targetSdk = 35
        versionCode = 1
        versionName = "1.0.0"

        ndk {
            // arm64 only: every Dimensity 6000-series device is arm64.
            // Add "armeabi-v7a" only if you really need 32-bit support.
            abiFilters += listOf("arm64-v8a")
        }

        externalNativeBuild {
            cmake {
                cppFlags += listOf("-O3", "-ffast-math", "-fexceptions", "-frtti")
                arguments += listOf(
                    "-DANDROID_STL=c++_shared",
                    "-DGGML_NATIVE=OFF",
                    "-DGGML_OPENMP=OFF",
                    "-DWHISPER_BUILD_EXAMPLES=OFF",
                    "-DWHISPER_BUILD_TESTS=OFF",
                    "-DWHISPER_BUILD_SERVER=OFF",
                    "-DSPM_ENABLE_SHARED=OFF",
                    "-DSPM_ENABLE_TCMALLOC=OFF",
                    "-DSP_BUILD_TEST=OFF",
                    "-DSP_BUILD_BENCHMARK=OFF"
                )
            }
        }
    }

    // Optional release signing. Create keystore.properties with
    //   storeFile=... storePassword=... keyAlias=... keyPassword=...
    // and uncomment this block plus the signingConfig line below.
    /*
    signingConfigs {
        create("release") {
            val props = java.util.Properties().apply {
                load(rootProject.file("keystore.properties").inputStream())
            }
            storeFile = rootProject.file(props["storeFile"] as String)
            storePassword = props["storePassword"] as String
            keyAlias = props["keyAlias"] as String
            keyPassword = props["keyPassword"] as String
        }
    }
    */

    buildTypes {
        release {
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro"
            )
            // signingConfig = signingConfigs.getByName("release")
        }
        debug {
            isMinifyEnabled = false
            isJniDebuggable = true
        }
    }

    externalNativeBuild {
        cmake {
            path = file("src/main/cpp/CMakeLists.txt")
            version = "3.22.1"
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
    }

    packaging {
        jniLibs {
            // Required so the JNI libs are extracted and loadable on API 26-29 devices.
            useLegacyPackaging = true
        }
        resources {
            excludes += setOf(
                "META-INF/AL2.0", "META-INF/LGPL2.1",
                "META-INF/*.kotlin_module", "META-INF/DEPENDENCIES"
            )
        }
        jniLibs.pickFirsts += listOf("**/libc++_shared.so")
    }
}

dependencies {
    val composeBom = platform("androidx.compose:compose-bom:2024.12.01")
    implementation(composeBom)
    androidTestImplementation(composeBom)

    implementation("androidx.core:core-ktx:1.15.0")
    implementation("androidx.activity:activity-compose:1.9.3")
    implementation("androidx.lifecycle:lifecycle-runtime-compose:2.8.7")
    implementation("androidx.compose.ui:ui")
    implementation("androidx.compose.ui:ui-tooling-preview")
    implementation("androidx.compose.material3:material3")
    debugImplementation("androidx.compose.ui:ui-tooling")

    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.9.0")

    // Housekeeping only (cache cleanup); the dubbing job itself is a foreground service.
    implementation("androidx.work:work-runtime-ktx:2.9.1")

    // ONNX Runtime for the translation model (CPU + optional NNAPI).
    // Vendored AAR (vendor/onnxruntime) — no network needed to build.
    // To resolve from Maven instead, replace this line with:
    //   implementation("com.microsoft.onnxruntime:onnxruntime-android:1.20.0")
    // 1.22.0: its prebuilt libonnxruntime4j_jni.so is 16 KB page aligned, which 1.20.0 is
    // not — that was the last non-aligned library in the APK.
    implementation("com.microsoft.onnxruntime:onnxruntime-android:1.22.0@aar")
}
