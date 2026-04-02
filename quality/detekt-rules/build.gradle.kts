plugins {
    alias(libs.plugins.jetbrainsKotlinJvm)
}

val jvmVersion = 25

java {
    sourceCompatibility = JavaVersion.toVersion(jvmVersion)
    targetCompatibility = JavaVersion.toVersion(jvmVersion)
}

dependencies {
    compileOnly("dev.detekt:detekt-api:${libs.versions.detekt.get()}")

    testImplementation(libs.junit)
    testImplementation("dev.detekt:detekt-test:${libs.versions.detekt.get()}")
}

kotlin {
    compilerOptions {
        jvmTarget.set(
            org.jetbrains.kotlin.gradle.dsl.JvmTarget
                .fromTarget(jvmVersion.toString()),
        )
    }
}
