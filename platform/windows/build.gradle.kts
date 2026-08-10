plugins {
    kotlin("jvm")
}

dependencies {
    // ModelProvider contract — the desktop GGUF implementation
    // (ARCHITECTURE.md Part 3, Phase 5c) lives in this module.
    api(project(":shared:model-api"))
    api(project(":shared:platform-api"))
    // ActionOrigin (OperationContext.origin) lives in shared:core; platform-api
    // declares it implementation-only, so consumers that build OperationContext
    // (capability tests) must declare core explicitly — same as platform:android.
    implementation(project(":shared:core"))

    // kherud/java-llama.cpp — actively maintained JVM binding for llama.cpp
    // (GGUF format). Bundles native .dll/.dylib/.so for Windows/macOS/Linux
    // and provides in-process inference via JNI without an external daemon.
    implementation("de.kherud:java-llama.cpp:4.0.0")

    // JNA platform — stable Win32 bindings (User32, GDI32, GDI32Util) for the
    // Windows desktop automation adapter. jna-platform has NO UI Automation
    // (UIAutomation) classes upstream, so the adapter drives the native API
    // tier of ARCHITECTURE.md RULE 5 (EnumWindows, control messaging, GDI
    // capture) directly. Same JNA family the Android app already pins (5.13.0).
    implementation("net.java.dev.jna:jna-platform:5.13.0")

    // Coroutines for Flow-based streaming and lifecycle management.
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-core:1.9.0")

    testImplementation("junit:junit:4.13.2")
    testImplementation("org.jetbrains.kotlinx:kotlinx-coroutines-test:1.9.0")
}