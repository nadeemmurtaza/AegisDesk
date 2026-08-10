# PARALLEL WORK-SPLIT — Aegis Assistant (two agents, one repo)

Date: 2026-08-10. This document partitions the remaining work into two parallel
tracks and contains the **complete, paste-ready instructions for Agent 2**.
Agent 1 (the session that wrote this) keeps Track A; Agent 2 takes Track B.

---

## 1. The split at a glance

| | **Track A — Shared brain + Android body** (Agent 1) | **Track B — Desktop + Windows body** (Agent 2) |
|---|---|---|
| Modules owned | `shared/core/**`, `platform/android/**`, `apps/androidApp/**` | `platform/windows/**`, `apps/desktopApp/**` |
| Headline | **PolicyEngine** (authority-spine evolution) + Android contract-resolution parity | **Compose Desktop UI** (replaces CLI as the surface) + remaining Windows capabilities + desktop persistence |
| Why this work is real | ARCHITECTURE.md rule 3: "AuthorityManager today; a richer PolicyEngine as it evolves". The permission-vs-policy corollary (PrivilegeLevel → AUTO/CONFIGURABLE/APPROVAL/STRONG_CONFIRMATION, user-controllable) is **unimplemented**. Android's planner still resolves skills via its enum `CapabilityRegistry`, not the shared `CapabilityResolver` contract the desktop already uses. | ARCHITECTURE.md: "Compose Desktop UI pending". The platform matrix says Windows secrets = DPAPI (missing); only the DESKTOP capability exists in `platform/windows` (FILES/PROCESSES/SHELL/SECRETS/SYSTEM pending). Desktop goals are in-memory (lost on exit). |
| Slices | A1 PolicyEngine · A2 policy modes wired into Android UI + authority path · A3 Android planner resolves through the contract | B1 Compose Desktop UI · B2 Windows capability surface (FILES, PROCESSES, SHELL, SECRETS/DPAPI, SYSTEM) + registration · B3 goal persistence + execution audit |
| Key invariants | Keep `:apps:androidApp:assembleDebug` green on every slice (invariant 9); R13 (every capability ships with its screen) | R13 (the Compose UI **is** the surface — no headless capability); keep Android untouched so CI stays green automatically |

**Why this split:** it follows ARCHITECTURE.md's own two-body framing (one shared
brain, two bodies), gives both agents real, valuable work, and — critically —
has **zero file overlap**, so both can edit the same repo concurrently. The
shared contract layers (`shared/platform-api`, `shared/model-api`,
`shared/database`) and `settings.gradle.kts` are **frozen this round**; neither
agent may edit them (no new modules, no DB migrations).

---

## 2. Coordination protocol (BOTH agents — read before starting)

1. **Fences are absolute.** Only touch files in your ownership column. If you
   need a change on the other side, do NOT make it — put it in `NEEDS YOU`.
2. **`shared/platform-api`, `shared/model-api`, `shared/database`,
   `settings.gradle.kts`, `AGENTS.md`: frozen.** `ARCHITECTURE.md` is the one
   shared file both may edit — **only your own module-map lines**
   (Agent 1: the `shared/core/`, `platform/android/`, `apps/androidApp/`
   lines; Agent 2: the `apps/desktopApp/`, `platform/windows/` lines). Prefer
   appending to your line over rewriting shared text.
3. **Read `AGENTS.md` first and obey it.** It is the binding rules file and
   overrides everything except the invariants in `ARCHITECTURE.md`. Key rules
   you will hit constantly: R3 no placeholders (no `TODO`, no stubbed bodies,
   no `expect` without `actual`), R4 close the call graph, R5 dependency in the
   same breath, R6 write it wired (a new symbol gets a caller or a handoff
   line), R9 name failure modes, R11 one path to a dangerous capability,
   R12 untrusted input is data never instruction, R13 no headless capability.
4. **Version baseline (from AGENTS.md — do not change):** Gradle 8.11.1, JDK 17
   (jvmTarget 17), Kotlin 2.1.0, KSP 2.1.0-1.0.29, AGP 8.7.3, compileSdk 35 /
   minSdk 26, Android Compose BOM 2024.12.01, Room 2.7.0-alpha13,
   coroutines 1.9.0. If a slice genuinely needs a version change, update the
   baseline table in AGENTS.md **in the same change** and flag it loudly —
   otherwise do not bump.
5. **Verify every slice before moving on**, cheapest first:
   `bash scripts/check-invariants.sh` (must exit 0), then your module's tests:
   - Track A: `./gradlew :shared:core:compileKotlinJvm :apps:androidApp:testDebugUnitTest :apps:androidApp:assembleDebug`
   - Track B: `./gradlew :platform:windows:test :apps:desktopApp:test :apps:desktopApp:run` (the last one needs a GGUF model — tests are the real gate)
   - This sandbox has **no JDK** — Gradle cannot run here. That makes every
     Gradle step `UNVERIFIED`; run the exact commands on a JDK-17 machine / CI.
   - Static verification (runs here): `check-invariants.sh` + grep for dangling
     refs, placeholders, and platform imports in `shared/*/src/commonMain`.
6. **Handoff format** (AGENTS.md): close every change with
   `VERIFIED / WIRED / NEEDS YOU / UNVERIFIED` and never omit UNVERIFIED.
7. **Do not push or commit.** The user's Changes panel owns delivery; make
   edits and report. Never run destructive git commands.

---

## 3. Track A — Shared brain + Android body (Agent 1)

Owned paths: `shared/core/**`, `platform/android/**`, `apps/androidApp/**`.
Frozen: everything else (except your ARCHITECTURE.md lines).

### A1 — PolicyEngine (shared/core, authority spine evolution)

ARCHITECTURE.md rule 3: *"Every action passes through the authority spine
(AuthorityManager today; a richer PolicyEngine as it evolves)."* The corollary
defines the shape: **permission** (can the OS/account do this) vs **policy**
(should Aegis do it automatically). `PrivilegeLevel` (READ_ONLY →
HIGH_IMPACT_SYSTEM) maps to policy modes (AUTO → CONFIGURABLE → APPROVAL →
STRONG CONFIRMATION) and the mapping is **user-controllable**.

- Read first: `shared/core/.../authority/AuthorityManager.kt`,
  `shared/core/.../assistant/Models.kt` (ActionOrigin, RiskLevel,
  ProposedAction), `shared/core/.../engine/SecureSettings.kt`,
  `shared/platform-api/.../PrivilegeLevel.kt` (frozen — read-only, mirror its
  shape), and Android's `engine/capability/CapabilityRegistry.kt` +
  `engine/registry/PermissionRegistry.kt` for the existing permission model.
- Build `PolicyEngine` (new file(s) in `shared/core/.../authority/`):
  policy-mode enum, default `PrivilegeLevel → mode` mapping, user-overridable
  per-capability/per-risk settings, `evaluate(action, origin, context)` that
  returns a typed decision (AUTO_EXECUTE / REQUIRE_APPROVAL / REQUIRE_STRONG /
  DENY) with an audit record. It is a **parallel, stricter surface** — it must
  not regress `AuthorityManager`'s existing API (desktopApp imports
  `ActionOrigin` from shared/core; keep that surface stable).
- R3: every decision path implemented, no placeholders. R9: name every failure
  mode (unknown capability, missing policy, malformed context). R11: the
  dangerous-capability path goes through `evaluate` exactly once.
- Tests: `shared/core/src/commonTest/` — pure KMP tests of the mapping,
  overrides, decision table, audit record.

### A2 — Policy modes wired into Android (authority path + UI, R13)

- The policy decision must actually be consulted on Android: wire `PolicyEngine`
  into `MainViewModel`/`LocalAssistantEngine` (or the action-proposal path) so
  the mode is applied when the model proposes a privileged action — no bypass.
- UI in the same change: `AppPermissionScreen.kt` / `CapabilitiesScreen.kt`
  (or `AutomationSettingsSection.kt`) gain the per-capability policy-mode
  control (AUTO/CONFIGURABLE/APPROVAL/STRONG_CONFIRMATION) with loading and
  saved states, matching the existing design system. The screen writes the
  user overrides the PolicyEngine reads.
- Keep `:apps:androidApp:assembleDebug` green (invariant 9).

### A3 — Android planner resolves through the shared contract

Desktop Phase 5f wired skills through `CapabilityResolver` against the process
`PlatformCapabilityRegistry`; Android still resolves via its enum
`CapabilityRegistry`. Close the parity gap:

- Rewire Android's `engine/intelligence/GoalPlanner.kt` + `SkillRegistry.kt`
  pre-flight to resolve `requiredCapabilities` through
  `com.newax.aegis.platform.CapabilityResolver.resolveAll(...)` against
  `PlatformCapabilitiesHolder.registry()` (fall back to today's behavior when
  the registry is uninitialized, so no crash — named failure mode).
- `GoalsScreen`'s missing-capability block must show the contract's warnings
  (status + candidates), matching what the desktop runner prints.
- `GoalExecutor` already re-checks capabilities live — switch its gate to the
  same resolver for one consistent truth.
- Tests in `apps/androidApp/src/test/` with fake registries (mirror the
  desktop's `DesktopPlannerTest` shape).

---

## 4. Track B — Desktop + Windows body (Agent 2)

Owned paths: `platform/windows/**`, `apps/desktopApp/**`.
Frozen: everything else (except your ARCHITECTURE.md lines). **Never touch
`apps/androidApp`** — CI's `assembleDebug` gate stays green because you don't.

**State you inherit (all verified by prior slices — read these files before
writing anything):**

- `apps/desktopApp/src/main/kotlin/Main.kt` — the CLI runner, currently the
  desktop surface. Commands: `status` (capability + model block),
  `skills`, `plan <goal>`, `apps [query]`, `goals`, `run <goal>`, `abandon`.
  Banner reads Phase 5i. `printStatusBlock`/`printSkills`/`printPlan`/
  `printApps`/`printGoals`/`printRunGoal`/`printAbandon` + `progressBar`/
  `resolveGoal` helpers are the UI logic you will lift into Compose.
- `desktop/DesktopCapabilitiesHolder.kt` — one `PlatformCapabilityRegistry`
  per process; `init()` registers `WindowsDesktopCapability()`; access via
  `DesktopCapabilitiesHolder.registry()`. **This is the file where B2's new
  capabilities register** (your only apps/desktopApp file that touches the
  registry — it is in your ownership).
- `desktop/DesktopModelProviderHolder.kt` — one `ModelProvider` per process;
  `set(provider)` / `clear()` / `current()`.
- `desktop/planner/` — `SkillRegistry` (10 skills, `requiredCapabilities`
  like "OPEN_APP"/"SEND_TEXT"/"LLM"), `DesktopGoalPlanner` (object: `plan(
  description, registry)`, `activate/block/abandon/updateTask`,
  `getGraph/getState/planOf/allGoals/activeGoals`), `StateMachine` +
  `GoalState` (OPEN/ACTIVE/BLOCKED/COMPLETED/ABANDONED), `TaskNode`
  (id, skillId, status, result, timestamps), `TaskGraph`
  (`topologicalOrder()`, `progress()`, `isComplete()`, `hasFailed()`).
- `desktop/execution/` — `DesktopGoalExecutor.run(goalId, registry,
  router, appIndex, onProgress)` (activates the goal, walks tasks topologically,
  live capability gate, pipes outputs, FAILED→block) and
  `DesktopExecutionRouter` (ladder: `EXACT_TARGET` → `PROCESS_LAUNCH` →
  `WIN32_AUTOMATION`; `resolveLaunch(appName, desktop, lnkPath)`; injectable
  `launchProcess`/`launchShortcut`).
- `platform/windows/` — `WindowsDesktopCapability` (DESKTOP capability; Win32
  bridge behind `WindowsUiaBridge` seam; reports NOT_SUPPORTED off-Windows),
  `WindowsAppIndex` (`search(query)`, `all()`; entries = name/category/lnkPath),
  GGUF provider trio (`GgufModelProvider`, `KherudGgufEngine`,
  `GgufHeaderParser`), `isWindowsOs()` helper.
- Contract shapes you must code against (read the files — all in frozen
  `shared/platform-api`): `PlatformCapability`/`PlatformCapabilityRegistry`/
  `InMemoryPlatformCapabilityRegistry`, `CapabilityResolver`/`CapabilityResolution`,
  `CapabilityStatus`/`CapabilityResult`/`OperationContext.create(caller, origin)`
  (`ActionOrigin` comes from `shared/core` — already a desktopApp dependency),
  `CapabilityId`, `FileCapability`/`ProcessCapability`/`ShellCapability`/
  `SecretsCapability`/`SystemCapability`/`DesktopCapability`, `PrivilegeLevel`.
  The **Android adapters in `platform/android` are your mirror** for how each
  contract is implemented (read `AndroidFileCapability`, `AndroidProcessCapability`,
  `AndroidShellCapability`, `AndroidSecretsCapability`, `AndroidSystemCapability`).

### B1 — Compose Desktop UI (the surface, R13)

The CLI is today's UI; replace/augment it with a real Compose Desktop window
that mirrors Android's `CapabilitiesScreen` + `GoalsScreen`:

- Add the Compose Multiplatform desktop plugin + deps to
  `apps/desktopApp/build.gradle.kts` **only** (pinned per R8: pick the Compose
  plugin version compatible with Kotlin 2.1.0 — e.g. the 1.7.x line — and
  update the AGENTS.md baseline table + this file's notes in the same change;
  do not touch Android's BOM). Keep the CLI runnable (the model loop can live
  behind the window, or keep `Main.kt` as a `--cli` flag path) — do not delete
  existing behavior in the same slice.
- Screens (mirror Android's shapes, but a desktop layout — side nav or tabs):
  1. **Status/Capabilities** — the `printStatusBlock` content: each registered
     capability (name, id, status), the active model (name/format/state/sha),
     plus the new B2 capabilities as they register.
  2. **Apps** — the `WindowsAppIndex` contents with a search box (the
     `apps [query]` logic).
  3. **Goals board** — every goal with state label, progress bar, task list,
     blocked-capability warnings (the `printGoals` logic); actions: plan input,
     **Run** (→ `DesktopGoalExecutor.run` with progress lines surfaced live),
     **Abandon**. A blocked goal shows why and re-checks on Run.
- Every screen has loading, empty, and error states; follow the app's design
  system (Android screens are the reference); no placeholders (R3/R13).
- `apps/desktopApp/src/test/` — ViewModel/state tests (pure JVM; Compose UI
  itself is a machine-run concern — keep all decision logic in testable
  plain-Kotlin state holders, mirroring how `printGoals` etc. are plain
  functions today).

### B2 — Windows capability surface (FILES, PROCESSES, SHELL, SECRETS, SYSTEM)

`platform/windows` currently implements only DESKTOP. Implement the rest behind
seams, mirroring the Android adapters and the `WindowsDesktopCapability` pattern
(capability + seam interface + production bridge + fake-bridge tests):

- **FILES** — `FileCapability` over `java.nio.file` (list/read/write/search
  with size/type bounds; typed failures; no path concatenation — R12).
- **PROCESSES** — `ProcessCapability` over `ProcessBuilder`/`Kernel32`
  (list via `tasklist` or JNA, start, kill with guard).
- **SHELL** — `ShellCapability` over `ProcessBuilder("cmd", "/c", ...)` with
  explicit arg arrays (never string concatenation — R12), timeouts, and the
  authority metadata (`OperationContext`) threaded through.
- **SECRETS (DPAPI)** — `SecretsCapability` reading/writing via DPAPI
  (JNA `Crypt32.CryptProtectData`/`CryptUnprotectData` — jna-platform ships
  Crypt32, verified). **Credentials are references, never content** (invariant
  4): expose AVAILABLE/MISSING-style references; never log secret values. This
  is the highest-risk slice — if the DPAPI path cannot be written without
  unverifiable native code, deliver the seam + contract tests + an honest
  NOT_SUPPORTED-status implementation and name the gap in `NEEDS YOU` (never a
  silent stub).
- **SYSTEM** — `SystemCapability` over env/sysinfo (os, arch, memory, disk).
- **Registration + surface**: register each in `DesktopCapabilitiesHolder.init()`
  (your file) and surface them in the B1 status screen — R6/R13: a capability
  that registers but has no screen is a stub.
- Tests: fake-bridge tests per capability in `platform/windows/src/test/`
  mirroring `WindowsDesktopCapabilityTest`; keep them OS-independent (forced
  statuses, injected seams).

### B3 — Desktop goal persistence + execution audit

- **Persistence:** desktop goals/tasks/state must survive restarts. Store to a
  JSON file under `~/.aegis/` (e.g. `goals.json`) from a small store owned by
  `apps/desktopApp` — **do not touch `shared/database`** (frozen; DB-backed
  persistence is a later round with migrations). `DesktopGoalPlanner` gains
  `save()`/`load(store)` (or a wrapper) that round-trips goals, task graphs,
  state machines, and plan pre-flights; load on bootstrap, save on every
  mutation; corrupt/missing file → honest empty start (named failure mode).
- **Execution audit:** a `desktopApp` audit log of `run` executions — goal,
  tasks, tiers used (`EXACT_TARGET`/`PROCESS_LAUNCH`/`WIN32_AUTOMATION`),
  outcomes, timestamps — appended on each executor run and surfaced in the UI
  (a "Recent runs" section on the goals screen). This is the desktop twin of
  Android's audit/event-bus surface (invariant 8: consequential modifications
  are auditable).
- Tests: round-trip (save → new planner instance → load → identical state),
  corrupt-file recovery, audit append. Pure JVM.

---

## 5. AGENT 2 INSTRUCTIONS (paste this into the Agent 2 session)

> You are Agent 2 of a two-agent parallel effort on the Aegis Assistant
> repository (Kotlin/KMP: Android + desktop bodies, shared KMP brain). Your
> track is **Track B — Desktop + Windows body**. Agent 1 owns the Android side
> and `shared/core`; you must **never edit those** (see fences below).
>
> **FIRST: read, in order** — `AGENTS.md` (binding rules; R3/R4/R5/R6/R9/R11/R12/R13
> will gate every slice), `ARCHITECTURE.md` (invariants + module map), and
> `docs/PARALLEL_WORKSPLIT.md` (your full brief: section 2 coordination
> protocol, section 4 track details). Then read the "State you inherit" file
> list in section 4 before writing anything — those are the exact shapes you
> build on.
>
> **Your ownership (edit only these):** `platform/windows/**` and
> `apps/desktopApp/**`. Frozen (never edit): `shared/**`, `apps/androidApp/**`,
> `settings.gradle.kts`, `AGENTS.md`, and all of `docs/` except
> `ARCHITECTURE.md`'s `apps/desktopApp/` and `platform/windows/` module-map
> lines. `shared/platform-api` is read-only — it defines the contracts you
> implement; read it constantly.
>
> **Slices, in order (each is a complete, verifiable unit — finish it before
> starting the next; no placeholders ever):**
>
> 1. **B1 — Compose Desktop UI.** Add Compose Multiplatform (desktop) to
>    `apps/desktopApp/build.gradle.kts` with a version compatible with Kotlin
>    2.1.0; update the AGENTS.md baseline table in the same change (your only
>    AGENTS.md touch — a version note, not a rule). Build three screens that
>    lift the existing CLI logic into a window: Status/Capabilities (registry +
>    model state), Apps (WindowsAppIndex with search), Goals board (progress
>    bars, warnings, Plan/Run/Abandon). All decision logic stays in
>    plain-Kotlin testable state holders. Keep the CLI runnable this slice.
>    R13: the window is the surface — every capability you register in B2 must
>    appear here in B2's slice.
> 2. **B2 — Windows capabilities.** Implement FILES, PROCESSES, SHELL,
>    SECRETS (DPAPI via JNA Crypt32 — the seam + honest fallback if the native
>    path is unverifiable; never a stub), and SYSTEM capabilities in
>    `platform/windows`, mirroring the Android adapters in `platform/android`
>    and the `WindowsDesktopCapability` seam pattern. Register them in
>    `DesktopCapabilitiesHolder.init()` and surface them in the B1 status
>    screen. Credentials stay references, never content. Contract tests per
>    capability, OS-independent via injected seams.
> 3. **B3 — Persistence + audit.** Goals/tasks/state survive restarts via a
>    JSON store under `~/.aegis/` (NOT `shared/database`); an execution audit
>    log (goal, tasks, tiers, outcomes, timestamps) appended per `run` and
>    surfaced as "Recent runs" in the UI. Round-trip and corrupt-file tests.
>
> **Verification per slice** (this sandbox has no JDK — Gradle steps are
> `UNVERIFIED`; run them on a JDK-17 machine/CI): `bash scripts/check-invariants.sh`
> must exit 0; `./gradlew :platform:windows:test :apps:desktopApp:test`; keep
> Android untouched so CI's `assembleDebug` stays green. Static greps: no
> placeholders in new files, no dangling references, no platform imports in
> `shared/*/src/commonMain` (you never touch shared, so this stays green).
>
> **Handoff:** close every slice with the AGENTS.md block
> (VERIFIED / WIRED / NEEDS YOU / UNVERIFIED). Put anything you need from
> Agent 1's side (shared/core changes, contract tweaks) in `NEEDS YOU` — do
> not implement it yourself. Do not commit or push; the Changes panel owns
> delivery. If a slice is bigger than one session, deliver the first complete
> slice and describe the next exactly — never a skeleton.
