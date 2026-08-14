# Five Tracks — parallel agent assignment

The current split of Newax Aegis into five independently-workable tracks.

Read `docs/PARALLEL_RULES.md` first — this document applies those rules; it does
not replace them. `docs/PARALLEL_WORKSPLIT.md` is an earlier, narrower split by
platform body for one specific effort.

---

## Read this before assigning anyone

**Five agents cannot start today.** Two rules from `PARALLEL_RULES.md` bind:

- **Rule 0** — the build has never been green. Nothing any track produces can be
  verified until Gate 0 clears.
- **Rule 1** — the decomposition of `MainActivity` (1403 lines) and
  `MainViewModel` (1207 lines) is touched by nearly every feature. Parallel work
  on top of it conflicts continuously.

So the ramp is:

```
WAVE 0   Track 1 alone.        Gate 0 · version catalog · CODEOWNERS · lint guards
           ↓
WAVE 1   Track 1 + Track 3.    Track 3 does the decomposition (slice 6) alone in
         Others: docs/specs    apps/android UI; nobody else edits that module
           ↓
WAVE 2   All five, fanned out. Disjoint ownership holds from here
           ↓
WAVE 3   Synchronized wave.    Tenancy T-1/T-2 lands in Track 2, then all five
                               do their own tenancy slices together
```

Assigning five agents at Wave 0 produces four agents writing unverifiable code
against a shape that is about to change. That is the failure this split exists
to prevent.

---

## The five tracks

| # | Track | Owns | Cannot touch |
|---|---|---|---|
| **1** | **Build, CI & Release** | build files, CI, tooling | any `src/` |
| **2** | **Core, Data & Policy** | `shared/core`, `shared/database`, `shared/platform-api`, `shared/model-api` | UI, agents, platform adapters |
| **3** | **Design System & Android UI** | `shared/ui`, `apps/android` UI | `agents/`, `engine/`, other modules |
| **4** | **Platform Bodies** | `platform-impl/*`, `apps/desktop`, `apps/macos`, `apps/ios` | `apps/android`, `shared/*` |
| **5** | **Agents, Automation & Safety** | `apps/android/…/agents`, `…/engine`, `relay` | UI files, `shared/*` |

The test from Rule 1 holds: no two tracks own the same file.

---

## Track 1 — Build, CI & Release

**Owns:** `*/build.gradle.kts` · `settings.gradle.kts` · `gradle/` ·
`.github/workflows/` · `scripts/` · the `AGENTS.md` baseline table ·
`keystore.properties.example`

**Why it owns all build files:** every other track needs to add dependencies,
and build files are the single most conflict-prone surface in a multi-module
repo. Other tracks **request** a dependency; Track 1 adds it. This is Rule 2
applied where it matters most.

**Work, in order:**

1. **Gate 0** — the Room KSP `MissingType` failure. Blocks everything.
2. **Version catalog** (`gradle/libs.versions.toml`) + CI check banning inline
   version literals. AGP and Kotlin are each declared in three files today.
3. `CODEOWNERS` and branch protection.
4. The guard table from `PARALLEL_RULES.md`: lint rules (no `Color(0x` outside
   `shared:ui`, no hardcoded user strings), doc link-check, banned-symbol list.
5. Slices 22 (supply chain: dependency verification, SBOM, reproducible builds)
   and the release pipeline.

**Publishes:** a green build, and the guards every other track is measured by.

**Blocked by:** nothing. **Blocks:** everyone.

---

## Track 2 — Core, Data & Policy

**Owns:** `shared/core/**` · `shared/database/**` · `shared/platform-api/**` ·
`shared/model-api/**` · schema migrations · `docs/MEMORY_DESIGN.md`

**Work:**

- Slice 7 — unify the three risk vocabularies (`Risk`, `RiskLevel`,
  `PolicyMode`). Serialized: it changes a type everyone reads.
- Slice 8 — conversation persistence. There is no conversation/message table
  among the 24 DAOs; chat is an in-memory list wiped on process death.
- Slice 9 — wire `ModelProvider.stream()`, which exists and is called nowhere.
- Tenancy **T-1, T-2, T-3** — profile scope, per-profile keys and databases,
  namespaced storage. **The highest-risk work in the project**: T-2 migrates
  every existing user's data.
- Property-based tests over the policy engine (`ENGINEERING.md` §B7) — the
  highest-value tests available.

**Publishes:** the data and policy interfaces every other track consumes.
**Consumes:** nothing from other tracks.

**Rule 1 note:** schema versions are claimed in the tracker before writing.
Two agents writing "v20" is unmergeable, not merely conflicting — and Track 2
is the only track that writes migrations.

---

## Track 3 — Design System & Android UI

**Owns:** `shared/ui/**` · `apps/android/src/main/java/com/newax/aegis/*Screen.kt`
· `MainActivity.kt` · `MainViewModel.kt` · `ui/**` · `res/**` ·
`docs/UI_DESIGN.md`

**Work:**

- **Slice 6 — decomposition, first and alone.** The god Activity and ViewModel
  become per-screen composables and testable state holders. Copy the pattern
  from `apps/desktop/.../ui/state/*.kt`, which already does this correctly.
- Verify slices 1–3 (`shared:ui` tokens, adoption, a11y primitives) once Gate 0
  clears — they are written but have never compiled.
- Slice 4 — string externalization. **Early**, because every later slice adds
  strings.
- Slice 5 — dark theme completion; unpin `NewaxTheme(darkTheme = false)`.
- Slice 10 — the component library, accessibility-first from each component's
  first commit.
- Slices 11–15 — chat shell, authority surface, Memory/Tasks/Capabilities, the
  Settings subtree, onboarding — the 105-route tree in `UI_DESIGN.md` §6.

**Consumes:** Track 2's data interfaces, Track 5's action/approval types.
**Publishes:** `shared:ui` components used by Track 4's desktop bodies.

---

## Track 4 — Platform Bodies

**Owns:** `platform-impl/**` · `apps/desktop/**` · `apps/macos/**` ·
`apps/ios/**` · `docs/SYNC_DESIGN.md`

**Work:**

- Slice 17 — the iOS body. **Requires a macOS host with Xcode**; Apple targets
  cannot compile from a Linux sandbox.
- Slice 18 — desktop parity. Desktop has no chat surface at all today; chat is
  a `--cli` REPL.
- Slice 16 — expanded layout: three panes, menu bar, keyboard shortcuts, command
  palette. The repo has **zero** key handling today, so this is greenfield.
- Tenancy **T-8** — device custody tiers, and raising Windows from DPAPI
  (user-account-scoped, not hardware-backed) to TPM via CNG. The highest-value
  platform-security work available.
- Tenancy **T-6** — multi-device enrollment across all four bodies.

**Consumes:** `shared:ui` from Track 3, capability contracts from Track 2.

**Hard constraint:** this track needs real hardware — a Mac for Apple targets, a
Windows machine for TPM work. Assigning it to an agent without them produces
unverifiable code, which Rule 9 says must be marked as such.

---

## Track 5 — Agents, Automation & Safety

**Owns:** `apps/android/src/main/java/com/newax/aegis/agents/**` ·
`…/engine/**` · `relay/**` · `docs/AGENTS_DESIGN.md` · `docs/COMPUTER_USE.md`

**Work:**

- **Slices C-1…C-9 — computer use.** C-2 (the untrusted-screen boundary) first,
  with the injection corpus built alongside the capability rather than after it.
- The agent-system gaps from `AGENTS_DESIGN.md` "Coverage status": no sandbox
  runtime ships (WASM is the only mobile option — Docker cannot run there), and
  no concurrency control exists despite atomic sequential writes being claimed.
- Tenancy **T-19** — per-profile agent memory. Collective learning must
  propagate within a profile and never across one.
- Slice 19 — security hardening: `CryptoObject`-bound biometrics, agent package
  signing, hash-chained audit.

**Consumes:** Track 2's policy engine and typed actions.
**Publishes:** the action vocabulary Track 3 renders approvals for.

**This track carries the most safety-critical work in the project.** The
approval spine is what stands between a model's output and the user's device.

---

## Cross-cutting waves

Two efforts do not fit in one track. They are **synchronized waves** where every
track does its own slices at the same time, against an interface Track 2 lands
first.

### Tenancy (T-1 … T-19)

```
Track 2: T-1 profile scope · T-2 per-profile DB · T-3 namespacing
              │  ← everyone waits here
   ┌──────────┼──────────┬──────────────┐
   ▼          ▼          ▼              ▼
Track 3    Track 4    Track 5      Track 2
T-9 UI     T-6 enroll T-19 agent   T-4/5/7
           T-8 custody memory      identity, lifecycle, recovery
```

Attempting T-9 before T-2 means building UI for a profile boundary that does not
exist yet.

### Computer use (C-1 … C-9)

Track 5 owns the safety architecture; Track 3 renders the approvals; Track 4
provides per-platform automation. C-1 (consequence classes) lands in Track 2's
policy engine first, because the floors are enforced at the spine.

---

## Shared files — the conflict surface

These are touched by everyone and belong to no one:

| File | Rule |
|---|---|
| `settings.gradle.kts`, all `build.gradle.kts` | **Track 1 only.** Others request |
| `AGENTS.md` baseline table | Track 1 only |
| `AGENTS.md` reference index | Append-only; integrator resolves |
| `.github/workflows/*` | Track 1 only — a new module invisible to CI is not built |
| `docs/ENGINEERING.md` slice list | Append-only; each track updates its own status |
| `ARCHITECTURE.md` concept registry | Append-only, and **read before naming any new type** |

---

## The inter-track contract

Because tracks publish interfaces to each other, `PARALLEL_RULES.md` Rule 6
binds hardest here: **change a contract and all its call sites in one commit.**

Every track, on finishing a task, records in its PR:

1. What was verified, and by which gate.
2. What was **not** verified, and why.
3. **Interfaces added or changed, and which tracks now depend on them.**
4. Anything discovered that invalidates a doc another track owns — raised to
   that track, not silently edited.
5. Assumptions taken, stated as assumptions.

Point 3 is the one that prevents the half-wired interfaces already in this repo:
`ModelProvider.stream()` and `AgentStream` both look like live capability and
are called from nowhere.

---

## If you must start five agents immediately

Not recommended — but if the constraint is fixed, this is the least-bad
arrangement, because it keeps four agents off code that cannot compile:

| Track | Pre-Gate-0 work |
|---|---|
| 1 | Gate 0. Real work |
| 2 | Design the conversation schema and the unified risk vocabulary. Write migrations without merging |
| 3 | Route-by-route component specs from `UI_DESIGN.md` §6; screenshot-test fixtures |
| 4 | Provision a Mac and a Windows machine; audit per-platform key custody |
| 5 | **Build the prompt-injection corpus.** Pure data, needs no compiler, and is the highest-value artifact available before the build is green |

Four of those five produce specifications and test data rather than shipping
code — which is the honest answer to what parallelism is worth before there is
a gate.
