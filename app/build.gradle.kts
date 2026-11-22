plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.android)
    alias(libs.plugins.kotlin.compose)
    alias(libs.plugins.ksp)
}

android {
    namespace = "com.postscheduler"
    compileSdk = 35

    defaultConfig {
        applicationId = "com.postscheduler"
        minSdk = 26
        targetSdk = 35
        versionCode = 1
        versionName = "1.0.0"
        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
        // Backend URL from gradle.properties; override in local.properties if needed.
        buildConfigField("String", "BACKEND_URL", "\"${project.findProperty("planner.backend.url") ?: "https://postschedulerapp.duckdns.org"}\"")
        // API key for the backend (X-Api-Key header). Empty is the
        // placeholder — set the real key in ~/.gradle/gradle.properties
        // (planner.api.key), machine-local, never in the repo. The backend
        // rejects /api/* calls without it once API_KEY is set on the VM.
        buildConfigField("String", "API_KEY", "\"${project.findProperty("planner.api.key") ?: ""}\"")
        // Facebook App ID for Facebook Login (Connect Page). "0" is a
        // placeholder — set the real App ID in gradle.properties or
        // local.properties (planner.facebook.app.id) before logging in.
        val fbAppId = (project.findProperty("planner.facebook.app.id") ?: "0").toString()
        manifestPlaceholders["facebookAppId"] = fbAppId
        manifestPlaceholders["facebookLoginScheme"] = "fb$fbAppId"
        // The SDK rejects a bare numeric App ID written directly in the
        // manifest, so it goes in as a generated string resource instead.
        resValue("string", "facebook_app_id", fbAppId)
        // Client token, also required by the SDK before init. Same "0"
        // placeholder until a real Facebook app supplies one.
        val fbClientToken = (project.findProperty("planner.facebook.client.token") ?: "0").toString()
        resValue("string", "facebook_client_token", fbClientToken)
    }

    // Debug builds are always signed with the committed debug.keystore at the
    // repo root, so the debug APK signature stays stable across machines/CI.
    signingConfigs {
        getByName("debug") {
            storeFile = file("../debug.keystore")
            storePassword = "android"
            keyAlias = "androiddebugkey"
            keyPassword = "android"
        }
    }
    buildTypes {
        debug {
            signingConfig = signingConfigs.getByName("debug")
        }
    }

    buildFeatures {
        compose = true
        buildConfig = true
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions {
        jvmTarget = "17"
    }
    packaging {
        resources {
            excludes += "/META-INF/{AL2.0,LGPL2.1}"
        }
    }
}

dependencies {
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.lifecycle.runtime.ktx)
    implementation(libs.androidx.lifecycle.viewmodel.compose)
    implementation(libs.androidx.activity.compose)
    implementation(platform(libs.androidx.compose.bom))
    implementation(libs.androidx.compose.ui)
    implementation(libs.androidx.compose.ui.graphics)
    implementation(libs.androidx.compose.ui.tooling.preview)
    implementation(libs.androidx.compose.material3)
    implementation(libs.androidx.compose.material.icons.extended)
    implementation(libs.androidx.navigation.compose)
    implementation(libs.androidx.room.runtime)
    implementation(libs.androidx.room.ktx)
    implementation(libs.androidx.datastore.preferences)
    implementation(libs.androidx.work.runtime.ktx)
    implementation(libs.retrofit)
    implementation(libs.retrofit.converter.gson)
    implementation(libs.okhttp.logging)
    implementation(libs.coil.compose)
    implementation(libs.coil.video)
    implementation(libs.facebook.login)
    ksp(libs.androidx.room.compiler)
}
