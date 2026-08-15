plugins {
    id("com.android.library")
}

android {
    namespace = "com.newax.aegis.platform.android"
    compileSdk = 37
    defaultConfig {
        minSdk = 26
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
}

// AGP 9 built-in Kotlin: jvmTarget via the kotlin extension (was kotlinOptions).
// LiteRT-LM 0.15.0 ships Kotlin 2.3 metadata; Kotlin 2.4.10 reads it natively,
// so the -Xskip-metadata-version-check palliative is gone (AGENTS.md blocker
// resolved by the version alignment).
kotlin {
    compilerOptions {
        jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_17)
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
    implementation("com.google.ai.edge.litertlm:litertlm-android:0.16.0")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-core:1.11.0")

    // Same proven vault primitive the app already uses for its secure store
    // (platform/android/frontend/src/main/java/com/newax/aegis/memory/SecureKeyVault.kt).
    implementation("androidx.security:security-crypto:1.1.0")

    testImplementation("junit:junit:4.13.2")
}
