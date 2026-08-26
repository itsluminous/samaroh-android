plugins {
    alias(libs.plugins.android.library)
    alias(libs.plugins.kotlin.android)
}

// Generated Android string resources land inside the build directory (git-ignored by
// definition). The canonical catalogs live in the shared submodule; keys are NEVER
// added directly here — see AGENTS.md.
val generatedResDir = layout.buildDirectory.dir("generated/sharedStrings/res")

val generateStrings by tasks.registering(Exec::class) {
    group = "i18n"
    description = "Generates values*/strings.xml from shared/strings catalogs via shared/codegen/gen-android.mjs"
    inputs.dir(rootProject.file("shared/strings"))
    inputs.dir(rootProject.file("shared/codegen"))
    outputs.dir(generatedResDir)
    commandLine(
        "node",
        rootProject.file("shared/codegen/gen-android.mjs").absolutePath,
        generatedResDir.get().asFile.absolutePath,
    )
}

android {
    namespace = "com.itsluminous.samaroh.core.i18n"
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
            res.srcDir(generatedResDir)
        }
    }

    testOptions {
        unitTests.all { test ->
            // The catalog key-parity test walks the canonical catalogs in the submodule.
            test.systemProperty("samaroh.sharedStringsDir", rootProject.file("shared/strings").absolutePath)
            // The usage-audit test scans the repo's Kotlin sources for R.string references.
            test.systemProperty("samaroh.repoRootDir", rootProject.projectDir.absolutePath)
        }
    }
}

// Every task that consumes resources must run after codegen.
tasks.named("preBuild") {
    dependsOn(generateStrings)
}

dependencies {
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.appcompat)

    testImplementation(libs.junit)
    testImplementation(libs.truth)
}
