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

**Status: LANDED (T3.0).** One PR shipped all three: a) `private enum class
Risk` deleted, the chat badge derives from `action.riskLevel` via
`riskBadgeStyle` (`apps/android/.../ui/risk/RiskBadge.kt`); b) chat survives
process death — `RoomChatHistoryStore` over `conversationDao`, restore on boot,
every turn persisted (plain-text turns store no block rows), delete only via
`deleteConversation`, with a Clear-chat action in the chat top bar; c)
`MainViewModel` collects `ModelProvider.stream()` directly into a streaming
bubble with a Stop button (Flow cancellation — generation is **abandoned, not
aborted**, stated in the thread) and `ChatBubble` roles fixed per
`docs/UI_DESIGN.md` §7.3. The banned-symbol guard for `enum class Risk` is
queued for Track 1. Gradle verification (assembleDebug / testDebugUnitTest)
runs on a machine with JDK 17 + the Android SDK.

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

**Status: LANDED (T3.1).** The decomposition shipped in one PR: `MainActivity`
slimmed 1517 → 425 lines (shell = theme + drawer + top bar + dispatch;
`StatusBadge`/`BiometricOverlay` stay shell-side); `MainViewModel` slimmed
1331 → 281 lines (observable state + lifecycle + chat-history seam + public
delegates — every screen's call surface unchanged); the four remaining
embedded screens moved to `ChatScreen.kt` / `MemoryScreen.kt` /
`SettingsScreen.kt` / `MeetingScreen.kt` (each self-contained with its own
token aliases + private `SectionLabel`, matching the 13 already-extracted
screens); the inference pipeline moved to `AssistantController.kt` (submit +
fast paths + streamed LLM + runAction + calendar + model lifecycle, writing
through the VM's state/seams — the regex fast-paths are preserved verbatim and
remain Track 5's future handover); and each screen got a plain-Kotlin state
holder in `ui/state/` (`ChatScreenState`, `MemoryScreenState`,
`SettingsScreenState`, `MeetingScreenState`) with JVM unit tests — the
chat-transcript merge that used to live inline in the VM init is now
`ChatScreenState.mergeTranscript` and tested. `AssistantController` (1145
lines) is the one file over the ~400 guideline — it is the brain, not a
screen; splitting it per action-class is the next slice.

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

**Status: FIRST PASS LANDED (T3.2a).** `apps/android/src/main/res/values/strings.xml`
created (the app had none) and the T3.0/T3.1 surface converted to it: the shell
(`MainActivity` — drawer labels, top-bar titles, status badge, clear-chat
/ biometric dialogs), the four extracted screens (`ChatScreen`, `MemoryScreen`,
`SettingsScreen`, `MeetingScreen` — labels, buttons, dialogs, placeholders,
content descriptions, a11y state descriptions), the risk badge (labels are now
`labelRes` in `RiskBadgeStyle`, pins `R.string.risk_*` in the JVM test), the
chat state holder (`ChatScreenState.suggestionChips` is now `List<Int>` of
resource ids — the screen resolves and submits the localized text), and the
chat pipeline's system/status messages (`MainViewModel` boot greeting +
authority notices via `getString`, `AssistantController`'s ~40 system messages
via an injected `StringResolver` — including the model-lifecycle status strings
and the fixed context filter, which now matches the localized
`chat_processing_background` prefix instead of the English literal).

**The content-vs-chrome boundary (deliberate, documented):** assistant replies
— deterministic fast-path output (commitments, files, drafts list, person
profiles, scan reports) and model output — are *content*, not chrome, and stay
in Kotlin; a content-i18n pass is out of T3.2's scope. Storage keys and wire
formats (`"personal"`, `"pain_points"`, ambient-mode values
`"Meeting"/"Lecture"`, the `"title :: epoch"` meeting format) stay English by
design — translating them would break stored state; only their display labels
resolve through resources (`categoryLabelRes` in MemoryScreen,
`ambientModeLabelRes` in SettingsScreen, with the holders' title-case/raw-key
fallbacks for unknown keys).

**Status: LANDED (T3.2b).** The second pass converted the remaining screens to
`strings.xml`: Drafts, Backup, People, Capabilities, PolicyHistory, Goals,
Nearby, Sync, AgentMemory, Agents, Skills, Updates, AppPermissions, the
`*Section.kt`/`*Holder.kt` settings helpers, and the dev-console tabs
(`ui/devconsole/**`). The file is now the single source for 750+ keys; a
static audit cross-checks every `R.string`/`R.plurals` reference in Kotlin
against the file (754 used ≡ 754 defined, zero duplicates — the duplicate
resource names AAPT would have rejected are gone). State chips and enum
labels resolve through `labelRes()` (`GoalState`, `PrivilegeLevel`,
`ModelState`, `ModelFormat`) so the label stays next to its enum;
counts/plurals use `<plurals>` (`goals_count`, `drafts_pending_count`,
`people_count`, `updates_banner_count`); non-composable callbacks resolve via
`context.getString`.

**Still-literal by the documented boundary (deliberate):** chat commands
(`"approve draft N"`), storage keys and wire formats (`"personal"`,
`"pain_points"`, `"work"`, `"policy"`, `"MEMORY_RULE"`), deterministic
fast-path and model output, runtime relative-date formatting (`"2d ago"`,
`"just now"`), audit-CSV internals and exception text, animation `label`
params, and the accessibility service's matchers for *other* apps' UI text
(`"Save"`, `"Post"`, `"Send"` — matching targets, not our copy). One
follow-up recorded, not fixed: the notification strings in
`SyncForegroundService` / `ScreenCaptureService` / `VoiceRecognitionService`
are user-facing but live in services, not screens — a small `getString` pass
in a later slice.

**Track 1: the hardcoded-string guard can go ON now.** The screens are
converted; it will not fail on them. Keep the content boundary in mind when
wiring it — flag literals, don't auto-reject the documented data/wire/content
exceptions above.

**shared/ui:** zero user-facing string literals exist in `commonMain` today, so
there is nothing to externalize yet. The multiplatform strategy decision
(Compose Resources vs moko-resources) is deferred to the first component that
adds strings (T3.4); **Track 1: do not add the dependency yet** — it will be
requested then, in one place.

**Known follow-ups recorded here, not fixed (behaviour-preserving slice):**
- `SettingsScreenState.isModelReady` still string-matches `"ready"` against the
  model status. It works for the default locale (`model_ready` keeps the word);
  a typed model-state, not a string contains-check, is the real fix — later slice.
- Plurals (`%1$d memories` / `%1$d meetings`) use simple format strings, not
  `plurals` resources — refinement for the locale pass.
- Pseudolocale (`en-XA`) pass and screenshot checks not run — no toolchain in
  the sandbox; they are device/CI work (see the PR verification notes).

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

**Status: LANDED (T3.3).** The light-only top-level aliases are gone — a
repo-wide sweep shows zero `NewaxLightColors` references, and every screen
reads `NewaxTheme.colors.*` at the call site. `NewaxTheme {}` in `MainActivity`
now follows the system setting (no pinned `darkTheme = false`), so the dark
palette applies everywhere. `ContrastTest` already covers both palettes (84
pairs). The in-app override behind the Settings theme route
(UI_DESIGN.md 5.1.4) remains future work; a later slice. Screenshot tests in
both themes are device/CI work (no toolchain in the sandbox).

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

**Status: FIRST SLICE LANDED (T3.4a).** The cross-cutting primitives every
screen hand-rolled for itself are now shared components in
`shared/ui/.../ui/components/` — 17 composables in four files
(`Feedback.kt` — `EmptyState`/`ErrorState`/`LoadingState`/
`ConfirmDialog`/`TypeToConfirmDialog`; `Lists.kt` — `SectionHeader`/
`ChevronRow`/`StatusChip`/`InfoTag`/`TimelineItem`/`SearchBar`;
`Chat.kt` — `ChatBubble`/`TypingIndicator`/`StreamingText`; `Blocks.kt` —
`CopyButton`/`CodeBlock`/`StepBlock`). Every component ships its
accessibility contract in the same commit: `heading()` on section headers,
`stateDescription` on expand/collapse controls (the state is on the row, the
chevron stays silent), `statusSemantics` on colour-coded chips (never colour
alone), polite live regions for streaming/progress and assertive for failures,
44 dp touch floors on interactive elements, and `contentDescription = null`
on decorative icons. Components are **string-free by design**: every label,
placeholder, and state description is a parameter the caller localizes — the
Compose Resources vs moko-resources decision stays deferred, and `commonMain`
still has zero user-facing literals. Icons are restricted to the
`material-icons-core` set (`Search`, `Close`, `KeyboardArrowRight`,
`Warning`) so the module compiles without the extended set.

**Wired into the Android app in the same change (R6):** `ChatScreen`
(chat bubble → §7.3 roles, typing indicator, streaming text); `UpdatesScreen`
(section headers, empty states, risk chips); `GoalsScreen` (state chips,
tags, empty state); `CapabilitiesScreen` (error/empty states, model-state
chip, tags); `PeopleScreen` (list empty state, two expandable headers →
`ChevronRow`); and the destructive confirmations — `MainActivity`
(clear-chat), `MemoryScreen` (clear-memory, plus its `SectionLabel`),
`DraftsScreen` (approve/reject-all) — now go through `ConfirmDialog`
(destructive variant renders the confirm in `error`).

**Not yet wired — library-only until their T3.5 route lands** (named here so
nothing looks finished that is not): `LoadingState` (route 1.2 streaming
surfaces), `TypeToConfirmDialog` (backup restore, route 5.4 — the most
destructive action in the app gets the type-to-confirm gate), `TimelineItem`
(2.1 memory timeline), `CopyButton` / `CodeBlock` / `StepBlock` (1.3 artifact
panel, 1.9 step blocks). `SearchBar` has since landed its first route — the
1.11 conversation search in T3.5a; the 3.8 skills search is still pending.

**Verify gate, needs Track 1:** the §3.6 per-component screenshot/semantics
pass (light, dark, 200% font scale) needs a UI-test harness — ask Track 1 to
add a Compose UI-test dependency to `shared/ui` `commonTest`. What landed
here without it: `ComponentLogicTest` (the type-to-confirm gate, pure
Kotlin). The palette-arithmetic `ContrastTest` still runs on
`:shared:ui:jvmTest`.

**Status: SECOND SLICE LANDED (T3.4b).** The Blocks and Chat families are
complete: `ApprovalCard` (the approval surface — assertive live region,
Approve never default focus), `BlockedCard`, `FailureCard`, `CopyableTextBox`,
`ImageBlock` (caller-supplied image slot — `commonMain` stays loader-free),
`ImageGenBlock` (+ `ImageGenPhase`), `DocumentsContainer` (+ `DocumentRow`),
`McqCard` (+ `mcqOptions` — custom option always last), `ThoughtContainer`,
`ArtifactChip` (+ `artifactAccessibleName`), `ArtifactPanel`, and the chat
chrome — `Composer` (busy spinner replaces the send control, described),
`SuggestionGrid`, `ConversationRow`, `AttachmentChip`, `ModelStatusLine`,
`DegradedBanner`. **Wired in the same change (R6):** `ChatScreen.kt` now uses
shared `Composer` / `SuggestionGrid` / `ApprovalCard`; the hand-rolled
`ChatComposer`, suggestion-chip grid, and `ActionProposalCard` are deleted
(approval still derives its risk chip from the canonical `RiskLevel` via
`riskBadgeStyle`). Icons stay within `material-icons-core` (Lock, Warning,
Refresh, Star, KeyboardArrowDown, Close, Send, Mic-via-caller-slot).
`BlocksLogicTest` extends the pure-logic coverage (`mcqOptions`,
`clampProgress`, `artifactAccessibleName`, `documentRowAccessibleName`).
Library-only until their T3.5 routes land (named so nothing looks finished
that is not): `ConversationRow` — landed its 1.1/1.11 route in T3.5a —
`AttachmentChip` (1.2),
`ModelStatusLine` (model sheet), `DegradedBanner` (1.4), `ImageBlock` (1.7),
`ImageGenBlock`, `DocumentsContainer`, `McqCard`, `ThoughtContainer` (1.9),
`ArtifactChip` / `ArtifactPanel` (1.3),`BlockedCard` / `FailureCard` (1.9), `CopyableTextBox` (1.3). Shell, Lists & data, Settings, Pairing, Voice, and the Overlays `Sheet` / `BiometricGate` remain for T3.4c/T3.5.

**Status: LANDED (T3.4c).** The remaining §8 families shipped in one change:
**Shell** (`NavDrawer`, `Sidebar`, `NavRail`, `TopBar`, `MenuBar`,
`CommandPalette`, `RouteScaffold` over a shared `NavItem` — badges are text
on an accent disc, never colour alone), **Lists & data** (`AmberTaskCard`,
`AgentCard`, `SkillRow`, `PersonRow`, `SwipeActionRow` + its overflow
equivalent `OverflowActions`, `GraphCanvas` + `GraphListFallback`),
**Settings** (`SettingsGroup`, `SettingsRow`, `ChoiceChips`, `TagEditor`,
`EditValueSheet`, `ProfileHeader`, `DeviceCard`, `PairedDeviceRow`),
**Pairing** (`PairRoleCard`, `PairQrCard` — caller-supplied QR slot like
`ImageBlock` — `SasConfirmCard`, `NearbyDeviceRow`, `PairSuccessCard`),
**Voice** (`VoiceEnrollSheet`, `ListeningIndicator`, `TranscriptPreview`),
and the Overlays `Sheet` + `BiometricGate`. **Wired in the same change
(R6):** `TopBar` (MainActivity shell top bar + the people-detail back
header), `PersonRow` (PeopleScreen list), `AgentCard` (AgentsScreen list),
`SkillRow` (SkillsScreen list), `SettingsRow`/`SettingsGroup` (SettingsScreen
permissions group + people/sync/backup rows), `ChoiceChips` (ambient-mode
picker). Every component ships its a11y contract in the same commit
(44 dp floors, named controls, `stateDescription`/`statusSemantics` where
colour would otherwise stand alone, live regions on voice/gate status,
reduced-motion static fallback in `ListeningIndicator`). Pure-logic coverage:
`DataRowsLogicTest`, `GraphLogicTest` (deterministic circular layout),
`SettingsLogicTest` (`tagsAfterAdd`), `PairingLogicTest` (`sasGrouped`/
`sasCodesMatch`), `VoiceLogicTest` (`clampAmplitude`). **Library-only until
their T3.5 routes land** (named so nothing looks finished that is not): the
remaining Shell family, `SwipeActionRow`/`OverflowActions`, `AmberTaskCard`,
`GraphCanvas`/`GraphListFallback`, `TagEditor`, `EditValueSheet`,
`ProfileHeader`/`DeviceCard`/`PairedDeviceRow`, the pairing and voice
families, `BiometricGate` — the platform `BiometricPrompt` stays in the
callers (MainActivity + AutomationSettingsSection), which is exactly the
`BiometricGate` contract. Icons stay within `material-icons-core` (Menu,
ArrowBack, MoreVert, Delete, Check, CheckCircle, Edit, Add, Lock, Star,
List, Search, KeyboardArrowRight). Verify gate unchanged:
`./gradlew :apps:android:assembleDebug :apps:android:testDebugUnitTest :shared:ui:jvmTest`.

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

**Status: FIRST SLICE LANDED (T3.5a).** The conversation shell — routes 1.1
(conversation list), 1.6 (rename/delete per row) and 1.11 (search) — shipped in
one change: `ChatHistoryStore`/`RoomChatHistoryStore` became conversation-
scoped (load/append by id, `observeConversations` flow, rename, delete through
the single transactional DAO path, and a client-side transcript scan for
search — no FTS table exists, so Track 2 can add a `LIKE` query later without
changing the contract); `MainViewModel` gained the conversation context
(`conversations` flow + `activeConversationId`, boot restore now opens the
most-recent thread, `openConversation`/`newChat`/`deleteConversation`/
`renameConversation`/`searchChats`, and the clear-chat action is now the
delete-active-conversation path); the new `ConversationsScreen` wires the
previously library-only `ConversationRow` and `SearchBar` (their routes land
here) with a debounced search, the rename `EditValueSheet`, the destructive
`ConfirmDialog`, and a New-chat empty state; and a `ConversationListState`
plain-Kotlin holder (relative time labels, rename validation against the
store's single title cap) carries the decisions, unit-tested alongside
extended `ChatHistoryStoreTest` coverage (conversation isolation, rename,
delete-one-keeps-others, search matching/snippet, most-recent restore).
Out of scope for this slice, recorded for the next: the 1.3/1.4/1.5/1.7–1.10
chat overlays, 1.12 export, and the drawer-becomes-the-list rework of the
compact IA.

**Status: SECOND SLICE LANDED (T3.5b).** The chat overlays with live data
shipped in one change. **1.4 model sheet** — current model + honest state
chip, SHA-256 identity, Import / Reload / Unload (Unload returns to basic
mode but keeps the imported file on disk; Reload brings it back; the status
line says exactly that), and "All model settings" → Settings. **1.9 step
detail** — the pending action's name, its canonical `RiskLevel` chip (never a
re-derived vocabulary), the required gate and the policy rule read from the
one `PolicyHolder` engine (deny / override / default), plus "See in history"
→ Policy History and "Change this rule" → Settings. **1.12 export** —
Markdown / Text / JSON through a new pure `ChatExportState` holder
(renderers, JSON escaping per R12, and file-name sanitization unit-tested in
`ChatExportStateTest`), written via a SAF `CreateDocument` launcher, with
polite success / assertive failure live regions and an honest "nothing to
export" for an empty transcript. **Thread chrome (1.2)** — `DegradedBanner`
in basic mode (its action opens the model sheet), `ModelStatusLine` beneath
the composer sharing one `modelStateChip` definition with the sheet, the
thread top bar's overflow menu (Export / Delete conversation — replaces the
lone delete button), and the drawer's model-status footer now opens the sheet
with a real 44 dp target. The shared `ApprovalCard` gained an optional,
string-free "Details" link that opens 1.9 before the user decides.
Previously library-only components wired here: `ModelStatusLine` (1.4 + 1.2),
`DegradedBanner` (1.2), `ChoiceChips` (1.12 format picker), `StatusChip`
(1.9 risk chip). Still library-only, recorded for the next slice: 1.3
artifact panel, 1.5 attachment sheet, 1.7/1.8 image & document viewers
(no image/document data flows through the message model yet), 1.10 voice
capture (the mic already inserts into the composer; a capture sheet needs the
audio pipeline), and the drawer-becomes-the-list rework of the compact IA.
Verify gate unchanged: `./gradlew :apps:android:assembleDebug
:apps:android:testDebugUnitTest :shared:ui:jvmTest`.

**Status: THIRD SLICE LANDED (T3.5c).** Route 1.10 **voice capture** shipped
in one change — the last chat route that is not blocked on content data flows
(1.3/1.5/1.7/1.8 need artifact/attachment/image data in the message model,
which is Track 2's to publish). The composer's mic now opens a capture sheet
instead of the one-shot system recognizer, because the live level meter needs
`onRmsChanged` and the running transcript needs `onPartialResults` — neither
exists on the `RecognizerIntent` activity. The recognizer is a platform seam
(`VoiceCaptureSession`, `voice/` — owns create/start/cancel/destroy, maps
recognizer error codes to strings once, and forces `EXTRA_PREFER_OFFLINE` so
the recognizer never silently falls back to a network service in a product
that refuses the INTERNET permission); the sheet's phases live in a pure
`VoiceCaptureState` holder (`ui/state/`), unit-tested in
`VoiceCaptureStateTest` (partial/final rules, amplitude gating, error phase,
Stop's transcript pick, late-event guards after Stop/Cancel). Stop inserts
into the composer — the draft text was lifted to `MainViewModel.composerText`
so a shell-level sheet can write it — and returns to 1.2; Cancel discards;
Stop with nothing heard shows the nothing-recognized error instead of closing
silently. RECORD_AUDIO (a runtime permission on 26+) is requested on first
capture; denial opens the sheet in the permission error phase. Previously
library-only components wired here: `ListeningIndicator` + `TranscriptPreview`
(their 1.10 route lands; the meter is decorative, the transcript is the
accessible representation, both per §6.3's a11y note). The spec's "Voice
settings → 5.1.3" control is **deferred, not dropped**: 5.1.3 does not exist
as its own route until the Settings subtree slice, and a dead control is
worse than none. Still library-only / pending, recorded for the next slice:
1.3 artifact panel, 1.5 attachment sheet, 1.7/1.8 image & document viewers
(blocked on content data), the drawer-becomes-the-list rework of the compact
IA, and the authority-surface slice 12 remainder. Verify gate unchanged:
`./gradlew :apps:android:assembleDebug :apps:android:testDebugUnitTest
:shared:ui:jvmTest`.

**Status: FOURTH SLICE LANDED (T3.5d).** The compact-IA rework shipped in
one change — the last recorded unblocked item. The drawer is now route 1.1
compact: brand header ("Newax Aegis"), New chat, the search row (→ the full
1.1 route / 1.11), the conversation list with the same `ConversationRow`s and
relative labels as the full route (one tested `ConversationListState`), then
the four spec sections + the model footer. Navigation is grouped per the
route tree: **Memory** and **Tasks** get section homes (`SectionHomes.kt` —
`MemoryHomeScreen` 2.x: Memory, People, Drafts, Meeting, Agent Memory;
`TasksHomeScreen` 3.x: Goals, Agents, Skills; rows are the shared
`SettingsRow` — one focus stop, 44 dp, heading section titles; the draft and
update badges moved onto the Memory/Settings section rows), while
**Capabilities** (4.1) and **Settings** (5) link straight to their landing
screens. Nothing is orphaned (R13): the settings sub-routes that left the
drawer — Policy History (5.3.1.3), Nearby (5.4.2), Updates (5.6.2) — gained
rows inside the Settings page (`Safety & Privacy` / `System` sections,
onNavigateTo* callbacks). `nav_brand` now reads "Newax Aegis" (R14 — the
brand header was the one user-visible surface still saying "Newax").
Navigation stays hamburger-driven (no back stack yet — the deterministic back restructure is part of the route-tree slices still open). Verify gate
unchanged: `./gradlew :apps:android:assembleDebug
:apps:android:testDebugUnitTest :shared:ui:jvmTest`.

**Status: FIFTH SLICE LANDED (T3.5e).** The remaining buildable route-tree
gaps shipped in one change, each backed by real data — nothing dead (R3):
- **2.1/2.2 memory search** — the shared `SearchBar` lands its 2.1 route;
  ranked hits come from the encrypted store's TF-IDF (`EncryptedMemory.relevant`),
  mapped back to the owning category by a new pure `MemorySearchState`
  (unit-tested: category lookup, longest-word highlight range), tapping a hit
  opens that category's editor (2.3), and the match is emphasized weight+
  colour, never colour alone (SC 1.4.1).
- **4.3 Apps index** — new `AppsIndexScreen` (search over name + package via
  the tested `AppsIndexState`, Rebuild index → the real `AppScanner.scan`
  into the app registry, row → `vm.submit("open <name>")` — the registered
  `open_app` intent, which is FLOW C: the typed action passes through the
  authority spine before the accessibility service launches it, so there is
  exactly one launch sink). Reached from a new Apps row on Capabilities.
- **4.2 Capability detail** — each capability card now expands (state on the
  control, SC 4.1.2): remedy row for MISSING_PERMISSION → Permissions screen,
  MISSING_CREDENTIAL → Settings, Retry re-reads the registry snapshot.
- **Slice 12 — the inline step block** (spec §7.2): the previously
  library-only `StepBlock` lands in the thread as the live agent-run status.
  `AssistantController` now publishes the active agent-session id
  (`activeAgentSessionId`, set at session start, cleared on every terminal
  path in the job's `finally`); the block renders that session's real
  `AgentStream` events (STATUS/ARTIFACT/ERROR via the tested
  `StepStatusState`), is live while the run goes, and collapses to its final
  state when the session clears — "collapsed when successful, expandable".
- **AppPermissionScreen dark-theme tokens** — a T3.3 miss fixed: the
  pre-theme hardcoded whites are gone; the screen now follows
  `NewaxTheme.colors` like every other surface.
28 new strings (861/861 balanced); the three new pure holders are
unit-tested (`MemorySearchStateTest`, `AppsIndexStateTest`,
`StepStatusStateTest`). Verify gate unchanged: `./gradlew
:apps:android:assembleDebug :apps:android:testDebugUnitTest
:shared:ui:jvmTest`.

**Route-tree closure (slices 12–15) — what is done and what cannot be built yet.**
With T3.5e, every route in the tree that has real data to render is rendered.
The remaining routes are genuinely blocked on data that is not Track 3's to
invent, and wiring them would be dead controls (R3):
- **1.3 artifact panel, 1.5 attachment sheet, 1.7/1.8 viewers** — the message
  model is plain text and the pipeline emits text; there is no artifact,
  attachment or image content to view. Needs Track 2's content-carrying
  `ChatMessage` blocks *and* a producer/consumer of rich content.
- **1.0 profile switcher, 1.13 notification digest, 5.1.1 Profile, 5.1.2
  Devices, 5.7 Profiles, onboarding 0.x** — tenancy (Person identity,
  Work/Personal profiles, recovery kit, per-profile digest) is Track T's
  design; the UI cannot fake a profile that does not exist.
- **5.1.3.2 wake word, 5.1.3.3 voice authentication** — need the voice-auth
  pipeline (enrollment, key derivation) that does not exist yet.
- **2.8 Connections graph canvas** — the node list fallback renders (the
  knowledge-graph nodes on the Memory screen, which is the TalkBack/VoiceOver
  default per the route's own a11y requirement); an interactive graph canvas
  is deferred until the graph data model is stable.
- **T3.4's screenshot/semantics verify gate** — needs Track 1's Compose
  UI-test harness; all of T3.0→T3.5e is static-read-back-verified only.

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
