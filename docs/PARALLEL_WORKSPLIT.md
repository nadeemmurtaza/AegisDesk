# PARALLEL WORK-SPLIT — Newax Aegis Assistant (four agents, one repo)

> **General rules for parallel agents live in `docs/PARALLEL_RULES.md`.**
> This document is one *specific* split (four platform bodies). The rules there
> apply to any split, and should be read first.

Date: 2026-08-11. This document partitions the remaining work into **four
parallel tracks — one per device: iOS, Android, macOS, Windows — labeled
I, A, M, W** — and contains the **complete, paste-ready instructions for
Agents W, M, and I**. Agent A (the session that wrote this) keeps Track A.

The previous round was a two-way split (Track A = shared brain + Android body,
Track B = desktop + Windows body). **Track B is renamed Track W** here; its
slices carry over unchanged (B1–B3 → W1–W3). Nothing from that round is lost —
the split just widens from "one shared brain, two bodies" to "one shared
brain, four bodies", which is what the ARCHITECTURE.md platform matrix always
described (Android / Windows / macOS / iOS columns).

---

## 1. The split at a glance

| | **Track I — iOS body** (Agent 4) | **Track A — Android body + shared brain** (Agent 1, this session) | **Track M — macOS body** (Agent 3) | **Track W — Windows body** (Agent 2, ex-Track B) |
|---|---|---|---|---|
| Modules owned | `platform/ios/backend/**` *(new)*, `platform/ios/frontend/**` *(new)* | `shared/core/**`, `platform/android/backend/**`, `platform/android/frontend/**` | `platform/macos/backend/**` *(new)*, `platform/macos/frontend/**` *(new)* | `platform/windows/backend/**`, `platform/windows/frontend/**` |
| Headline | CMP iOS app shell (shared UI) + iOS capability surface (FILES, SECRETS/Keychain, SYSTEM; automation = NOT_SUPPORTED) | **PolicyEngine arc completion** (authority-spine evolution) + Android body — **A1–A8 already landed** | Compose Desktop macOS app (mirrors W's UI) + macOS capability surface (AXUIElement automation, Keychain secrets, GGUF model) | Compose Desktop UI (replaces CLI) + remaining Windows capabilities + desktop persistence |
| Why this work is real | ARCHITECTURE.md platform matrix: iOS row exists with "CMP iOS (shared UI, planned)" and "Native driver (planned)" — zero iOS code exists. No iOS target is even configured in the shared KMP modules. | ARCHITECTURE.md rule 3 corollary (permission vs policy) + rule 10 (PLAN is never EXECUTE). A1–A8 shipped the policy spine; the arc continues with execution-audit surfacing and goal-tier hints. | ARCHITECTURE.md platform matrix: macOS row = Compose Desktop (same as Windows), AXUIElement automation, Keychain secrets — all unimplemented; no macOS module exists. | ARCHITECTURE.md: "Compose Desktop UI pending". platform/windows/backend implements only DESKTOP + the app index (FILES/PROCESSES/SHELL/SECRETS/SYSTEM pending). Desktop goals are in-memory. |
| Slices | **I0** ios targets on shared KMP modules (Phase 0) · I1 CMP iOS app shell + shared UI · I2 iOS capability surface + registration + screens · I3 iOS persistence | A1–A8 landed (PolicyEngine, policy modes UI, contract resolution, rule-10 execution gate, goal persistence, policy-refusal UI, plan-time pre-flight, execution audit) · **A9+** next (audit in Capabilities/Policy screen, goal-tier hints) | M1 Compose Desktop macOS app (Status/Apps/Goals) · M2 macOS capability surface (FILES/PROCESSES/SHELL via JVM stdlib, DESKTOP via AXUIElement behind a seam, SECRETS via Keychain, SYSTEM) + registration + screens · M3 persistence + execution audit | W1 Compose Desktop UI · W2 Windows capability surface (FILES, PROCESSES, SHELL, SECRETS/DPAPI, SYSTEM) + registration · W3 goal persistence + execution audit |
| Key invariants | Shared modules stay platform-free (invariant 5); R13 — every capability ships with its screen; expect/actual balance in every target | Keep `:platform:android:frontend:assembleDebug` green on every slice (invariant 9); R13 | R13 (the window is the surface — no headless capability); keep Android untouched so CI stays green automatically; macOS-only code never leaks into shared commonMain | Same as M; keep Android untouched so CI stays green automatically |

**Why this split:** it matches ARCHITECTURE.md's own platform matrix (one
shared brain, four bodies — Android, Windows, macOS, iOS) and gives every
agent real, non-overlapping work. A and W already exist as buildable modules;
M and I are greenfield and require **Phase 0** (section 2) so the tracks can
run concurrently with zero file overlap afterward.

**Honest constraint — who can build what:**
- **Android (A)** builds anywhere with JDK 17 (CI gate: `assembleDebug`).
- **Windows (W) and macOS (M)** are plain JVM modules — buildable on any JDK-17
  machine; the *native* halves (Win32 UIA, AXUIElement, Keychain, DPAPI) only
  behave on their OS, so they sit behind seams with OS-independent tests.
- **iOS (I) is Mac-only**: Kotlin/Native needs Xcode, and the shared KMP
  modules gain iOS targets in Phase 0. **This sandbox has no JDK and no Xcode**
  — every Gradle step in every track is `UNVERIFIED` here; run the exact
  commands on the right machine / CI.

---

## 2. Phase 0 — setup commit (do this BEFORE the agents start)

The current `settings.gradle.kts` includes only `platform/android/frontend`,
`platform/windows/frontend`, `shared/{core,database,platform-api,model-api}`,
`platform/{android,windows}`. Tracks M and I need modules that do not exist,
and Track I needs iOS targets on the shared KMP modules. **One setup commit
lands all of this so the four tracks never touch the same file again:**

1. **Scaffold the new modules** (deliberate scaffolding is allowed —
   empty adapters ahead of their consumers, named as a known gap in the
   handoff): `platform/macos/backend/` (plain `kotlin("jvm")`, mirroring
   `platform/windows/backend`), `platform/ios/backend/` (KMP: `iosArm64` +
   `iosSimulatorArm64` (+ `iosX64` for Intel simulators)), `platform/macos/frontend/`
   (`kotlin("jvm")` + `application`, mirroring `platform/windows/frontend`),
   `platform/ios/frontend/` (CMP iOS app).
2. **Add the four includes to `settings.gradle.kts`** in the same change
   (R5 — module and registration in the same breath).
3. **Add iOS targets to the shared KMP modules** — `shared/core`,
   `shared/platform-api`, `shared/model-api` gain `iosArm64` +
   `iosSimulatorArm64` (+ `iosX64`). Every `expect` in their commonMain needs
   an `actual` in the new iOS source sets (R4). `shared/database` is **not**
   touched: Room's iOS native driver is "planned" per the matrix — Track I
   does its own storage (I3) and consumes the DB only when the driver lands.
4. **Update `ARCHITECTURE.md`** module map + **`AGENTS.md`** baseline table
   (new modules, Compose Multiplatform desktop version, the new KMP targets)
   in the same commit.
5. **Verify** (on a Mac with JDK 17 + Xcode): `bash scripts/check-invariants.sh`
   (must exit 0), then
   `./gradlew :shared:core:compileKotlinIosArm64 :shared:core:compileKotlinIosSimulatorArm64 :platform:ios:backend:compileKotlinIosSimulatorArm64 :platform:windows:frontend:compileKotlin :platform:macos:frontend:compileKotlin :platform:android:frontend:assembleDebug`.
   Android must stay green (invariant 9) — the iOS targets are additive.

After Phase 0: `settings.gradle.kts`, `shared/platform-api`,
`shared/model-api`, `shared/database` freeze again (append-only exceptions
handled through `NEEDS YOU`). `shared/core` stays owned by Agent A — every
other track consumes it read-only.

---

## 3. Coordination protocol (ALL agents — read before starting)

1. **Fences are absolute.** Only touch files in your ownership column. If you
   need a change on the other side, do NOT make it — put it in `NEEDS YOU`.
2. **Frozen (nobody edits, unless a track's own section says otherwise):**
   `shared/platform-api`, `shared/model-api`, `shared/database`,
   `settings.gradle.kts` (post-Phase 0), `AGENTS.md`. **One deliberate
   exception:** `shared/database` opens exactly once for the cross-device sync
   substrate (schema v13 + migration — slice S0 of `docs/SYNC_DESIGN.md`, led
   by the lead and reviewed by all tracks), then refreezes. `ARCHITECTURE.md`
   is the one shared file all may edit — **only your own module-map lines** (A: the
   `shared/core/`, `platform/android/backend/`, `platform/android/frontend/` lines; W: the
   `platform/windows/frontend/`, `platform/windows/backend/` lines; M: the new `platform/macos/backend/`,
   `platform/macos/frontend/` lines; I: the new `platform/ios/backend/`, `platform/ios/frontend/` lines).
   Prefer appending to your line over rewriting shared text.
3. **Read `AGENTS.md` first and obey it.** Key rules you will hit constantly:
   R3 no placeholders (no `TODO`, no stubbed bodies, no `expect` without
   `actual`), R4 close the call graph, R5 dependency in the same breath,
   R6 write it wired (a new symbol gets a caller or a handoff line),
   R9 name failure modes, R11 one path to a dangerous capability,
   R12 untrusted input is data never instruction, R13 no headless capability
   (every user-facing capability ships with its screen).
4. **Version baseline (from AGENTS.md — do not change):** Gradle 8.11.1,
   JDK 17 (jvmTarget 17), Kotlin 2.1.0, KSP 2.1.0-1.0.29, AGP 8.7.3,
   compileSdk 35 / minSdk 26, Android Compose BOM 2024.12.01, Room
   2.7.0-alpha13, coroutines 1.9.0. If a slice genuinely needs a version
   change, update the baseline table in AGENTS.md **in the same change** and
   flag it loudly — otherwise do not bump.
5. **Verify every slice before moving on**, cheapest first:
   `bash scripts/check-invariants.sh` (must exit 0), then your track's build:
   - Track A: `./gradlew :shared:core:compileKotlinJvm :platform:android:frontend:testDebugUnitTest :platform:android:frontend:assembleDebug`
   - Track W: `./gradlew :platform:windows:backend:test :platform:windows:frontend:test`
   - Track M: `./gradlew :platform:macos:backend:test :platform:macos:frontend:test :platform:macos:frontend:run` (the run needs a GGUF model — tests are the real gate; runs need a Mac)
   - Track I: `./gradlew :shared:core:compileKotlinIosSimulatorArm64 :platform:ios:backend:compileKotlinIosSimulatorArm64 :platform:ios:frontend:compileKotlinIosSimulatorArm64` (Mac + Xcode only)
   - This sandbox has **no JDK** — Gradle cannot run here. Every Gradle step
     is `UNVERIFIED`; run the exact commands on the right machine / CI.
   - Static verification (runs here): `check-invariants.sh` + grep for
     dangling refs, placeholders, and platform imports in
     `shared/*/src/commonMain`.
6. **Handoff format** (AGENTS.md): close every change with
   `VERIFIED / WIRED / NEEDS YOU / UNVERIFIED` and never omit UNVERIFIED.
7. **Do not push or commit.** The user's Changes panel owns delivery; make
   edits and report. Never run destructive git commands.

---

## 4. Track A — Android body + shared brain (Agent 1, this session)

Owned paths: `shared/core/**`, `platform/android/backend/**`, `platform/android/frontend/**`.
Frozen: everything else (except your ARCHITECTURE.md lines).

**Landed so far (A1–A8)** — see ARCHITECTURE.md's `platform/android/frontend` and
`shared/core` lines for the full list: A1 PolicyEngine, A2 policy modes UI +
authority path, A3 planner resolves through the shared contract, A4
execution-time authority (ActionOrigin.AGENT + GoalExecutor policy gate —
rule 10), A5 goal persistence (snapshots → org.json → kv_store), A6 policy
refusals surfaced in the Goals UI (TaskFailureKind + amber treatment +
scroll-to-policy), A7 plan-time policy pre-flight (plan warnings), A8
execution audit trail ("Recent runs" on the Goals screen).

**Next slices (A9+), highest value first:**
- A9 — surface the execution audit in the Capabilities/Policy screen too
  (the audit trail currently lives only on Goals; rule 6 says every
  consequential modification is auditable — put the audit next to the mode
  controls that caused the decisions).
- A10 — goal-level tier hint in the plan card (which `ExecutionTier` each
  task will run on, from the A8 records).
- A11 — per-goal re-plan (edit the goal description, re-plan, keep task
  statuses where the DAG is unchanged).

Keep `:platform:android:frontend:assembleDebug` green on every slice (invariant 9) and
R13 (every capability ships with its screen). No instructions section needed —
this is the session that wrote this document.

---

## 5. Track W — Windows body (Agent 2, ex-Track B)

Owned paths: `platform/windows/backend/**`, `platform/windows/frontend/**`.
Frozen: everything else (except your ARCHITECTURE.md lines). **Never touch
`platform/android/frontend`** — CI's `assembleDebug` gate stays green because you don't.

**State you inherit (all verified by prior slices — read these files before
writing anything):**

- `platform/windows/frontend/src/main/kotlin/Main.kt` — the CLI runner, currently the
  desktop surface. Commands: `status`, `skills`, `plan <goal>`,
  `apps [query]`, `goals`, `run <goal>`, `abandon`. Banner reads Phase 5i.
  `printStatusBlock`/`printSkills`/`printPlan`/`printApps`/`printGoals`/
  `printRunGoal`/`printAbandon` + `progressBar`/`resolveGoal` helpers are the
  UI logic you will lift into Compose.
- `desktop/DesktopCapabilitiesHolder.kt` — one `PlatformCapabilityRegistry`
  per process; `init()` registers `WindowsDesktopCapability()`. **This is the
  file where W2's new capabilities register** (your only platform/windows/frontend file
  that touches the registry — it is in your ownership).
- `desktop/DesktopModelProviderHolder.kt` — one `ModelProvider` per process;
  `set(provider)` / `clear()` / `current()`.
- `desktop/planner/` — `SkillRegistry` (10 skills), `DesktopGoalPlanner`
  (object: `plan/activate/block/abandon/updateTask`,
  `getGraph/getState/planOf/allGoals/activeGoals`), `StateMachine` +
  `GoalState`, `TaskNode`, `TaskGraph` (`topologicalOrder()`, `progress()`,
  `isComplete()`, `hasFailed()`).
- `desktop/execution/` — `DesktopGoalExecutor.run(goalId, registry, router,
  appIndex, onProgress)` (activates, walks tasks topologically, live
  capability gate, pipes outputs, FAILED→block) and `DesktopExecutionRouter`
  (ladder: `EXACT_TARGET` → `PROCESS_LAUNCH` → `WIN32_AUTOMATION`).
- `platform/windows/backend/` — `WindowsDesktopCapability` (Win32 bridge behind the
  `WindowsUiaBridge` seam; NOT_SUPPORTED off-Windows), `WindowsAppIndex`
  (Start Menu enumeration, fuzzy search, behind a seam), GGUF provider trio
  (`GgufModelProvider`, `KherudGgufEngine`, `GgufHeaderParser`),
  `isWindowsOs()`.
- Contract shapes you must code against (read the files — all in frozen
  `shared/platform-api`): `PlatformCapability`/`PlatformCapabilityRegistry`/
  `InMemoryPlatformCapabilityRegistry`, `CapabilityResolver`/`CapabilityResolution`,
  `CapabilityStatus`/`CapabilityResult`/`OperationContext.create(caller, origin)`
  (`ActionOrigin` from `shared/core` — already a platform:windows:frontend dependency),
  `CapabilityId`, `FileCapability`/`ProcessCapability`/`ShellCapability`/
  `SecretsCapability`/`SystemCapability`/`DesktopCapability`, `PrivilegeLevel`.
  **The Android adapters in `platform/android/backend` are your mirror** for how each
  contract is implemented.

### W1 — Compose Desktop UI (the surface, R13)

The CLI is today's UI; replace/augment it with a real Compose Desktop window
that mirrors Android's `CapabilitiesScreen` + `GoalsScreen`:

- Add the Compose Multiplatform desktop plugin + deps to
  `platform/windows/frontend/build.gradle.kts` **only** (pinned per R8: pick the Compose
  plugin version compatible with Kotlin 2.1.0 — e.g. the 1.7.x line — and
  update the AGENTS.md baseline table + this file's notes in the same change;
  do not touch Android's BOM). Keep the CLI runnable (the model loop can live
  behind the window, or keep `Main.kt` as a `--cli` flag path) — do not delete
  existing behavior in the same slice.
- Screens (mirror Android's shapes, but a desktop layout — side nav or tabs):
  1. **Status/Capabilities** — the `printStatusBlock` content: each registered
     capability (name, id, status), the active model (name/format/state/sha),
     plus the new W2 capabilities as they register.
  2. **Apps** — the `WindowsAppIndex` contents with a search box.
  3. **Goals board** — every goal with state label, progress bar, task list,
     blocked-capability warnings; actions: plan input, **Run** (→
     `DesktopGoalExecutor.run` with progress lines surfaced live), **Abandon**.
- Every screen has loading, empty, and error states; no placeholders (R3/R13).
- `platform/windows/frontend/src/test/` — state-holder tests (pure JVM; Compose UI
  itself is a machine-run concern — keep all decision logic in testable
  plain-Kotlin state holders).

### W2 — Windows capability surface (FILES, PROCESSES, SHELL, SECRETS, SYSTEM)

`platform/windows/backend` currently implements only DESKTOP. Implement the rest
behind seams, mirroring the Android adapters and the `WindowsDesktopCapability`
pattern (capability + seam interface + production bridge + fake-bridge tests):

- **FILES** — `FileCapability` over `java.nio.file` (list/read/write/search
  with size/type bounds; typed failures; no path concatenation — R12).
- **PROCESSES** — `ProcessCapability` over `ProcessBuilder`/`Kernel32`
  (list via `tasklist` or JNA, start, kill with guard).
- **SHELL** — `ShellCapability` over `ProcessBuilder("cmd", "/c", ...)` with
  explicit arg arrays (never string concatenation — R12), timeouts, and the
  authority metadata (`OperationContext`) threaded through.
- **SECRETS (DPAPI)** — `SecretsCapability` via DPAPI (JNA `Crypt32`
  `CryptProtectData`/`CryptUnprotectData` — jna-platform ships Crypt32,
  verified). **Credentials are references, never content** (invariant 4):
  expose AVAILABLE/MISSING-style references; never log secret values. This is
  the highest-risk slice — if the DPAPI path cannot be written without
  unverifiable native code, deliver the seam + contract tests + an honest
  NOT_SUPPORTED-status implementation and name the gap in `NEEDS YOU` (never a
  silent stub).
- **SYSTEM** — `SystemCapability` over env/sysinfo (os, arch, memory, disk).
- **Registration + surface**: register each in `DesktopCapabilitiesHolder.init()`
  (your file) and surface them in the W1 status screen — R6/R13.
- Tests: fake-bridge tests per capability in `platform/windows/backend/src/test/`
  mirroring `WindowsDesktopCapabilityTest`; OS-independent (forced statuses,
  injected seams).

### W3 — Desktop goal persistence + execution audit

- **Persistence:** desktop goals/tasks/state must survive restarts. Store to a
  JSON file under `~/.aegis/` (e.g. `goals.json`) from a small store owned by
  `platform/windows/frontend` — **do not touch `shared/database`** (frozen; DB-backed
  persistence is a later round with migrations). `DesktopGoalPlanner` gains
  `save()`/`load(store)` that round-trips goals, task graphs, state machines,
  and plan pre-flights; load on bootstrap, save on every mutation;
  corrupt/missing file → honest empty start (named failure mode).
- **Execution audit:** a `platform:windows:frontend` audit log of `run` executions — goal,
  tasks, tiers used (`EXACT_TARGET`/`PROCESS_LAUNCH`/`WIN32_AUTOMATION`),
  outcomes, timestamps — appended on each executor run and surfaced in the UI
  (a "Recent runs" section on the goals screen). This is the desktop twin of
  Android's A5/A8 (invariant 8) — mirror the Android shapes
  (`GoalSnapshotCodec`/`ExecutionAuditStore`, org.json → file) but stay inside
  `platform/windows/frontend`.
- Tests: round-trip (save → new planner instance → load → identical state),
  corrupt-file recovery, audit append. Pure JVM.

---

## 6. Track M — macOS body (Agent 3)

Owned paths: `platform/macos/backend/**` *(new)*, `platform/macos/frontend/**` *(new)*.
Frozen: everything else (except your ARCHITECTURE.md lines). **Never touch
`platform/android/frontend` or `platform/windows/frontend`** — CI's Android gate and W's work stay
green because you don't.

**What exists to build on:** the JVM patterns are identical to Windows —
read `platform/windows/backend` (your mirror: `WindowsDesktopCapability` seam pattern,
`WindowsAppIndex`, the GGUF provider trio) and `platform/windows/frontend`
(the CLI runner whose `print*` functions are the UI logic). The contracts in
frozen `shared/platform-api` are the same list as Track W section 5. **Phase 0
must have landed first** — your modules exist as scaffolding, empty adapters
ahead of their consumers.

**Platform reality (from ARCHITECTURE.md matrix):** macOS UI = Compose
Desktop (same JVM stack as W), desktop automation = AXUIElement, secrets =
Keychain, DB (Room KMP bundled) = ✅. Everything else (brain, memory) = ✅.

### M1 — Compose Desktop macOS app (the surface, R13)

Mirror W1's screens but as a macOS app:
- Add Compose Multiplatform desktop plugin + deps to `platform/macos/frontend/build.gradle.kts`
  (same version decision as W1 — one Compose desktop version shared by both
  desktop apps; note it once in AGENTS.md's baseline table, not twice).
- Screens: **Status/Capabilities** (registry + model state), **Apps**
  (macOS app index — see M2), **Goals board** (progress bars, warnings,
  Plan/Run/Abandon). All decision logic in plain-Kotlin testable state
  holders; loading/empty/error states everywhere; no placeholders (R3/R13).
- The model provider for macOS is the GGUF path — the `de.kherud:java-llama.cpp`
  binding bundles a `.dylib` for macOS, so `platform/macos/backend` mirrors
  `platform/windows/backend`' `GgufModelProvider`/`KherudGgufEngine`/`GgufHeaderParser`
  behind the same `ModelProvider` contract.

### M2 — macOS capability surface (FILES, PROCESSES, SHELL, DESKTOP, SECRETS, SYSTEM)

Mirror the Android adapters + the `WindowsDesktopCapability` pattern
(capability + seam + production bridge + fake-bridge tests):

- **FILES** — `FileCapability` over `java.nio.file` (same shape as W2's;
  typed failures, no path concatenation — R12).
- **PROCESSES** — `ProcessCapability` over `ProcessBuilder` (list via
  `ps`, start, kill with guard).
- **SHELL** — `ShellCapability` over `ProcessBuilder("/bin/zsh", "-c", ...)`
  or `["/bin/sh", "-c", ...]` with explicit arg arrays, timeouts, and
  `OperationContext` threaded through.
- **DESKTOP** — `DesktopCapability` over **AXUIElement** (the macOS
  accessibility API) behind a `MacUiaBridge` seam — mirror
  `WindowsDesktopCapability`. If the AXUIElement path cannot be written
  without unverifiable native code, deliver the seam + contract tests + an
  honest NOT_SUPPORTED-status implementation and name the gap in `NEEDS YOU`.
- **SECRETS (Keychain)** — `SecretsCapability` via the `security` CLI
  (`security add-generic-password`/`find-generic-password`) or the JNA
  Security framework bindings. Credentials are references, never content
  (invariant 4); never log secret values.
- **SYSTEM** — `SystemCapability` over env/sysinfo (os, arch, memory, disk —
  `com.apple` sysctl or JVM `OperatingSystemMXBean`).
- **Registration + surface**: register each in a macOS
  `MacCapabilitiesHolder.init()` (your file) and surface them in the M1
  status screen — R6/R13.
- Tests: fake-bridge tests per capability in `platform/macos/backend/src/test/`
  mirroring `WindowsDesktopCapabilityTest`; OS-independent (forced statuses,
  injected seams). The seam keeps everything except the real bridge testable
  on Linux/CI.

### M3 — macOS persistence + execution audit

Same contract as W3 (desktop goals survive restarts + "Recent runs" audit) —
store under `~/Library/Application Support/Aegis/` (or `~/.aegis/` for
parity with W) as JSON; round-trip + corrupt-file + audit-append tests, pure
JVM. **Do not touch `shared/database`** (frozen; DB-backed persistence is a
later round).

---

## 7. Track I — iOS body (Agent 4)

Owned paths: `platform/ios/backend/**` *(new)*, `platform/ios/frontend/**` *(new)*.
Frozen: everything else (except your ARCHITECTURE.md lines). **Phase 0 must
have landed first** — it added `iosArm64`/`iosSimulatorArm64` (+ `iosX64`)
targets to `shared/core`, `shared/platform-api`, `shared/model-api`, and
scaffolded your two modules.

**Platform reality (from ARCHITECTURE.md matrix):** iOS UI = CMP iOS (shared
UI, planned), DB = native driver **(planned — you cannot use
`shared/database` this round)**; desktop automation = **❌ none** (your
DesktopCapability reports NOT_SUPPORTED); SMS/notifications = ❌; secrets =
Keychain; brain/memory = ✅ via the shared KMP modules.

**Contract shapes (read the files — all in frozen `shared/platform-api`):**
same list as Track W section 5 (`PlatformCapability`, `CapabilityResolver`,
`CapabilityStatus`/`CapabilityResult`, `OperationContext`,
`FileCapability`/`ProcessCapability`/`ShellCapability`/`SecretsCapability`/
`SystemCapability`/`DesktopCapability`, `PrivilegeLevel`). `ActionOrigin`
comes from `shared/core` — already consumable once Phase 0 adds the iOS
target. **The Android adapters in `platform/android/backend` are your mirror** for
how each contract is implemented.

### I1 — CMP iOS app shell + shared UI

- Build the iOS app with Compose Multiplatform for iOS (the `composeApp`
  pattern: shared Compose UI + iOS entrypoint in `platform/ios/frontend`). Mirror
  Android's screens (Capabilities + Goals) — shared UI is the whole point of
  CMP; where Android and iOS differ, keep the differences behind
  expect/actual or platform source sets, never `if (platform)` in commonMain.
- Every screen has loading, empty, and error states; no placeholders (R3/R13).
- Keep the UI logic in plain-Kotlin state holders (testable in commonTest /
  iosTest), mirroring Android's `GoalsScreen` state handling.

### I2 — iOS capability surface (FILES, SECRETS, SYSTEM; DESKTOP = NOT_SUPPORTED)

Mirror the Android adapters; Kotlin/Native interop where needed:

- **FILES** — `FileCapability` over Foundation (`NSFileManager`) via
  kotlinx.cinterop, or over the KMP `okio`/`kotlinx-io` layer if the shared
  modules already use it. Typed failures; no path concatenation (R12).
- **PROCESSES** — iOS has no process API for apps (sandbox): `ProcessCapability`
  reports NOT_SUPPORTED honestly (named failure mode), or implement only the
  parts the OS allows.
- **SHELL** — no shell on iOS (sandbox): NOT_SUPPORTED.
- **SECRETS (Keychain)** — `SecretsCapability` via the Security framework
  (`SecItemAdd`/`SecItemCopyMatching`/`SecItemDelete` through cinterop)
  behind a seam. Credentials are references, never content (invariant 4).
- **SYSTEM** — `SystemCapability` over `UIDevice`/`NSProcessInfo` (os, arch,
  memory, disk).
- **DESKTOP** — no desktop automation on iOS: NOT_SUPPORTED (matrix: ❌ none).
- **Registration + surface**: register each in an iOS
  `IosCapabilitiesHolder.init()` (your file) and surface them in the I1
  screens — R6/R13.
- Tests: seam/fake tests in `platform/ios/backend/src/iosTest/` (and commonTest for
  the pure logic), mirroring the Android/Windows fake-bridge tests.

### I3 — iOS persistence

`shared/database`'s native driver is planned, not landed — so store iOS state
locally without Room: goals/audit as JSON under Application Support
(`NSFileManager` URLs), mirroring the A5/A8 shapes (snapshots → org.json).
Round-trip + corrupt-file tests in iosTest. When the Room iOS driver lands
(later round, through the lead — not you), swap the store behind the same
interface.

---

## 8. AGENT INSTRUCTIONS (paste into each Agent 2/3/4 session)

### Agent W (Windows body)

> You are Agent 2 of a four-agent parallel effort on the Newax Aegis Assistant
> repository (Kotlin/KMP: Android + Windows + macOS + iOS bodies, shared KMP
> brain). Your track is **Track W — Windows body** (formerly Track B). Agent A
> owns Android + `shared/core`; you must **never edit those**.
>
> **FIRST: read, in order** — `AGENTS.md` (binding rules; R3/R4/R5/R6/R9/R11/R12/R13
> will gate every slice), `ARCHITECTURE.md` (invariants + module map), and
> `docs/PARALLEL_WORKSPLIT.md` (your full brief: section 3 coordination
> protocol, section 5 track details). Then read the "State you inherit" file
> list in section 5 before writing anything — those are the exact shapes you
> build on.
>
> **Your ownership (edit only these):** `platform/windows/backend/**` and
> `platform/windows/frontend/**`. Frozen (never edit): `shared/**`, `platform/android/frontend/**`,
> `settings.gradle.kts`, `AGENTS.md`, and all of `docs/` except
> `ARCHITECTURE.md`'s `platform/windows/frontend/` and `platform/windows/backend/` module-map
> lines. `shared/platform-api` is read-only — it defines the contracts you
> implement; read it constantly.
>
> **Slices, in order (each is a complete, verifiable unit — finish it before
> starting the next; no placeholders ever):**
>
> 1. **W1 — Compose Desktop UI.** Add Compose Multiplatform (desktop) to
>    `platform/windows/frontend/build.gradle.kts` with a version compatible with Kotlin
>    2.1.0; update the AGENTS.md baseline table in the same change (your only
>    AGENTS.md touch — a version note, not a rule). Build three screens that
>    lift the existing CLI logic into a window: Status/Capabilities (registry +
>    model state), Apps (WindowsAppIndex with search), Goals board (progress
>    bars, warnings, Plan/Run/Abandon). All decision logic stays in
>    plain-Kotlin testable state holders. Keep the CLI runnable this slice.
>    R13: the window is the surface — every capability you register in W2 must
>    appear here in W2's slice.
> 2. **W2 — Windows capabilities.** Implement FILES, PROCESSES, SHELL,
>    SECRETS (DPAPI via JNA Crypt32 — the seam + honest fallback if the native
>    path is unverifiable; never a stub), and SYSTEM capabilities in
>    `platform/windows/backend`, mirroring the Android adapters in `platform/android/backend`
>    and the `WindowsDesktopCapability` seam pattern. Register them in
>    `DesktopCapabilitiesHolder.init()` and surface them in the W1 status
>    screen. Credentials stay references, never content. Contract tests per
>    capability, OS-independent via injected seams.
> 3. **W3 — Persistence + audit.** Goals/tasks/state survive restarts via a
>    JSON store under `~/.aegis/` (NOT `shared/database`); an execution audit
>    log (goal, tasks, tiers, outcomes, timestamps) appended per `run` and
>    surfaced as "Recent runs" in the UI. Mirror Android's A5/A8 shapes.
>    Round-trip and corrupt-file tests.
>
> **Verification per slice** (this sandbox has no JDK — Gradle steps are
> `UNVERIFIED`; run them on a JDK-17 machine / CI): `bash scripts/check-invariants.sh`
> must exit 0; `./gradlew :platform:windows:backend:test :platform:windows:frontend:test`; keep
> Android untouched so CI's `assembleDebug` stays green. Static greps: no
> placeholders in new files, no dangling references, no platform imports in
> `shared/*/src/commonMain` (you never touch shared, so this stays green).
>
> **Handoff:** close every slice with the AGENTS.md block
> (VERIFIED / WIRED / NEEDS YOU / UNVERIFIED). Put anything you need from
> Agent A's side (shared/core changes, contract tweaks) in `NEEDS YOU` — do
> not implement it yourself. Do not commit or push; the Changes panel owns
> delivery. If a slice is bigger than one session, deliver the first complete
> slice and describe the next exactly — never a skeleton.

### Agent M (macOS body)

> You are Agent 3 of a four-agent parallel effort on the Newax Aegis Assistant
> repository (Kotlin/KMP: Android + Windows + macOS + iOS bodies, shared KMP
> brain). Your track is **Track M — macOS body**. Agent A owns Android +
> `shared/core`; you must **never edit those**.
>
> **FIRST: read, in order** — `AGENTS.md` (binding rules), `ARCHITECTURE.md`
> (invariants + module map + platform matrix), and
> `docs/PARALLEL_WORKSPLIT.md` (section 3 coordination protocol, section 6
> track details). **Phase 0 must have landed** — `platform/macos/backend` and
> `platform/macos/frontend` exist as scaffolding; if they don't, stop and tell the user
> Phase 0 is missing.
>
> **Your mirror:** `platform/windows/backend` (the `WindowsDesktopCapability` seam
> pattern, `WindowsAppIndex`, the GGUF provider trio) and `platform/windows/frontend`
> (the CLI runner whose `print*` functions are the UI logic). Your modules
> mirror them 1:1 with macOS natives (AXUIElement, Keychain, `/bin/zsh`).
> Read `platform/android/backend`'s adapters too — they show the contract shapes.
>
> **Your ownership (edit only these):** `platform/macos/backend/**` and
> `platform/macos/frontend/**`. Frozen (never edit): `shared/**`, `platform/android/frontend/**`,
> `platform/windows/frontend/**`, `platform/windows/backend/**`, `settings.gradle.kts`,
> `AGENTS.md`, and all of `docs/` except `ARCHITECTURE.md`'s
> `platform/macos/backend/` and `platform/macos/frontend/` module-map lines. `shared/platform-api`
> is read-only — it defines the contracts you implement.
>
> **Slices, in order (each complete and verifiable; no placeholders):**
>
> 1. **M1 — Compose Desktop macOS app.** Add Compose Multiplatform (desktop)
>    to `platform/macos/frontend/build.gradle.kts` (same version decision as Track W's
>    W1 — one desktop Compose version for both desktop apps; if W1 already
>    pinned it in AGENTS.md's baseline table, reuse it without touching the
>    table). Screens: Status/Capabilities, Apps (your macOS app index), Goals
>    board (Plan/Run/Abandon with live progress). Decision logic in
>    plain-Kotlin state holders; loading/empty/error states; R13.
> 2. **M2 — macOS capabilities.** FILES/PROCESSES/SHELL via the JVM stdlib,
>    DESKTOP via AXUIElement behind a `MacUiaBridge` seam (honest
>    NOT_SUPPORTED fallback if the native path is unverifiable — never a
>    stub), SECRETS via Keychain (`security` CLI or JNA Security framework;
>    references only, never content), SYSTEM via sysinfo. Register in
>    `MacCapabilitiesHolder.init()` and surface in the M1 status screen.
>    Fake-bridge contract tests, OS-independent.
> 3. **M3 — Persistence + audit.** JSON store under
>    `~/Library/Application Support/Aegis/` (or `~/.aegis/` for parity with
>    W3); "Recent runs" audit appended per run and surfaced in the UI.
>    Round-trip, corrupt-file, and audit-append tests.
>
> **Verification per slice** (this sandbox has no JDK — Gradle steps are
> `UNVERIFIED`; run them on a Mac with JDK 17): `bash scripts/check-invariants.sh`
> must exit 0; `./gradlew :platform:macos:backend:test :platform:macos:frontend:test`. The
> native halves (AXUIElement, Keychain) are behind seams — the fake-bridge
> tests run anywhere. Keep Android and Windows untouched.
>
> **Handoff:** close every slice with the AGENTS.md block
> (VERIFIED / WIRED / NEEDS YOU / UNVERIFIED). Do not commit or push; the
> Changes panel owns delivery. If a slice is bigger than one session, deliver
> the first complete slice and describe the next exactly — never a skeleton.

### Agent I (iOS body)

> You are Agent 4 of a four-agent parallel effort on the Newax Aegis Assistant
> repository (Kotlin/KMP: Android + Windows + macOS + iOS bodies, shared KMP
> brain). Your track is **Track I — iOS body**. Agent A owns Android +
> `shared/core`; you must **never edit those**.
>
> **FIRST: read, in order** — `AGENTS.md` (binding rules), `ARCHITECTURE.md`
> (invariants + module map + platform matrix — note the iOS row: CMP UI
> planned, DB native driver planned, automation ❌ none), and
> `docs/PARALLEL_WORKSPLIT.md` (section 3 coordination protocol, section 7
> track details). **Phase 0 must have landed** — iOS targets exist on
> `shared/core`, `shared/platform-api`, `shared/model-api`, and your modules
> are scaffolded. If not, stop and tell the user Phase 0 is missing.
>
> **Your mirror:** `platform/android/backend`'s adapters show the contract shapes;
> Android's `CapabilitiesScreen`/`GoalsScreen` are the UI reference. Kotlin/
> Native interop (kotlinx.cinterop) is how you reach Foundation/Security/
> UIKit; keep every interop detail in `platform/ios/backend` and `platform/ios/frontend` iOS
> source sets — **never** in shared commonMain (invariant 5).
>
> **Your ownership (edit only these):** `platform/ios/backend/**` and
> `platform/ios/frontend/**`. Frozen (never edit): `shared/**`, `platform/android/frontend/**`,
> `platform/windows/frontend/**`, `platform/windows/backend/**`, `platform/macos/backend/**`,
> `settings.gradle.kts`, `AGENTS.md`, and all of `docs/` except
> `ARCHITECTURE.md`'s `platform/ios/backend/` and `platform/ios/frontend/` module-map lines.
> `shared/platform-api` is read-only — it defines the contracts you implement.
>
> **Slices, in order (each complete and verifiable; no placeholders):**
>
> 1. **I1 — CMP iOS app shell + shared UI.** Build the iOS app (Compose
>    Multiplatform iOS pattern: shared Compose UI + iOS entrypoint), mirroring
>    Android's Capabilities + Goals screens. Loading/empty/error states
>    everywhere; UI logic in plain-Kotlin state holders testable in iosTest.
> 2. **I2 — iOS capabilities.** FILES via Foundation (`NSFileManager`),
>    SECRETS via Keychain (`SecItem*` through cinterop) behind a seam,
>    SYSTEM via `UIDevice`/`NSProcessInfo`, DESKTOP/PROCESSES/SHELL → honest
>    NOT_SUPPORTED (iOS sandbox / matrix says none). Register in
>    `IosCapabilitiesHolder.init()` and surface in the I1 screens. Seam/fake
>    tests; credentials are references, never content (invariant 4).
> 3. **I3 — iOS persistence.** No Room on iOS yet (native driver planned) —
>    store goals/audit as JSON under Application Support, mirroring Android's
>    A5/A8 shapes behind a store interface you own. Round-trip + corrupt-file
>    tests in iosTest.
>
> **Verification per slice** (this sandbox has no JDK and no Xcode — Gradle
> steps are `UNVERIFIED`; run them on a Mac with JDK 17 + Xcode):
> `bash scripts/check-invariants.sh` must exit 0;
> `./gradlew :shared:core:compileKotlinIosSimulatorArm64 :platform:ios:backend:compileKotlinIosSimulatorArm64 :platform:ios:frontend:compileKotlinIosSimulatorArm64`.
> Keep Android and the desktop tracks untouched.
>
> **Handoff:** close every slice with the AGENTS.md block
> (VERIFIED / WIRED / NEEDS YOU / UNVERIFIED). Do not commit or push; the
> Changes panel owns delivery. If a slice is bigger than one session, deliver
> the first complete slice and describe the next exactly — never a skeleton.
