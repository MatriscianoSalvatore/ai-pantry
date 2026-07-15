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
    kotlinOptions {
        jvmTarget = "17"
    }
    buildFeatures {
        compose = true
        buildConfig = true
    }

    // Come viene consegnato il modello LLM (Gemma 4 E2B in entrambi i casi):
    //  - play: AI pack via Play for On-device AI (chunk ≤1.5GB ricomposti)
    //  - beta: modello embeddato nell'APK (~2.8GB) da distribuire a mano ai
    //          tester (AGP vieta nomi flavor che iniziano con "test")
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
    // Il modello .litertlm non va compresso nell'APK: LiteRT-LM lo mappa in memoria
    androidResources {
        noCompress += listOf("litertlm")
    }

    // Play for On-device AI: il modello LLM viaggia come AI pack via Play
    assetPacks += listOf(":llm_pack_0", ":llm_pack_1", ":llm_pack_2")
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

    implementation(libs.play.ai.delivery)
    implementation(libs.kotlinx.coroutines.play.services)

    testImplementation(libs.junit)
}
