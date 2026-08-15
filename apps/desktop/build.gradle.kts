plugins {
    id("org.jetbrains.kotlin.jvm")
    // Compose Multiplatform (desktop) — the UI surface replacing the CLI (Phase B1).
    // Version is root-declared (CMP 1.11.1, the lockstep pairing for Kotlin 2.4.10
    // per the AGENTS.md baseline) so this app, apps/macos, and shared:ui cannot
    // drift apart — two CMP versions in one build do not resolve. The compose
    // compiler comes from org.jetbrains.kotlin.plugin.compose (2.4.10).
    // NB: no `application` plugin here — compose.desktop.application already owns
    // the run/package tasks, and applying both fails with "task 'run' already exists".
    id("org.jetbrains.compose")
    id("org.jetbrains.kotlin.plugin.compose")
}

compose.desktop {
    application {
        mainClass = "MainKt"
    }
}

dependencies {
    // The desktop GGUF model provider (platform-impl:windows — Phase 5c) fulfills
    // the shared ModelProvider contract for desktop inference.
    implementation(project(":platform-impl:windows"))

    // ActionOrigin (OperationContext.origin) lives in shared:core; platform-api
    // declares it implementation-only, so consumers that build OperationContext
    // (the GoalExecutor's Win32 launch context — Phase 5h) must declare core
    // explicitly — same as platform-impl:windows and platform-impl:android.
    implementation(project(":shared:core"))
    // Shared design tokens (docs/UI_DESIGN.md §4) — the one source of truth
    // for colour, type, spacing, and shape across all four bodies.
    implementation(project(":shared:ui"))

    // The sync engine's JVM seam (mDNS proximity discovery, TCP transfer
    // channel, the encrypted Quick Share protocol — P2 desktop listener).
    implementation(project(":shared:sync"))

    // Room entities (Episode and friends) surface in DesktopSync's return types,
    // and the module that provides them declares shared:database as
    // implementation-only — so the types are not on this module's compile
    // classpath unless it declares them too. Same rule as shared:core above.
    // Without this the desktop body does not compile at all, which went
    // unnoticed because no CI workflow builds it.
    implementation(project(":shared:database"))

    // The shared desktop sync engine (identity, LAN transport loop, Room
    // journal at ~/.aegis/sync.db, memory materialization, pairing) — one
    // implementation for both the Windows and macOS desktop bodies.
    implementation(project(":shared:desktop-sync"))

    // Coroutines for the interactive prompt loop (runBlocking) and the UI state
    // holders (the window's app scope).
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-core:1.9.0")

    // JSON persistence for goals + execution audit (Phase B3) — the desktop twin of
    // Android's org.json snapshots. Tiny, no transitive deps; FileGoalsStore writes
    // ~/.aegis/goals.json best-effort (corrupt/missing file → honest empty start).
    implementation("org.json:json:20260719")

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