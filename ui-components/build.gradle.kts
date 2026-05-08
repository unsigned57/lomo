plugins {
    alias(libs.plugins.androidLibrary)
    alias(libs.plugins.composeCompiler)
    alias(libs.plugins.kover)
}

android {
    namespace = "com.lomo.ui"
    compileSdk {
        version = release(37)
    }

    defaultConfig {
        minSdk = 26
        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
    }

    buildFeatures {
        compose = true
    }

    compileOptions {
        sourceCompatibility = JavaVersion.toVersion(25)
        targetCompatibility = JavaVersion.toVersion(25)
    }

    composeCompiler {
        metricsDestination = rootProject.layout.buildDirectory.dir("reports/compose-compiler/ui-components/metrics")
        reportsDestination = rootProject.layout.buildDirectory.dir("reports/compose-compiler/ui-components/reports")
    }

    lint {
        abortOnError = true
        warningsAsErrors = true
        checkDependencies = false
        textReport = true
    }
}

dependencies {
    api(project(":domain"))
    lintChecks(libs.slack.compose.lint.checks)

    implementation(platform(libs.androidx.compose.bom)) {
        exclude(group = "androidx.compose.material3", module = "material3")
        exclude(group = "androidx.compose.material3", module = "material3-android")
    }
    implementation(libs.androidx.ui)
    implementation(libs.androidx.ui.graphics)
    implementation(libs.androidx.ui.tooling.preview)
    api(libs.androidx.material3)
    implementation(libs.androidx.activity.compose) // For rememberLauncherForActivityResult

    // Extended Icons
    implementation(libs.androidx.material.icons.extended)

    // Coil
    implementation(libs.coil.compose)
    implementation(libs.coil.network)

    implementation(libs.kotlinx.collections.immutable)
    api(libs.markdown.renderer.android)
    implementation(libs.reorderable)

    // CommonMark
    api(libs.commonmark)
    implementation(libs.commonmark.strikethrough)
    implementation(libs.commonmark.tables)
    implementation(libs.commonmark.autolink)
    implementation(libs.commonmark.tasklist)

    debugImplementation(libs.androidx.ui.tooling)
    debugRuntimeOnly(libs.androidx.ui.test.manifest)

    // Media3 (Voice Memo)
    implementation(libs.androidx.media3.exoplayer)

    testImplementation(libs.junit)
    androidTestImplementation(libs.androidx.junit)
    androidTestImplementation(platform(libs.androidx.compose.bom))
    androidTestImplementation(libs.androidx.ui.test.junit4)
}

kotlin {
    compilerOptions {
        jvmTarget.set(
            org.jetbrains.kotlin.gradle.dsl.JvmTarget
                .fromTarget("25"),
        )
        freeCompilerArgs.add("-Xannotation-default-target=param-property")
    }
}

kover {
    currentProject {
        createVariant("quality") {
            add("debug")
        }
    }
}
