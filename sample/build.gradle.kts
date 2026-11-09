import java.util.Properties

plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
    id("org.jetbrains.kotlin.plugin.compose")
}

// Firebase is optional: without google-services.json the app runs and skips FCM.
if (file("google-services.json").exists()) apply(plugin = "com.google.gms.google-services")

// Endpoint and app key come from sdks/android/local.properties (gitignored):
//   herald.endpoint=https://your-herald.example.com
//   herald.appKey=pk_live_...
val localProperties = Properties().apply {
    rootProject.file("local.properties").takeIf { it.exists() }?.inputStream()?.use(::load)
}
fun localProperty(key: String, fallback: String) = localProperties.getProperty(key) ?: fallback

android {
    namespace = "com.herald.sample"
    compileSdk = 35

    defaultConfig {
        applicationId = "com.herald.sample"
        minSdk = 26
        targetSdk = 35
        versionCode = 1
        versionName = "0.1.0"
        buildConfigField("String", "HERALD_ENDPOINT", "\"${localProperty("herald.endpoint", "https://herald.example.com")}\"")
        buildConfigField("String", "HERALD_APP_KEY", "\"${localProperty("herald.appKey", "pk_test_replace_me")}\"")
    }

    buildFeatures {
        compose = true
        buildConfig = true
    }

    sourceSets {
        // The shared wire fixtures (messages.json) back the debug-only Preview screen.
        getByName("debug").assets.srcDir("../../spec/fixtures")
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
}

kotlin {
    jvmToolchain(17)
}

dependencies {
    implementation(project(":herald-ui"))
    implementation(project(":herald-fcm"))
    implementation(platform("androidx.compose:compose-bom:2024.09.02"))
    implementation("androidx.compose.material3:material3")
    implementation("androidx.activity:activity-compose:1.9.2")
}
