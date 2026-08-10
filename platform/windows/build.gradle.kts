plugins {
    id("org.jetbrains.kotlin.jvm")
}

kotlin {
    compilerOptions {
        jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_17)
    }
}

dependencies {
    // The adapter's public API is built entirely on the platform-api contract
    // types (CapabilityId, CapabilityStatus, PlatformCapabilities, ...), so the
    // contract must be exported to consumers (the desktop app) via api().
    api(project(":shared:platform-api"))
    implementation(project(":shared:core"))

    // Windows interop (ARCHITECTURE.md platform matrix): DPAPI for the secrets
    // vault (Crypt32Util) and User32 for window-level desktop automation.
    // JNA is the standard, maintained JVM bridge to Win32 (Apache-2.0/LGPL-2.1).
    implementation("net.java.dev.jna:jna:5.14.0")
    implementation("net.java.dev.jna:jna-platform:5.14.0")

    testImplementation(kotlin("test"))
    // kotlin("test") maps to kotlin-test-junit (JUnit 4) by default; the explicit
    // junit dependency guarantees org.junit.Assume for the Windows-only tests.
    testImplementation("junit:junit:4.13.2")
}
