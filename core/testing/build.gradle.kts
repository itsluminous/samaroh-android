plugins {
    alias(libs.plugins.android.library)
    alias(libs.plugins.kotlin.android)
}

android {
    namespace = "com.itsluminous.samaroh.core.testing"
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
}

dependencies {
    api(project(":core:model"))
    api(project(":core:database"))

    // Test infra is exposed as MAIN source here so other modules can testImplementation(project(":core:testing")).
    api(libs.junit)
    api(libs.kotlinx.coroutines.test)
    implementation(libs.room.runtime)
    implementation(libs.kotlinx.coroutines.core)
}
