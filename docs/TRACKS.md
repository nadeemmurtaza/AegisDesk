# Five Tracks — parallel agent assignment

The split of Newax Aegis into five independently-workable tracks, with a
self-contained brief for each.

Read `docs/PARALLEL_RULES.md` first — this applies those rules, it does not
replace them.

---

## Current state

**Gate 0 is cleared. The build compiles on all four bodies.**

It had never had a green CI run; the causes were five ordinary compile errors,
not the Room/KSP incompatibility the error suggested — including a bare `@Fts4`
whose implicit default Room cannot resolve on Kotlin/Native. Full fault list and
verification log in `ENGINEERING.md` Gate 0.

| Gate | State |
|---|---|
| `Static invariants` | ✅ green |
| `build` (Android + adapters) | ✅ **green** |
| `KMP compile gates + Android green` | ✅ **green** |
| `apple-compile` | ✅ **green** |
| `Windows adapter tests` | ✅ green |
| `instrumented-tests` | ❌ see below |

Those are the **first green runs of `build`, `KMP compile gates` and
`apple-compile` in the project's recorded history.**

### The one red check is new information, not a regression

`instrumented-tests` runs `MigrationTest` on an API-29 emulator, gated on
`needs: build`. Because `build` had never passed, **this test had never executed
once.** It runs now, and it fails.

That is Gate 0 paying off immediately: a suite that was structurally unable to
report is now reporting. Treat the failure as a **finding about the schema**,
not as something this week's changes broke — the exported schema JSON is
byte-identical across these commits.

**Owned by Track 2** (migrations). First job: get the failure detail from the
emulator run and decide whether it is a real migration defect or a test-harness
problem.

**Nothing blocks any track.** All five can start; the instrumented failure is Track 2's to chase and holds up no one else.

Environment: **JDK 17** (not 21) and Android SDK **platform 37**. If a
`cmdline-tools` index has no `platforms;android-37`, update the tooling — it is
not a project problem.

## The ramp

Wave 0 is done. What remains serialized:

```
WAVE 1  ← YOU ARE HERE. All five tracks start.
  Track 3 does slice 6 (decomposition) ALONE inside apps/android;
  Tracks 1, 2, 4, 5 are elsewhere in the tree and unaffected.

WAVE 2  After slice 6 merges: Track 3 fans out across the route tree.

WAVE 3  Synchronized. Track 2 lands tenancy T-1/T-2, then all five do their
        own tenancy slices together.
```

**All five tracks can start now.** The only ordering constraint left is that
nobody else edits `apps/android` until Track 3's decomposition merges — and that
is Track 3's first task.

---

## Ownership map

| # | Track | Owns | Never touches |
|---|---|---|---|
| 1 | Build, CI & Release | all `build.gradle.kts`, `settings.gradle.kts`, `gradle/`, `.github/`, `scripts/`, AGENTS.md baseline | any `src/` |
| 2 | Core, Data & Policy | `shared/core`, `shared/database`, `shared/platform-api`, `shared/model-api` | UI, agents, platform adapters |
| 3 | Design System & Android UI | `shared/ui`, `apps/android/**` UI + `res/` | `agents/`, `engine/`, other modules |
| 4 | Platform Bodies | `platform-impl/**`, `apps/desktop`, `apps/macos`, `apps/ios` | `apps/android`, `shared/**` |
| 5 | Agents, Automation & Safety | `apps/android/**/agents`, `**/engine`, `relay/` | UI files, `shared/**` |

No two tracks own the same file — the Rule 1 test holds.

---

## Every agent, before your first commit

1. Read `AGENTS.md` (invariants + Gate 0's five questions), then
   `docs/PARALLEL_RULES.md`.
2. Confirm you can build:
   ```
   ./gradlew :shared:core:compileKotlinJvm :shared:ui:jvmTest
   ```
   If that fails on your machine and not in CI, fix your environment before
   writing code. You need **JDK 17** (not 21) and an Android SDK with
   **platform 37**.
3. Claim your track in the PR title: `[T3] …`.
4. Never edit a file outside your ownership column. Request it instead.

---

# Track 1 — Build, CI & Release

**Owns:** every build file · `gradle/` · `.github/workflows/` · `scripts/` ·
the AGENTS.md baseline table

**Why you own all build files:** every track needs dependencies added, and build
files are the most conflict-prone surface in a multi-module repo. They request;
you add. That single rule removes an entire class of merge conflict.

### Do these in order

1. **`gradle/libs.versions.toml`** — a version catalog. AGP `9.3.1` and Kotlin
   `2.4.10` are each declared in three separate files today, and that is exactly
   how Compose Multiplatform drifted to `1.7.1` against a `1.11.1` baseline. Move
   every version into the catalog; modules reference aliases.
2. **CI check: no inline version literals** in any `build.gradle.kts`.
3. **`CODEOWNERS`** from the ownership map above, plus branch protection.
4. **The guard table** in `PARALLEL_RULES.md` — lint rules banning `Color(0x`
   outside `shared:ui` and hardcoded user strings; a doc link-check.
5. Slice 22 — dependency verification, SBOM, reproducible builds.

### Your gate
Every workflow green on a PR that changes only build files.

### Watch for
`platforms;android-37` is not in older `cmdline-tools` indexes. If a contributor
cannot install it, that is a tooling-version problem, not a project one.

---

# Track 2 — Core, Data & Policy

**Owns:** `shared/core/**` · `shared/database/**` · `shared/platform-api/**` ·
`shared/model-api/**` · **all schema migrations** · `docs/MEMORY_DESIGN.md`

### Do these in order

1. **The `instrumented-tests` failure.** `MigrationTest` has never run in the
   project's history and now does. Get the emulator failure detail; decide
   whether it is a genuine migration defect or a harness issue. Nothing else is
   blocked on it, but it is the only red check.
2. **Slice 7** — unify the three risk vocabularies (`Risk`, `RiskLevel`,
   `PolicyMode`). Three names for "how dangerous is this action", and a
   mis-mapping silently downgrades a safety requirement. Serialized: everyone
   reads this type.
3. **Property tests over the policy engine** (`ENGINEERING.md` §B7) — the
   highest-value tests in the project. Four invariants are already stated there.
4. **Slice 8** — conversation persistence. No conversation/message table exists
   among the 24 DAOs; chat is an in-memory list wiped on process death. Blocks
   Track 3's routes 1.1, 1.6, 1.11, 1.12.
5. **Slice 9** — wire `ModelProvider.stream()`. It exists and is called nowhere.
6. **Tenancy T-1/T-2/T-3** — Wave 3. T-2 migrates every existing user's data and
   is the highest-risk slice in the project: migration test,
   backup-before-migrate, rollback path.

### Your gate
`:shared:database:assemble` · `:shared:database:desktopJar` ·
`:shared:platform-api:jvmTest` · your new property tests.

### Rules that bind you hardest
You are the **only** track that writes migrations. Claim the schema version in
the tracker before writing one — two agents writing "v20" is unmergeable, not
merely conflicting.

---

# Track 3 — Design System & Android UI

**Owns:** `shared/ui/**` · `apps/android/**` UI and `res/` · `docs/UI_DESIGN.md`

### Do these in order

1. **Slice 6 — decomposition. First, alone, and everyone is waiting.** The
   1403-line `MainActivity` and 1207-line `MainViewModel` become per-screen
   composables and testable state holders. **Copy the pattern from
   `apps/desktop/.../ui/state/*.kt`** — desktop already does this correctly and
   Android does not. Until this merges, no other track should touch
   `apps/android`.
2. **Slice 4 — string externalization.** Early, because every later slice adds
   strings. Currently every screen hardcodes them, which is why RTL and Urdu
   cannot be verified.
3. **Slice 5 — dark theme.** Unpin `NewaxTheme(darkTheme = false)` and migrate
   screens from the light-only aliases to `NewaxTheme.colors`. ContrastTest
   already covers both palettes.
4. **Slice 10 — the component library**, accessibility-first from each
   component's first commit. Retrofitting a11y is how it never happens.
5. **Slices 11–15** — the 105-route tree in `UI_DESIGN.md` §6.

### Your gate
`:apps:android:assembleDebug` · `:apps:android:testDebugUnitTest` ·
`:shared:ui:jvmTest` · screenshot tests once they exist.

### Already done for you
Slices 1–3 are landed and verified: the token layer, its adoption across 189
call sites, and the accessibility primitives. ContrastTest enforces 84 contrast
assertions on every build — **do not change a brand colour without recomputing.**

---

# Track 4 — Platform Bodies

**Owns:** `platform-impl/**` · `apps/desktop/**` · `apps/macos/**` ·
`apps/ios/**` · `docs/SYNC_DESIGN.md`

### Hard prerequisite
You need **real hardware**: a Mac with Xcode for Apple targets, a Windows
machine for TPM work. Without them your work cannot be verified and must be
marked unverified under Rule 9. Sort this before starting.

### Do these in order

1. **Desktop parity (slice 18).** Desktop has no chat surface at all — chat is a
   `--cli` REPL. `shared:ui` is ready for you to consume.
2. **Expanded layout (slice 16)** — three panes, menu bar, keyboard shortcuts,
   command palette. The repo has **zero** key handling, so this is greenfield.
   The shortcut table is in `UI_DESIGN.md` §5.2; approve/reject must be
   reachable but never single-key.
3. **Windows custody (tenancy T-8).** DPAPI is user-account-scoped, **not
   hardware-backed** — a profile is only as protected as the weakest device
   holding it. Raising Windows to TPM via CNG is the highest-value
   platform-security work available.
4. **iOS body (slice 17)** — unblocked; the Apple targets compile.
5. **T-6** — multi-device enrollment across all four bodies.

### Your gate
`:platform-impl:windows:test` (runs anywhere) · the Windows-only job for
DPAPI/Toolhelp32 paths · `apple-compile` once unblocked.

---

# Track 5 — Agents, Automation & Safety

**Owns:** `apps/android/**/agents` · `**/engine` · `relay/` ·
`docs/AGENTS_DESIGN.md` · `docs/COMPUTER_USE.md`

You carry the most safety-critical work in the project. The approval spine is
what stands between a model's output and the user's device.

### Do these in order

1. **The prompt-injection corpus.** Screens seeded with payloads; assert the
   plan is unchanged. Pure data, needs no compiler, and every payload that works
   is a bug with a test. Build it before the capability, not after.
2. **C-2 — the untrusted-screen boundary.** Screen content is **data, never
   instruction**: it may fill a parameter of an action the user already asked
   for, never select the action, retarget it, or escalate its risk class.
3. **C-1 — consequence classes** with their approval floors. `SPEND` and
   `RECORD` at `STRONG_CONFIRMATION`; `CREDENTIAL` refused outright. Coordinate
   with Track 2 — the floors are enforced at the spine.
4. **C-3/C-4** — the verify/match/locate/confirm pre-flight loop; coordinate
   clicking demoted to the last rung with its own approval.
5. **The agent-system gaps** in `AGENTS_DESIGN.md` "Coverage status": no sandbox
   runtime ships (WASM is the only mobile option — Docker cannot run there), and
   no concurrency control exists despite atomic sequential writes being claimed.
6. **T-19** — per-profile agent memory. Collective learning propagates within a
   profile and never across one.

### Your gate
`:apps:android:testDebugUnitTest` · the injection corpus · property tests over
the guard.

### Two invariants you must not break
The router routes and **never decides permission** — otherwise influencing
routing becomes privilege escalation. And **no agent holds execution
authority** — every agent is a planner emitting typed actions into the one
spine, or you get authority laundering.

---

## Cross-cutting waves

Two efforts span every track and are **synchronized**, not assigned:

**Tenancy.** Track 2 lands T-1/T-2/T-3 first; everyone else then does their own
slices — Track 3 the UI (T-9), Track 4 enrollment and custody (T-6, T-8),
Track 5 agent memory (T-19). Building tenancy UI before the profile boundary
exists is the specific mistake this ordering prevents.

**Computer use.** Track 5 owns the safety architecture, Track 3 renders the
approvals, Track 4 provides per-platform automation. C-1 lands in Track 2's
policy engine first.

---

## Shared files

Touched by everyone, owned by no one:

| File | Rule |
|---|---|
| All build files, `settings.gradle.kts` | **Track 1 only.** Others request |
| `.github/workflows/*` | Track 1 only — a module invisible to CI is not built |
| AGENTS.md baseline table | Track 1 only |
| AGENTS.md reference index | Append-only; integrator resolves |
| `ENGINEERING.md` slice list | Append-only; each track updates its own status |
| `ARCHITECTURE.md` concept registry | Append-only — **read before naming any new type** |

---

## What every PR records

1. What was verified, **and by which gate** — name the command.
2. What was **not** verified, and why.
3. Interfaces added or changed, and which tracks now depend on them.
4. Anything discovered that invalidates a doc another track owns — raised to
   that track, not silently edited.
5. Assumptions taken, stated as assumptions.

Point 3 prevents the half-wired interfaces already in this repo:
`ModelProvider.stream()` and `AgentStream` both look like live capability and
are called from nowhere.

Point 1 has teeth now. Before this week nothing compiled, so "done" could only
ever mean "looks right" — which is how a missing import sat in `MainActivity`
undetected. There is a compiler again. Use it.
