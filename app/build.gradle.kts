plugins {
    alias(libs.plugins.androidApplication)
    alias(libs.plugins.composeCompiler)
    alias(libs.plugins.hilt)
    alias(libs.plugins.ksp)
    alias(libs.plugins.kotlinSerialization)
    // alias(libs.plugins.androidxBaselineProfile)
}

android {
    namespace = "com.lomo.app"
    compileSdk = 36

    defaultConfig {
        applicationId = "com.lomo.app"
        minSdk = 26
        targetSdk = 36
        versionCode = 5
        versionName = "2.2.0"

        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
        vectorDrawables {
            useSupportLibrary = true
        }
    }

    signingConfigs {
        create("release") {
            val envKeystoreFile = System.getenv("KEYSTORE_FILE")?.let { file(it) } ?: file("debug.keystore")
            val envKeystorePassword = System.getenv("KEYSTORE_PASSWORD") ?: "android"
            val envKeyAlias = System.getenv("KEY_ALIAS") ?: "androiddebugkey"
            val envKeyPassword = System.getenv("KEY_PASSWORD") ?: "android"

            storeFile = envKeystoreFile
            storePassword = envKeystorePassword
            keyAlias = envKeyAlias
            keyPassword = envKeyPassword
        }
    }

    buildTypes {
        create("benchmark") {
            initWith(getByName("release"))
            signingConfig = signingConfigs.getByName("debug")
            matchingFallbacks += listOf("release")
            isDebuggable = false
        }
        debug {
            isMinifyEnabled = false
            // Disable PNG crunching for faster builds
            isCrunchPngs = false
            applicationIdSuffix = ".debug"
            versionNameSuffix = "-DEBUG"
        }
        release {
            isMinifyEnabled = true
            isShrinkResources = true
            // Enable R8 full mode checks (global flag set in gradle.properties)
            // Enable R8 full mode checks (global flag set in gradle.properties)
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
            signingConfig = signingConfigs.getByName("release")
        }
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    // compilerOptions removed from here
    buildFeatures {
        compose = true
        buildConfig = true
    }

    packaging {
        resources {
            excludes += "/META-INF/{AL2.0,LGPL2.1}"
        }
    }
}

dependencies {
    implementation(project(":domain"))
    implementation(project(":data"))
    implementation(project(":ui-components"))

    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.lifecycle.viewmodel.ktx)
    implementation(libs.androidx.lifecycle.runtime.ktx)
    implementation(libs.androidx.lifecycle.runtime.compose)
    implementation(libs.androidx.activity.compose)
    implementation(libs.androidx.core.splashscreen)
    implementation(libs.androidx.appcompat)
    implementation(platform(libs.androidx.compose.bom))
    implementation(libs.androidx.ui)
    implementation(libs.androidx.ui.graphics)
    implementation(libs.androidx.ui.tooling.preview)
    implementation(libs.androidx.material3)
    implementation(libs.androidx.material.icons.extended)
    implementation(libs.androidx.material3.adaptive)
    implementation(libs.androidx.material3.adaptive.layout)
    implementation(libs.androidx.material3.adaptive.navigation)

    // Hilt
    implementation(libs.hilt.android)
    ksp(libs.hilt.compiler)
    implementation(libs.hilt.navigation.compose)
    implementation(libs.androidx.hilt.work)
    ksp(libs.androidx.hilt.compiler.work)

    // Paging
    implementation(libs.androidx.paging.runtime)
    implementation(libs.androidx.paging.compose)

    // Navigation
    implementation(libs.androidx.navigation.compose) // Corrected reference
    implementation(libs.kotlinx.serialization.json)

    // WorkManager
    implementation(libs.androidx.work.runtime.ktx)

    testImplementation(libs.junit)
    testImplementation(libs.mockk)
    testImplementation(libs.kotlinx.coroutines.test)
    androidTestImplementation(libs.androidx.junit)
    androidTestImplementation(libs.androidx.espresso.core)
    androidTestImplementation(platform(libs.androidx.compose.bom))
    androidTestImplementation(libs.androidx.ui.test.junit4)
    debugImplementation(libs.androidx.ui.tooling)
    debugImplementation(libs.androidx.ui.test.manifest)

    // DocumentFile
    implementation(libs.androidx.documentfile)
    implementation(libs.kotlinx.collections.immutable)

    // Logging
    implementation(libs.timber)
    implementation(libs.zoomable.image.coil)

    // P3-004: Coil for image preloading
    implementation(libs.coil.compose)

    // Glance (App Widget)
    implementation(libs.androidx.glance)
    implementation(libs.androidx.glance.appwidget)
    implementation(libs.androidx.glance.material3)

    // Media3 (Voice Memo)
    implementation(libs.androidx.media3.exoplayer)
    implementation(libs.androidx.media3.ui)
    implementation(libs.androidx.media3.common)

    // Baseline Profile
    // baselineProfile(project(":benchmark"))
}

kotlin {
    compilerOptions {
        jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_17)
        freeCompilerArgs.add("-Xannotation-default-target=param-property")
    }
}
