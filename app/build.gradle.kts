import org.gradle.api.DefaultTask
import org.gradle.api.file.DirectoryProperty
import org.gradle.api.provider.ListProperty
import org.gradle.api.provider.Property
import org.gradle.api.tasks.Input
import org.gradle.api.tasks.InputDirectory
import org.gradle.api.tasks.Optional
import org.gradle.api.tasks.TaskAction
import org.gradle.work.DisableCachingByDefault
import java.io.File
import java.util.Locale

plugins {
    alias(libs.plugins.androidApplication)
    alias(libs.plugins.composeCompiler)
    alias(libs.plugins.hilt)
    alias(libs.plugins.ksp)
    alias(libs.plugins.kotlinSerialization)
    alias(libs.plugins.androidxBaselineProfile)
}

val artifactBaseName =
    rootProject.name
        .lowercase(Locale.ROOT)
        .replace(Regex("[^a-z0-9._-]+"), "-")

private val requiredReleaseSigningEnvVars =
    listOf(
        "KEYSTORE_FILE",
        "KEYSTORE_PASSWORD",
        "KEY_ALIAS",
        "KEY_PASSWORD",
    )

@DisableCachingByDefault(because = "Renames generated artifacts after packaging")
abstract class RenameReleaseArtifactTask : DefaultTask() {
    @get:Input
    abstract val artifactBaseNameInput: Property<String>

    @get:Input
    abstract val artifactVersion: Property<String>

    @get:Input
    abstract val artifactExtension: Property<String>

    @get:Optional
    @get:InputDirectory
    abstract val artifactDir: DirectoryProperty

    @TaskAction
    fun renameArtifacts() {
        val outputDir = artifactDir.orNull?.asFile ?: return
        if (!outputDir.exists()) return

        val extension = artifactExtension.get().lowercase(Locale.ROOT)
        val artifacts =
            outputDir
                .listFiles { file ->
                    file.isFile && file.extension.lowercase(Locale.ROOT) == extension
                }?.sortedBy { it.name }
                .orEmpty()
        if (artifacts.isEmpty()) return

        if (artifacts.size == 1) {
            val target = outputDir.resolve("${artifactBaseNameInput.get()}-v${artifactVersion.get()}.$extension")
            if (artifacts.first().name != target.name) {
                if (target.exists()) target.delete()
                check(artifacts.first().renameTo(target)) {
                    "Failed to rename ${artifacts.first().name} to ${target.name}"
                }
            }
            return
        }

        artifacts.forEachIndexed { index, artifact ->
            val target = outputDir.resolve("${artifactBaseNameInput.get()}-v${artifactVersion.get()}-${index + 1}.$extension")
            if (artifact.name != target.name) {
                if (target.exists()) target.delete()
                check(artifact.renameTo(target)) {
                    "Failed to rename ${artifact.name} to ${target.name}"
                }
            }
        }
    }
}

@DisableCachingByDefault(because = "Validates environment variables before release signing")
abstract class VerifyReleaseSigningTask : DefaultTask() {
    @get:Input
    abstract val requiredEnvVars: ListProperty<String>

    @get:Input
    abstract val disallowedKeystoreName: Property<String>

    @TaskAction
    fun verify() {
        val missingEnv =
            requiredEnvVars.get().filter { envName ->
                System.getenv(envName).isNullOrBlank()
            }
        check(missingEnv.isEmpty()) {
            "Missing required release signing environment variables: ${missingEnv.joinToString(", ")}"
        }

        val keystorePath = checkNotNull(System.getenv("KEYSTORE_FILE")) { "KEYSTORE_FILE is required for release builds." }.trim()
        val keystoreFile = File(keystorePath)
        check(keystoreFile.exists()) {
            "Release signing keystore does not exist: $keystorePath"
        }
        check(keystoreFile.isFile) {
            "Release signing keystore path is not a file: $keystorePath"
        }
        check(!keystoreFile.name.equals(disallowedKeystoreName.get(), ignoreCase = true)) {
            "Release signing cannot use debug.keystore. Please provide a production keystore in KEYSTORE_FILE."
        }
    }
}

android {
    namespace = "com.lomo.app"
    compileSdk = 36
    ndkVersion = libs.versions.androidNdk.get()

    defaultConfig {
        applicationId = "com.lomo.app"
        minSdk = 26
        targetSdk = 36
        versionCode = 22
        versionName = "0.6.2"

        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
        vectorDrawables {
            useSupportLibrary = true
        }
    }

    signingConfigs {
        create("release") {
            val keystorePath = System.getenv("KEYSTORE_FILE")?.trim().orEmpty()
            if (keystorePath.isNotBlank()) {
                storeFile = file(keystorePath)
            }
            storePassword = System.getenv("KEYSTORE_PASSWORD")?.trim().orEmpty()
            keyAlias = System.getenv("KEY_ALIAS")?.trim().orEmpty()
            keyPassword = System.getenv("KEY_PASSWORD")?.trim().orEmpty()
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
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
            signingConfig = signingConfigs.getByName("release")
        }
    }
    compileOptions {
        sourceCompatibility = JavaVersion.toVersion(25)
        targetCompatibility = JavaVersion.toVersion(25)
    }
    buildFeatures {
        compose = true
        buildConfig = true
    }

    composeCompiler {
        // Remove Compose source information in release builds
        includeSourceInformation = false
    }

    packaging {
        resources {
            excludes += "/META-INF/{AL2.0,LGPL2.1}"
            excludes += "META-INF/INDEX.LIST"
            excludes += "META-INF/io.netty.versions.properties"
        }
    }
}

baselineProfile {
    dexLayoutOptimization = true
}

val releaseVersionName = android.defaultConfig.versionName ?: "0.0.0"

val verifyReleaseSigningEnv by tasks.registering(VerifyReleaseSigningTask::class) {
    group = "verification"
    description = "Validates required environment variables for release signing."
    requiredEnvVars.set(requiredReleaseSigningEnvVars)
    disallowedKeystoreName.set("debug.keystore")
}

tasks
    .matching {
        it.name in
            setOf(
                "validateSigningRelease",
                "packageRelease",
                "packageReleaseUniversalApk",
                "assembleRelease",
                "bundleRelease",
                "signReleaseBundle",
                "packageReleaseBundle",
            )
    }.configureEach {
        dependsOn(verifyReleaseSigningEnv)
    }

val renameReleaseApkArtifacts by tasks.registering(RenameReleaseArtifactTask::class) {
    artifactBaseNameInput.set(artifactBaseName)
    artifactVersion.set(releaseVersionName)
    artifactExtension.set("apk")
    artifactDir.set(layout.buildDirectory.dir("outputs/apk/release"))
}

val renameReleaseBundleArtifacts by tasks.registering(RenameReleaseArtifactTask::class) {
    artifactBaseNameInput.set(artifactBaseName)
    artifactVersion.set(releaseVersionName)
    artifactExtension.set("aab")
    artifactDir.set(layout.buildDirectory.dir("outputs/bundle/release"))
}

tasks.matching { it.name == "packageRelease" || it.name == "packageReleaseUniversalApk" }.configureEach {
    finalizedBy(renameReleaseApkArtifacts)
}

tasks.matching { it.name == "signReleaseBundle" || it.name == "packageReleaseBundle" }.configureEach {
    finalizedBy(renameReleaseBundleArtifacts)
}

dependencies {
    implementation(project(":domain"))
    runtimeOnly(project(":data"))
    implementation(project(":ui-components"))

    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.lifecycle.viewmodel.ktx)
    implementation(libs.androidx.lifecycle.runtime.ktx)
    implementation(libs.androidx.lifecycle.runtime.compose)
    implementation(libs.androidx.activity.compose)
    implementation(libs.androidx.core.splashscreen)
    implementation(libs.androidx.appcompat)
    implementation(libs.androidx.biometric)
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

    // Navigation
    implementation(libs.androidx.navigation.compose) // Corrected reference
    implementation(libs.kotlinx.serialization.json)

    // WorkManager
    implementation(libs.androidx.work.runtime.ktx)
    implementation(libs.androidx.profileinstaller)

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
    baselineProfile(project(":benchmark"))
}

kotlin {
    jvmToolchain(25)
    compilerOptions {
        jvmTarget.set(
            org.jetbrains.kotlin.gradle.dsl.JvmTarget
                .fromTarget("25"),
        )
        freeCompilerArgs.add("-Xannotation-default-target=param-property")
        freeCompilerArgs.addAll(
            "-Xno-call-assertions",
            "-Xno-param-assertions",
            "-Xno-receiver-assertions",
        )
    }
}
