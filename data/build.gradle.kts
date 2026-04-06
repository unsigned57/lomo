plugins {
    alias(libs.plugins.androidLibrary)
    alias(libs.plugins.ksp)
    alias(libs.plugins.hilt)
    alias(libs.plugins.kover)
    alias(libs.plugins.kotlinSerialization)
}

val roomSchemaDir = layout.projectDirectory.dir("schemas")

android {
    namespace = "com.lomo.data"
    compileSdk {
        version = release(37)
    }

    defaultConfig {
        minSdk = 26
        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
        consumerProguardFiles("consumer-rules.pro")
    }

    sourceSets {
        getByName("androidTest").assets.directories.add(roomSchemaDir.asFile.path)
    }

    compileOptions {
        sourceCompatibility = JavaVersion.toVersion(25)
        targetCompatibility = JavaVersion.toVersion(25)
    }

    lint {
        abortOnError = true
        warningsAsErrors = true
        checkDependencies = false
        textReport = true
    }

    ksp {
        arg("room.schemaLocation", roomSchemaDir.asFile.path)
    }
}

dependencies {
    implementation(project(":domain"))
    implementation(platform(libs.aws.sdk.kotlin.bom))

    implementation(libs.androidx.core.ktx)
    implementation(libs.aws.sdk.kotlin.s3)
    implementation(libs.kotlinx.coroutines.android)
    implementation(libs.bouncycastle.bcprov)

    // Room
    implementation(libs.androidx.room.runtime)
    implementation(libs.androidx.room.ktx)
    ksp(libs.androidx.room.compiler)

    // Hilt
    implementation(libs.hilt.android)
    ksp(libs.hilt.compiler)
    implementation(libs.androidx.hilt.work)
    ksp(libs.androidx.hilt.compiler.work)

    // DocumentFile for SAF
    implementation(libs.androidx.documentfile)

    // DataStore for preferences
    implementation(libs.androidx.datastore.preferences)

    // WorkManager
    implementation(libs.androidx.work.runtime.ktx)

    // Ktor (LAN Share)
    implementation(libs.ktor.server.cio)
    implementation(libs.ktor.server.content.negotiation)
    implementation(libs.ktor.serialization.kotlinx.json)
    implementation(libs.ktor.client.okhttp)
    implementation(libs.ktor.client.content.negotiation)
    implementation(libs.kotlinx.serialization.json)

    // Git sync
    implementation(libs.jgit)
    implementation(libs.bitfire.dav4jvm) {
        exclude(group = "org.ogce", module = "xpp3")
    }

    // Logging
    implementation(libs.timber)

    testImplementation(libs.junit)
    testImplementation(libs.turbine)
    testImplementation(libs.mockk)
    testImplementation(libs.kotlinx.coroutines.test)
    androidTestImplementation(libs.androidx.junit)
    androidTestImplementation(libs.androidx.espresso.core)
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
