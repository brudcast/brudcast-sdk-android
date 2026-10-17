import java.util.Properties

plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.android)
    alias(libs.plugins.kotlin.compose)
}

val localProperties = Properties().apply {
    rootProject.file("local.properties").takeIf { it.exists() }?.inputStream()?.use(::load)
}

fun localProperty(name: String, fallback: String): String = localProperties.getProperty(name, fallback).trim()

android {
    namespace = "com.brudcast.sample"
    compileSdk = 36

    defaultConfig {
        applicationId = "com.brudcast.sample"
        minSdk = 23
        targetSdk = 36
        versionCode = 1
        versionName = project.version.toString()
        buildConfigField("String", "BRUDCAST_PUBLISHABLE_KEY", "\"${localProperty("brudcast.publishableKey", "")}\"")
        buildConfigField("String", "BRUDCAST_ENVIRONMENT", "\"${localProperty("brudcast.environment", "production")}\"")
        buildConfigField("String", "BRUDCAST_LOCAL_HOST", "\"${localProperty("brudcast.localHost", "10.0.2.2")}\"")
        buildConfigField("String", "SAMPLE_BACKEND_URL", "\"${localProperty("brudcast.sampleBackendUrl", "")}\"")
    }

    buildFeatures {
        buildConfig = true
        compose = true
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
}

kotlin {
    jvmToolchain(17)
}

if (file("google-services.json").exists()) {
    apply(plugin = libs.plugins.google.services.get().pluginId)
}

dependencies {
    implementation(project(":sdk-inapp"))
    implementation(project(":sdk-push"))
    implementation(project(":sdk-push-firebase"))
    implementation(libs.firebase.messaging)
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.activity.compose)
    implementation(platform(libs.compose.bom))
    implementation(libs.compose.ui)
    implementation(libs.compose.foundation)
    implementation(libs.compose.material3)
    implementation(libs.compose.material.icons.core)
    implementation(libs.androidx.navigation.compose)
}
