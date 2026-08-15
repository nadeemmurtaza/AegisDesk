# Track 2 — Core, Data & Policy

Your complete brief. Read `AGENTS.md` and `docs/PARALLEL_RULES.md` before your
first commit.

---

## 1. Who you are

You own the brain's data and its safety rules. Every other track consumes what
you publish.

**You own:**

```
shared/core/**            authority spine, models, Platform seam
shared/database/**        Room entities, DAOs, ALL migrations
shared/platform-api/**    capability contracts
shared/model-api/**       ModelProvider
docs/MEMORY_DESIGN.md
```

**You never touch:** UI files, `agents/`, `engine/`, `platform-impl/*`, build
files. Need a dependency? Ask Track 1.

**You are the only track that writes schema migrations.** Two agents writing
"v20" is unmergeable — the loser rewrites, they do not rebase. Claim the version
in the tracker *before* writing.

---

## 2. Set up, and prove it works

```bash
export JAVA_HOME=/usr/lib/jvm/java-17-openjdk-amd64      # NOT 21
export ANDROID_HOME=$HOME/android-sdk
echo "sdk.dir=$ANDROID_HOME" > local.properties          # gitignored

./gradlew :shared:database:desktopJar :shared:database:assemble
```

Must print `BUILD SUCCESSFUL`. `assemble` includes the three Apple native
targets — Kotlin/Native cross-compiles them from Linux, so you get real Apple
verification without a Mac. Only linking a final framework needs one.

---

## 3. Rules that bind you hardest

- **`commonMain` is platform-free.** No `java.*`, no `android.*`. This rule
  caused two of the five faults that kept the build red for the project's entire
  history: `kotlinx.datetime.Clock.System` and a bare `@Volatile`. Both looked
  harmless; both failed only on some targets.
- **`@Volatile` needs `import kotlin.concurrent.Volatile`** in commonMain. The
  JVM auto-imports `kotlin.jvm.Volatile`, so per-target compiles pass and only
  `compileCommonMainKotlinMetadata` fails. Always run `assemble`, never just
  `desktopJar`.
- **New target → per-target KSP config in the same commit** (AGENTS.md R5).
- **Read `docs/rules/compatibility.md`** before touching Room, KSP, or sqlite
  versions. They are coupled.

---

## Slice T2.1 — ~~The `instrumented-tests` failure~~ ✅ RESOLVED

**Nothing to do. Kept as a record, because the route here was instructive.**

`MigrationTest` completed its first full run in the project's history on
`66aad40`: **22 started, 22 finished, zero failures.** All 18 `migrateNToN+1`
steps pass, plus both full paths (`1→13`, `1→19`), the sync DAO round-trip and
current-schema validation. The schema is sound.

### Three wrong answers came first

| Diagnosis | Verdict |
|---|---|
| "A migration/schema mismatch" | **Wrong** — guessed from the Gradle stack-trace tail |
| Eager Ed25519 call in `Application.onCreate` | Real: crashed every device below Android 12 |
| SQLCipher native library never loaded | Real: crashed *every* device, every ABI |
| `serialization-json:1.8.1` vs core `{strictly 1.7.3}` | Real: could not parse the schema JSON |

None was a schema problem. Two were shipped crashes that existed *because* CI
could never run the app — the suite that would have caught them was structurally
unable to report.

**The lesson, and the reason this section survives:** both wrong diagnoses came
from reading the Gradle stack trace instead of the runner output. `Starting 0
tests` was in the log the entire time, above two hundred lines of Gradle
internals. **Read the runner's own output first.** And when a check turns green,
check the test *count* — a suite that runs zero tests also passes.

---

## Slice T2.2 — Unify the risk vocabularies (slice 7)

**Goal:** one type for "how dangerous is this action".

**Why:** three exist today —
`MainActivity`'s local `Risk {Routine, Sensitive, HighImpact}`, the shared
`RiskLevel {LOW, MEDIUM, HIGH, CRITICAL}`, and `PolicyMode {AUTO,
CONFIGURABLE, APPROVAL, STRONG_CONFIRMATION}`. Three names for one safety
concept is how a mis-mapping silently downgrades an approval requirement, and
nothing would catch it.

**This is a Rule 1 serialized task.** Everyone reads this type. Land it alone.

**Steps:**

1. Decide the canonical model first, in `ARCHITECTURE.md`'s concept registry.
   `RiskLevel` describes the action; `PolicyMode` describes the required gate.
   Those are genuinely two things — keep both, delete `Risk`.
2. Enumerate every call site before changing any:
   ```bash
   grep -rn 'RiskLevel\|PolicyMode\|enum class Risk' --include=*.kt .
   ```
3. Change the type and **all** call sites in one commit (Rule 6). A half-migrated
   safety type is worse than either version.
4. `MainActivity`'s `Risk` lives in Track 3's file. **Request the edit; do not
   make it.** Coordinate — this is the one place your slice reaches into
   another track.
5. Ask Track 1 to add the retired name to the banned-symbol guard.

**Verify:** `./gradlew :shared:core:assemble :apps:android:assembleDebug`

---

## Slice T2.3 — Property tests over the policy engine

**Goal:** the highest-value tests in the project.

**Why:** the authority spine is what stands between a model's output and the
user's device. Example-based tests will not find the ordering bug that breaks
it; property tests will.

**Invariants to encode** (already stated in `ENGINEERING.md` §B7):

1. No sequence of inputs downgrades a `STRONG_CONFIRMATION` action to `AUTO`.
2. Every executed action has a preceding approval of at least its required level.
3. A rejected action never executes.
4. Every terminal state produces exactly one audit entry.

**Steps:** add jqwik or Kotest property testing to `shared/core`'s test source
set (**ask Track 1 for the dependency**). Generate action sequences, assert the
invariants hold.

**Done when:** all four run in CI. Do not chase coverage percentages — cover the
spine exhaustively and leave glue alone.

---

## Slice T2.4 — Conversation persistence (slice 8)

**Goal:** chat survives process death.

**Why:** there is **no conversation or message table** among the 24 DAOs. Chat is
`mutableStateListOf` in `MainViewModel:97`, wiped when the process dies. This
blocks Track 3's routes 1.1, 1.6, 1.11, 1.12 — they cannot build a conversation
list over data that does not exist.

**Steps:**

1. **Claim schema version 20 in the tracker before writing anything.**
2. Design `ConversationEntity` + `MessageEntity`. Read `docs/UI_DESIGN.md` §7
   first — messages carry stacked content blocks, not just text, and designing
   for plain text now means a second migration later.
3. Write the migration, export the schema, add a `migrate19To20` test.
4. Publish a DAO interface and **tell Track 3 in your PR** — they are waiting.

**Verify:** `:shared:database:assemble` + the new migration test.

---

## Slice T2.5 — Wire streaming (slice 9)

`ModelProvider.stream(): Flow<String>` exists in `shared/model-api` and is
**called from nowhere**. `MainViewModel:594` uses `complete()`, so replies arrive
whole and there is no stop button to design around.

Your half: make `stream()` real in the providers and cancellable. Track 3 renders
it. Coordinate the interface in your PR body — Rule 6 says a contract change
names its dependents.

---

## Slice T2.6 — Tenancy T-1/T-2/T-3 (Wave 3)

Read `docs/TENANCY_DESIGN.md` §9 and §11 in full first.

- **T-1** — `ProfileId` + `ProfileScope`; retire the 8 process-wide `object`
  holders. This is also what blocks unit testing today, so it pays twice.
- **T-2** — per-profile keys and databases. **The highest-risk slice in the
  project**: it migrates every existing user's data. Migration test,
  backup-before-migrate, and a rollback path *before* it ships.
- **T-3** — namespace memory, persona settings, policy, audit.

**Isolation is by key, never by `tenant_id` filtering.** One missing `WHERE` is
a silent cross-profile leak; a missing key is unreadable bytes.

Note the name collision: the existing `ProfileManager` means *persona settings*.
Rename it to `PersonaSettings` in the same commit that introduces `Profile` as
an isolation boundary, or two very different things share one word.

---

## When you are blocked

- **Need a dependency:** ask Track 1.
- **Need a change in `MainActivity`/`MainViewModel`:** ask Track 3. Happens in
  T2.2 and T2.5 — expect it, plan for it.
- **Apple-only failure:** you can compile Apple targets from Linux
  (`:shared:database:assemble`). Only framework linking needs a Mac.

---

## Before every PR

- [ ] Title starts `[T2]`
- [ ] Only your files changed
- [ ] `./gradlew :shared:database:assemble :shared:database:desktopJar :shared:platform-api:jvmTest` passes
- [ ] **`assemble`, not just `desktopJar`** — metadata and native targets fail differently
- [ ] Schema version claimed before any migration
- [ ] Interface changes named in the PR body, with the tracks that depend on them
