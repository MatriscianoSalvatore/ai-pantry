plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.android)
    alias(libs.plugins.kotlin.compose)
    alias(libs.plugins.kotlin.serialization)
    alias(libs.plugins.ksp)
}

android {
    namespace = "com.smatrisciano.aipantry"
    compileSdk = 36

    defaultConfig {
        applicationId = "com.smatrisciano.aipantry"
        minSdk = 31
        targetSdk = 36
        versionCode = 1
        versionName = "1.0"
    }

    buildTypes {
        release {
            isMinifyEnabled = false
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro"
            )
        }
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    buildFeatures {
        compose = true
        buildConfig = true
    }

    // How the LLM is delivered (Gemma 4 E2B in both cases):
    //  - play: AI packs via Play for On-device AI (≤1.5 GB chunks, reassembled)
    //  - beta: model embedded in the APK (~2.8 GB), handed out manually to
    //          testers (AGP forbids flavor names starting with "test")
    flavorDimensions += "distribution"
    productFlavors {
        create("play") {
            dimension = "distribution"
            isDefault = true
            buildConfigField("String", "MODEL_SOURCE", "\"AI_PACKS\"")
        }
        create("beta") {
            dimension = "distribution"
            buildConfigField("String", "MODEL_SOURCE", "\"BUNDLED\"")
        }
    }
    // Models must not be compressed in the APK: the runtimes memory-map them
    androidResources {
        noCompress += listOf("litertlm", "tflite")
    }

    // Play for On-device AI: the LLM travels as AI packs via Play
    assetPacks += listOf(":llm_pack_0", ":llm_pack_1", ":llm_pack_2")
}

kotlin {
    compilerOptions {
        jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_17)
    }
}

dependencies {
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.core.splashscreen)
    implementation(libs.androidx.lifecycle.runtime.ktx)
    implementation(libs.androidx.lifecycle.runtime.compose)
    implementation(libs.androidx.lifecycle.viewmodel.compose)
    implementation(libs.androidx.activity.compose)

    implementation(platform(libs.androidx.compose.bom))
    implementation(libs.androidx.compose.ui)
    implementation(libs.androidx.compose.ui.graphics)
    implementation(libs.androidx.compose.ui.tooling.preview)
    implementation(libs.androidx.compose.material3)
    implementation(libs.androidx.compose.material.icons)
    debugImplementation(libs.androidx.compose.ui.tooling)

    implementation(libs.androidx.navigation.compose)

    implementation(libs.koin.android)
    implementation(libs.koin.androidx.compose)

    implementation(libs.androidx.room.runtime)
    implementation(libs.androidx.room.ktx)
    ksp(libs.androidx.room.compiler)

    implementation(libs.kotlinx.serialization.json)

    implementation(libs.androidx.camera.core)
    implementation(libs.androidx.camera.camera2)
    implementation(libs.androidx.camera.lifecycle)
    implementation(libs.androidx.camera.view)

    implementation(libs.litertlm.android)
    // Gemini Nano on-device via AICore (Prompt API): only on supported devices,
    // gated at runtime with checkStatus(); elsewhere the CLIP detector is used
    implementation(libs.mlkit.genai.prompt)
    // LiteRT runtime for the MobileCLIP encoder of the zero-shot detector
    implementation(libs.litert)

    implementation(libs.play.ai.delivery)
    implementation(libs.kotlinx.coroutines.play.services)

    testImplementation(libs.junit)
}
