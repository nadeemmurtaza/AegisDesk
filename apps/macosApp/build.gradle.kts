plugins {
    id("org.jetbrains.kotlin.jvm")
    // Compose Multiplatform (desktop) — the macOS body of the 4-device mesh
    // (docs/SYNC_DESIGN.md §2, Track M). Same plugin couple as desktopApp:
    // CMP 1.7.1 locked to Kotlin 2.1.0; the compose compiler comes from
    // org.jetbrains.kotlin.plugin.compose (2.1.0, root-declared).
    id("org.jetbrains.compose") version "1.7.1"
    id("org.jetbrains.kotlin.plugin.compose")
}

compose.desktop {
    application {
        mainClass = "MainKt"
    }
}

dependencies {
    // The shared desktop sync engine (identity, LAN transport loop, Room
    // journal at ~/.aegis/sync.db, memory materialization, text-code pairing)
    // — one implementation for both the Windows and macOS desktop bodies.
    implementation(project(":shared:desktop-sync"))

    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-core:1.9.0")

    // Compose Multiplatform desktop UI: the window surface (sync status,
    // pairing, memory) + Material 3 + extended icons.
    implementation(compose.desktop.currentOs)
    implementation(compose.material3)
    implementation(compose.materialIconsExtended)

    testImplementation("junit:junit:4.13.2")
}
