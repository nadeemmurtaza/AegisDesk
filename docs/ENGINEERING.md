# Newax Aegis — Delivery Slices & Engineering Standard

The reference for anyone — human or agent — building on this project.

**Part A** is the complete slice list: what ships, in what order, with what
gates. **Part B** is how to build it to a standard that survives public scale,
enterprise deployment, and a hostile threat model.

Read `AGENTS.md` first for the non-negotiable invariants. This document extends
them; it never overrides them.

---

## Contents

| | |
|---|---|
| [Gate 0](#gate-0--the-build-must-go-green) | The prerequisite that blocks everything |
| [Part A](#part-a--delivery-slices) | Slices 0–24, dependencies, verification |
| [Part B](#part-b--engineering-standard) | How to build it properly |
| [B1](#b1--non-negotiables) | Invariants that never bend |
| [B2](#b2--architecture--module-rules) | Module boundaries, dependency direction |
| [B3](#b3--kotlin-kmp--compose) | Language and framework practice |
| [B4](#b4--accessibility) | The WCAG 2.2 AA standard as engineering practice |
| [B5](#b5--security--authentication) | Threat model, hardware-backed identity, real auth |
| [B6](#b6--performance--resource-budgets) | On-device inference, startup, battery |
| [B7](#b7--testing-strategy) | What to test, at which level, and why |
| [B8](#b8--cicd--release-engineering) | Gates, signing, supply chain |
| [B9](#b9--scale-distribution--enterprise) | What "public scale" means for an offline app |
| [B10](#b10--working-agreements-for-agents-and-developers) | Process rules |
| [B11](#b11--known-anti-patterns-in-this-repo) | What to fix, not copy |

---

# Gate 0 — the build must go green

`main` had never had a green CI run in its recorded history: `android.yml`
30/30 failures, `apple.yml` 24/24. Diagnosed and largely fixed — the causes
were three ordinary compile errors, not an exotic Room/KSP incompatibility.

Room's `[ksp] [MissingType]: Element 'com.newax.aegis.db.NewaxDatabase'
references a type that is not present` was a **downstream symptom**, not the
cause. Room emits it when the `@Database` class fails to resolve — which
happens whenever that file has a compile error. Chasing it as a Room problem
was the wrong tree; the compiler names the real fault.

| Fault | Where | Fix |
|---|---|---|
| `kotlinx.datetime.Clock.System` | `NewaxDatabase.kt:825` | Moved to `kotlin.time` in kotlinx-datetime 0.8.0. Replaced with the module's own `currentTimeMillis()` expect/actual seam, which commonMain should have used anyway (AGENTS.md §0.5) |
| `@Volatile` | `NewaxDatabase.kt:90` | `kotlin.jvm.Volatile` is auto-imported on JVM targets and absent from metadata. Explicit `import kotlin.concurrent.Volatile` |
| `EnterTransition` | `MainActivity.kt:451` | Missing import from the slice-3 reduced-motion work |

**Verified locally** (Android SDK installed, JDK 17, real compiles):

```
:shared:database:desktopJar          SUCCESS
:shared:database:assemble (android)  SUCCESS
:shared:core:compileKotlinJvm        SUCCESS
:shared:platform-api:jvmTest         SUCCESS
:shared:ui:compileKotlinJvm          SUCCESS
:shared:ui:jvmTest                   SUCCESS  ← ContrastTest, 84 assertions
:apps:android:compileDebugKotlin     SUCCESS
```

## What remains: Apple targets only

```
e: [ksp] FileIndexEntity.kt:104: Cannot find external content entity class.
   kspKotlinMacosArm64 · kspKotlinIosArm64 · kspKotlinIosSimulatorArm64
```

Fires **only** on the three native targets; JVM and Android pass. `FileTextFts`
uses a bare `@Fts4`, whose default `contentEntity` resolves to `Any::class` →
`java.lang.Object`, which does not exist on Kotlin/Native. `PersonFactFts`,
which sets `contentEntity` explicitly, is unaffected — that contrast is the
evidence.

**Not fixed here, deliberately.** The obvious change — giving `FileTextFts` an
explicit `contentEntity` — alters the generated `CREATE VIRTUAL TABLE` and is
therefore a **schema change on a v19 database**, requiring a migration and a
version bump. `PARALLEL_RULES.md` Rule 1 says schema work is serialized and
claimed, and it cannot be verified from a Linux host without a Mac. It needs
its own change, by whoever owns Track 2.

Interim options: set the explicit `contentEntity` with a migration; make
`file_text_fts` a standalone (non-Room) FTS table created by callback; or
accept Apple targets red until a Mac is available.

**Rule: no slice is "done" while it cannot be compiled.** A slice may be
*written* against a red build, but it must be marked unverified, and the
verification debt is tracked until its gate clears.

---

# Part A — Delivery slices

Status legend: ✅ landed · 🟡 written but unverified · ⬜ not started ·
🔒 blocked

Every slice declares: **goal · touches · depends on · gate**. A slice is done
when its gate passes — not when the code looks right.

## Phase 0 — Foundation

### Slice 0 — Raise `compileSdk` to 37 ✅
- **Goal:** clear AGP's AAR-metadata failure.
- **Touches:** the 7 modules with an `android {}` block, `AGENTS.md` baseline.
- **Gate:** `build` progresses past `CheckAarMetadataTask`. ✅ confirmed.

### Slice 0b — Fix the Room KSP blocker ✅ (Apple targets remain)
- **Goal:** Gate 0. Make the project compile.
- **Touches:** `shared/database` — DAOs, entities, or the Room/KSP version pair.
- **Gate:** `build`, `KMP compile gates`, and `apple-compile` all green.
- **Note:** everything below inherits this dependency.

### Slice 1 — `shared:ui` token layer ✅
- **Goal:** one source of truth for colour, type, spacing, shape.
- **Touches:** new `shared/ui` module; CMP unified on 1.11.1.
- **Gate:** `:shared:ui:jvmTest` (ContrastTest) + `assemble`.
- **Verified:** compiles, and `:shared:ui:jvmTest` runs ContrastTest's 84 assertions green.

### Slice 2 — Token adoption ✅
- **Goal:** delete 189 duplicated colour declarations across 18 files.
- **Also fixed:** `@style/Theme.NewaxNewax` (a theme that does not exist),
  `android:label="Newax Newax"`, contradictory `styles.xml` chrome.
- **Gate:** `assembleDebug` + visual parity check on a device.

### Slice 3 — Accessibility primitives ✅
- **Goal:** `reducedMotionEnabled()` seam + semantics helpers; applied to the
  typing indicator and bubble width.
- **Gate:** compiles; TalkBack pass on the chat surface.

### Slice 4 — String externalization ⬜
- **Goal:** every user-visible string into resources. Currently they are
  hardcoded Kotlin literals across every screen, which blocks translation, RTL
  verification, and the Urdu support the app already half-ships.
- **Touches:** all Android screens; a shared string strategy for CMP
  (`moko-resources` or Compose resources).
- **Gate:** zero hardcoded user-facing literals in a lint check; pseudolocale
  (`en-XA`) renders without clipping.
- **Why early:** every slice after this one adds strings. Doing it later means
  redoing them.

### Slice 5 — Dark theme completion ⬜
- **Goal:** unpin `NewaxTheme(darkTheme = false)`; migrate screens from the
  light-only aliases to `NewaxTheme.colors`.
- **Depends on:** slice 2.
- **Gate:** ContrastTest already covers both palettes; add screenshot tests in
  both themes.

## Phase 1 — Architecture correction

### Slice 6 — Decompose `MainActivity` and `MainViewModel` ⬜
- **Goal:** 1403-line Activity and 1207-line god-ViewModel become per-screen
  composables and testable state holders.
- **Pattern to copy:** `apps/desktop/.../ui/state/*.kt` — plain-Kotlin,
  injectable, unit-tested. Desktop already does this correctly; Android does not.
- **Gate:** no file over ~400 lines; each state holder has unit tests.

### Slice 7 — Unify the three risk vocabularies ⬜
- **Goal:** `MainActivity`'s local `Risk` enum, `RiskLevel {LOW..CRITICAL}`, and
  `PolicyMode {AUTO..STRONG_CONFIRMATION}` collapse to one model.
- **Why it matters:** three vocabularies for one safety concept is how a
  mis-mapping silently downgrades an approval requirement.
- **Gate:** property-based tests over the policy engine (see B7).

### Slice 8 — Conversation persistence ⬜
- **Goal:** a conversation/message schema. Chat is currently an in-memory
  `mutableStateListOf` wiped on process death; there is no conversation table
  among the 24 DAOs.
- **Blocks:** routes 1.1, 1.6, 1.11, 1.12 in `UI_DESIGN.md` §6.
- **Gate:** migration test (the repo already has `MigrationTest` — extend it).

### Slices T-1 … T-12 — Tenancy, identity & multi-device ⬜
- **Goal:** organizations and people; every person gets a Work and a Personal
  profile; profiles follow the person across Android, iOS, Windows and macOS.
- **Spec:** `docs/TENANCY_DESIGN.md` — slice table in §11 (T-1…T-18).
- **Profile-aware behaviour** (§12): triggers, focus filters, VIPs, digest,
  calendar busy projection, tone, connector silos. Three collisions resolved
  there rather than papered over — a trigger can never unlock a profile, the
  calendar projection uses a type with no detail field, and network connectors
  are flagged as an open product decision.
- **Depends on:** Gate 0 and slice 6. Layering this onto eight process-wide
  singletons and a 1400-line Activity would have to be redone.
- **Key decisions:**
  - The isolation boundary is the **Profile**, not the tenant — one key and one
    database each. Isolation by key, never by `tenant_id` filtering: a missing
    `WHERE` is a silent leak, a missing key is unreadable bytes.
  - Multi-device is **enrollment, not login** — `shared/sync` already has the
    Ed25519 identity, QR+SAS pairing, and Noise handshake, so this is key
    transfer over an authenticated channel, not new crypto and not a server.
  - An organization governs Work profiles **tighten-only** and is
    cryptographically unable to see Personal. That is the BYOD guarantee.
- **Highest risk:** T-2, which migrates every existing user's data.
- **Not in scope:** hosted server tenancy — it would mean adding the `INTERNET`
  permission the product deliberately refuses. Costed in §7.

### Slices C-1 … C-9 — Computer use & GUI automation ⬜
- **Goal:** drive real UIs on the user's behalf — spreadsheets, CRM, expense
  reports, checkout flows — safely.
- **Spec:** `docs/COMPUTER_USE.md`.
- **Depends on:** Gate 0, slice 6, and tenancy T-1…T-5.
- **Why it gets its own document:** the mechanics largely exist already
  (`ExecutionRouter`, `ExecutionGuard`, Windows `SendInput`, accessibility
  automation). What is missing is consequence modelling, pre-flight
  verification, and screen-content trust.
- **The defining risk:** a computer-use agent reads the screen to decide what to
  do, so every pixel of it is adversary-controlled input. Screen content is
  **data, never instruction** — it may fill a parameter, never select an action
  or retarget one.
- **Highest-value slice:** C-2, the untrusted-screen boundary, with an injection
  corpus built alongside the capability rather than after it.

### Agent system — coverage, not greenfield ⬜
- **Read first:** `docs/AGENTS_DESIGN.md` "Coverage status". Most of the
  multi-agent stack is already schema-backed (v14–v19): the three memory layers,
  episodic memory, work log, PBAC guard, PRAM controller, MCP exchange,
  freeze/thaw, RLAIF-E with its evolution ledger, fuzzer and staging gatekeeper.
- **Real gaps:** no sandbox runtime ships (seam only, and Docker cannot run on
  mobile — WASM or nothing); no concurrency control in the agents package
  despite "atomic sequential writes" being claimed; ZIP import only.
- **The architectural conflict:** the memory design assumes the swarm shares one
  database. Tenancy replaces that with one per profile. Collective learning
  "swarm-wide" is correct within a profile and a leak across one — a lesson can
  encode the data it was learned from, and no classifier separates those
  reliably. Fixed structurally (T-19), not by filtering.

### Slice 9 — Streaming ⬜
- **Goal:** wire `ModelProvider.stream()`. It exists and is never called;
  `MainViewModel` uses `complete()`, so replies arrive whole with no stop button.
- **Gate:** token-by-token render; stop cancels within one token.

## Phase 2 — The design system

### Slice 10 — Component library ⬜
- **Goal:** the ~60 components in `UI_DESIGN.md` §8, built accessibility-first.
- **Rule:** every component ships semantics, focus behaviour, and a 44 dp target
  in its **first** commit. Retrofitting accessibility is how it never happens.
- **Gate:** per-component screenshot tests (light + dark + 200% font scale) and
  semantics assertions.

### Slice 11 — Chat shell (FLOW B) ⬜
- Routes 1.1, 1.2, 1.4, 1.10, 1.11. Depends on slices 8, 9, 10.

### Slice 12 — Authority surface (FLOW C) ⬜
- **Goal:** approval moves inline; step blocks; artifact panel.
- **The product's defining interaction.** See B5 for the auth binding.

### Slice 13 — Memory, Tasks, Capabilities ⬜
- Sections 2–4 of the route tree.

### Slice 14 — Settings subtree ⬜
- Section 5, in the §6.7 order. 30+ routes.

### Slice 15 — Onboarding ⬜
- Routes 0.x. **Last**, because it links into 5.1.3.2, 5.1.3.3, 5.2.1.1.

## Phase 3 — Reach

### Slice 16 — Expanded layout ⬜
- Three panes, menu bar, keyboard shortcuts, command palette. The repo has
  **zero** key handling today — this is greenfield.

### Slice 17 — iOS body ⬜
- CMP target + the §9 platform seams. Requires a macOS host with Xcode.

### Slice 18 — Desktop parity ⬜
- Desktop currently has no chat surface at all (chat is a `--cli` REPL).

## Phase 4 — Production hardening

### Slice 19 — Security hardening ⬜ (see B5)
- Hardware-backed keys, `CryptoObject`-bound biometrics, agent package
  signing, tamper-evident audit log, secure-screen flags.

### Slice 20 — Performance budgets ⬜ (see B6)
- Baseline Profiles, startup budget, inference telemetry, battery accounting
  for the always-on wake-word service.

### Slice 21 — Test infrastructure ⬜ (see B7)
- Screenshot, accessibility, property-based policy tests, protocol fuzzing.

### Slice 22 — Supply chain & release ⬜ (see B8)
- Gradle dependency verification, SBOM, reproducible builds, signed releases.

### Slice 23 — Enterprise deployment ⬜ (see B9)
- Managed configuration, signed policy bundles, MDM story.

### Slice 24 — Localization ⬜
- Depends on slice 4. RTL verification, Urdu completion, pseudolocale in CI.

---

# Part B — Engineering standard

## B1 — Non-negotiables

These are stated in `AGENTS.md` §0. Repeated because they are the ones most
often eroded by convenience:

1. **PLAN is never EXECUTE.** The model proposes typed `ProposedAction`s. Only
   an explicit approval executes one. Model prose never reaches an API.
2. **Approving step *n* never approves step *n+1*.** A multi-step plan is
   never one approval.
3. **Displayed action text is generated from the typed action**, never from
   model output. Otherwise the model can lie about what it is about to do.
4. **Every terminal state writes an audit entry** — approved, rejected,
   blocked, failed.
5. **No headless capability (R13).** Backend never ships without a real screen,
   including loading, empty, error, and approval states.
6. **Branding (R14):** exactly "Newax Aegis" in user-visible text.
7. **Frozen identifiers (R15):** `com.newax.aegis.*`, `~/.aegis/` — never
   renamed, whatever the product is called.

**If a change makes any of these more convenient to bypass, it is the wrong
change**, regardless of how much nicer the code looks.

## B2 — Architecture & module rules

**Dependency direction is one-way:**

```
apps/*            (bodies: android, desktop, macos, ios)
   ↓
shared/ui         (design system — no business logic)
   ↓
shared/core       (authority spine, models — platform-free)
   ↓
shared/platform-api  (capability contracts)
   ↑
platform-impl/*   (per-OS adapters implement the contracts)
```

- **`shared/*` `commonMain` stays platform-free.** Enforced by
  `scripts/check-invariants.sh`. Never add a platform import to bypass a seam;
  add an `expect`/`actual`.
- **New target → per-target KSP config in the same commit** (R5). The repo has
  been bitten by this.
- **New module → `settings.gradle.kts` + the AGENTS.md module table, same
  change.**
- **`shared:ui` holds no business logic.** It is tokens, components, and
  accessibility primitives. A component that knows about `ProposedAction`
  belongs in an app module.
- **Version declarations live in one place.** CMP is declared once in the root
  build with `apply false`; modules apply it without a version. The 1.7.1/1.11.1
  drift that blocked shared UI for weeks came from inline per-module versions.

## B3 — Kotlin, KMP & Compose

**Kotlin**
- Explicit API mode for `shared/*` — public surface should be deliberate.
- Prefer sealed hierarchies over enums + `when` for anything the type system can
  check exhaustively. `ProposedAction` already does this correctly.
- No `!!` in shared code. Model absence in the type.
- `Result`/typed errors over exceptions across module boundaries.

**KMP**
- `expect`/`actual` only where the OS genuinely differs. Every seam is a
  maintenance cost across four bodies.
- `applyDefaultHierarchyTemplate()` explicitly — KGP no longer applies it
  implicitly, and without it intermediate source sets silently don't compile.
- Apple targets need a macOS host. Do not claim a target compiles without
  evidence; declare it and mark it unverified.

**Compose**
- **State holders outside composables.** Decision logic in plain Kotlin classes
  that unit-test without a device — the pattern `apps/desktop/ui/state/` already
  uses. This is the single highest-leverage practice in this codebase.
- Stable/immutable types for parameters; `@Immutable` on token classes (already
  done in `shared:ui`).
- `derivedStateOf` for computed state; avoid recomposition on every frame.
- Never read a `CompositionLocal` in a hot loop.
- Hoist state; composables take data and emit events.
- No business logic in `@Composable` functions.
- Baseline Profiles for startup-critical paths (slice 20).

## B4 — Accessibility

`docs/UI_DESIGN.md` §3 is the normative spec. In practice:

- **It is a definition-of-done, not a phase.** A component without semantics is
  incomplete, the same way a component that doesn't compile is incomplete.
- **Contrast is checked by arithmetic.** `ContrastTest` enforces every pair
  against every text-bearing surface. Do not change a brand colour without
  recomputing — five values in the original palette failed, including a 2.00:1
  colour that marked policy-blocked actions.
- **Test against the worst surface, not the best.** Three failures were hidden
  by only testing against the page background.
- **Colour is never the only signal** (SC 1.4.1): status dots pair with labels,
  risk chips carry icon + word.
- **`contentDescription = null` is correct for decorative icons.** Do not
  blanket-fill them; a chevron inside a labelled row should stay silent. Naming
  everything makes screen readers worse, not better.
- **State goes on the control, not the glyph.** An expand/collapse chevron swap
  conveys nothing; the row needs `stateDescription`.
- **Every animation declares its reduced-motion substitution.** Reach the same
  end state without the movement — never just delete the affordance.
- **Sizes in `sp`, widths as fractions.** A `widthIn(max = 300.dp)` bubble clips
  at the 200% font scale WCAG requires.

## B5 — Security & authentication

### Start with the threat model, not the adjective

"Military-grade" is a marketing phrase, not a specification. What matters is
which adversary you're defending against. For this product:

| Adversary | Capability | Primary defence |
|---|---|---|
| Thief with the device | Physical access, unlocked or locked | Hardware-backed keys, `setUnlockedDeviceRequired`, at-rest encryption |
| Malicious app on-device | Same-device IPC, screen reading | No exported components, `FLAG_SECURE`, permission minimization |
| Network attacker | LAN or relay MITM | Existing Noise-style handshake + SAS; never auto-confirm |
| Malicious peer | A paired device turns hostile | Per-peer permissions, revocation records, no implicit trust |
| Supply chain | Compromised dependency or model | Dependency verification, signed packages, hash-verified models |
| Coerced user | Forced to unlock | Duress considerations; do not over-promise here |
| **The assistant itself** | It can drive the whole device | **The approval spine — this is the one that matters most** |

That last row is the unusual one and the reason this project exists. An
assistant with an accessibility service is, functionally, a remote-code-execution
primitive pointed at the user's own phone. Every security decision should be
weighed against "does this make it easier for a model's output to become an
action without a human saying yes?"

### Authentication done correctly

The single most common mistake, and the one to avoid here:

```kotlin
// WRONG — a boolean an attacker can skip past.
if (biometricSucceeded) { performSensitiveAction() }

// RIGHT — authentication gates a KEY, and the action needs that key.
val cipher = getCipherBoundToKeystoreKey()      // key requires user auth
biometricPrompt.authenticate(promptInfo, BiometricPrompt.CryptoObject(cipher))
// the action is impossible without the unlocked key — not merely discouraged
```

**Requirements:**

- **Hardware-backed keys.** Android Keystore with `setIsStrongBoxBacked(true)`
  where available; iOS Secure Enclave (`kSecAttrTokenIDSecureEnclave`). Fall
  back gracefully and *tell the user* the tier they're on rather than silently
  degrading.
- **`setUserAuthenticationRequired(true)`** with a short validity window, plus
  `setUnlockedDeviceRequired(true)` and `setInvalidatedByBiometricEnrollment(true)`
  — so enrolling a new fingerprint invalidates the key.
- **`BIOMETRIC_STRONG` (Class 3) only.** Class 2 and device credential are not
  equivalent; do not accept them for `STRONG_CONFIRMATION` actions.
- **Bind auth strength to action risk.** The `PolicyMode` ladder already models
  this; wire it to real key requirements rather than UI-level checks.
- **Voice authentication is a fallback, never a primary factor.** Speaker
  embeddings are spoofable by replay. It is fail-secure today (no enrolment →
  verification fails) — keep that property, and never let it satisfy
  `STRONG_CONFIRMATION` alone.
- **Passkeys / FIDO2** via Credential Manager if any account concept is ever
  added. Never passwords.
- **Key attestation** to prove keys are hardware-backed — note that Play
  Integrity requires network, which conflicts with the offline-first promise.
  Make it optional and off by default.

### Data at rest

- SQLCipher is present. **The passphrase must be derived from a Keystore-wrapped
  key, never a static or hardcoded secret.** Audit this.
- Backup KDF must be **Argon2id** (or scrypt), not low-iteration PBKDF2. Backups
  are offline-attackable, so the KDF is the entire defence.
- Secrets in `CharArray`, zeroed after use. Never in `String` — immutable and
  lingers in the heap.
- Never log secrets, tokens, or memory contents. Add a lint rule.

### Integrity

- **Sign `.aegis-agent` packages.** Deny-by-default permissions are good; they
  don't help if the package itself is tampered with.
- **Hash-verify model bundles** — already done (`SHA-256` + magic header).
  Extend to agent and policy bundles.
- **Tamper-evident audit log:** hash-chain the entries so deletion is
  detectable. An audit log an attacker can edit is decoration.
- **`FLAG_SECURE`** on screens showing memory, credentials, or pairing codes.
- **Keep the always-blocked list enforced in code**, not just in the onboarding
  copy: banking apps, password managers, permission dialogs, CAPTCHAs, biometric
  prompts, protected content.

### Privacy as an architectural property

> **Correction — verify claims against the manifest, not the README.**
> `README.md:11` states "No Internet permission, analytics, account, or cloud
> storage." **`AndroidManifest.xml:6` declares `android.permission.INTERNET`.**
> The permission is legitimately used — `shared/sync` reaches the relay over
> WebSockets (`WsClient.kt`) — but the README claim is false as written, and it
> is a *privacy* claim, which is the worst kind to get wrong.
>
> This needs a product decision, not a silent edit: either remove the permission
> and move relay transport behind a separately-installed component, or amend the
> README to describe what is actually true ("no analytics, no account, no cloud
> storage; network used only for peer-to-peer relay"). Tracked in B11.

The defensible version of the guarantee is narrower and still valuable: **no
analytics, no account, no cloud storage of user content, and network used only
to relay ciphertext between the user's own paired devices.** Defend *that*, and
keep any new network use behind an explicit, separately-gated boundary.

## B6 — Performance & resource budgets

Set numbers, measure them in CI, and fail the build on regression.

**On-device inference** (the hard constraint — Gemma 3 1B INT4 on an 8 GB S21):

| Metric | Budget |
|---|---|
| Time to first token | < 1.5 s |
| Sustained throughput | ≥ 8 tok/s |
| Peak RSS during inference | < 60% of available RAM |
| Thermal | no sustained throttling in a 5-minute session |

**App**

| Metric | Budget |
|---|---|
| Cold start to first frame | < 800 ms (with Baseline Profiles) |
| Frame rendering | ≥ 99% under 16 ms |
| APK size | tracked per release; model weights stay out |
| Wake-word service battery | < 2%/hour |

The always-on wake-word foreground service (`START_STICKY`, Vosk) is the
biggest battery risk. Budget it explicitly and surface the cost in the UI —
route 5.1.3.2 already says "uses battery"; make that honest with a number.

**Device tiering.** A single model profile will not serve the device range
public distribution implies. Build a capability tier (RAM, SoC, NPU presence)
and select the model pack accordingly, degrading to the deterministic engine
rather than shipping an unusable experience.

## B7 — Testing strategy

| Level | What | Tool | Runs |
|---|---|---|---|
| Unit | State holders, policy engine, parsers, crypto | kotlin.test / JUnit | Every build |
| **Property-based** | **Policy engine invariants** | jqwik / Kotest | Every build |
| Contrast | Every colour pair | `ContrastTest` (exists) | Every build |
| Screenshot | Components, light/dark/200% | Roborazzi or Paparazzi | Every build |
| Accessibility | Semantics, focus, targets | Compose semantics assertions + `AccessibilityChecks` | Every build |
| Migration | Room schema versions | Instrumented (exists) | Every build |
| Protocol fuzzing | Pairing payloads, wire codec | Jazzer | Nightly |
| E2E | Approval flows on a device | Instrumented | Pre-release |
| Manual | TalkBack, VoiceOver, RTL | Human | Per release |

**Property-based testing of the policy engine is the highest-value test in this
project.** The invariants are stateable and machine-checkable:

- No sequence of inputs downgrades a `STRONG_CONFIRMATION` action to `AUTO`.
- Every executed action has a preceding approval of at least its required level.
- A rejected action never executes.
- Every terminal state produces exactly one audit entry.

Example-based tests will not find the ordering bug that violates these; property
tests will.

**Do not chase coverage percentages.** Cover the authority spine, the crypto,
the parsers, and the policy engine exhaustively; UI glue needs far less.

## B8 — CI/CD & release engineering

**Required gates before merge:**
1. `scripts/check-invariants.sh` (fast, no toolchain)
2. Per-target KMP compiles
3. `assembleDebug` + unit tests
4. `:shared:ui:jvmTest` (contrast)
5. Screenshot diffs
6. Accessibility assertions
7. Apple compiles (macOS runner)

**Supply chain:**
- **Gradle dependency verification** (`gradle/verification-metadata.xml`) with
  checksums *and* signatures. Currently absent — this is the highest-value
  supply-chain control available and costs one command to bootstrap.
- Pin every version explicitly. No dynamic versions, no ranges, ever.
- Generate an **SBOM** (CycloneDX) per release.
- Work toward **reproducible builds**; they make "is the shipped binary the
  reviewed source?" answerable.
- Keep release signing out of the repo — `keystore.properties` is gitignored
  with a template. Preserve that.

**Release:**
- Staged Play rollout (1% → 5% → 20% → 100%) with crash-rate halt criteria.
- Play App Signing.
- Local-only crash reporting stays local; any export is explicit and user-driven.

**CI hygiene:** the workflows name explicit task lists, so a new module is
invisible to CI until added. When you add a module, add it to the workflows in
the same commit — otherwise it silently isn't built.

## B9 — Scale, distribution & enterprise

### Be honest about what "scale" means here

This is an offline-first, account-free, on-device app. There is no multi-tenant
backend to scale. "Largest public use" therefore means:

- **Device diversity**, not QPS — thousands of SoC/RAM/OS combinations.
- **Model distribution**: hash-verified, resumable, delta-updatable bundles.
- **Localization breadth**, including RTL (slice 24).
- **Support surface without telemetry** — you cannot debug from analytics you
  deliberately don't collect. Invest in the local dev console, exportable
  diagnostics, and precise honest error messages instead.
- **Sync mesh growth**: the 4-device mesh design must degrade gracefully at
  more devices, or state the supported limit.

The only networked component is `relay/`. **That is the entire server-side
attack surface and the only thing needing conventional scaling** — rate
limiting, DoS resistance, no plaintext access to relayed payloads (it must stay
a dumb pipe), and no logging of content or metadata beyond what routing needs.

### Enterprise, without breaking the product

Genuine enterprise needs, ranked by value:

1. **Managed configuration** — Android Enterprise `RestrictionsManager` so
   admins can pre-set policy modes, hard-deny lists, and sync settings.
2. **Signed policy bundles** — an org pushes a policy; the device verifies the
   signature. **Policy may only ever tighten, never loosen**, and every applied
   policy is visible to the user in route 5.3.1. An admin silently granting
   `AUTO` to dangerous action classes is exactly the attack this app exists to
   prevent.
3. **Exportable, tamper-evident audit** — hash-chained, signed CSV/JSON.
4. **Attestation** for device fleet health — optional, network-gated, off by
   default.
5. **Zero-trust between peers** — already the design; keep it.

**What to refuse:** silent remote administration, content exfiltration to a
management console, telemetry that defeats the privacy promise, and any
"enterprise override" of the approval spine. If an enterprise requirement
conflicts with B1, the answer is no, and the doc should say why.

## B10 — Working agreements for agents and developers

1. **Read `AGENTS.md` Gate 0 before writing.** Contract, surface, authority.
2. **Depth-first, no placeholders.** Never emit a TODO, a stub, or a function
   that calls something unwritten. If you can't finish it, don't start it.
3. **Verify before claiming.** "Compiles" means you compiled it. "Accessible"
   means you measured it. State unverified work as unverified — prominently,
   not in a footnote.
4. **Recompute, don't trust.** Every contrast ratio in this project was
   recomputed rather than copied; that caught five real failures. Apply the same
   scepticism to your own earlier output.
5. **Audit before migrating.** Reading the actual call sites before the token
   migration revealed two surface levels the spec had missed, and one constant
   that was dead. A migration designed from the spec alone would have been wrong.
6. **Comments must match the code.** Stale comments are worse than none — this
   repo had six files pointing at a `REFINED_THEME.md` that does not exist and
   build files claiming a Kotlin version two majors old. Update or delete.
7. **One concern per commit**, with a message explaining *why*, not what. The
   diff shows what.
8. **Keep docs and code in the same commit.** Token values and the §4 tables
   move together, or they drift within a week.
9. **Pre-existing breakage is not yours to inherit silently** — diagnose it,
   report it with evidence, and say clearly whether you fixed it.
10. **Scale down scope, never quality.** If you can't finish the slice, ship
    less of it fully rather than all of it partially — and say what you left out.

## B11 — Known anti-patterns in this repo

Fix these; do not copy them.

| Anti-pattern | Where | Why it hurts |
|---|---|---|
| God Activity (1403 lines) | `MainActivity.kt` | Shell + 4 screens + theme in one file |
| God ViewModel (1207 lines) | `MainViewModel.kt` | All chat state and business logic; untestable |
| Regex fast-paths | `MainViewModel.submit()` | ~10 hardcoded branches bypass the model entirely |
| Three risk vocabularies | `MainActivity`, `shared/core`, policy | A mis-mapping silently downgrades safety |
| Hardcoded UI strings | Every screen | Blocks i18n and RTL verification |
| In-memory chat | `MainViewModel:97` | Wiped on process death; no history |
| Unused capability | `ModelProvider.stream()`, `AgentStream` | Built, never wired — dead weight that looks live |
| Inline `Color(0x…)` | (fixed in slice 2) | 189 copies of one palette across 18 files |
| Docs referencing ghosts | (fixed) | `REFINED_THEME.md` cited in 6 files, never existed |
| **False privacy claim** | `README.md:11` vs `AndroidManifest.xml:6` | README says "No Internet permission"; the manifest declares it. Needs a product decision (remove the permission, or correct the claim) — not a silent edit |

---

## Appendix — the shortest correct path from here

1. **Fix Room KSP.** Nothing is real until the build compiles. (Slice 0b)
2. **Verify slices 1–3**, which are written but unproven.
3. **Externalize strings** before more screens exist. (Slice 4)
4. **Decompose the god objects** before building on them. (Slice 6)
5. **Unify risk vocabularies + property-test the policy engine.** (Slices 7, 21)
6. **Then** build the component library and the route tree.

Steps 1–5 are unglamorous and every one of them gets more expensive the longer
it waits.
