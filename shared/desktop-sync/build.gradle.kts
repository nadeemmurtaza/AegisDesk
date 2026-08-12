plugins {
    kotlin("jvm")
}

kotlin {
    compilerOptions {
        jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_17)
    }
}

// Keep javac on the same target as Kotlin (AGENTS.md baseline: JVM 17 for all
// modules) — without this, a JDK > 17 host builds compileJava at the JDK's own
// target and the Kotlin plugin's JVM-target validation fails the build.
java {
    sourceCompatibility = JavaVersion.VERSION_17
    targetCompatibility = JavaVersion.VERSION_17
}

dependencies {
    // The engine types (StoredIdentity, PairedPeer, PeerEndpoint) are part of
    // DesktopSync's public API (pairing/status) — export them to consumers.
    api(project(":shared:sync"))
    // RoomJournalStore + the desktop database builder (bundled sqlite).
    implementation(project(":shared:database"))
    // The memory-profile journal payloads are org.json arrays.
    implementation("org.json:json:20260719")
    // runBlocking bridges the blocking JournalStore onto the suspend DAOs.
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-core:1.11.0")
}
