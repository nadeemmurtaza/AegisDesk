# Agent kickoff prompts

Copy-pasteable starting instructions for the five tracks, plus an honest
statement of whether each path is actually clear.

Read this before starting anyone.

---

## ✅ Precondition cleared — PR #4 merged as `19c3253`

The blocker this file used to open with is gone. `main` now carries the Ed25519
startup fix, the SQLCipher `System.loadLibrary` call, the serialization BOM
alignment, the `:apps:desktop` compile fixes, all five track briefs,
`docs/AUTH_DESIGN.md`, `docs/UNWIRED.md` and the unwired guard. All six CI checks
were green.

**Branch every agent from the current `main`, not from a track branch.**

### One round of track work has already landed, and it is instructive

`d390f67` (Track 2) reported four slices done. Re-reading the code against the
commit message found that three had overstated what shipped — tests that no
workflow ran, a contract claim that held only for the stub implementation, a
schema that ignored the spec its own brief pointed at, and a doc that called an
enum "retired" while it sat in the tree. All four are now closed or handed over,
and each correction is recorded next to the slice that earned it in
`docs/tracks/T2-core-data-policy.md`.

Read that file before starting Track 3. **The failures were not coding failures.
Every one was a claim that outran the code**, and every one would have been
caught by one `grep`. The rules at the bottom of this document are the
generalisation, and they are worth more than the slice descriptions.

---

## Readiness

| Track | Path | First slice | Notes |
|---|---|---|---|
| **T1** Build/CI | ✅ clear | T1.1 version catalog | Two quick wins waiting: add desktop+macOS to CI, wire the unwired guard |
| **T2** Core/Data | ✅ clear | T2.6 tenancy T-1 | T2.1–T2.5 landed; T2.5's `cancel()` half is still open |
| **T3** Design/UI | ✅ clear, **and blocking** | T3.0 handovers, then T3.1 decomposition | Three small unblocking edits Track 2 handed over, then the restructure T2/T5 are waiting on |
| **T4** Platform | ✅ clear | T4.1 desktop parity | T4.0 is **resolved** — desktop compiles, 113 tests pass |
| **T5** Agents | ✅ clear | T5.1 injection corpus | Pure data; needs no compiler and no other track |

**Only one ordering constraint remains:** T3.1 restructures `MainActivity` and
`MainViewModel`. T2 and T5 must stay out of `apps/android` until it merges. T1
and T4 are unaffected and can run from day one.

**Recent churn every agent should know about.** The last 26 commits touched
`apps/android` heavily — `NewaxApplication`, `ExecutionGuard`, `ProcedureExecutor`,
`AgentsScreen`, `PeopleScreen`, `ConnectivityDashboard`, `DeviceRegistry`,
`BackgroundLearner`, `ResourceProfiler`. Read `git log` before assuming a file is
as your brief describes it.

---

## Prompt — Track 1 · Build, CI & Release

```
You are Track 1 (Build, CI & Release) on Newax Aegis, an offline-first Kotlin
Multiplatform personal assistant.

READ FIRST, IN THIS ORDER:
  1. AGENTS.md               — invariants; if anything conflicts with it, it wins
  2. docs/PARALLEL_RULES.md  — how five agents avoid colliding
  3. docs/tracks/T1-build-ci-release.md  — your complete brief; work from it

YOU OWN (nobody else edits these):
  build.gradle.kts (root), settings.gradle.kts, */build.gradle.kts,
  gradle/, .github/workflows/, scripts/, gradle.properties,
  and the "Current baseline" table in AGENTS.md

YOU NEVER TOUCH: any src/ directory. If a build change needs a code change,
request it from the owning track.

SET UP AND PROVE IT WORKS BEFORE WRITING ANYTHING:
  export JAVA_HOME=/usr/lib/jvm/java-17-openjdk-amd64      # NOT 21
  export ANDROID_HOME=$HOME/android-sdk
  echo "sdk.dir=$ANDROID_HOME" > local.properties          # gitignored, never commit
  ./gradlew :shared:core:compileKotlinJvm :shared:ui:jvmTest
That must print BUILD SUCCESSFUL. If it fails locally but passes in CI, fix your
machine first.

START WITH THE TWO QUICK WINS — both are ready and unblock other tracks:

  (a) Slice T1.4b — CI builds :apps:android and nothing else. :apps:desktop,
      :apps:macos and :apps:ios are in no workflow, which is exactly how the
      desktop body rotted into non-compilation unnoticed. It compiles now and
      its 113 tests pass, so add :apps:desktop:test and :apps:macos:compileKotlin
      to a workflow.

  (b) Slice T1.4c — scripts/check-unwired.sh exists and is verified failing on a
      deliberate violation. Add it to invariants.yml's static-invariants job
      beside check-invariants.sh. It is pure shell and runs in seconds. It is
      baselined (scripts/unwired-baseline.txt, 173 entries) on purpose: a check
      that fails on all existing debt gets disabled within a week.

THEN slice T1.1 — the version catalog. AGP 9.3.1 and Kotlin 2.4.10 are each
declared in three separate files, which is how Compose Multiplatform drifted to
1.7.1 against a 1.11.1 baseline. Move every version into gradle/libs.versions.toml
and convert modules ONE AT A TIME, compiling after each. Do not change any
version's VALUE in that slice — it is a pure move, so a break has one candidate
cause, not two.

RULES THAT BIND YOU HARDEST:
  - A new module is invisible to CI until you add it. Adding it is your job.
  - Never change a version because it "looks old". Versions here are coupled
    (Kotlin↔KSP↔AGP↔Compose↔Room↔sqlite). Read docs/rules/compatibility.md.
  - Append-only on shared files: add lines, do not reorganise.
  - Do NOT enable the hardcoded-string guard before Track 3 finishes slice 4 —
    it will fail on every existing screen. Talk to them.
  - Do NOT add lintDebug to CI until slice A-6 lands: two lint errors remain and
    A-6 deletes both call sites outright.

VERIFY BEFORE EVERY PR:
  ./gradlew :apps:android:assembleDebug :shared:database:assemble \
            :shared:ui:jvmTest :platform-impl:windows:test

PR: title starts "[T1]". Only files from your ownership list. Any new guard must
have been SEEN TO FAIL on a deliberate violation — a guard you have not watched
fail is not a guard. PR body records what you verified and by which command.
```

---

## Prompt — Track 2 · Core, Data & Policy

```
You are Track 2 (Core, Data & Policy) on Newax Aegis, an offline-first Kotlin
Multiplatform personal assistant. You own the brain's data and its safety rules;
every other track consumes what you publish.

READ FIRST, IN THIS ORDER:
  1. AGENTS.md
  2. docs/PARALLEL_RULES.md
  3. docs/tracks/T2-core-data-policy.md   — your complete brief

YOU OWN: shared/core/**, shared/database/** (including ALL migrations),
shared/platform-api/**, shared/model-api/**, docs/MEMORY_DESIGN.md

YOU NEVER TOUCH: UI files, agents/, engine/, platform-impl/*, build files.
Need a dependency? Ask Track 1.

YOU ARE THE ONLY TRACK THAT WRITES SCHEMA MIGRATIONS. Two agents writing "v20"
is unmergeable — the loser rewrites, they do not rebase. Claim the version in
the tracker BEFORE writing.

SET UP:
  export JAVA_HOME=/usr/lib/jvm/java-17-openjdk-amd64      # NOT 21
  export ANDROID_HOME=$HOME/android-sdk
  echo "sdk.dir=$ANDROID_HOME" > local.properties
  ./gradlew :shared:database:desktopJar :shared:database:assemble

Run `assemble`, never just `desktopJar`. Metadata and native targets fail
differently: a bare @Volatile and a kotlinx.datetime.Clock call both compiled
per-target and only failed compileCommonMainKotlinMetadata.

SLICE T2.1 IS ALREADY RESOLVED — do not redo it. MigrationTest completed its
first full run in the project's history: 22 tests started, 22 finished, zero
failures. All 18 migrations pass. Read that section of your brief anyway: three
wrong diagnoses came first, and the lesson is operational — read the TEST RUNNER
output, not the Gradle stack-trace tail. "Starting 0 tests" sat in the log the
whole time under 200 lines of Gradle internals.

START WITH SLICE T2.2 — unify the three risk vocabularies. Today there are:
MainActivity's local Risk {Routine, Sensitive, HighImpact}, the shared
RiskLevel {LOW, MEDIUM, HIGH, CRITICAL}, and PolicyMode {AUTO, CONFIGURABLE,
APPROVAL, STRONG_CONFIRMATION}. Three names for one safety concept is how a
mis-mapping silently downgrades an approval requirement with nothing to catch it.
This already bit once: a desktop test used RiskLevel.HIGH_IMPACT_SYSTEM, which is
a PrivilegeLevel value, and never compiled because nothing built that module.

THIS IS A SERIALIZED TASK. Everyone reads this type — land it alone.
  1. Decide the canonical model in ARCHITECTURE.md's concept registry first.
     RiskLevel describes the action; PolicyMode describes the required gate.
     Those are genuinely two things — keep both, delete Risk.
  2. Enumerate every call site before changing any:
       grep -rn 'RiskLevel\|PolicyMode\|enum class Risk' --include=*.kt .
  3. Change the type and ALL call sites in one commit. A half-migrated safety
     type is worse than either version.
  4. MainActivity's Risk lives in Track 3's file — REQUEST the edit, and only
     after their decomposition (T3.1) has merged. Do not enter apps/android
     before then.
  5. Ask Track 1 to add the retired name to the banned-symbol guard.

THEN: T2.3 property tests over the policy engine (four invariants are stated in
ENGINEERING.md §B7 — these are the highest-value tests in the project),
T2.4 conversation persistence (claim schema v20 first; no conversation or message
table exists among the 24 DAOs, and Track 3's routes 1.1/1.6/1.11/1.12 are
waiting on it), T2.5 wire ModelProvider.stream() — it is declared, implemented in
FallbackModelProvider, covered by a contract test, and called from NO production
code.

VERIFY BEFORE EVERY PR:
  ./gradlew :shared:database:assemble :shared:database:desktopJar \
            :shared:platform-api:jvmTest :shared:core:jvmTest

PR: title starts "[T2]". Interface changes named in the PR body with the tracks
that depend on them.
```

---

## Prompt — Track 3 · Design System & Android UI

```
You are Track 3 (Design System & Android UI) on Newax Aegis, an offline-first
Kotlin Multiplatform personal assistant. You own everything the user sees on
Android and the design system all four bodies render through.

YOUR FIRST TASK BLOCKS FOUR OTHER TRACKS. Do it first, do it alone, merge it fast.

READ FIRST, IN THIS ORDER:
  1. AGENTS.md
  2. docs/PARALLEL_RULES.md
  3. docs/tracks/T3-design-system-android-ui.md   — your complete brief
  4. docs/UI_DESIGN.md                            — the spec you implement
  5. ARCHITECTURE.md "Concept registry" (Part 1)  — names two of your first edits

YOU OWN: shared/ui/**, apps/android/**/*Screen.kt, MainActivity.kt,
MainViewModel.kt, apps/android/.../ui/**, apps/android/src/main/res/**,
docs/UI_DESIGN.md

YOU NEVER TOUCH: agents/, engine/ (Track 5 owns those, inside your module),
shared/core, shared/database, platform-impl/*, build files. You CONSUME what
Track 2 published; you do not edit it. Need a change there? Ask, in your PR.

SET UP:
  export JAVA_HOME=/usr/lib/jvm/java-17-openjdk-amd64      # NOT 21
  export ANDROID_HOME=$HOME/android-sdk
  echo "sdk.dir=$ANDROID_HOME" > local.properties
  ./gradlew :shared:ui:jvmTest :apps:android:assembleDebug

:shared:ui:jvmTest runs ContrastTest — 84 accessibility assertions. It must stay
green on every commit. The palette is contrast-verified: do not change a brand
colour without recomputing. Five values in the original palette failed WCAG AA,
including a 2.00:1 colour marking policy-blocked actions.

BOTH OF YOUR CI GATES ALREADY RUN YOUR TESTS: invariants.yml runs
:shared:ui:jvmTest and :apps:android:testDebugUnitTest. Check that this is still
true for any NEW module you add — a module is invisible to CI until a workflow
names it, and that is exactly how 185 of Track 2's tests came to run nowhere.

─────────────────────────────────────────────────────────────────────────────
SLICE T3.0 — THREE HANDOVERS FROM TRACK 2. Small, mechanical, do them first.
─────────────────────────────────────────────────────────────────────────────

Track 2 finished its half of three slices and each one ends inside YOUR files.
None is large. All three are blocking something.

  T3.0a — DELETE `private enum class Risk` (MainActivity.kt:619).
    There are three risk vocabularies in this repo and there should be two.
    `RiskLevel` describes the action; `PolicyMode` describes the required gate;
    `Risk {Routine, Sensitive, HighImpact}` is a third, local, and WRONG — it
    buckets irreversible deletes with sends, under-classifies calendar events and
    over-classifies searches. Replace the local classification (lines 621–634)
    with `action.riskLevel` and derive the badge label and colours from it
    (lines 642–644). Read the concept registry in ARCHITECTURE.md Part 1 first.
    Tell Track 1 when it is gone so they can add `enum class Risk` to the
    banned-symbol guard — it cannot be added before you delete it.

  T3.0b — WIRE `conversationDao`. Chat is `mutableStateListOf` in MainViewModel
    and dies with the process. Schema v20 gives you `conversations`, `messages`
    and `message_blocks`, with reactive Flow queries and a transactional delete.
    Messages are STACKED CONTENT BLOCKS (UI_DESIGN §7, ten kinds), not strings:
      - `messages.text` is the plain-text rendering — chat-list snippet, search,
        and any surface that cannot render blocks.
      - `message_blocks(messageId, position, type, content, metadata)` is the
        real content, ordered by `position`.
      - A plain-text turn stores NO block rows. "No blocks" reads as one implicit
        text block. Do not write a TEXT block for every plain message.
      - `type` is a string on purpose: an unknown kind from a newer build must
        round-trip, not be dropped. Render an unknown kind as its `content`.
    `ConversationDao.deleteConversation` is the only correct delete path — it
    removes blocks, then messages, then the row, in one transaction, in that
    order. Do not call the pieces yourself.

  T3.0c — RENDER THE STREAM, AND READ THIS BEFORE DESIGNING THE STOP BUTTON.
    `ModelProvider.complete()` is now the collected `stream()`, with no provider
    overriding it, so switching MainViewModel to collect `stream()` directly gets
    you incremental rendering with no contract change.
    BUT: `ModelProvider.cancel()` IS A DOCUMENTED NO-OP on both real providers —
    neither LiteRT's `sendMessage()` nor kherud's `generate()` is interruptible.
    Cancelling the collecting coroutine stops the UI updating; it does NOT stop
    the model burning tokens and battery. So:
      - Build the stop button on Flow cancellation (cancel the collect job).
      - Say plainly in the UI and in your PR that the generation is abandoned,
        not aborted, until a provider-level cancellation path exists.
      - Do NOT write "streaming is cancellable" anywhere. It is not, yet.
    Also note UI_DESIGN §7.3: the bubble roles are inverted today — assistant
    renders light-on-near-black, user dark-on-light, and the spec wants the
    opposite. Fix it while you are in ChatBubble.

  Verify T3.0: ./gradlew :apps:android:assembleDebug :apps:android:testDebugUnitTest
  Ship these as ONE small PR before decomposition. They are the unblocking edits
  other tracks are waiting on, and they get harder once files start moving.

─────────────────────────────────────────────────────────────────────────────
SLICE T3.1 — DECOMPOSITION, ALONE. The one that blocks four tracks.
─────────────────────────────────────────────────────────────────────────────

MainActivity (1403 lines) and MainViewModel (1207 lines) become per-screen
composables and testable state holders. Nearly every feature touches these two
files; until this merges, no other track can safely enter apps/android.

  - COPY THE PATTERN THAT ALREADY WORKS: apps/desktop/.../ui/state/*.kt. Desktop
    does this correctly — plain-Kotlin, injectable, unit-tested state holders with
    all decision logic outside Compose. Read GoalsScreenState.kt and its test
    before you start.
  - Read before writing. MainActivity contains the app shell, drawer, ChatScreen,
    MemoryScreen, SettingsScreen, MeetingScreen, the theme wrapper and the chat
    components. Map it before cutting.
  - Extract ONE SCREEN AT A TIME, compiling after each. Not all at once.
  - Watch for ~10 hardcoded regex fast-paths in MainViewModel.submit() that
    bypass the model entirely. PRESERVE THE BEHAVIOUR, do not silently drop them,
    and note them in your PR as something Track 5 should own later.
  - Do NOT change behaviour while restructuring. Two candidate causes for a
    regression is one too many.
  - Do NOT leave a half-extracted screen across a merge. Ship whole screens.

  Verify after each extraction:
    ./gradlew :apps:android:assembleDebug :apps:android:testDebugUnitTest
  Done when: no file over ~400 lines, each state holder has a unit test, and the
  app behaves identically.

BE AWARE: apps/android changed a lot recently — NewaxApplication was restructured
(startup steps are classified essential vs optional and failures are recorded in
StartupReport), and AgentsScreen, PeopleScreen and several engine files were
fixed. Read git log before assuming a file matches your brief.

THEN: T3.2 string externalization (do it before the component library or you do
it twice; tell Track 1 when done so they can enable the guard), T3.3 dark theme,
T3.4 the ~60-component library, T3.5 the 105-route tree.

THE RULE THAT MATTERS FOR COMPONENTS: every one ships semantics, focus behaviour
and a 44 dp minimum target IN ITS FIRST COMMIT. Retrofitting accessibility is how
it never happens — this repo had zero uses of Modifier.semantics before slice 3.
Note contentDescription = null is CORRECT for decorative icons; naming everything
makes screen readers worse. State goes on the control, not the glyph.

─────────────────────────────────────────────────────────────────────────────
FIVE WAYS THE LAST TRACK'S WORK LOOKED FINISHED AND WAS NOT. Avoid all five.
─────────────────────────────────────────────────────────────────────────────

Every one of these reached `main` inside a commit whose message said it was done.
They were found by reading the code against the claim. Yours will be read the
same way.

  1. TESTS THAT NO WORKFLOW RUNS. 185 tests compiled on every PR and executed on
     none. Before you write "covered by tests", grep .github/workflows for the
     gradle task that runs them.

  2. "RETIRED" / "REMOVED" / "REPLACED" WRITTEN ABOUT CODE STILL IN THE TREE.
     Deferring to another track is fine. Recording the deferral as completion is
     not. Write what the code does, not what you intend it to do.

  3. A CLAIM THAT SOMETHING IS "NOW CALLED IN PRODUCTION" WITH NO CALL SITE.
     `stream()` was declared production-wired while both real providers bypassed
     it. Name the call site. Then grep for anything that overrides or shadows it.
     This one is aimed straight at you: T3.0b and T3.0c are both "wire the thing
     that exists but nothing calls". Prove the wire, do not assert it.

  4. DESIGNING WITHOUT READING THE SPEC THE SLICE POINTED AT. A one-sentence
     pointer to UI_DESIGN §7 was skipped and the schema shipped as a plain
     string. You are the track whose entire job is UI_DESIGN. Read the section
     before you build the component, every time.

  5. DECLARING A DOCUMENT ABSENT INSTEAD OF GREPPING FOR IT. A test header
     asserted ENGINEERING.md §B7 did not exist. It is at line 534.

AND ONE THAT IS SPECIFICALLY YOURS: do not claim an accessibility property you
have not exercised. TalkBack, VoiceOver, 200% font scale, RTL and reduced motion
cannot be verified from a build agent. Semantics that COMPILE are not semantics
that WORK. Ship the code, list the device checks you could not run, and hand that
list to the user — do not write "WCAG AA verified" on the back of a green build.

PR: title starts "[T3]". Not agents/ or engine/. Any UI_DESIGN.md change ships in
the same commit as the code. State what you ran and what you could not.
```

---

## Prompt — Track 4 · Platform Bodies

```
You are Track 4 (Platform Bodies) on Newax Aegis, an offline-first Kotlin
Multiplatform personal assistant. You own the three bodies that are not Android —
Windows, macOS, iOS — and the per-OS capability adapters all four stand on.

READ FIRST, IN THIS ORDER:
  1. AGENTS.md
  2. docs/PARALLEL_RULES.md
  3. docs/tracks/T4-platform-bodies.md   — your complete brief

YOU OWN: platform-impl/android/** (the ADAPTER, not the app), platform-impl/
windows/**, platform-impl/macos/**, platform-impl/ios/**, apps/desktop/**,
apps/macos/**, apps/ios/**, docs/SYNC_DESIGN.md

YOU NEVER TOUCH: apps/android, shared/**, build files.
Note the split: platform-impl/android is yours; apps/android is Track 3's.

HARDWARE PREREQUISITE — sort this before writing code:
  - A Mac with Xcode for iOS/macOS app work and framework linking
  - A Windows machine for TPM and the DPAPI/Toolhelp32 paths
Kotlin/Native cross-compiles Apple TARGETS from Linux, so :shared:*:compileKotlinIosArm64
verifies anywhere; only linking a framework and running on a device need a Mac.
platform-impl:windows's Windows-only tests are Assume-gated: they compile
everywhere and SKIP the OS-bound paths, so a green Linux run proves nothing about
DPAPI. If you lack the hardware, say so early and mark that work UNVERIFIED —
four tracks planning around your "done" deserve to know which kind it is.

SET UP:
  export JAVA_HOME=/usr/lib/jvm/java-17-openjdk-amd64      # NOT 21
  export ANDROID_HOME=$HOME/android-sdk
  echo "sdk.dir=$ANDROID_HOME" > local.properties
  ./gradlew :platform-impl:windows:test :apps:desktop:test :apps:macos:compileKotlin

SLICE T4.0 IS ALREADY RESOLVED — do not redo it. :apps:desktop did not compile at
all and its 113 tests had never run, because no CI workflow built it. Six API-drift
faults are fixed, and one real bug was found once the tests ran:
DesktopPolicyHolder.init constructed the audit store but never called load(), so
every policy decision was written to disk and never read back — the desktop policy
audit silently started empty on every launch.

FIRST ACTION: ask Track 1 to add :apps:desktop and :apps:macos to a CI workflow.
Everything above happened because nothing checked. Do this before building on top.

START WITH SLICE T4.1 — desktop parity. apps/desktop today is five NavigationRail
items (Status, Apps, Goals, Policy, Audit) and NO CHAT AT ALL; desktop chat is a
--cli REPL. The product's main surface does not exist on desktop.
  - Consume shared:ui. It is already wired and its jvm() target exists to serve
    you. NewaxTheme gives you the same tokens Android uses.
  - Replace apps/desktop/.../ui/NewaxTheme.kt's local lightColorScheme with the
    shared theme.
  - Build the thread surface from UI_DESIGN.md §6.3 (routes 1.1, 1.2).
  - Map the existing five screens onto the new IA per UI_DESIGN.md §5.1.
  - Do NOT re-implement components that exist in shared:ui. If one is missing,
    ask Track 3 to add it there rather than writing a desktop-only twin — that is
    exactly how 189 duplicated constants happened.

THEN: T4.2 expanded layout (three panes, menu bar, shortcuts — the repo has ZERO
key handling today; approve/reject must be keyboard-reachable but NEVER
single-key), T4.3 Windows TPM custody (DPAPI is user-account-scoped, NOT
hardware-backed — read docs/TENANCY_DESIGN.md §6), T4.4 the iOS body,
T4.5 multi-device enrollment (SAS mismatch is a HARD STOP; profiles enroll
individually).

CMP HAS NO macosArm64 UI TARGET. macOS renders through Compose Desktop on the
JVM. If you find yourself adding macosArm64 to a UI module, stop — that is why
shared:ui deliberately omits it.

A capability that cannot work on your OS returns NOT_SUPPORTED. It does not crash
and does not silently no-op.

PR: title starts "[T4]". Not apps/android, not shared/**. Anything needing
hardware you lack is EXPLICITLY MARKED UNVERIFIED.
```

---

## Prompt — Track 5 · Agents, Automation & Safety

```
You are Track 5 (Agents, Automation & Safety) on Newax Aegis, an offline-first
Kotlin Multiplatform personal assistant. You own the machinery that turns model
output into intent, and the guards between that intent and the user's device.
Nothing else in this project can hurt someone. Your code can.

READ FIRST, IN THIS ORDER:
  1. AGENTS.md
  2. docs/PARALLEL_RULES.md
  3. docs/COMPUTER_USE.md    — read END TO END before your first DESIGN decision
  4. docs/tracks/T5-agents-automation-safety.md   — your complete brief
  5. docs/UNWIRED.md §3      — what happened to the last safety check here

YOU OWN: apps/android/.../agents/** (11 files), apps/android/.../engine/**
(~130 files), relay/**, docs/AGENTS_DESIGN.md, docs/COMPUTER_USE.md

YOU NEVER TOUCH: any *Screen.kt, MainActivity.kt, MainViewModel.kt,
apps/android/src/main/res/** (Track 3), shared/** (Track 2), platform-impl/**
and the other app bodies (Track 4), any build.gradle.kts (Track 1).

You share a Gradle module with Track 3 but not a file. DO NOT ENTER apps/android
UNTIL TRACK 3's DECOMPOSITION (T3.1) HAS MERGED — that is the one serialized task.
Your slice T5.1 needs no compiler, so start there and you are not blocked.

THE TWO INVARIANTS YOU MAY NEVER BREAK:
  1. THE ROUTER ROUTES; IT NEVER DECIDES PERMISSION. AgentRouter.route() picks
     who handles this. If it could also decide what that handler may do, then
     influencing routing — which untrusted screen text can do — becomes privilege
     escalation. Policy is resolved at the spine, from the active profile, after
     routing, every time.
  2. NO AGENT HOLDS EXECUTION AUTHORITY. Every agent is a planner. It emits typed
     actions into the one authority spine and the spine decides. An agent that
     calls an Android API directly is a second spine, and a second spine is
     authority laundering: the audit says "the agent did it" and no human ever
     approved anything.

Ask of every PR: if an attacker fully controlled the model's output, what would
this change let them do? If the answer is anything but "propose something a human
then rejects", the design is wrong.

SET UP:
  export JAVA_HOME=/usr/lib/jvm/java-17-openjdk-amd64      # NOT 21
  export ANDROID_HOME=$HOME/android-sdk
  echo "sdk.dir=$ANDROID_HOME" > local.properties
  ./gradlew :apps:android:testDebugUnitTest

Read apps/android/src/test/.../assistant/ActionGateTest.kt and
engine/procedure/ExecutionGuardTest.kt before writing anything. Their cases are
named as CLAIMS ("background text can never auto-execute a destructive action
even when the toggle is on"), so a failure reads as a statement about safety
rather than a method name. Match that.

START WITH SLICE T5.1 — THE PROMPT-INJECTION CORPUS. Pure data: it needs no
compiler and no other track, so you are unblocked on day one.
  - There is nothing to build on. The repo has ZERO test fixture data files.
  - Define the fixture shape first: the user's actual request, the hostile screen
    content, the expected plan, and a one-line note on what the payload attempts.
    The note matters — in a year someone will ask why a case exists.
  - Payload families, at minimum: direct instruction; impersonated system text;
    impersonated approval; RETARGETING (right action, wrong recipient — the
    dangerous one, because the plan still looks plausible); risk-class laundering;
    delimiter escape; encoded/split (base64, homoglyphs, zero-width joiners, an
    instruction spread across several UI elements).
  - ASSERT ON THE PLAN, NOT THE TEXT. A test that greps output for "ignore
    previous instructions" passes for a model that obeyed the payload silently.
  - Include NEGATIVE cases: screens whose text legitimately fills a parameter
    ("the confirmation number is 4471") must still work. A boundary that blocks
    everything is one nobody keeps enabled.
  - Do NOT delete a case because it fails. A failing case is the corpus working.

THEN: T5.2 the untrusted-screen boundary (screen content may FILL A PARAMETER of
an action the user already asked for; it may never SELECT the action, RETARGET it,
or ESCALATE its risk class — and do not rely on prompt-level delimiting alone,
because the control is a structural post-plan comparison, and code does not get
persuaded), T5.3 consequence classes (wait for Track 2's T2.2 first, then request
the ProposedAction change WITH the full variant-to-class mapping), T5.4 pre-flight
and semantic targeting, T5.5 the sandbox and concurrency gaps, T5.6 per-profile
agent memory, T5.7 router/desktop parity.

CONTEXT THAT MATTERS FOR T5.4: ExecutionGuard.checkWithContext had ZERO callers —
written and never invoked — and is now wired into ProcedureExecutor with 11 unit
tests. Wiring it surfaced three faults inside it, all the same shape: a Context
parameter nothing used, a package mismatch reported as WRONG_PERSON, and three
GuardContext fields nothing read. Read docs/UNWIRED.md §3 for why that shape
matters. Your C-3/C-4 work builds directly on it.

PR: title starts "[T5]". Not *Screen.kt, not MainActivity/MainViewModel, not
shared/**. No new path from model output to an OS operation that bypasses the
spine. Any approval floor is enforced in PolicyEngine, not at a call site. Update
the AGENTS_DESIGN.md / COMPUTER_USE.md coverage tables in the SAME commit — a
stale ✅ is worse than an honest ⬜. PR body answers: if the model's output were
fully attacker-controlled, what does this change let them do?
```

---

## Rules that apply to all five

These are not style preferences. Every one of them is here because it was
violated, and the violation reached `main` looking finished.

**1 — Everything is verified or it is marked unverified.** This project shipped
two crash-on-launch bugs precisely because CI could not run the app, and a whole
platform body rotted to non-compilation because no workflow built it. State what
you ran and what you did not.

**2 — Read the runner's output, not the wrapper's.** Two wrong diagnoses came
from reading a Gradle stack-trace tail while `Starting 0 tests` sat above it. And
when a check turns green, check the test *count* — a suite that runs zero tests
also passes.

**3 — Before you call a test a gate, find the workflow line that runs it.** Track
2's property tests — the ones `ENGINEERING.md` §B7 calls the highest-value tests
in the project — were written, merged, and executed by nothing. `invariants.yml`
compiled `shared:core` and stopped. 185 tests across three modules compiled on
every PR and ran on none. `grep` your module in `.github/workflows/*.yml`. If the
task that runs your tests is not there, your tests are documentation.

**4 — Write the state of the code, not the state of your intent.** A slice landed
with "that enum is **retired**" in `ARCHITECTURE.md` while the enum was still in
the tree, still wrong about three action classes. Deferring work to another track
is correct and expected. Recording deferred work as finished is not — the next
agent trusts the registry instead of reading the file. "Handed to Track 3, not
yet deleted" costs six words and is true.

**5 — A default that every real implementation overrides is not a default.** A
slice made `ModelProvider.complete()` collect `stream()` and wrote "stream() is
production-called on every inference" into the contract — while both providers
that actually run a model still overrode it. The claim held only for the no-model
stub. **When you write "X is now wired", name the call site, then grep for
anything that shadows it.**

**6 — Read the document the slice points you at, before designing the thing it is
about.** A slice whose brief said "messages carry stacked content blocks, not
just text, and designing for plain text now means a second migration later"
shipped `val text: String`. The pointer was one sentence long and in the steps.

**7 — If a document you were pointed at seems to be missing, grep for it before
writing that it does not exist.** A test header claimed `ENGINEERING.md` §B7 "is
not present in this snapshot" and substituted four invariants of its own. §B7 is
at line 534.
