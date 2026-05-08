plugins {
    `kotlin-dsl`
}

dependencies {
    implementation("org.ow2.asm:asm:9.8")

    testImplementation(gradleTestKit())
    testImplementation(kotlin("test"))
    testImplementation("junit:junit:4.13.2")
    testImplementation("org.ow2.asm:asm:9.8")
}

gradlePlugin {
    plugins {
        register("staticBaselineProfile") {
            id = "com.lomo.baseline.static-profile"
            implementationClass = "com.lomo.baseline.StaticBaselineProfilePlugin"
        }
    }
}
