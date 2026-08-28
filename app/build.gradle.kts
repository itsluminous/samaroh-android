import java.util.Properties

plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.android)
    alias(libs.plugins.kotlin.compose)
    alias(libs.plugins.ksp)
    alias(libs.plugins.hilt)
}

// Supabase/Google settings come from local.properties (git-ignored) with safe empty
// defaults, so a fresh checkout and CI both build without any secrets (§6 security).
val localProps =
    Properties().apply {
        val f = rootProject.file("local.properties")
        if (f.exists()) f.inputStream().use(::load)
    }

fun quotedProp(name: String): String = "\"${localProps.getProperty(name)?.trim().orEmpty()}\""

// Release signing is driven entirely by environment variables (same convention as CI
// secrets): unset variables yield an unsigned release APK instead of a failing build.
val signingKeystoreFile: String? = System.getenv("SIGNING_KEYSTORE_FILE")
val signingKeystorePassword: String? = System.getenv("SIGNING_KEYSTORE_PASSWORD")
val signingKeyAlias: String? = System.getenv("SIGNING_KEY_ALIAS")
val signingKeyPassword: String? = System.getenv("SIGNING_KEY_PASSWORD")
val hasReleaseSigning =
    !signingKeystoreFile.isNullOrBlank() &&
        !signingKeystorePassword.isNullOrBlank() &&
        !signingKeyAlias.isNullOrBlank() &&
        !signingKeyPassword.isNullOrBlank()

android {
    namespace = "com.itsluminous.samaroh"
    compileSdk = 35

    defaultConfig {
        applicationId = "com.itsluminous.samaroh"
        minSdk = 26
        targetSdk = 35
        versionCode = (project.findProperty("appVersionCode") as? String)?.toInt() ?: 1
        versionName = (project.findProperty("appVersionName") as? String) ?: "0.6.0"

        // Hilt-aware runner (swaps in HiltTestApplication for the e2e androidTest suite).
        testInstrumentationRunner = "com.itsluminous.samaroh.SamarohTestRunner"

        buildConfigField("String", "SUPABASE_URL", quotedProp("SUPABASE_URL"))
        buildConfigField("String", "SUPABASE_ANON_KEY", quotedProp("SUPABASE_ANON_KEY"))
        buildConfigField("String", "GOOGLE_WEB_CLIENT_ID", quotedProp("GOOGLE_WEB_CLIENT_ID"))
    }

    if (hasReleaseSigning) {
        signingConfigs {
            create("release") {
                storeFile = file(signingKeystoreFile!!)
                storePassword = signingKeystorePassword
                keyAlias = signingKeyAlias
                keyPassword = signingKeyPassword
            }
        }
    }

    buildTypes {
        release {
            if (hasReleaseSigning) {
                signingConfig = signingConfigs.getByName("release")
            }
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro",
            )
        }
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
        // System animator/window animations off during instrumented runs — Compose's own
        // test clock handles composition animations; this covers the framework's.
        animationsDisabled = true
    }

    packaging {
        resources {
            excludes += "/META-INF/{AL2.0,LGPL2.1}"
        }
    }
}

// Human-friendly artifact names: Samaroh.apk / Samaroh-debug.apk.
androidComponents {
    onVariants { variant ->
        val suffix = if (variant.buildType == "release") "" else "-${variant.buildType}"
        variant.outputs.forEach { output ->
            (output as? com.android.build.api.variant.impl.VariantOutputImpl)
                ?.outputFileName
                ?.set("Samaroh$suffix.apk")
        }
    }
}

dependencies {
    implementation(project(":core:designsystem"))
    implementation(project(":core:i18n"))
    implementation(project(":core:model"))
    implementation(project(":core:database"))
    implementation(project(":core:data"))
    implementation(project(":core:sync"))
    implementation(project(":core:auth"))
    implementation(project(":core:google"))
    implementation(project(":core:invoice"))
    implementation(project(":feature:booking"))
    implementation(project(":feature:expenses"))
    implementation(project(":feature:inventory"))
    implementation(project(":feature:menu"))
    implementation(project(":feature:onboarding"))
    implementation(project(":feature:reports"))

    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.core.splashscreen)
    implementation(libs.androidx.appcompat)
    implementation(libs.androidx.lifecycle.runtime.compose)
    implementation(libs.androidx.activity.compose)

    implementation(platform(libs.compose.bom))
    implementation(libs.compose.ui)
    implementation(libs.compose.material3)
    implementation(libs.compose.material.icons.extended)
    implementation(libs.androidx.datastore.preferences)
    debugImplementation(libs.compose.ui.tooling)
    implementation(libs.compose.ui.tooling.preview)

    implementation(libs.androidx.navigation.compose)

    // App-wide Coil ImageLoader configuration (offline-friendly disk cache, ADR-023).
    implementation(libs.coil.compose)

    implementation(libs.hilt.android)
    ksp(libs.hilt.compiler)
    implementation(libs.androidx.hilt.navigation.compose)

    // Provides the XML Theme.Material3.* parent used in themes.xml.
    implementation(libs.google.material)

    testImplementation(libs.junit)
    testImplementation(libs.truth)

    // End-to-end instrumented suite (spec §11 W2-B): Compose UI tests + Hilt + intents.
    androidTestImplementation(platform(libs.compose.bom))
    androidTestImplementation(libs.compose.ui.test.junit4)
    debugImplementation(libs.compose.ui.test.manifest)
    androidTestImplementation(libs.androidx.test.runner)
    androidTestImplementation(libs.androidx.test.ext.junit)
    androidTestImplementation(libs.androidx.test.core.ktx)
    androidTestImplementation(libs.androidx.test.espresso.intents)
    androidTestImplementation(libs.hilt.android.testing)
    // TestAuthModule mirrors AuthModule's SupabaseClient? binding (pinned null).
    androidTestImplementation(libs.supabase.auth)
    kspAndroidTest(libs.hilt.compiler)
    androidTestImplementation(libs.truth)
    androidTestImplementation(project(":core:testing"))
    // RoomDatabase supertype of SamarohDatabase must be on the androidTest compile classpath.
    androidTestImplementation(libs.room.runtime)
}
