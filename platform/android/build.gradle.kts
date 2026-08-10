plugins {
    id("com.android.library")
    id("org.jetbrains.kotlin.android")
}

android {
    namespace = "com.newax.aegis.platform.android"
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
}

dependencies {
    // The adapter's public API is built entirely on the platform-api contract
    // types (CapabilityId, CapabilityStatus, PlatformCapabilities, ...), so the
    // contract must be exported to consumers (the app) via api().
    api(project(":shared:platform-api"))
    // Same for the model contract: LiteRtModelProvider implements ModelProvider
    // (Phase 5b), so shared:model-api is public surface too.
    api(project(":shared:model-api"))
    implementation(project(":shared:core"))

    // LiteRT-LM runtime + coroutines for the on-device model provider
    // (LiteRtOfflineModel / LiteRtModelProvider — moved here from androidApp).
    implementation("com.google.ai.edge.litertlm:litertlm-android:0.14.0")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-core:1.9.0")

    // Same proven vault primitive the app already uses for its secure store
    // (apps/androidApp/src/main/java/com/newax/aegis/memory/SecureKeyVault.kt).
    implementation("androidx.security:security-crypto:1.1.0-alpha06")

    testImplementation("junit:junit:4.13.2")
}
