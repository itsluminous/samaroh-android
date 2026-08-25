import java.util.Properties

plugins {
    alias(libs.plugins.android.library)
    alias(libs.plugins.kotlin.android)
    alias(libs.plugins.kotlin.compose)
    alias(libs.plugins.kotlin.serialization)
    alias(libs.plugins.ksp)
    alias(libs.plugins.hilt)
}

// Supabase/Google settings come from local.properties (git-ignored) with safe empty
// defaults (§6 security) — same convention as :app. Empty values degrade gracefully:
// auth reports "not configured" instead of crashing, and the app stays fully offline-usable.
val localProps =
    Properties().apply {
        val f = rootProject.file("local.properties")
        if (f.exists()) f.inputStream().use(::load)
    }

fun quotedProp(name: String): String = "\"${localProps.getProperty(name)?.trim().orEmpty()}\""

android {
    namespace = "com.itsluminous.samaroh.core.auth"
    compileSdk = 35

    defaultConfig {
        minSdk = 26

        buildConfigField("String", "SUPABASE_URL", quotedProp("SUPABASE_URL"))
        buildConfigField("String", "SUPABASE_ANON_KEY", quotedProp("SUPABASE_ANON_KEY"))
        buildConfigField("String", "GOOGLE_WEB_CLIENT_ID", quotedProp("GOOGLE_WEB_CLIENT_ID"))
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
        buildConfig = true
    }

    lint {
        lintConfig = rootProject.file("lint.xml")
        abortOnError = true
    }

    testOptions {
        unitTests.all { test ->
            // The permissions round-trip test validates the model against the canonical
            // JSON Schema in the shared submodule.
            test.systemProperty(
                "samaroh.permissionsSchema",
                rootProject.file("shared/permissions/permissions-schema.json").absolutePath,
            )
        }
    }
}

dependencies {
    api(project(":core:model"))
    implementation(project(":core:data"))
    implementation(project(":core:database"))
    implementation(project(":core:designsystem"))

    implementation(libs.kotlinx.coroutines.core)
    implementation(libs.kotlinx.serialization.json)

    // Supabase auth + database access (Postgrest) over the Ktor OkHttp engine.
    implementation(libs.supabase.auth)
    implementation(libs.supabase.postgrest)
    implementation(libs.ktor.client.okhttp)

    // Sign in with Google via Credential Manager.
    implementation(libs.androidx.credentials)
    implementation(libs.androidx.credentials.play.services.auth)
    implementation(libs.google.identity.googleid)

    implementation(libs.hilt.android)
    ksp(libs.hilt.compiler)

    testImplementation(libs.junit)
    testImplementation(libs.truth)
    testImplementation(libs.turbine)
    testImplementation(libs.kotlinx.coroutines.test)
}
