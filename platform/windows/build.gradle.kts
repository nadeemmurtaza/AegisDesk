plugins {
    kotlin("jvm")
}

kotlin {
    compilerOptions {
        jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_17)
    }
}

dependencies {
    // ModelProvider contract — the desktop GGUF implementation
    // (ARCHITECTURE.md Part 3, Phase 5c) lives in this module.
    api(project(":shared:model-api"))
    // The capability adapters' public API is built entirely on the platform-api
    // contract types (CapabilityId, CapabilityStatus, PlatformCapabilities, ...),
    // so the contract must be exported to consumers (the desktop app) via api().
    api(project(":shared:platform-api"))
    // ActionOrigin (OperationContext.origin) lives in shared:core; platform-api
    // declares it implementation-only, so consumers that build OperationContext
    // (capability tests) must declare core explicitly — same as platform:android.
    implementation(project(":shared:core"))

    // kherud/java-llama.cpp — actively maintained JVM binding for llama.cpp
    // (GGUF format). Bundles native .dll/.dylib/.so for Windows/macOS/Linux
    // and provides in-process inference via JNI without an external daemon.
    // NOTE: the Maven artifact is de.kherud:llama (the GitHub repo is
    // java-llama.cpp) — de.kherud:java-llama.cpp does not exist on Central.
    implementation("de.kherud:llama:4.0.0")

    // JNA platform — stable Win32 bindings (User32, GDI32, Crypt32Util,
    // Kernel32/Tlhelp32) for the Windows adapters: the UIA bridge's native tier,
    // the DPAPI secrets vault, and the Toolhelp32 process snapshot. Same JNA
    // family the Android app already pins (5.13.0).
    implementation("net.java.dev.jna:jna-platform:5.13.0")

    // Coroutines for Flow-based streaming and lifecycle management.
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-core:1.9.0")

    testImplementation("junit:junit:4.13.2")
    testImplementation("org.jetbrains.kotlinx:kotlinx-coroutines-test:1.9.0")
}
