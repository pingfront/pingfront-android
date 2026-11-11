plugins {
    `embedded-kotlin`
}

dependencies {
    implementation(gradleApi())
    // The binary-compatibility-validator library, used directly: its Gradle plugin does not hook
    // AGP 9's built-in Kotlin compilations. See ApiValidation.kt.
    implementation("org.jetbrains.kotlinx:binary-compatibility-validator:0.18.2")
    implementation("org.jetbrains.kotlin:kotlin-metadata-jvm:2.4.21")
    implementation("org.ow2.asm:asm:9.9")
    implementation("org.ow2.asm:asm-tree:9.9")
}
