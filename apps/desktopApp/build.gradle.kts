plugins {
    id("org.jetbrains.kotlin.jvm")
    // Compose Multiplatform (desktop) — the UI surface replacing the CLI (Phase B1).
    // 1.7.1 is the CMP line locked to Kotlin 2.1.0 (the repo baseline); the compose
    // compiler comes from org.jetbrains.kotlin.plugin.compose (2.1.0, root-declared).
    // NB: no `application` plugin here — compose.desktop.application already owns
    // the run/package tasks, and applying both fails with "task 'run' already exists".
    id("org.jetbrains.compose") version "1.7.1"
    id("org.jetbrains.kotlin.plugin.compose")
}

compose.desktop {
    application {
        mainClass = "MainKt"
    }
}

dependencies {
    // The desktop GGUF model provider (platform:windows — Phase 5c) fulfills
    // the shared ModelProvider contract for desktop inference.
    implementation(project(":platform:windows"))

    // ActionOrigin (OperationContext.origin) lives in shared:core; platform-api
    // declares it implementation-only, so consumers that build OperationContext
    // (the GoalExecutor's Win32 launch context — Phase 5h) must declare core
    // explicitly — same as platform:windows and platform:android.
    implementation(project(":shared:core"))

    // The sync engine's JVM seam (mDNS proximity discovery, TCP transfer
    // channel, the encrypted Quick Share protocol — P2 desktop listener).
    implementation(project(":shared:sync"))

    // The Room-backed journal store for automatic sync (RoomJournalStore) —
    // the desktop DB lives at ~/.aegis/sync.db (bundled sqlite, no SQLCipher
    // on the JVM yet).
    implementation(project(":shared:database"))

    // Coroutines for the interactive prompt loop (runBlocking) and the UI state
    // holders (the window's app scope).
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-core:1.9.0")

    // JSON persistence for goals + execution audit (Phase B3) — the desktop twin of
    // Android's org.json snapshots. Tiny, no transitive deps; FileGoalsStore writes
    // ~/.aegis/goals.json best-effort (corrupt/missing file → honest empty start).
    implementation("org.json:json:20240303")

    // Compose Multiplatform desktop UI (Phase B1): full window surface + the
    // Material 3 layer and the extended icon set the Android screens already use
    // (Refresh, Shield, Add, CheckCircle — REFINED_THEME.md design tokens).
    implementation(compose.desktop.currentOs)
    implementation(compose.material3)
    implementation(compose.materialIconsExtended)

    // JVM unit tests for the process-wide holders (Phase 5e) and the plain-Kotlin
    // UI state holders (Phase B1 — all decision logic lives outside Compose).
    testImplementation("junit:junit:4.13.2")
    testImplementation("org.jetbrains.kotlinx:kotlinx-coroutines-test:1.9.0")
}