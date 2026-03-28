plugins {
    alias(libs.plugins.jetbrainsKotlinJvm)
    alias(libs.plugins.kover)
}

val jvmVersion = 25

java {
    sourceCompatibility = JavaVersion.toVersion(jvmVersion)
    targetCompatibility = JavaVersion.toVersion(jvmVersion)
}

dependencies {
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-core:${libs.versions.coroutines.get()}")

    testImplementation(libs.junit)
    testImplementation(libs.kotlinx.coroutines.test)
    testImplementation(libs.mockk)
}

kotlin {
    compilerOptions {
        jvmTarget.set(
            org.jetbrains.kotlin.gradle.dsl.JvmTarget
                .fromTarget(jvmVersion.toString()),
        )
        freeCompilerArgs.add("-Xannotation-default-target=param-property")
    }
}

kover {
    currentProject {
        createVariant("quality") {
            add("jvm")
        }
    }
}
