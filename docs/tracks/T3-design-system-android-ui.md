# Track 3 — Design System & Android UI

Your complete brief. Read `AGENTS.md` and `docs/PARALLEL_RULES.md` before your
first commit.

---

## 1. Who you are

You own everything the user sees on Android, and the design system all four
bodies render through.

**You own:**

```
shared/ui/**                                  design tokens + components
apps/android/src/main/java/com/newax/aegis/*Screen.kt
apps/android/.../MainActivity.kt
apps/android/.../MainViewModel.kt
apps/android/.../ui/**
apps/android/src/main/res/**
docs/UI_DESIGN.md
```

**You never touch:** `agents/`, `engine/` (Track 5 owns those, inside your
module), `shared/core`, `shared/database`, `platform-impl/*`, build files.

**Your first task blocks four other tracks.** Do it first, alone, and merge it
fast.

---

## 2. Set up, and prove it works

```bash
export JAVA_HOME=/usr/lib/jvm/java-17-openjdk-amd64      # NOT 21
export ANDROID_HOME=$HOME/android-sdk
echo "sdk.dir=$ANDROID_HOME" > local.properties          # gitignored

./gradlew :shared:ui:jvmTest :apps:android:assembleDebug
```

`:shared:ui:jvmTest` runs `ContrastTest` — 84 accessibility assertions. It must
stay green on every commit you make.

---

## 3. Already done for you

Slices 1–3 are landed and **verified against a real compiler**:

- **`shared:ui`** — `NewaxTheme` with colours, type scale, spacing, shapes.
  Targets Android, JVM (serves both desktop bodies), and the two iOS targets.
- **Token adoption** — 189 duplicated colour declarations across 18 files now
  read from one source.
- **Accessibility primitives** — `reducedMotionEnabled()` expect/actual,
  semantics helpers, applied to the typing indicator and bubble width.

**The palette is contrast-verified. Do not change a brand colour without
recomputing.** Five values in the original palette failed WCAG AA, including a
2.00:1 colour marking *policy-blocked actions*. `ContrastTest` enforces 84 pairs
across both themes and will fail you — that is the point.

---

## Slice T3.0 — Three handovers from Track 2 ← **do these first, in one small PR**

Track 2 finished its half of three slices and each one ends inside your files.
None is large. All three unblock something, and all three get harder once T3.1
starts moving code around.

### T3.0a — Delete `private enum class Risk`

`MainActivity.kt:619`, used at 621–644.

There are three risk vocabularies in this repo and there should be two.
`RiskLevel` describes the **action**; `PolicyMode` describes the required
**gate**; `Risk {Routine, Sensitive, HighImpact}` is a third, local one — and it
is *wrong*: it buckets irreversible deletes with sends, under-classifies calendar
events, and over-classifies searches. A badge that disagrees with the engine
about how dangerous something is, is a safety-surface defect, not a cosmetic one.

**Steps:**

1. Read the concept registry in `ARCHITECTURE.md` Part 1.
2. Replace the local classification (lines 621–634) with `action.riskLevel`.
3. Derive the badge label and colours from `RiskLevel` (lines 642–644). Four
   levels now, not three — `LOW`/`MEDIUM`/`HIGH`/`CRITICAL`.
4. Tell Track 1 when it is gone, so they can add `enum class Risk` to the
   banned-symbol guard. That guard **cannot** be added before you delete it — it
   would fail on the code it exists to prevent.

**Verify:** `./gradlew :apps:android:assembleDebug :apps:android:testDebugUnitTest`

### T3.0b — Wire `conversationDao` so chat survives process death

Chat is `mutableStateListOf` in `MainViewModel` and dies with the process. Schema
v20 gives you three tables and a DAO with reactive `Flow` queries.

**Messages are stacked content blocks (`docs/UI_DESIGN.md` §7), not strings.**
Read §7's table of ten kinds before you design the rendering.

| Concept | Where it lives |
|---|---|
| Chat-list row | `conversations`, recent-first by `updatedAtMs` |
| Plain-text rendering — snippet, search, block-less surfaces | `messages.text` |
| The actual content of a rich message | `message_blocks`, ordered by `position` |

Three properties of that schema that will bite you if you miss them:

- **A plain-text turn stores no block rows at all.** "No blocks" reads as one
  implicit text block. Do not write a `TEXT` block for every plain message — the
  common case is meant to stay one row.
- **`type` is a string on purpose.** A block kind written by a newer build must
  round-trip through an older reader rather than being dropped. Render an
  unrecognised kind as its `content`; never discard it.
- **`deleteConversation` is the only correct delete path.** It removes blocks,
  then messages, then the row, in one transaction, in that order. Calling the
  pieces yourself in the wrong order orphans every block in the conversation.

**Verify:** the DAO round-trips are instrumented (`MigrationTest`). Your side is
`:apps:android:testDebugUnitTest` over the state holder.

### T3.0c — Render the stream, and read this before designing the stop button

`ModelProvider.complete()` is now the collected `stream()`, with **no provider
overriding it**, so switching `MainViewModel` to collect `stream()` directly buys
incremental rendering with no contract change.

**`ModelProvider.cancel()` is a documented no-op on both real providers.** Neither
LiteRT's `sendMessage()` nor kherud's `generate()` is interruptible. Cancelling
the collecting coroutine stops the UI updating; it does **not** stop the model
burning tokens and battery.

So: build the stop button on Flow cancellation, say plainly — in the UI and in
your PR — that generation is *abandoned*, not *aborted*, and **do not write
"streaming is cancellable" anywhere**. A real cancellation path needs a
thread-interrupt or session-abort in the native bindings, and that is Track 4's
to build.

While you are in `ChatBubble`: `UI_DESIGN.md` §7.3 records that the bubble roles
are inverted today — assistant renders light-on-near-black and user
dark-on-light, and the spec wants the opposite. Fix it here.

**Verify:** `./gradlew :apps:android:assembleDebug :apps:android:testDebugUnitTest`

**Done when:** all three land in one PR, before decomposition starts.

---

## Slice T3.1 — Decomposition (slice 6) ← **do this next, alone**

**Goal:** `MainActivity` (1403 lines) and `MainViewModel` (1207 lines) become
per-screen composables and testable state holders.

**Why it is first:** nearly every feature touches these two files. Until this
merges, no other track can safely enter `apps/android`, and every later slice of
yours would conflict with itself.

**Copy the pattern that already works:** `apps/desktop/src/main/kotlin/com/newax/aegis/desktop/ui/state/*.kt`.
Desktop does this correctly — plain-Kotlin, injectable, unit-tested state
holders with all decision logic outside Compose. Android does not. Read
`GoalsScreenState.kt` and its test before you start.

**Steps:**

1. **Read before writing.** `MainActivity` contains the app shell, the drawer,
   `ChatScreen`, `MemoryScreen`, `SettingsScreen`, `MeetingScreen`, the theme
   wrapper, and the chat components. Map it before cutting.
2. Extract **one screen at a time**, compiling after each. Not all at once.
3. For each screen: a `@Composable` that takes state and emits events, plus a
   plain-Kotlin state holder with the logic and a unit test.
4. `MainViewModel` splits along the same lines. Watch for the ~10 hardcoded
   regex fast-paths in `submit()` that bypass the model entirely — **preserve
   the behaviour, do not silently drop them**, and note them in your PR as
   something Track 5 should own later.
5. Keep `MainActivity` as a thin shell: `setContent { NewaxTheme { … } }` plus
   navigation.

**Verify after each extraction:**
```bash
./gradlew :apps:android:assembleDebug :apps:android:testDebugUnitTest
```

**Done when:** no file over ~400 lines, each state holder has a unit test, the
app behaves identically.

**Do not:**
- Change behaviour while restructuring. Two reasons for a regression is one too
  many.
- Touch `agents/` or `engine/` — Track 5's, even though they sit in your module.
- Leave a half-extracted screen across a merge. Ship whole screens.

---

## Slice T3.2 — String externalization (slice 4)

**Goal:** no user-facing string literal in Kotlin.

**Why early:** every later slice adds strings. Doing this after the component
library means doing it twice. It is also why RTL and the Urdu support the app
half-ships cannot be verified today.

**Steps:**

1. `apps/android/src/main/res/values/strings.xml` for Android screens.
2. For `shared/ui` components, pick a multiplatform strategy — Compose
   Resources or `moko-resources`. **Ask Track 1 to add the dependency.**
3. Convert screen by screen, compiling as you go.
4. Ask Track 1 to enable the hardcoded-string guard **after** you finish — it
   will fail on every screen before then. Tell them when you are done.

**Verify:** `assembleDebug`, plus a pseudolocale pass (`en-XA`) — text expands
~30% and reveals clipping.

**Do not** externalize log messages, exception text, or `testTag` values. Those
are not user-facing and translating them makes debugging worse.

---

## Slice T3.3 — Dark theme (slice 5)

**Goal:** unpin `NewaxTheme(darkTheme = false)` in `MainActivity`.

**Why it is pinned:** screens still read the light-only top-level aliases
(`private val BG = NewaxLightColors.bg`). Flipping the switch now yields a
half-dark UI.

**Steps:**

1. Migrate screens from the file-level aliases to `NewaxTheme.colors.*` at the
   call site. This is the second half of the token migration.
2. Note `NewaxTheme.colors` is `@Composable`-only. Logic outside composition that
   needs a colour is a signal the logic is in the wrong place — move it, do not
   reach for the light palette.
3. Unpin `darkTheme` last, once no aliases remain.
4. Delete the alias blocks.

**Verify:** `ContrastTest` already covers both palettes. Add screenshot tests in
both themes if the harness exists by then.

---

## Slice T3.4 — Component library (slice 10)

Build the ~60 components in `UI_DESIGN.md` §8.

**The rule that matters:** every component ships semantics, focus behaviour, and
a 44 dp minimum target **in its first commit**. Retrofitting accessibility is how
it never happens — and this repo had *zero* uses of `Modifier.semantics`,
`stateDescription`, `heading()`, or `liveRegion` before slice 3.

Use the helpers in `shared/ui/.../a11y/Semantics.kt`: `heading()`,
`statusSemantics()`, `liveRegionPolite()`, `liveRegionAssertive()`,
`describedAs()`, `minimumTouchTarget()`.

**Two accessibility rules people get backwards:**

- `contentDescription = null` is **correct** for decorative icons. A chevron
  inside a labelled row should stay silent. Naming everything makes screen
  readers worse.
- **State goes on the control, not the glyph.** Swapping an expand/collapse
  chevron conveys nothing; the row needs `stateDescription`.

**Verify:** per-component screenshot tests (light, dark, 200% font scale) plus
semantics assertions.

---

## Slice T3.5 — The route tree (slices 11–15)

`UI_DESIGN.md` §6 specifies **105 routes**, each with contents in sequence and
every control resolved to exactly one destination.

Order: chat shell (11) → authority surface (12) → Memory/Tasks/Capabilities (13)
→ Settings subtree (14) → onboarding (15).

**Onboarding is last** because routes 0.x link into 5.1.3.2, 5.1.3.3 and 5.2.1.1,
which must exist first.

**Dependencies to watch:**
- Routes 1.1, 1.6, 1.11, 1.12 need **Track 2's conversation persistence**.
- The streaming composer needs **Track 2's `stream()` wiring**.
- The approval surface needs **Track 5's typed action vocabulary**.

Ask, then build. Do not stub their side.

**A control with no destination is a spec bug, not a design choice.** If §6
leaves one ambiguous, fix the doc in the same PR — you own it.

---

## When you are blocked

- **Need data that does not exist:** Track 2. Conversation storage, streaming,
  and policy types are theirs.
- **Need the action vocabulary for approvals:** Track 5.
- **Need a dependency:** Track 1.
- **`ContrastTest` fails after a colour change:** it is right and you are wrong.
  Recompute against every text-bearing surface — `bg`, `surface`,
  `surfaceSelected`, `surfaceMuted` — not just the page background. Three real
  failures hid behind testing `bg` alone.

---

## Before every PR

- [ ] Title starts `[T3]`
- [ ] Only your files — **not `agents/` or `engine/`**
- [ ] `./gradlew :apps:android:assembleDebug :apps:android:testDebugUnitTest :shared:ui:jvmTest` passes
- [ ] New components have semantics, focus, and 44 dp targets
- [ ] Any `UI_DESIGN.md` change is in the same commit as the code
- [ ] PR body records what was verified and by which command
