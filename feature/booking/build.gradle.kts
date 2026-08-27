plugins {
    alias(libs.plugins.android.library)
    alias(libs.plugins.kotlin.android)
    alias(libs.plugins.kotlin.compose)
    alias(libs.plugins.kotlin.serialization)
    alias(libs.plugins.ksp)
    alias(libs.plugins.hilt)
}

// The built-in event types come straight from the shared submodule (single source of
// truth, §4.1); the file is copied into a generated asset dir at build time so the
// feature never carries a stale duplicate.
val generatedAssetsDir = layout.buildDirectory.dir("generated/bookingAssets")

val copyEventTypes by tasks.registering(Copy::class) {
    group = "assets"
    description = "Copies shared/event-types.json into the feature's generated assets"
    from(rootProject.file("shared/event-types.json"))
    into(generatedAssetsDir)
}

// Booking colour palette (ADR-030): same single-source-of-truth pattern as event types.
val copyBookingColors by tasks.registering(Copy::class) {
    group = "assets"
    description = "Copies shared/booking-colors.json into the feature's generated assets"
    from(rootProject.file("shared/booking-colors.json"))
    into(generatedAssetsDir)
}

android {
    namespace = "com.itsluminous.samaroh.feature.booking"
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

    buildFeatures {
        compose = true
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
    dependsOn(copyEventTypes)
    dependsOn(copyBookingColors)
}

dependencies {
    implementation(project(":core:designsystem"))
    implementation(project(":core:model"))
    implementation(project(":core:data"))

    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.appcompat)
    implementation(libs.androidx.navigation.compose)
    implementation(libs.androidx.lifecycle.runtime.compose)
    implementation(libs.androidx.lifecycle.viewmodel.compose)
    implementation(libs.androidx.activity.compose)

    implementation(libs.hilt.android)
    ksp(libs.hilt.compiler)
    implementation(libs.androidx.hilt.navigation.compose)

    implementation(libs.androidx.work.runtime.ktx)
    implementation(libs.androidx.lifecycle.process)
    implementation(libs.androidx.startup.runtime)
    implementation(libs.androidx.datastore.preferences)
    implementation(libs.kotlinx.serialization.json)
    implementation(libs.kotlinx.coroutines.core)

    testImplementation(project(":core:testing"))
    testImplementation(libs.junit)
    testImplementation(libs.truth)
    testImplementation(libs.turbine)
    testImplementation(libs.kotlinx.coroutines.test)
    testImplementation(libs.robolectric)
    testImplementation(libs.androidx.test.core.ktx)
    testImplementation(libs.androidx.work.testing)
}
