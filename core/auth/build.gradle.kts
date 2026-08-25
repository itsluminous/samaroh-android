plugins {
    alias(libs.plugins.android.library)
    alias(libs.plugins.kotlin.android)
}

android {
    namespace = "com.itsluminous.samaroh.core.auth"
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
    implementation(libs.kotlinx.coroutines.core)
}
