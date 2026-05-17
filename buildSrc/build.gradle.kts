plugins {
    `kotlin-dsl`
}

dependencies {
    implementation("org.ow2.asm:asm:9.8")

    testImplementation(gradleTestKit())
    testImplementation("io.kotest:kotest-runner-junit5:6.1.11")
    testImplementation("io.kotest:kotest-assertions-core:6.1.11")
    testImplementation("org.ow2.asm:asm:9.8")
}

tasks.withType<org.gradle.api.tasks.testing.Test>().configureEach {
    useJUnitPlatform()
}

gradlePlugin {
    plugins {
        register("staticBaselineProfile") {
            id = "com.lomo.baseline.static-profile"
            implementationClass = "com.lomo.baseline.StaticBaselineProfilePlugin"
        }
    }
}
