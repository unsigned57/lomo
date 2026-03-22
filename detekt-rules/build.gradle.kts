plugins {
    alias(libs.plugins.jetbrainsKotlinJvm)
}

dependencies {
    compileOnly("dev.detekt:detekt-api:${libs.versions.detekt.get()}")
}

kotlin {
    jvmToolchain(25)
    compilerOptions {
        jvmTarget.set(
            org.jetbrains.kotlin.gradle.dsl.JvmTarget
                .fromTarget("25"),
        )
    }
}
