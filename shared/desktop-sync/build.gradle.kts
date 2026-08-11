plugins {
    kotlin("jvm")
}

kotlin {
    compilerOptions {
        jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_17)
    }
}

dependencies {
    // The engine types (StoredIdentity, PairedPeer, PeerEndpoint) are part of
    // DesktopSync's public API (pairing/status) — export them to consumers.
    api(project(":shared:sync"))
    // RoomJournalStore + the desktop database builder (bundled sqlite).
    implementation(project(":shared:database"))
    // The memory-profile journal payloads are org.json arrays.
    implementation("org.json:json:20240303")
    // runBlocking bridges the blocking JournalStore onto the suspend DAOs.
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-core:1.9.0")
}
