plugins {
    alias(libs.plugins.androidLibrary)
    alias(libs.plugins.ksp)
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
    api(project(":domain"))
    implementation(platform(libs.aws.sdk.kotlin.bom))

    implementation(libs.androidx.core.ktx)
    implementation(libs.aws.sdk.kotlin.s3)
    implementation(libs.aws.smithy.kotlin.http.client.engine.okhttp.jvm)
    runtimeOnly(libs.kotlinx.coroutines.android)
    implementation(libs.bouncycastle.bcprov)

    // Room
    implementation(libs.androidx.room3.runtime)
    implementation(libs.androidx.room3.paging)
    implementation(libs.androidx.sqlite.bundled)
    ksp(libs.androidx.room3.compiler)

    // Koin
    api(libs.koin.android)
    api(libs.koin.androidx.workmanager)

    // DocumentFile for SAF
    implementation(libs.androidx.documentfile)

    // DataStore for preferences
    implementation(libs.androidx.datastore.preferences)

    // Ktor (LAN Share)
    api(libs.androidx.work.runtime.ktx)
    implementation(libs.ktor.server.cio)
    implementation(libs.ktor.server.content.negotiation)
    implementation(libs.ktor.serialization.kotlinx.json)
    implementation(libs.ktor.client.okhttp)
    implementation(libs.ktor.client.content.negotiation)
    implementation(libs.kotlinx.serialization.json)
    implementation(libs.okio)

    // Git sync
    api(libs.jgit)
    implementation(libs.bitfire.dav4jvm) {
        exclude(group = "org.ogce", module = "xpp3")
    }

    // Logging
    implementation(libs.timber)

    testImplementation(libs.kotest.runner.junit5)
    testImplementation(libs.kotest.assertions.core)
    testImplementation(libs.kotest.property)
    testImplementation(libs.kotest.framework.engine)
    testImplementation(libs.mockk)
    testImplementation(libs.kotlinx.coroutines.test)
    testImplementation(libs.turbine)
    testImplementation(libs.ktor.client.mock)
    testRuntimeOnly(libs.sqlite.jdbc)
    androidTestImplementation(libs.androidx.junit)
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

tasks.withType<org.gradle.api.tasks.testing.Test>().configureEach {
    useJUnitPlatform()
}
