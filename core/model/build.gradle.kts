plugins {
    alias(libs.plugins.kotlin.jvm)
    alias(libs.plugins.kotlin.serialization)
}

java {
    sourceCompatibility = JavaVersion.VERSION_17
    targetCompatibility = JavaVersion.VERSION_17
}

kotlin {
    jvmToolchain(17)
}

// This is a pure-JVM module; alias the Android-style gate task name so
// `./gradlew testDebugUnitTest` (local gate + CI) also runs these tests.
tasks.register("testDebugUnitTest") {
    dependsOn(tasks.named("test"))
}

dependencies {
    api(libs.kotlinx.serialization.json)

    testImplementation(libs.junit)
    testImplementation(libs.truth)
}
