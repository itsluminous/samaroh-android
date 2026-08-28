plugins {
    alias(libs.plugins.android.library)
    alias(libs.plugins.kotlin.android)
    alias(libs.plugins.kotlin.serialization)
    alias(libs.plugins.ksp)
    alias(libs.plugins.hilt)
}


// Seed template + colour palette come straight from the shared submodule (single source
// of truth, ADR-032): copied into a generated asset dir at build time so this module
// never carries a stale duplicate (same pattern as feature:booking's copyEventTypes).
val generatedAssetsDir = layout.buildDirectory.dir("generated/dataAssets")

val copySharedDataAssets by tasks.registering(Copy::class) {
    group = "assets"
    description = "Copies shared/event-types.json + booking-colors.json into generated assets"
    from(rootProject.file("shared/event-types.json"))
    from(rootProject.file("shared/booking-colors.json"))
    into(generatedAssetsDir)
}

android {
    namespace = "com.itsluminous.samaroh.core.data"
    compileSdk = 35

    defaultConfig {
        minSdk = 26
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    kotlinOptions {
        jvmTarget = "17"
    }

    lint {
        lintConfig = rootProject.file("lint.xml")
        abortOnError = true
    }

    sourceSets {
        getByName("main") {
            assets.srcDir(generatedAssetsDir)
        }
    }

    testOptions {
        unitTests {
            isIncludeAndroidResources = true
        }
    }
}

tasks.named("preBuild") {
    dependsOn(copySharedDataAssets)
}

dependencies {
    api(project(":core:model"))
    api(project(":core:database"))
    implementation(project(":core:i18n"))

    implementation(libs.kotlinx.serialization.json)
    implementation(libs.androidx.datastore.preferences)
    implementation(libs.kotlinx.coroutines.core)

    implementation(libs.hilt.android)
    ksp(libs.hilt.compiler)

    testImplementation(project(":core:testing"))
    testImplementation(libs.junit)
    testImplementation(libs.truth)
    testImplementation(libs.kotlinx.coroutines.test)
    testImplementation(libs.robolectric)
    testImplementation(libs.androidx.test.core.ktx)
    // SamarohDatabase's RoomDatabase supertype must be on the test compile classpath.
    testImplementation(libs.room.runtime)
}
