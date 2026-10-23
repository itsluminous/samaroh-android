plugins {
    alias(libs.plugins.android.library)
    alias(libs.plugins.kotlin.android)
    alias(libs.plugins.kotlin.serialization)
    alias(libs.plugins.ksp)
    alias(libs.plugins.hilt)
}

// The single-source user guide (shared/help/guide.json + routes.json, ADR-108) is copied
// into a generated asset dir at build time — the same pattern as core:data's seed assets
// and core:i18n's generateStrings — so this module never carries a stale duplicate and
// the in-app Help, the assistant context and the public help site all read ONE file.
val generatedAssetsDir = layout.buildDirectory.dir("generated/helpAssets")

val generateHelpAssets by tasks.registering(Copy::class) {
    group = "i18n"
    description = "Copies shared/help/guide.json + routes.json into generated assets (help/)"
    from(rootProject.file("shared/help/guide.json"))
    from(rootProject.file("shared/help/routes.json"))
    into(generatedAssetsDir.map { it.dir("help") })
}

android {
    namespace = "com.itsluminous.samaroh.core.help"
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
        unitTests.all { test ->
            // The guide tests parse the canonical files in the submodule, not the copy.
            test.systemProperty("samaroh.sharedHelpDir", rootProject.file("shared/help").absolutePath)
            test.systemProperty("samaroh.sharedPermissionsDir", rootProject.file("shared/permissions").absolutePath)
            test.systemProperty("samaroh.sharedStringsDir", rootProject.file("shared/strings").absolutePath)
        }
    }
}

tasks.named("preBuild") {
    dependsOn(generateHelpAssets)
}

dependencies {
    implementation(project(":core:model"))
    implementation(project(":core:data"))

    implementation(libs.kotlinx.serialization.json)
    implementation(libs.kotlinx.coroutines.core)

    implementation(libs.hilt.android)
    ksp(libs.hilt.compiler)

    testImplementation(libs.junit)
    testImplementation(libs.truth)
    testImplementation(libs.kotlinx.coroutines.test)
}
