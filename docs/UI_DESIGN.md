# Newax Aegis — UI/UX Design Specification

**One design, four bodies** — Android, iOS, Windows, macOS — built from one
shared Compose Multiplatform UI. This document is the spec an implementation PR
builds against. It is a plan, not code.

It answers four questions, in order:

1. **What happens** — §2, the workflow tree: the sequenced flows a user moves
   through, from first launch to a completed action.
2. **What it must guarantee** — §3, accessibility, as normative WCAG 2.2 AA
   requirements rather than a polish pass.
3. **What it looks like** — §4–§5, corrected design tokens and the adaptive
   layout across the three window-size classes.
4. **Where everything is** — §6, the route tree: every page, its contents in
   sequence, and every control resolved to exactly one destination.

§7–§12 cover content blocks, components, platform seams, delivery order,
coverage matrices, and open decisions.

---

## Contents

| § | Section |
|---|---|
| 0 | [References — what ChatGPT and Claude each contribute](#0--references) |
| 1 | [Goals, principles & hard constraints](#1--goals-principles--hard-constraints) |
| 2 | [The workflow tree](#2--the-workflow-tree) |
| 3 | [Accessibility specification (WCAG 2.2 AA)](#3--accessibility-specification-wcag-22-aa) |
| 4 | [Design language — tokens, type, spacing, motion](#4--design-language) |
| 5 | [Adaptive information architecture across four bodies](#5--adaptive-information-architecture) |
| 6 | [The route tree](#6--the-route-tree) |
| 7 | [Content blocks & the artifact panel](#7--content-blocks--the-artifact-panel) |
| 8 | [Shared component library](#8--shared-component-library) |
| 9 | [Platform seams](#9--platform-seams) |
| 10 | [Delivery sequence](#10--delivery-sequence) |
| 11 | [Coverage matrices](#11--coverage-matrices) |
| 12 | [Open decisions & risks](#12--open-decisions--risks) |

---

## 0 · References

Newax Aegis borrows deliberately from two products. Naming what comes from
where keeps future changes coherent instead of drifting toward whichever app
was opened most recently.

### From ChatGPT — the shell

- Side-menu app shell (drawer); **chat-first**, no bottom nav, no tab chrome.
- Searchable conversation history with rename / delete / export per row.
- A welcome state with first-message suggestion buttons.
- A minimal composer: attach · text · mic ⇄ send/stop. Nothing else.
- A model chip, and a settings IA of category → section → row.

### From Claude — the substance

- **Artifact panel.** Long or structured output leaves the transcript and lives
  in a persistent, re-openable panel. Newax Aegis already produces exactly this
  kind of output — CSV audit exports, extracted document text, generated
  reports — and currently has nowhere to put it but a very long bubble.
- **Legible tool use.** Every action is a discrete, named, collapsible step
  showing what was proposed, what it touches, and what came back. This maps 1:1
  onto the existing `ProposedAction` → `AuthorityManager` → `AgentStream` →
  execution-audit spine. An approval architecture is only as trustworthy as it
  is inspectable.
- **Extended thinking.** Reasoning streams inside a collapsed container;
  collapse state persists; it never steals focus.
- **Plain-language model choice.** Describe a model by what the user gets
  ("faster, shorter answers" / "slower, understands images"), not by codename.
  This is a real decision here: Gemma 3 1B INT4 versus Gemma 3n E2B on an 8 GB
  Galaxy S21 is a genuine speed/quality trade.
- **Source attribution.** An answer names the memory entries, files, or screen
  reads that produced it. With no external ground truth to appeal to,
  attribution *is* the trust mechanism in an offline assistant.
- **Typographic restraint.** Hierarchy from type and spacing rather than
  borders and fills; reading-grade line-height for long answers.

### Deliberately not borrowed

Accounts and billing, cloud sync UI, sharing and publishing, plugin
marketplaces, telemetry-driven personalization. Newax Aegis is offline-first
and ships with no Internet permission on Android. None of that has a place
here, and this line exists so it cannot creep back in.

---

## 1 · Goals, principles & hard constraints

### Constraints

- **One design on all four bodies.** A single shared Compose Multiplatform UI
  in `commonMain`; Android, iOS, Windows, and macOS render the same routes,
  adapted by window-size class (§5).
- **Platform-free shared code.** `expect`/`actual` seams only where the OS
  demands them (§9).
- **R13 — no headless capability.** Every capability shipped today keeps a
  reachable, usable route with real loading, empty, error, and approval states.
  §11 carries the audit.
- **R14 — branding.** The product name is exactly **Newax Aegis** in every
  title, label, and empty state. Never a bare "Aegis".
- **R15 — frozen identifiers.** `com.newax.aegis.*` and `~/.aegis/` are
  compatibility surfaces and are never renamed, regardless of what the UI says.

### Principles

1. **Chat is the home.** Every other surface is a route pushed from the side
   menu and returned from. There is no tab bar.
2. **PLAN is never EXECUTE.** The model proposes typed actions; only an
   explicit approval executes one. This is an architectural invariant
   (AGENTS.md §0), and §2.4 is its visual form.
3. **One owner per setting.** A setting is editable on exactly one route.
   Everything else showing that value is a read-only mirror that navigates to
   the owner.
4. **No dead ends.** Every control resolves to a destination; every error names
   a remedy and the route that provides it.
5. **Progressive disclosure.** Onboarding never blocks the first message.
   Deferred setup resurfaces in context, at the moment it is needed.
6. **Accessible by construction.** Accessibility is a property of each
   component from its first commit, not a pass at the end. §3 is normative.

---

## 2 · The workflow tree

Six flows in dependency order. §2 says what happens; §6 says where you are.

### 2.1 · Entry — three cold-start states

```
Launch
├── First ever run ──────────────▶ FLOW A · Onboarding (§2.2 → routes 0.x)
├── Returning, model ready ──────▶ FLOW B · Thread (1.2), focus in composer
└── Returning, degraded ─────────▶ FLOW B + a status banner
     ├─ no model imported        → "Basic mode — deterministic commands only"
     ├─ model failed to load     → red dot + Tap to retry ──▶ ⊞1.4 Model sheet
     ├─ Screen Access revoked    → banner; action controls disabled, with reason
     └─ storage/decrypt failure  → ⊞9.2 blocking error, recovery paths only
```

**Degraded is never a dead end.** Each degraded state names the capability that
is lost, in the user's terms, and offers exactly one route to fix it. The
deterministic command engine keeps working with no model at all — that is a
supported mode, not a failure.

### 2.2 · FLOW A — First run

```
0.1 Welcome ─▶ 0.2 Identity ─▶ 0.3 Brain ─▶ 0.4 Reach ─▶ 0.5 Voice ─▶ 1.2 Thread
                  [Skip]         [Skip]      [Not now]     [Skip]
                     └──────── every Skip advances; none returns to the start
```

The governing rule: **onboarding never blocks the first message.** After step 1
the app is usable. Steps 2–5 are individually deferrable and re-offered in
context — asking "what's on my screen?" without Screen Access shows an inline
permission block *in the thread*, not a modal. Progress is resumable; the
sequence never restarts.

### 2.3 · FLOW B — The daily loop

```
Composer input ─ text │ voice │ suggestion chip │ attachment
        ▼
   Classify
    ├── Conversation ─────▶ stream answer ─▶ [blocks §7] ─▶ idle
    ├── Ambiguous ────────▶ MCQ card (2–4 options + Custom…) ─▶ re-classify
    ├── Memory read/write ▶ apply ─▶ inline confirmation ─▶ idle
    └── Action ───────────▶ FLOW C (§2.4)
```

Composer states, each visually **and** semantically distinct:

| State | Composer | Announced as |
|---|---|---|
| `idle` | field active, send enabled | — |
| `thinking` | field active, thought container opens | "Thinking" (polite) |
| `streaming` | Stop replaces Send | streamed text (polite) |
| `awaiting approval` | disabled **with a stated reason** | "Waiting for your approval" (assertive) |
| `executing` | disabled, step block live | step status (polite) |

A disabled composer always says why. It is never silently inert.

### 2.4 · FLOW C — Authority

The approval card moves **into** the thread, at the point the action was
proposed. This is the app's defining interaction and it gets Claude-grade
legibility.

```
Model proposes ProposedAction (typed — never free text)
        ▼
PolicyEngine classifies ─▶ risk × action class
  ├── AUTO ─────────────────▶ execute ─▶ step block, collapsed, "done"
  ├── CONFIGURABLE ─────────▶ execute if permitted, else ↓
  ├── APPROVAL ─────────────▶ inline Approval block
  │      ├─ Approve ─▶ execute
  │      ├─ Reject ──▶ recorded; thread continues; the model is told why
  │      └─ Why? ────▶ expands: matched rule · what it touches ─▶ →5.3.1.1
  ├── STRONG_CONFIRMATION ──▶ Approval block + ⊞9.4 biometric
  │      └─ fail / cancel ─▶ hard stop, audited
  └── HARD DENY ────────────▶ blocked block (icon + the word "Blocked" +
                              colour — never colour alone) ─▶ →5.3.1
        ▼
Execute — capability ladder
  API → deep link → Intent → learned procedure → accessibility nodes →
  screen grounding → vision fallback
  ├── success ─▶ result step block ─▶ audit entry ─▶ Undo (where reversible,
  │              time-boxed)
  └── failure ─▶ named failure + which rung failed
                 ─▶ Retry │ Try another way │ Stop
        ▼
`then` plans: numbered steps, each re-entering this flow independently.
```

**Invariants, stated so the UI cannot quietly violate them:**

- PLAN is never EXECUTE.
- Approving step *n* never approves step *n+1*. A plan is never one approval.
- Displayed action text is generated from the typed `ProposedAction`, never
  from model prose.
- Every terminal state — approved, rejected, blocked, failed — writes an audit
  entry reachable from 5.3.1.3.

### 2.5 · FLOW D — Recovery

| Symptom | What the user sees | Remedy | Owning route |
|---|---|---|---|
| No model imported | Banner: "Basic mode — deterministic commands only" | Import a model | `5.2.1.1` |
| Model failed to load | Red dot on the model status line | Retry, or import another | `5.2.1` |
| Permission missing / revoked | Banner + disabled controls with reason | Grant it | `5.6.1` |
| Policy blocked the action | Blocked block, icon + "Blocked" + why | Change the rule | `5.3.1.1` |
| Action failed mid-plan | Failure block naming the failed rung | Retry / Try another way / Stop | inline |
| Peer unreachable | Peer row shows "Never synced" | Check transport, re-pair | `5.4.1.2` |
| Backup or restore failed | Named reason, not a generic error | Retry with the reason addressed | `5.5.1.2` |
| Storage / decrypt failure | Blocking error route | Restore from backup | `9.2` → `5.5.1.2` |

Every remedy lands on the single route that owns that setting. Never on a
duplicate control.

### 2.6 · FLOW E — Configure

```
Status mirror ─ composer model line · drawer sync badge · degraded banner
        └─▶ navigates to ─▶ the owning route ─▶ back returns to chat
```

Mirrors are read-only by definition. If a surface can change a value, it is the
owner and it is the only owner.

### 2.7 · FLOW F — Multi-device

```
Pair — step 0 role · step 1 code exchange · step 2 SAS confirm · step 3 paired
   ▼
Choose what syncs (5.4.1.1)
   ▼
Act on a peer  ─▶ re-enters FLOW C on that device
   ▼
Observe in sync status (5.4.1)
```

Biometric-gated at the pairing step. A SAS mismatch is a hard stop with a
warning state — never a soft retry, because SAS is the only interception
defence in the protocol.

---

## 3 · Accessibility specification (WCAG 2.2 AA)

Normative. Each requirement carries its success criterion so it can be checked
rather than admired.

### 3.1 · Perceivable

- **Contrast** — every text pair ≥ 4.5:1; every UI component boundary and
  meaningful graphic ≥ 3:1 (SC 1.4.3, 1.4.11). The §4 tables carry measured
  ratios; values that fail are not shipped.
- **Never colour alone** (SC 1.4.1) — status dots pair with a text label; risk
  chips carry an icon *and* a word; a blocked action shows an icon and the word
  "Blocked"; password strength shows a label, not just a coloured bar.
- **Text scaling to 200%** (SC 1.4.4) — every size in `sp`; no fixed-height
  text containers; message bubbles use `fillMaxWidth(fraction)`, never
  `widthIn(max = …dp)`, which clips at large font scales.
- **Reflow** (SC 1.4.10) — usable at a 320 dp equivalent width with no
  two-dimensional scrolling. Wide content (tables, code, diagrams) scrolls
  inside its own container; the page never scrolls horizontally.

### 3.2 · Operable

- **Targets ≥ 44 × 44 dp.** SC 2.5.8 requires 24; 44 is the platform floor on
  both mobile OSes and the value we hold to.
- **Full keyboard operability with a visible focus indicator** (SC 2.1.1,
  2.4.7). Mandatory on Windows and macOS. Focus ring: 2 dp, `accent`, 2 dp
  offset — 4.9:1 against the background.
- **No focus trap** (SC 2.1.2). Sheets and dialogs return focus to the control
  that opened them.
- **Motion opt-out** (SC 2.3.3) — honour `ANIMATOR_DURATION_SCALE` on Android
  and `isReduceMotionEnabled` on iOS. Under reduced motion the typing indicator
  is a static "Thinking…", bubble transitions become instant, and shimmer
  becomes a static placeholder.
- **Streaming never moves focus.** Text arrives in a polite live region; the
  caret stays where the user put it.
- **Dragging has an equivalent** (SC 2.5.7) — every swipe action also exists in
  an overflow menu.

### 3.3 · Understandable

- **Language declared per locale** (SC 3.1.1), including Urdu — the app ships
  Urdu TTS and an Urdu wake phrase.
- **Full RTL mirroring** — `start`/`end` padding and alignment throughout,
  never `left`/`right`. Chevrons, progress, and back affordances mirror.
- **Errors state cause and remedy** in plain language (SC 3.3.1, 3.3.3). "Model
  import failed: the file's SHA-256 doesn't match its header" and a Retry, not
  "Error 7".
- **Redundant entry avoided** (SC 3.3.7) — values already given during
  onboarding are never asked for again.
- **Accessible authentication** (SC 3.3.8) — biometric and voice paths both
  have a non-cognitive-test fallback.
- **Destructive actions are reversible, checked, or confirmed** (SC 3.3.4,
  3.3.6). The approval spine already satisfies this; the UI claims it
  explicitly.

### 3.4 · Robust

Semantics are part of every component's definition, not an afterthought:

| Element | Requirement |
|---|---|
| Meaningful icon | `contentDescription` naming the action |
| Decorative icon | `contentDescription = null` — **only** here |
| Section header | `Modifier.semantics { heading() }` |
| Toggle, status dot | `stateDescription` ("On" / "Ready" / "Failed") |
| Streamed assistant text | `liveRegion = Polite` |
| Approval request, failure | `liveRegion = Assertive` |
| Custom clickable | explicit `role` |
| Every interactive element | `testTag` for instrumentation |

### 3.5 · Per-route requirements

Every route in §6 declares its reading order, heading levels, live regions, and
focus entry/exit points alongside its contents.

### 3.6 · Test checklist

Each delivery phase (§10) passes this gate before it is considered done:

1. TalkBack traversal of every flow in §2 — every control reachable and named.
2. VoiceOver traversal of the same.
3. Keyboard-only pass on Windows and macOS, including approve and reject.
4. 200% font scale — no clipping, no overlap, no lost controls.
5. RTL pass — layout mirrored, no stranded chevrons.
6. Reduced-motion pass — no unbounded animation.
7. Contrast recomputation against the §4 tables.

---

## 4 · Design language

### 4.1 · Colour — light theme

Background `#F7F7F5`. Ratios are computed WCAG relative-luminance contrast
against that background unless stated otherwise.

| Token | Value | Ratio | Use |
|---|---|---|---|
| `bg` | `#F7F7F5` | — | app background |
| `surface` | `#FFFFFF` | — | cards, sheets, composer |
| `surfaceSelected` | `#EFEFEC` | — | selected rows, user bubbles |
| `surfaceMuted` | `#F2F2EF` | — | recessed fills, inset rows — **text-bearing** |
| `surfaceStrong` | `#E7E7E2` | — | progress/switch tracks, unselected chips — **not text-bearing** |
| `textPrimary` | `#1B1B1A` | 16.1:1 | body, titles, icons |
| `textSecondary` | `#4A4A45` | 8.3:1 | supporting text |
| `textTertiary` | `#6B6B65` | 5.0:1 | timestamps, hints |
| `accent` | `#0B7A5F` | 4.9:1 | links, focus ring, verified |
| `accentFill` | `#10A37F` | 5.4:1 vs `textPrimary` | filled chips — dark text on it |
| `warning` | `#8A5200` | 6.0:1 | blocked text, icon, border |
| `warningFill` | `#FEF3C7` | 5.7:1 vs `warning` | blocked-card background |
| `error` | `#B3261E` | 6.1:1 | failures, hard deny |
| `errorFill` | `#FEE2E2` | 5.4:1 vs `error` | error-card background |
| `success` | `#14762F` | 5.4:1 | ready, online, in sync |
| `successFill` | `#DCFCE7` | 5.2:1 vs `success` | success-card background |
| `info` | `#1D4ED8` | 6.3:1 | informational notices |
| `infoFill` | `#DBEAFE` | 5.5:1 vs `info` | info-card background |
| `border` | `#D8D8D3` | 1.3:1 | **decorative dividers only** |
| `borderStrong` | `#767671` | 4.3:1 | composer, inputs, unselected controls |

**Two surface levels beyond the original three.** The Android screens use five
neutral levels, not three — `surfaceMuted` appears at 64 call sites and
`surfaceStrong` at 9. Omitting them would have forced the migration to
approximate, changing appearance for no reason. They carry different
obligations: `surfaceMuted` is text-bearing and every foreground token clears
4.5:1 on it; `surfaceStrong` is a fill for tracks and unselected chips, where
only `textPrimary` is sanctioned (13.9:1). A sixth level, `PrimaryPr #30302E`,
was dropped — it had exactly one reference, its own declaration.

**`success` moved from `#15803D` to `#14762F`.** The original cleared 4.68:1 on
`bg` but only **4.47:1** on `surfaceMuted` — passing on the page and failing on
the recessed surface it is routinely drawn on. Testing every token against
every text-bearing surface, rather than against `bg` alone, is what caught it.

**Three paired fills and an `info` colour** complete the set. The screens used
pale card backgrounds (`#DCFCE7`, `#FEE2E2`, `#DBEAFE`) with a matching darker
foreground, and a blue for informational notices that has no equivalent in the
green/amber/red trio. `info` stays distinct from `accent`: accent means
"verified / active", info means "here is something neutral to know". `info` is
`#1D4ED8`, not the `#2563EB` the screens used — that measured **4.49:1** on
`surfaceSelected`, a hair under the floor.

**Why these values changed.** The previous palette shipped four measurable
failures, and one of them was a safety signal:

| Token | Was | Ratio | Verdict |
|---|---|---|---|
| `warning` | `#F59E0B` | **2.00:1** | Failed. It marked *policy-blocked actions*. |
| `accent` | `#10A37F` | **2.98:1** | Failed even the 3:1 UI floor — and it was the focus-ring colour. |
| `success` | `#22C55E` | **2.12:1** | Failed. |
| `error` | `#EF4444` | **3.51:1** | Failed body-text contrast. |
| `textTertiary` | `#8D8D87` | **3.11:1** | Failed; used at 11–12 sp for timestamps and hints. |

**The `border` / `borderStrong` split** is the WCAG-correct resolution of a
single 1.3:1 hairline doing two jobs. SC 1.4.11 exempts purely decorative
separators but requires 3:1 for boundaries that carry meaning — the edge of a
text field, an unselected radio, a control's hit area. Decorative dividers keep
the light hairline; anything a user must perceive to operate uses
`borderStrong`.

### 4.2 · Colour — dark theme

Background `#171717`. Note the correction: cards must be **lighter** than the
background in dark mode, mirroring the light theme's "surface = raised"
semantics. An earlier draft inverted this.

| Token | Value | Ratio | Use |
|---|---|---|---|
| `bg` | `#171717` | — | app background |
| `surface` | `#212121` | — | cards, sheets, composer |
| `surfaceSelected` | `#2E2E2E` | — | selected rows, user bubbles |
| `surfaceMuted` | `#1E1E1E` | — | recessed fills — text-bearing |
| `surfaceStrong` | `#333333` | — | tracks, unselected chips — not text-bearing |
| `textPrimary` | `#ECECEC` | 15.2:1 | body, titles |
| `textSecondary` | `#A8A8A2` | 7.5:1 | supporting text |
| `textTertiary` | `#9A9A95` | 6.3:1 | timestamps, hints |
| `accent` | `#3DD9A8` | 10.0:1 | links, focus ring |
| `warning` | `#F2B233` | 9.6:1 | blocked |
| `warningFill` | `#3A2A08` | 7.4:1 vs `warning` | blocked-card background |
| `error` | `#FF8A80` | 7.9:1 | failures |
| `errorFill` | `#3A1412` | 7.1:1 vs `error` | error-card background |
| `success` | `#4ADE80` | 10.3:1 | ready, online |
| `successFill` | `#0C2A16` | 8.9:1 vs `success` | success-card background |
| `info` | `#7AB7FF` | 8.6:1 | informational notices |
| `infoFill` | `#0E2440` | 7.5:1 vs `info` | info-card background |
| `border` | `#2E2E2E` | — | decorative dividers |
| `borderStrong` | `#84847F` | 4.8:1 | inputs, unselected controls |

Dark `textTertiary` and `borderStrong` are **not** the values a first pass
produces. `#8A8A85` and `#767671` clear `bg` and `surface` comfortably but
measure **3.92:1** and **2.97:1** against `surfaceSelected` — the user-bubble
background, where timestamps actually sit. Both were lightened until their
*worst* surface passed, not their best: 4.80:1 and 3.61:1 respectively.

Ratios in these tables are quoted against `bg`. The binding constraint is the
worst case across all four text-bearing surfaces, which is what
`ContrastTest` enforces.

Dark theme follows the system setting, with an in-app override in 5.1.4.

### 4.3 · Type

System font. Named roles, never raw literals — the repo currently has no
`Typography` object and uses inline `28/22/18/17/15/14/13/12/11.sp`.

| Role | Size | Line height | Weight |
|---|---|---|---|
| `display` | 28 sp | 34 sp | Semibold |
| `title` | 22 sp | 28 sp | Semibold |
| `heading` | 17 sp | 24 sp | Semibold |
| `body` | 15 sp | 22 sp | Regular |
| `bodyLong` | 15 sp | 25 sp | Regular |
| `label` | 13 sp | 18 sp | Medium |
| `caption` | 12 sp | 16 sp | Regular |
| `mono` | 14 sp | 20 sp | Regular, monospace |

`bodyLong` exists for streamed answers and documents — reading-grade leading,
per §0's typographic-restraint note. All values scale with the system font
setting to 200%.

### 4.4 · Spacing, shape, motion

- **Spacing** — 4 dp base: `xs 4 · sm 8 · md 12 · lg 16 · xl 24 · xxl 32`.
- **Radii** — three values, down from today's seven (`12/14/16/18/20/24/999`):
  `card 12 dp · sheet 20 dp · pill 999 dp`.
- **Hairlines** — 1 dp.
- **Motion** — `fast 120 ms` (state change), `base 200 ms` (bubble, sheet),
  `slow 320 ms` (route transition). Every animation declares its reduced-motion
  substitution; none is decorative.

### 4.5 · Where tokens live

One shared `NewaxTheme` exposing `Colors`, `Typography`, `Spacing`, and
`Shapes` through `CompositionLocal`s. This replaces **88 duplicated colour
constants across 19 files** (`MainActivity.kt:77-88` and eighteen copies of the
same block) and the ghost `REFINED_THEME.md` — cited in several code comments
but absent from the repository.

Two related defects to fix in the same phase (§10 phase 1):
`apps/android/src/main/res/values/styles.xml` defines a dark navy/teal
`Theme.NewaxAegis` that contradicts the light Compose palette, and
`AndroidManifest.xml:54,67` references `@style/Theme.NewaxNewax` — a theme name
that does not exist — alongside `android:label="Newax Newax"`. Both are
artefacts of an over-eager rename.

---

## 5 · Adaptive information architecture

One IA, three window-size classes, four bodies.

```
Compact  (<600 dp — phones)
  Overlay drawer · chat full-bleed · routes push full-screen · bottom composer
  Artifact → full-screen sheet, entered from a chip in the thread

Medium   (600–1023 dp — tablets, foldables, small desktop windows)
  Dismissible drawer or navigation rail · list–detail for Memory / Tasks /
  Settings · Artifact: a 40% side sheet

Expanded (≥1024 dp — Windows, macOS, iPad)
  Three panes:  sidebar (conversations + sections) │ thread │ artifact/context
  Menu bar · keyboard shortcuts · command palette (Ctrl/Cmd+K)
```

### 5.1 · Desktop reconciliation

`apps/desktop` today is five `NavigationRail` items with **no chat surface at
all** — desktop chat is a `--cli` REPL. Under the unified IA:

| Desktop today | New route |
|---|---|
| *(none — chat is CLI only)* | **1.2** Chat thread, the main pane |
| `StatusScreen` + `AppsScreen` | **4.1** Capability status, **4.3** Apps index |
| `GoalsScreen` | **3.1** Tasks → Goals |
| `PolicyScreen` | **5.3.1** Settings → Policy & Capabilities |
| `AuditScreen` | **5.3.1.3** Policy history / audit export |
| macOS `SyncScreen` | **5.4.1** Sync, **5.1.2** Devices, **5.1.2.1** Pair |

### 5.2 · Desktop-only surface

- **Menu bar** — File (New chat, Export, Close), Edit, View (Toggle sidebar,
  Toggle artifact panel, Theme), Assistant (Stop, Model…, Policy…), Help.
- **Keyboard shortcuts** — the repo has zero key handling today.

  | Shortcut | Action |
  |---|---|
  | `Ctrl/Cmd + N` | New chat |
  | `Ctrl/Cmd + K` | Command palette `⊞9.1` |
  | `Ctrl/Cmd + F` | Search conversations `→1.11` |
  | `Ctrl/Cmd + \` | Toggle sidebar |
  | `Ctrl/Cmd + /` | Toggle artifact panel |
  | `Ctrl/Cmd + ↑ / ↓` | Previous / next conversation |
  | `Esc` | Stop streaming, or dismiss overlay |
  | `Ctrl/Cmd + Enter` | Approve the focused action |
  | `Ctrl/Cmd + Backspace` | Reject the focused action |

  Approve and reject are keyboard-reachable but **never single-key** — an
  accidental keystroke must not authorize an action.
- **Window state persistence** — size, position, pane widths, sidebar state.

### 5.3 · iOS

The same shared CMP UI with the §9 seams. Apple targets cannot be compiled from
the Linux development sandbox; §12 records this.

---

## 6 · The route tree

### 6.0 · Rules that make the tree closed

**1 · Every route declares five things** — title · back target · contents in
sequence · every control's destination · empty / loading / error states
(AGENTS.md R13: real screens, not placeholders).

**2 · Every control resolves to exactly one destination**, using this notation:

| Mark | Meaning |
|---|---|
| `→ 5.2.1` | pushes a full route |
| `⊞ 1.4` | opens an overlay — sheet, dialog, or panel — over the current route |
| `⚡` | performs a typed task in place; the result is announced inline |
| `⇱` | platform seam (system settings, file picker, biometric, camera) that returns here |
| `↩ 5` | back / dismiss to the declared target |

A control with no mark is a specification bug. §11.1 carries the audit.

**3 · Back is deterministic** — always the parent in this tree, never "wherever
you came from", except for the two documented shortcut edges in §6.9.

**4 · Ownership** — a setting is editable on exactly one route. Everything else
displaying that value is a read-only mirror that navigates to the owner.

**5 · Destructive controls never fire directly** — `⊞9.3` confirm, or `⊞9.4`
biometric when the policy class is STRONG_CONFIRMATION. Irreversible actions
use the type-to-confirm variant.

**6 · Every route is reachable in ≤ 3 pushes from the chat thread.**

### 6.1 · The tree at a glance

```
0 ONBOARDING (first run only, linear)
  0.1 Welcome · 0.2 Identity · 0.3 Brain · 0.4 Reach · 0.5 Voice
  0.6 What it can do

1 CHAT ─ the home
  1.1 Conversation list          1.2 Thread ★
  1.3 Artifact panel             1.4 Model sheet
  1.5 Attachment sheet           1.6 Conversation actions
  1.7 Image viewer               1.8 Document viewer
  1.9 Step detail                1.10 Voice capture
  1.11 Conversation search       1.12 Export conversation

2 MEMORY
  2.1 Timeline ★                 2.2 Search results
  2.3 Entry detail               2.4 People
  2.5 Person detail              2.6 Facts
  2.7 Procedures                 2.8 Connections
  2.9 Drafts                     2.10 Meetings
  2.11 Meeting detail            2.12 Agent memory

3 TASKS
  3.1 Goals ★                    3.2 Goal detail
  3.3 New goal                   3.4 Run history
  3.5 Agents                     3.6 Agent detail
  3.7 Import agent               3.8 Skills
  3.9 Skill detail               3.10 Skill approvals

4 CAPABILITIES
  4.1 Capability status ★        4.2 Capability detail
  4.3 Apps index

5 SETTINGS ★ — the single settings page
  5.1 General            5.1.1 Profile · 5.1.2 Devices · 5.1.2.1 Pair a device
                         5.1.3 Ambient & Voice · 5.1.3.1 Ambient Mode
                         5.1.3.2 Wake word · 5.1.3.3 Voice authentication
                         5.1.4 About · 5.1.4.1 Privacy policy · 5.1.4.2 Licences
  5.2 Model & Intelligence
                         5.2.1 Offline AI Model · 5.2.1.1 Import · 5.2.1.2 Benchmark
                         5.2.2 Automation · 5.2.2.1 Group detail · 5.2.2.2 2FA setup
                         5.2.3 Learning & Memory · 5.2.3.1 Sources
  5.3 Safety & Privacy   5.3.1 Policy & Capabilities · 5.3.1.1 Action class
                         5.3.1.2 Hard-deny list · 5.3.1.3 Policy history
                         5.3.2 Privacy & Security · 5.3.2.1 Redaction
                         5.3.2.2 Security audit
  5.4 Connectivity       5.4.1 Sync · 5.4.1.1 Categories · 5.4.1.2 Peer detail
                         5.4.1.3 Relay · 5.4.2 Nearby Share · 5.4.2.1 Transfer
  5.5 Data & Storage     5.5.1 Data & Backup · 5.5.1.1 Create · 5.5.1.2 Restore
                         5.5.1.3 Export · 5.5.1.4 Clear data
  5.6 System             5.6.1 Permissions · 5.6.1.1 App permissions
                         5.6.2 Updates · 5.6.2.1 Update detail
                         5.6.3 Advanced (Dev) · 5.6.3.1 Feature flags
                         5.6.3.2 Dev console · 5.6.3.3 Diagnostics

9 GLOBAL OVERLAYS (dismiss returns to the caller)
  9.1 Command palette · 9.2 Blocking error · 9.3 Confirm dialog
  9.4 Biometric prompt · 9.5 Crash reporter
```

★ marks a section's landing route — what the drawer item opens.

---

### 6.2 · Section 0 — Onboarding

Linear, resumable, entered once. Re-runnable later from 5.1.4 → Replay setup.

#### 0.1 Welcome
Back: none (first route).

1. Brand mark.
2. Headline — "Your private, on-device assistant."
3. Three promise lines — no Internet permission · everything encrypted on this
   device · you approve every action.
4. **Continue**.
5. Two text links.

| Control | Destination |
|---|---|
| Continue | `→0.2` |
| What Newax Aegis can do | `→0.6` |
| Privacy policy | `→5.1.4.1` |

*A11y* — headline is `heading()`; focus enters on Continue.

#### 0.2 Identity
Back: `↩0.1`.

1. "What should I call you?"
2. Name field (autofocus).
3. Language row.
4. Communication style chips — Formal / Casual / Balanced / Technical.
5. **Continue** · 6. **Skip**.

| Control | Destination |
|---|---|
| Name field | `⚡ ProfileManager → EncryptedMemory` |
| Language row | `⊞ searchable language sheet` `⚡` |
| Style chips | `⚡` |
| Continue · Skip | `→0.3` |

#### 0.3 Brain
Back: `↩0.2`.

1. "Give Newax Aegis a brain."
2. What a model adds, and what basic mode still does without one.
3. **Import a model**.
4. **Continue without one**.
5. Verification result block (after an import attempt).

| Control | Destination |
|---|---|
| Import a model | `⇱ file picker` → `⚡ verify: format · size · magic header · SHA-256` |
| ↳ verification passes | `→0.4` |
| ↳ verification fails | inline fail block naming the failed check |
| Fail block: Retry | `⚡` |
| Fail block: Choose another | `⇱ file picker` |
| Fail block: Continue without | `→0.4` |
| Continue without one | `⊞9.3` → `→0.4` |

*Empty* — no model present, which is a supported state, not an error.
*Loading* — verification progress per check.
*Error* — the specific check that failed, never a generic message.

#### 0.4 Reach
Back: `↩0.3`.

1. "Let me see and act on your screen."
2. Plain-language capability explainer — what Screen Access enables, in terms
   of what the assistant will be able to do for you.
3. **Always blocked** list — banking apps · password managers · permission
   dialogs · CAPTCHAs · biometric prompts · apps with protected content.
4. **Enable** · 5. **Not now**.

| Control | Destination |
|---|---|
| Enable | `⇱ Accessibility settings` → returns → `⚡ re-read state` → `→0.5` |
| Not now | `→0.5` |
| "Why are these blocked?" | `→0.6` |

#### 0.5 Voice (optional)
Back: `↩0.4`.

1. "Talk to it."
2. Microphone row.
3. Wake word row.
4. Voice ID row.
5. **Done**.

| Control | Destination |
|---|---|
| Microphone | `⇱ mic permission` → `⚡ re-read` |
| Wake word | `→5.1.3.2` (onboarding variant — returns to 0.5) |
| Voice ID | `→5.1.3.3` (onboarding variant — returns to 0.5) |
| Done · Skip | `→1.2` |

#### 0.6 What Newax Aegis can do
Back: `↩` caller (0.1 or 0.4).

1. Capability groups — chat · screen actions · memory · plans · devices.
2. An example phrase per group.
3. **Close**.

| Control | Destination |
|---|---|
| Group row | `⊞` expands inline — no new route |
| Close | `↩` caller |

---

### 6.3 · Section 1 — Chat

#### 1.1 Conversation list
Compact: drawer content plus a full route. Expanded: the permanent sidebar.
Back: `↩1.2`.

1. Brand header — "Newax Aegis".
2. **New chat** (pencil-in-square icon).
3. Search field.
4. Pinned group.
5. Today / Yesterday / dated groups of conversation rows — title, two-line
   preview, relative timestamp.
6. Section links — Memory · Tasks · Capabilities · Settings.
7. Model status footer — dot + name.

| Control | Destination |
|---|---|
| New chat | `⚡ create thread` `→1.2` |
| Search field | `→1.11` |
| Conversation row | `→1.2` (that thread) |
| Row overflow ⋯ | `⊞1.6` |
| Memory | `→2.1` |
| Tasks | `→3.1` |
| Capabilities | `→4.1` |
| Settings | `→5` |
| Model status footer | `⊞1.4` |

*Empty* — "No conversations yet" + New chat.
*Loading* — six skeleton rows.
*Error* — history unreadable: named error + Retry `⚡` + `→5.5.1`.
*A11y* — day groups are `heading()`; the selected row carries
`stateDescription = "Selected"`.

#### 1.2 Thread ★ — the main surface
Back: `↩1.1` on compact; none on expanded, where it is the centre pane.

1. Header — menu · title · New chat · overflow.
2. Degraded banner (conditional, §2.1).
3. Message list — assistant bubbles start-aligned on `surface` with a hairline;
   user bubbles end-aligned on `surfaceSelected`.
4. Inline content blocks (§7).
5. Approval and step blocks (§2.4).
6. Attachment chips.
7. Composer — attach · text field (grows to 5 lines) · mic ⇄ send/stop.
8. Model status line — dot + name, directly beneath the composer.

| Control | Destination |
|---|---|
| Hamburger / sidebar toggle | `⊞1.1` |
| Title | `⊞ rename inline` `⚡` |
| New chat (pencil-square) | `⚡` `→1.2` fresh |
| Overflow ⋯ | `⊞1.6` |
| Degraded banner action | the route named by the banner — `⊞1.4` / `→5.6.1` / `→5.3.1` |
| Suggestion chip (6, empty state) | `⚡ send as first message` |
| Attach (paperclip) | `⊞1.5` |
| Send | `⚡ submit` |
| Stop | `⚡ cancel stream` |
| Mic | `⊞1.10` |
| Model status line | `⊞1.4` |
| Copy button (any block) | `⚡ clipboard` + announce "Copied" |
| Code block header copy | `⚡ clipboard` |
| Image block | `⊞1.7` |
| Document row | `⊞1.8` |
| Artifact chip | `⊞1.3` |
| Step block header | expands in place |
| Step block "Details" | `⊞1.9` |
| Approval: **Approve** | `⚡ execute` — STRONG_CONFIRMATION first `⊞9.4` |
| Approval: **Reject** | `⚡ record + continue` |
| Approval: **Why?** | expands inline; "Change this rule" `→5.3.1.1` |
| Blocked block: "Change policy" | `→5.3.1` |
| MCQ option | `⚡ send as reply` |
| MCQ `Custom…` | expands a text input `⚡` |
| Thought container header | `⚡ toggle` — state persisted per conversation |
| Failure: Retry / Try another way / Stop | `⚡` each |
| Undo (post-success, time-boxed) | `⚡ reverse` |
| Source attribution chip | `→2.3` (memory) · `⊞1.8` (file) · `→1.9` (screen read) |
| Message long-press | `⊞ message actions` — Copy `⚡` · Quote `⚡` · Delete `⊞9.3` |

*Empty* — brand mark, "Your private, on-device assistant.", and six
first-message suggestions in a 2-column grid (3-column on wide screens). Each
maps to a real intent: "What's on my screen?" · "Open an app" · "Draft a
reply" · "What do you remember?" · "Set a reminder" · "Find a file".
*Loading* — thought container plus typing indicator; static "Thinking…" under
reduced motion.
*Error* — failure block with the retry ladder.
*A11y* — streamed text is `liveRegion = Polite`; an approval request is
`Assertive`; focus never moves during streaming; the composer's disabled state
carries a `stateDescription` naming the reason.

#### 1.3–1.12 Chat overlays and sub-routes

| Route | Contents, in sequence | Controls |
|---|---|---|
| **1.3 Artifact panel** | 1 title · type · size · 2 content (document / code / table / image) · 3 action bar | Copy `⚡` · Save `⇱` · Open in viewer `⊞1.8` · Pin open (expanded) `⚡` · Close `↩1.2` |
| **1.4 Model sheet** | 1 current model + status dot + plain-language description · 2 available models · 3 Import · 4 Unload / Reload · 5 All model settings | model row `⚡ switch` · Import `→5.2.1.1` · Unload / Reload `⚡` · All settings `→5.2.1` · Close `↩` |
| **1.5 Attachment sheet** | 1 Photos · 2 Files · 3 Screen capture · 4 Recent | Photos / Files `⇱ picker` → chips on 1.2 · Screen capture `⇱ capture request` · Recent row `⚡ attach` · Close `↩` |
| **1.6 Conversation actions** | 1 Rename · 2 Pin · 3 Export · 4 Delete | Rename `⊞ edit sheet` `⚡` · Pin `⚡` · Export `→1.12` · Delete `⊞9.3` `⚡` |
| **1.7 Image viewer** | 1 image (pinch-zoom) · 2 caption · 3 action bar | Save `⇱` · Share `⇱` · Close `↩1.2` |
| **1.8 Document viewer** | 1 filename · type · size · 2 rendered content · 3 action bar | Copy text `⚡` · Save `⇱` · Open in artifact panel `⊞1.3` · Close `↩` |
| **1.9 Step detail** | 1 action name · 2 typed target · 3 risk class + matched policy rule · 4 ladder rung used · 5 result or error · 6 audit link | "This rule" `→5.3.1.1` · "See in history" `→5.3.1.3` · Retry `⚡` · Close `↩1.2` |
| **1.10 Voice capture** | 1 live level meter · 2 running transcript · 3 Stop · 4 Cancel | Stop `⚡ insert into composer` `↩1.2` · Cancel `↩1.2` · Voice settings `→5.1.3` |
| **1.11 Conversation search** | 1 search field (autofocus) · 2 result rows with matched snippet | result `→1.2` at that message · Clear `⚡` · Close `↩1.1` |
| **1.12 Export conversation** | 1 format — Markdown / Text / JSON · 2 include-attachments toggle · 3 Export | Export `⚡` → `⇱ save picker` → confirmation `↩1.2` · Cancel `↩` |

*A11y note for 1.10* — the level meter is decorative; the transcript is the
accessible representation and is a polite live region.

---

### 6.4 · Section 2 — Memory

Landing: **2.1**. Absorbs today's Memory, People, Drafts, Meeting, and Agent
Memory screens.

#### 2.1 Timeline ★
Back: `↩1.2`.

1. Search field.
2. Category filter chips — personal · business · relationships · goals · pain
   points · rules.
3. Day-grouped entries — content preview, source, category chip.
4. Section links.

| Control | Destination |
|---|---|
| Search field | `→2.2` |
| Category chip | `⚡ filter` |
| Entry row | `→2.3` |
| Entry overflow ⋯ | `⊞` — Pin `⚡` · Edit `→2.3` · Delete `⊞9.3` · Forget `⊞9.3` |
| People | `→2.4` |
| Facts | `→2.6` |
| Procedures | `→2.7` |
| Connections | `→2.8` |
| Drafts | `→2.9` |
| Meetings | `→2.10` |
| Agent memory | `→2.12` |
| Memory settings | `→5.2.3` |

*Empty* — "Nothing remembered yet" + an example of how to teach it.
*A11y* — day groups and section links are `heading()`; category chips carry
`stateDescription` for selected state.

#### 2.2–2.12

| Route | Contents, in sequence | Controls |
|---|---|---|
| **2.2 Search results** | 1 query field · 2 relevance-ranked hits with the match highlighted | hit `→2.3` · Clear `⚡` · back `↩2.1` |
| **2.3 Entry detail** | 1 content (editable) · 2 category · 3 source · 4 created / updated · 5 linked people · 6 actions | Save `⚡` · Category `⊞ picker` `⚡` · Source `→1.2` / `⊞1.8` / `→2.11` · person chip `→2.5` · Delete `⊞9.3` · Forget `⊞9.3` · back `↩2.1` |
| **2.4 People** | 1 search · 2 alphabetical rows — name, relationship, last contact · 3 Add person | row `→2.5` · Add `⊞ new person sheet` `⚡` · back `↩2.1` |
| **2.5 Person detail** | 1 header — name, relationship · 2 profile facts · 3 commitments · 4 communication history · 5 linked memory entries · 6 actions | Edit `⊞ edit sheet` `⚡` · fact `→2.3` · history row `→1.2` · commitment `→3.2` · Message `⚡ compose` `→1.2` · Delete `⊞9.3` · back `↩2.4` |
| **2.6 Facts** | 1 search · 2 subject–predicate–object triples grouped by subject | triple `→2.3` · subject header `→2.5` when it is a person · back `↩2.1` |
| **2.7 Procedures** | 1 learned-procedure rows — name, app, success count, last used | row `⊞ procedure detail` — steps · Run `⚡` (→FLOW C) · Forget `⊞9.3` · back `↩2.1` |
| **2.8 Connections** | 1 graph canvas · 2 legend · 3 zoom · 4 **list fallback** | node `→2.5` / `→2.3` · List view `⚡` · back `↩2.1` |
| **2.9 Drafts** | 1 pending self-learned fact rows — text, source, confidence · 2 bulk bar | Approve `⚡` · Reject `⚡` · row `→2.3` preview · Approve all `⊞9.3` · Learning settings `→5.2.3` · back `↩2.1` |
| **2.10 Meetings** | 1 date-grouped meeting rows · 2 New meeting | row `→2.11` · New `⚡` `→2.11` · back `↩2.1` |
| **2.11 Meeting detail** | 1 title + timestamp · 2 attendees · 3 notes (editable) · 4 extracted commitments | Save `⚡` · attendee `→2.5` · commitment `→3.2` · Delete `⊞9.3` · back `↩2.10` |
| **2.12 Agent memory** | 1 tabs — Episodes · Recall · Scratchpad · Handoffs · Work log · 2 rows per tab | tab `⚡` · row `⊞ detail` · agent name `→3.6` · Clear `⊞9.3` · back `↩2.1` |

**2.8 accessibility requirement** — the graph is never the only representation.
The list fallback carries the same data in a linear, screen-reader-traversable
form, and is the default under TalkBack or VoiceOver.

---

### 6.5 · Section 3 — Tasks

Landing: **3.1**. Absorbs Goals, Agents, and Skills.

| Route | Contents, in sequence | Controls |
|---|---|---|
| **3.1 Goals ★** | 1 segmented tabs Goals · Agents · Skills · 2 active goal cards — title, progress, per-task state · 3 blocked tasks in warning style (icon + the word "Blocked") · 4 New goal | card `→3.2` · blocked task "Why blocked" `→5.3.1.1` · New goal `→3.3` · Agents tab `→3.5` · Skills tab `→3.8` · back `↩1.2` |
| **3.2 Goal detail** | 1 title + status · 2 task graph in order · 3 per-task state · 4 Recent runs · 5 actions | task `⊞ task detail` — Run `⚡` (FLOW C) · Skip `⚡` · Recent runs `→3.4` · Run goal `⚡` · Edit `⊞ edit sheet` `⚡` · Export CSV `⚡`+`⇱` · Delete `⊞9.3` · back `↩3.1` |
| **3.3 New goal** | 1 goal text field · 2 planner pre-flight warnings, shown up front · 3 proposed task list · 4 Create | Create `⚡` `→3.2` · warning row `→5.3.1.1` or `→5.6.1` · Cancel `↩3.1` |
| **3.4 Run history** | 1 run rows — time, outcome, step count · 2 filter | run `→1.9` · Export CSV `⚡`+`⇱` · back `↩3.2` |
| **3.5 Agents** | 1 installed agent rows — name, domain, version, state · 2 Import agent · 3 live stream feed | row `→3.6` · Import `→3.7` · feed item `→1.9` · back `↩3.1` |
| **3.6 Agent detail** | 1 identity + version · 2 granted permissions · 3 routing preview · 4 memory link · 5 actions | permission toggle `⚡` — dangerous grants `⊞9.4` · Memory `→2.12` · Enable / Disable `⚡` · Remove `⊞9.3` · back `↩3.5` |
| **3.7 Import agent** | 1 pick `.aegis-agent` · 2 manifest + requested permissions, **deny-by-default** · 3 Install | Pick `⇱` · permission toggle `⚡` · Install `⊞9.4` `⚡` `→3.6` · Cancel `↩3.5` |
| **3.8 Skills** | 1 search · 2 skill rows with OFFLINE_OK / REQUIRES_ONLINE / DEFER tags · 3 skill sets · 4 Approvals badge | row `→3.9` · enable switch `⚡` · Approvals `→3.10` · set `⊞ set detail` · back `↩3.1` |
| **3.9 Skill detail** | 1 name + tag · 2 what it does · 3 permissions · 4 evolution history · 5 actions | permission `⚡` · history entry `→5.6.2.1` · Enable / Disable `⚡` · back `↩3.8` |
| **3.10 Skill approvals** | 1 pending approval rows — skill, requested change, diff | Approve `⊞9.4` `⚡` · Reject `⚡` · diff `→5.6.2.1` · back `↩3.8` |

*A11y* — the OFFLINE_OK / REQUIRES_ONLINE / DEFER tags are text, not colour
coding. Blocked tasks carry an icon and the word, per §3.1.

---

### 6.6 · Section 4 — Capabilities

Landing **4.1**. Primary on desktop; on mobile it is reached from the drawer
and from degraded banners.

| Route | Contents, in sequence | Controls |
|---|---|---|
| **4.1 Capability status ★** | 1 model provider card · 2 capability grid — files · processes · shell · desktop automation · secrets · system · connectivity · battery — each OPERATIONAL / NOT_SUPPORTED / ERROR with a text label · 3 Apps index | tile `→4.2` · model card `⊞1.4` · Apps `→4.3` · back `↩1.2` |
| **4.2 Capability detail** | 1 name + status · 2 what it enables, in user terms · 3 backing adapter · 4 last error · 5 remedy | Remedy `→5.6.1` or `⇱ system settings` · Retry `⚡` · back `↩4.1` |
| **4.3 Apps index** | 1 fuzzy search · 2 app rows — name, path | row `⚡ launch` (FLOW C) · Rebuild index `⚡` · back `↩4.1` |

---

### 6.7 · Section 5 — Settings

**5 · Settings** — the single settings page. Back: `↩1.2`.

Six category headers in a fixed sequence, each a group of chevron rows carrying
live sub-line state. **No setting exists outside this subtree.** The chat
overflow keeps only conversation actions and the transient model sheet; Memory,
Tasks, and People are content pages whose actions are data actions — pin, edit,
delete, forget — never configuration.

| Category | Row | Sub-line | Destination |
|---|---|---|---|
| **1 · General** | Profile | name · language | `→5.1.1` |
| | Devices | "This device + N paired" | `→5.1.2` |
| | Ambient & Voice | Off / Listening / Enrolled | `→5.1.3` |
| | About | version | `→5.1.4` |
| **2 · Model & Intelligence** | Offline AI Model | status dot + model name | `→5.2.1` |
| | Automation | "N of M groups on" | `→5.2.2` |
| | Learning & Memory | On/Off · N drafts | `→5.2.3` |
| **3 · Safety & Privacy** | Policy & Capabilities | "N rules · M hard-denied" | `→5.3.1` |
| | Privacy & Security | "Encrypted · biometric on" | `→5.3.2` |
| **4 · Connectivity** | Sync | LAN / Relay / Off | `→5.4.1` |
| | Nearby Share | On / Off | `→5.4.2` |
| **5 · Data & Storage** | Data & Backup | last backup | `→5.5.1` |
| **6 · System** | Permissions | "N of M granted" | `→5.6.1` |
| | Updates | "N pending" | `→5.6.2` |
| | Advanced (Dev) | collapsed by default | `→5.6.3` |

**Sequence logic** — who this device is and how it listens (General), then the
brain (Model & Intelligence), then the guardrails (Safety & Privacy), then how
it talks to other devices (Connectivity), then your data, then platform
control. Identity first; system last.

**Grouping rules** — Connectivity holds Sync and Nearby Share and nothing else;
a network line in Devices is a mirror, not a setting. Learning exports link to
Data & Storage rather than duplicating backup. Permissions stay in System
because they are platform grants, not assistant behaviour.

*A11y* — each category header is `heading()` level 2, each row label level 3;
every row's sub-line is part of the row's accessible name, so a screen reader
announces state without entering the route.

---

#### 5.1 · General

##### 5.1.1 Profile
Back: `↩5`. The single owner of identity. No other route changes name,
language, style, persona, wake phrase, or interests. Chat greetings and CSV
export headers read these values.

Backed by `ProfileManager`
(`apps/android/.../engine/manager/ProfileManager.kt`), stored **encrypted** via
`EncryptedMemory`. The page shows a brief loading state while encrypted values
are read, and every edit saves immediately with inline feedback — there is no
Save button.

**Header** — avatar (initial on `primary`), profile name, persona sub-line.

**A · Identity — who you are**
1. **Name** — row → edit sheet. Used in greetings and CSV headers. Empty is
   allowed; the header falls back to "Newax".
2. **Language** — row → searchable sheet (default `en`). Drives the "Respond in
   <lang>" prompt addition.
3. **Timezone** — row → picker (default: device timezone).

**B · Assistant style — how it talks to you**
4. **Communication style** — chips: Formal / Casual / Balanced / Technical
   (default Balanced). Maps 1:1 to `ProfileManager.CommunicationStyle`.
5. **Response length** — chips: Short / Medium / Long / Adaptive (default
   Medium). Maps to `ProfileManager.ResponseLength`.
6. **Persona** — row → edit sheet (default "helpful assistant").

**C · Voice — the phrase it listens for**
7. **Wake phrase** — row → edit sheet (default "Newax"). **The value lives
   here; the on/off switch lives in 5.1.3.2.** This page owns the phrase;
   Wake word owns the listening toggle. Explicit non-duplication.

**D · Personalization — what it knows you like**
8. **Interests** — tag editor (`addInterest` / `removeInterest`). The top five
   go into the model prompt.
9. **Dislikes** — tag editor (`addDislike` / `removeDislike`).

**How the assistant uses this** — a live preview card at the bottom renders
`ProfileManager.systemPromptAdditions()`, the actual string the model is given,
updating as fields change. A mirror, not a setting.

| Control | Destination |
|---|---|
| Any field | `⚡ ProfileManager → EncryptedMemory` |
| Language · Timezone · Persona · Name | `⊞ edit sheet` `⚡` |
| Style · Length chips | `⚡` |
| Wake phrase "Turn on/off" | `→5.1.3.2` |
| Reset profile | `⊞9.3` `⚡` |

*Sequence logic* — identity, then how it talks, then the phrase, then what it
knows. Devices is a sibling route, not part of this one.

##### 5.1.2 Devices
Back: `↩5`. Owns device identity and the paired list. Pairing itself lives in
5.1.2.1.

**A · This device** — read-only telemetry from `ConnectivityDashboard` plus
device identity. The only editable row is the name.

1. **Device name** — editable → edit sheet. This is the sync display name other
   paired devices see (`DeviceIdentity.displayName`). Renaming updates the
   identity carried by the sync advertisement and the pairing QR.
2. **Device ID** — read-only monospace `dev-abcd1234ef`, derived from the
   Ed25519 key fingerprint — stable across restarts, unforgeable. With a copy
   button.
3. **Fingerprint** — read-only `abcd1234` (`shortFingerprint`); the human check
   shown during pairing.
4. **Network** — live: `WiFi · HomeNet · signal` / `LTE · Carrier` / `Offline`.
   A read-only mirror; network settings belong to the OS.
5. **Battery & storage** — read-only `82% · 1.2 GB used`. Storage detail is a
   mirror of 5.3.2, which owns encryption status.
6. **Model & OS** — read-only: device model, OS version, app version.

**B · Paired devices** — from the sync peer store. This device is never listed
here; it is section A.

Each row: display name, short device id, last-synced relative time, and a
status dot with a text label — `In sync now` (success) / `Last synced 2 h ago`
(neutral) / `Never synced` (error).

| Control | Destination |
|---|---|
| Device name | `⊞ edit sheet` `⚡` |
| Copy device ID | `⚡` |
| Paired device row | `→5.4.1.2` |
| Forget device | `⊞9.3` `⚡` |
| Pair a new device | `→5.1.2.1` |

*Empty* — "No paired devices" + Pair a new device.
*Loading* — while the peer list loads.
*Error* — peer store unavailable: named error + Retry.

##### 5.1.2.1 Pair a device
Back: `↩` caller — 5.1.2 or 5.4.1 (a documented shortcut edge, §6.9). **The
only place pairing happens.** A state machine; the route re-renders per step.
Grounded in `shared/sync Pairing.kt` and SYNC_DESIGN.md §3 — QR → SAS →
confirm.

**Entry gate** — `⊞9.4` biometric runs before the route renders. Denied →
return to the caller with "Biometric required to pair devices".

**Step 0 · Role** — two cards.

| Control | Destination |
|---|---|
| Show my code | `⚡` → Step 1a |
| Scan a code | `⚡` → Step 1b |

**Step 1a · Initiator — Show my code**
1. **QR card** — ~240 dp QR rendering `Pairing.createRequest(...)` (payload
   `aegis-pair-v1|1|<name>|<signKey>|<ecdhKey>|<nonce>`, fresh nonce per
   request). Caption: display name + this device's `shortFingerprint`. Expiry
   "This code expires in 5:00" with a live countdown.
2. **Type-the-key fallback** — a chevron expands a copyable monospace box with
   the full encoded payload.
3. **Waiting state** — "Waiting for the other device to scan…"; when the
   responder's keys arrive, the card advances to Step 2.

| Control | Destination |
|---|---|
| Refresh (new nonce) | `⚡` |
| Copy payload | `⚡` |

**Step 1b · Responder — Scan a code** — three paths, one outcome.

| Control | Destination |
|---|---|
| Scan QR | `⇱ camera` → parse → Step 2 |
| Torch | `⚡` |
| Camera permission chip | `⇱ mic/camera permission` |
| Type a key | `⊞ input` → `⚡ PairingRequest.decode()` → Step 2; invalid → inline "Not a valid pairing code" |
| Nearby device row | `⚡` → Step 2 with that peer |

*Empty (nearby)* — "No devices found — make sure the other device is showing
its code."

**Step 2 · SAS confirm** — both roles converge. The 6-digit code from
`Pairing.sas(initiatorSign, responderSign, nonce)` — identical on both devices
— rendered large (monospace ≈ 48 sp, letter-spaced, bordered) with the
instruction "Check that both devices show the same code." A 2:00 countdown;
expiry restarts with a fresh nonce.

| Control | Destination |
|---|---|
| **Codes match → Confirm** | `⊞9.4` `⚡` → Step 3 |
| **Codes don't match → Cancel** | `⚡ abort` + warning card: "Codes don't match — possible interception. Do not confirm." |

SAS is the only interception defence point. Nothing here ever auto-confirms.

**Step 3 · Result**

| Outcome | Contents | Controls |
|---|---|---|
| Success | "Paired with <name> ✓" + short fingerprint | Done `↩5.1.2` |
| Already paired | "This device is already paired" | Re-pair `⚡` · Unpair `⊞9.3` `⚡` (writes a revocation record) |
| Pair-with-self | "You can't pair a device with itself" | Back `↩` Step 0 |
| Timeout | "No response — try again" | Retry `⚡` |
| Camera denied | typed-key path stays available | Type a key `⊞ input` |

*Sequence logic* — gate (sensitive) → role → code exchange → human-verified SAS
→ peer stored. Pairing is the only capability on this route.

##### 5.1.3 Ambient & Voice
Back: `↩5`. The single owner of mic-based input. Three chevron rows with live
sub-lines; no mic-input setting lives anywhere else. Mic permission itself
stays in 5.6.1 as a mirror.

| Row | Sub-line | Destination |
|---|---|---|
| Ambient Mode | `Off` / `Meeting — listening` | `→5.1.3.1` |
| Wake word | `Off` / `Listening for 'Newax'` | `→5.1.3.2` |
| Voice authentication | `Not enrolled` / `Enrolled` | `→5.1.3.3` |

##### 5.1.3.1 Ambient Mode
Back: `↩5.1.3`. The only place a continuous-transcription session starts or
stops. Grounded in `VoiceRecognitionService.ambientMode` / `ambientTranscript`
/ `endAmbientMode()`.

**A · Mode picker** — segmented chips, one active at a time:
- **Off** — no continuous transcription (default; selecting it ends any active
  session).
- **Meeting** — transcribe, then summarize and extract action items to memory.
- **Lecture** — transcribe, then create study notes and key concepts.

**B · Live session** — rendered only while a mode is active:
- Listening indicator — "Listening… 12:04" (elapsed) plus a Stop chip labelled
  with the running mode.
- Transcript preview — the running `ambientTranscript`, read-only, word-counted
  ("1,240 words so far"): exactly what gets summarized.

| Control | Destination |
|---|---|
| Mode chip | `⚡` |
| Stop | `⊞9.3` when the transcript is long ("Stop and summarize?") → `⚡` → transcript through `TriggerEngine` → saved to memory → "Saved to memory ✓" |
| Permission needed chip | `⇱ mic permission` |
| Retry (model unpack failed) | `⚡` |

*Idle* — picker only; each option's sub-line explains its outcome.
*Active* — picker disabled, session UI visible.
*Loading* — Vosk models unpacking.
*Error* — unpack failure stops the service: error card + Retry.

A "Mode active" chip on the thread header is a read-only mirror; it navigates
here and does not control the session.

*A11y* — the elapsed timer is a polite live region updating at most once per
10 s, so a screen reader is not flooded.

##### 5.1.3.2 Wake word
Back: `↩5.1.3` — or `↩0.5` in the onboarding variant, which renders Continue
instead of a back chevron. The single owner of always-on listening.

1. **Master switch** — runs the Vosk wake-word foreground service. On: status
   line "Listening for 'Newax'" plus the service notification; the process
   survives backgrounding. Sub-line: "Continuously listens for your wake phrase
   (uses battery)."
2. **Wake phrase** — read-only mirror of the value owned by 5.1.1.
3. **How it works** — "Say the phrase, then speak your command. Works fully
   offline."
4. **Test the phrase** — opens a ~10 s listening window using the live
   listener.

| Control | Destination |
|---|---|
| Master switch | `⚡` |
| Change phrase | `→5.1.1` |
| Test | `⚡` → inline "Detected ✓" or "Not detected — try again" |
| Permission needed chip | `⇱ mic permission` |

*Off* — Test disabled. *On* — status line and notification live.
*Loading* — Vosk model unpack in progress.

> **Known gap, recorded honestly.** The service matcher currently hardcodes its
> phrase and does not read `ProfileManager.wakeWord`. Wiring the phrase value
> into the matcher is a follow-up code change, not a design change.

*Sequence logic* — toggle (does it listen), phrase (what it hears), test (prove
it works).

##### 5.1.3.3 Voice authentication
Back: `↩5.1.3` — or `↩0.5` in the onboarding variant. Owns voice auth: your
voice confirms sensitive actions when device biometric is unavailable or fails.
Grounded in `VoiceAuthenticator` — cosine similarity against a threshold,
fail-secure.

1. **Enrolment card** — `Not enrolled` with an Enrol button, or `Enrolled ✓`
   with Re-enrol and Remove.
2. **Use voice to confirm** — a switch, enabled only while enrolled (disabled
   state reads "Enrol first"). Sub-line: "Voice confirms sensitive actions when
   biometric is unavailable or fails."
3. **How it's used** — "Actions at STRONG_CONFIRMATION level can be approved by
   your voice when the device biometric is unavailable or fails. Fail-secure:
   with no voiceprint, verification always fails and the action stays blocked."

| Control | Destination |
|---|---|
| Enrol / Re-enrol | `⊞ voice enrol sheet` — repeat the phrase ~3× → `⚡` |
| Use voice to confirm | `⚡` |
| What it gates | `→5.3.1` |
| Remove voiceprint | `⊞9.3` `⚡ clearEnrollment()` |
| Permission needed chip | `⇱ mic permission` |

*Fail-secure* — with no enrolment, `verify()` returns false, the UI shows "Not
enrolled", and the confirm switch stays off. Nothing ever auto-enables.

> **Honest note.** The voiceprint is held in memory for the session; re-enrol
> after an app restart. Persistence is a backend item, not a page change.

*Sequence logic* — enrol (who you are), enable (when it applies), understand
(how it's used). Safety & Privacy's biometric row covers device biometric, not
this voice model.

##### 5.1.4 About
Back: `↩5`. Information only; nothing here is a setting.

| Row | Content | Destination |
|---|---|---|
| Version | read-only | — |
| Theme | System / Light / Dark | `⚡` |
| Storage & encryption | read-only mirror `1.2 GB · Encrypted on device (AES-256-GCM)` | `→5.3.2` |
| Network | read-only badge "Offline — no data sent" | — |
| Privacy policy | | `→5.1.4.1` |
| Licences | | `→5.1.4.2` |
| Replay setup | re-runs onboarding | `⊞9.3` `→0.1` |

##### 5.1.4.1 Privacy policy
Back: `↩5.1.4`. The full policy text. No controls other than back.

##### 5.1.4.2 Licences
Back: `↩5.1.4`. Attribution rows — LiteRT · Vosk · SQLCipher · ML Kit ·
MediaPipe · zxing · Rhino · jmdns · OkHttp · JNA · coroutines · Room · Compose.
Row `⊞ licence text`.

---

#### 5.2 · Model & Intelligence

| Route | Contents, in sequence | Controls |
|---|---|---|
| **5.2.1 Offline AI Model** | 1 status dot + name + plain-language description · 2 provider · 3 size · 4 installed models · 5 Import · 6 Benchmark · 7 Unload / Reload | model row `⚡ activate` · Import `→5.2.1.1` · Benchmark `→5.2.1.2` · Unload / Reload `⚡` · Remove `⊞9.3` · back `↩5` |
| **5.2.1.1 Import model** | 1 supported formats (`.litertlm` / GGUF) · 2 Choose file · 3 verification checklist — format · size · magic header · SHA-256 · 4 result | Choose `⇱ picker` → `⚡ verify` · pass → Activate `⚡` `↩5.2.1` · fail → the failed check named + Retry `⚡` / Choose another `⇱` · Cancel `↩5.2.1` |
| **5.2.1.2 Benchmark** | 1 device profile · 2 Run · 3 results — load time, tokens/s, peak memory · 4 recommendation | Run `⚡` · Apply recommendation `⚡` `↩5.2.1` · back `↩5.2.1` |
| **5.2.2 Automation** | 1 Ghost Mode switch · 2 per-group switches — app · messaging · media · navigation · files · system · 3 dangerous-group note | group row `→5.2.2.1` · switch `⚡` — dangerous groups `⊞9.4` · Ghost Mode `⚡` · Set up 2FA `→5.2.2.2` · back `↩5` |
| **5.2.2.1 Group detail** | 1 group name · 2 the actions it covers · 3 required gate · 4 recent uses | gate `⊞ picker` `⚡` · recent use `→1.9` · back `↩5.2.2` |
| **5.2.2.2 2FA setup** | 1 TOTP QR + secret · 2 verify field · 3 backup codes | Copy secret `⚡` · Verify `⚡` · Copy backup codes `⚡` · Done `↩5.2.2` |
| **5.2.3 Learning & Memory** | 1 self-learning switch · 2 Sources · 3 batch interval · 4 Run one batch now · 5 Reset scan progress · 6 Drafts · 7 consolidation & forgetting · 8 export / clear link | switch `⚡` · Sources `→5.2.3.1` · interval `⊞ picker` `⚡` · Run batch `⚡` + inline result · Reset `⊞9.3` `⚡` · Drafts `→2.9` · Export / Clear `→5.5.1` · back `↩5` |
| **5.2.3.1 Sources** | 1 per-source switches — chat · SMS · meetings · files · 2 per-source last-scan state | switch `⚡`; when a permission is missing `→5.6.1` · back `↩5.2.3` |

The model status line under the chat composer (1.2) and the status dot on 5
are read-only mirrors of state owned by 5.2.1. They navigate here; they contain
no controls.

---

#### 5.3 · Safety & Privacy

| Route | Contents, in sequence | Controls |
|---|---|---|
| **5.3.1 Policy & Capabilities** | 1 per-action-class mode rows — AUTO · CONFIGURABLE · APPROVAL · STRONG_CONFIRMATION · 2 Hard-deny list · 3 Require biometric switch · 4 Recent decisions (last ~10) · 5 See all | class row `→5.3.1.1` · Hard-deny `→5.3.1.2` · biometric `⚡`+`⊞9.4` · decision `→1.9` · See all `→5.3.1.3` · back `↩5` |
| **5.3.1.1 Action class** | 1 class name · 2 the actions it covers · 3 mode selector · 4 consequence line in plain language · 5 recent decisions for this class | mode `⚡` — **loosening a mode** opens `⊞9.3` naming exactly what it will permit · decision `→1.9` · back `↩5.3.1` |
| **5.3.1.2 Hard-deny list** | 1 denied targets — apps, action types · 2 Add | Add `⊞ picker` `⚡` · Remove `⊞9.3` · back `↩5.3.1` |
| **5.3.1.3 Policy history** | 1 filters — outcome · class · date · 2 decision rows · 3 Export CSV | row `→1.9` · Export `⚡`+`⇱` · back `↩5.3.1` |
| **5.3.2 Privacy & Security** | 1 encryption status · 2 Sensitive-info redaction · 3 Require biometric (mirror — owner is 5.3.1) · 4 Security audit | Redaction `→5.3.2.1` · biometric row `→5.3.1` · Security audit `→5.3.2.2` · back `↩5` |
| **5.3.2.1 Redaction** | 1 switch · 2 category switches — numbers · addresses · IDs · health · 3 custom patterns · 4 preview | switch `⚡` · custom `⊞ editor` `⚡` · Test `⚡` inline preview · back `↩5.3.2` |
| **5.3.2.2 Security audit** | 1 Run · 2 findings — severity, what, remedy | Run `⚡` · finding remedy `→` its owning route · Export `⚡`+`⇱` · back `↩5.3.2` |

**Asymmetric confirmation.** Tightening a policy applies immediately.
Loosening one requires a confirmation that names what will become possible.
Safety defaults are cheap to adopt and deliberate to abandon.

*A11y* — the four modes are labelled text, never colour-coded chips alone;
recent-decision rows announce outcome first ("Blocked — open WhatsApp").

---

#### 5.4 · Connectivity

| Route | Contents, in sequence | Controls |
|---|---|---|
| **5.4.1 Sync** | 1 master switch · 2 transport status — LAN / relay · 3 What syncs · 4 paired peers · 5 Pair a device | switch `⚡` · What syncs `→5.4.1.1` · peer row `→5.4.1.2` · Relay `→5.4.1.3` · Pair a device `→5.1.2.1` · back `↩5` |
| **5.4.1.1 Categories** | 1 per-category switches — memory · agents · settings · goals · 2 size per category | switch `⚡` · back `↩5.4.1` |
| **5.4.1.2 Peer detail** | 1 name + fingerprint · 2 last synced · 3 per-peer permissions · 4 Sync now · 5 Forget | permission `⚡` · Sync now `⚡` + inline result · Forget `⊞9.3` `↩5.4.1` · back `↩5.4.1` |
| **5.4.1.3 Relay** | 1 enable switch · 2 relay address · 3 connection state · 4 Test | switch `⚡` · address `⊞ input` `⚡` · Test `⚡` inline result · back `↩5.4.1` |
| **5.4.2 Nearby Share** | 1 switch · 2 discovered devices · 3 active transfers with progress · 4 incoming accept / decline gate | switch `⚡` · device row `⚡ send` → `⇱ picker` · transfer `→5.4.2.1` · Accept `⊞9.3` `⚡` · Decline `⚡` · back `↩5` |
| **5.4.2.1 Transfer** | 1 file + peer · 2 chunk progress · 3 outcome | Cancel `⊞9.3` `⚡` · Open `⊞1.8` · back `↩5.4.2` |

The drawer sync badge is a read-only mirror of 5.4.1.

---

#### 5.5 · Data & Storage

| Route | Contents, in sequence | Controls |
|---|---|---|
| **5.5.1 Data & Backup** | 1 storage breakdown · 2 last backup · 3 Create backup · 4 Restore · 5 Export · 6 Clear data | Create `→5.5.1.1` · Restore `→5.5.1.2` · Export `→5.5.1.3` · Clear `→5.5.1.4` · back `↩5` |
| **5.5.1.1 Create backup** | 1 what is included · 2 password field + strength meter carrying a **text label**, not colour alone · 3 destination · 4 Create | destination `⇱ picker` · Create `⚡` + progress + result · Done `↩5.5.1` |
| **5.5.1.2 Restore** | 1 Choose file · 2 password · 3 manifest preview · 4 overwrite warning · 5 Restore | Choose `⇱` · Restore `⊞9.3` + `⊞9.4` `⚡` · success → Done `↩5.5.1` · failure → named reason + Retry `⚡` |
| **5.5.1.3 Export** | 1 what to export — memory JSON · policy CSV · execution CSV · conversations · 2 Export | Export `⚡`+`⇱` · back `↩5.5.1` |
| **5.5.1.4 Clear data** | 1 scope checkboxes — conversations · memory · drafts · audit · everything · 2 consequence text · 3 Clear | Clear `⊞9.3` — **type-to-confirm** for "everything" — then `⊞9.4` `⚡` · back `↩5.5.1` |

---

#### 5.6 · System

| Route | Contents, in sequence | Controls |
|---|---|---|
| **5.6.1 Permissions** | 1 permission rows with grant state inline — Screen Access · Notifications · SMS · Calendar · Contacts · Microphone · Camera · 2 App permissions | row `⇱ system flow` → `⚡ re-read` · "What this enables" `⊞` inline · App permissions `→5.6.1.1` · back `↩5` |
| **5.6.1.1 App permissions** | 1 per-app rows with allowed action classes | row `⊞ per-app sheet` `⚡` · Block app `⚡` — mirrored into 5.3.1.2 · back `↩5.6.1` |
| **5.6.2 Updates** | 1 pending staged mutations · 2 applied history · 3 Check for bundles | pending row `→5.6.2.1` · Check `⇱ picker` `⚡` · history row `→5.6.2.1` · back `↩5` |
| **5.6.2.1 Update detail** | 1 what changes · 2 diff view · 3 risk note · 4 Apply / Discard | Apply `⊞9.4` `⚡` · Discard `⊞9.3` `⚡` · back `↩5.6.2` |
| **5.6.3 Advanced (Dev)** | 1 Feature flags · 2 Dev console · 3 Diagnostics — collapsed by default, expanded in debug builds | `→5.6.3.1` · `→5.6.3.2` · `→5.6.3.3` · back `↩5` |
| **5.6.3.1 Feature flags** | 1 flag rows showing default vs current | flag `⚡` · Reset all `⊞9.3` `⚡` · back `↩5.6.3` |
| **5.6.3.2 Dev console** | 1 tabs — State · Logs · DB · Triggers · Files · 2 tab content | tab `⚡` · log row `⊞ detail` · DB table `⊞ rows` · trigger Run `⚡` · file `⊞1.8` · Export `⚡`+`⇱` · back `↩5.6.3` |
| **5.6.3.3 Diagnostics** | 1 metrics · 2 crash reports · 3 Export | crash row `→9.5` · Export `⚡`+`⇱` · Clear `⊞9.3` `⚡` · back `↩5.6.3` |

The dev console remains reachable by shake gesture on Android; the gesture is a
shortcut to 5.6.3.2, not a separate surface.

---

### 6.8 · Section 9 — Global overlays

These have no back target of their own; dismissing returns to the caller.

| Route | Contents, in sequence | Controls |
|---|---|---|
| **9.1 Command palette** (expanded only, Ctrl/Cmd+K) | 1 input · 2 grouped results — Conversations · Routes · Actions · Settings | result `→` its route, or `⚡` its action · Esc `↩` |
| **9.2 Blocking error** | 1 what failed · 2 what still works · 3 remedies | Retry `⚡` · Restore from backup `→5.5.1.2` · Clear and restart `⊞9.3` `⚡` · Export diagnostics `⚡`+`⇱` |
| **9.3 Confirm dialog** | 1 what will happen · 2 whether it is reversible · 3 Confirm / Cancel | Confirm `⚡` — type-to-confirm variant for irreversible actions · Cancel `↩` |
| **9.4 Biometric prompt** | platform seam | success `⚡` · fail or cancel `↩`, audited |
| **9.5 Crash reporter** | 1 stack + context · 2 Copy · 3 Dismiss | Copy `⚡` · Save `⇱` · Dismiss `↩` |

*A11y* — every overlay is a focus scope: focus enters on open, is contained
while open, and returns to the invoking control on dismiss. Never trapped: Esc
and the platform back gesture always close.

### 6.9 · The two documented shortcut edges

Everything else obeys "back = parent in the tree". These two routes are entered
from more than one place, and each shows a caller breadcrumb:

1. **5.4.1 Sync → 5.1.2.1 Pair a device.** Pairing is owned by Devices; Sync
   links to it. Back returns to the caller.
2. **0.5 Voice → 5.1.3.2 / 5.1.3.3.** The wake-word and voice-enrolment routes
   are reused inside onboarding. In that variant they render **Continue**
   instead of a back chevron and return to 0.5.

---

## 7 · Content blocks & the artifact panel

An assistant message can stack several blocks with 8 dp spacing. A plain text
paragraph is never boxed — only special content gets a box.

| Block | Rendering | A11y contract |
|---|---|---|
| **Copyable text** | bordered `surface` card, 12 dp radius, Copy button beneath | Copy announces "Copied"; the card has a `role` and an accessible name |
| **Code** | dark box, mono 14 sp, header with language label + Copy | language announced; horizontal scroll inside its own container |
| **Image** | rounded card at natural width, caption below | `contentDescription` from the caption or alt; tap `⊞1.7` |
| **Image generation** | prompt line, progress (shimmer or %), Cancel; collapses into Image on success; red error line + Retry on failure | progress is a polite live region; static placeholder under reduced motion |
| **Documents** | hairline card listing rows — type icon, filename, size, page/word count | each row is one focus stop with a full accessible name |
| **MCQ / choice** | bordered card, question line, 2–4 option rows, last option always `Custom…` | `role = RadioButton`; the question is the group's accessible name; one MCQ per message |
| **Thought** | collapsible; chevron header "Thinking"; expanded shows reasoning in `textSecondary` at 13 sp on `surfaceSelected` | polite live region **only while streaming**; never steals focus; collapse state persists per conversation |
| **Artifact chip** | compact chip — title, type, size | opens `⊞1.3`; the chip's name includes the artifact type |
| **Step** | named action, collapsed when successful, expandable | status in `stateDescription`; failures are assertive |
| **Approval** | typed action summary, target, risk chip (icon + word), Approve / Reject, "why" line when blocked | assertive live region on appearance; Approve is not the default focus |

### 7.1 · Artifact panel (1.3)

Output crosses into the panel when it exceeds a length threshold or is of a
structured kind — a document, report, code file, CSV export, or extracted text.
The thread keeps a chip; the panel keeps the content, persists across messages,
and is re-openable. On compact it is a full-screen sheet; on medium a 40% side
sheet; on expanded the third pane.

### 7.2 · Step block (1.9)

The unit of FLOW C. Backed by `AgentStream` (`agents/AgentStream.kt`) — a typed
`TOKEN` / `STATUS` / `ARTIFACT` / `ERROR` bus that already exists and whose only
consumer today renders `takeLast(5)`. Expanded, a step shows its typed target,
risk class, the policy rule that matched, which rung of the capability ladder
executed it, and the result.

### 7.3 · Two corrections to the current chat surface

- **Bubble roles are inverted.** `MainActivity.kt:507` renders the *assistant*
  as light text on near-black and the *user* as dark text on light. Both
  reference products do the opposite, and so does this spec: assistant on
  `surface`, user on `surfaceSelected`.
- **Streaming is specified but unused.** `ModelProvider.stream()` exists in
  `shared/model-api`; `MainViewModel.kt:594` calls `complete()`, so replies
  arrive all at once and there is no stop button. Phase 3 wires the stream.

---

## 8 · Shared component library

Built once in `commonMain`, used by all four bodies. Every component ships
semantics, focus behaviour, and a 44 dp minimum target from its first commit.

**Shell** — `NavDrawer` · `Sidebar` (expanded) · `NavRail` (medium) ·
`TopBar` · `MenuBar` (desktop) · `CommandPalette` · `RouteScaffold` (title,
back, empty/loading/error slots).

**Chat** — `ChatBubble` · `StreamingText` · `TypingIndicator` · `Composer` ·
`AttachmentChip` · `ConversationRow` · `ModelChip` · `ModelSheet` ·
`ModelStatusLine` · `SuggestionGrid` · `DegradedBanner`.

**Blocks** — `CopyableTextBox` · `CodeBlock` · `ImageBlock` · `ImageGenBlock` ·
`DocumentsContainer` · `McqCard` · `ThoughtContainer` · `ArtifactChip` ·
`ArtifactPanel` · `StepBlock` · `ApprovalCard` · `BlockedCard` ·
`FailureCard` · `CopyButton`.

**Lists & data** — `SearchBar` · `SectionHeader` · `ChevronRow` · `StatusChip` ·
`TimelineItem` · `AmberTaskCard` · `AgentCard` · `SkillRow` · `PersonRow` ·
`GraphCanvas` + `GraphListFallback` · `ListSwipeActions` (always paired with an
overflow equivalent).

**Settings** — `SettingsGroup` · `SettingsRow` · `EditValueSheet` ·
`ChoiceChips` · `TagEditor` · `ProfileHeader` · `DeviceCard` ·
`PairedDeviceRow`.

**Pairing** — `PairRoleCard` · `PairQrCard` · `SasConfirmCard` ·
`NearbyDeviceRow` · `PairSuccessCard`.

**Voice** — `VoiceEnrollSheet` · `ListeningIndicator` · `TranscriptPreview`.

**Overlays** — `Sheet` · `ConfirmDialog` (with a type-to-confirm variant) ·
`BiometricGate` · `EmptyState` · `ErrorState` · `LoadingState`.

---

## 9 · Platform seams

The only `expect`/`actual` surface. Everything else — layout, state, theming,
navigation, business logic — is shared Kotlin in `commonMain`.

`PlatformSafeArea` · `SystemBackHandler` · `Haptics` · `Clipboard` ·
`PermissionLauncher` · `BiometricPrompt` · `FilePicker` · `SaveFilePicker` ·
`VoiceRecognizer` · `ScreenCaptureRequest` · `BarcodeScanner` ·
`ReducedMotion` · `FontScale` · `SystemTheme` · `WindowState` (desktop) ·
`KeyboardShortcuts` (desktop) · `MenuBarHost` (desktop).

The last five are additions this spec requires: reduced-motion and font-scale
queries are needed by §3, and the desktop trio by §5.2.

---

## 10 · Delivery sequence

Each phase is a mergeable slice with Android staying green, and each carries the
§3.6 accessibility gate. A phase is not done when it looks right; it is done
when it traverses.

1. **Tokens, theme, and a11y primitives.** Shared `NewaxTheme` with the
   corrected palette, type scale, spacing, and shapes; reduced-motion and
   font-scale handling; semantics helpers. Resolves the 19-file token
   duplication, the contradictory `styles.xml`, and the
   `@style/Theme.NewaxNewax` / `android:label="Newax Newax"` manifest defects.
2. **Component library.** Built accessibility-first per §8.
3. **Chat shell and FLOW B.** Routes 1.1, 1.2, 1.4, 1.10, 1.11; wire the unused
   `stream()`; composer states.
4. **FLOW C.** Approval moves inline; step blocks (1.9); artifact panel (1.3).
5. **Sections 2–4.** Memory, Tasks, and Capabilities routes.
6. **Section 5.** The settings subtree, in the §6.7 order.
7. **Expanded layout.** Three panes, menu bar, shortcuts, command palette
   (9.1); macOS folds into the same shell.
8. **iOS body.** CMP target plus the §9 seams, verified on a macOS host.
9. **Onboarding (0.x).** Last, because it links into 5.1.3.2, 5.1.3.3, and
   5.2.1.1, which must exist first.

---

## 11 · Coverage matrices

These are the checks that keep the spec honest. They are filled in as each
phase lands.

### 11.1 · Control resolution

Every control named in §6 carries exactly one mark from the §6.0 notation
table. An unmarked control is a specification bug, not a design choice.

### 11.2 · Reachability

Every route in §6.1 is reachable from 1.2 in ≤ 3 pushes, has at least one
inbound edge, and declares a back target (overlays excepted). No orphans.

### 11.3 · R13 — no headless capability

Every capability shipping today keeps a route:

| Today | New route |
|---|---|
| Chat (`MainActivity` ChatScreen) | 1.2 |
| Memory screen | 2.1 |
| People screen | 2.4 → 2.5 |
| Drafts screen | 2.9 |
| Meeting screen | 2.10 → 2.11 |
| Agent Memory screen | 2.12 |
| Goals screen | 3.1 → 3.2 |
| Agents screen | 3.5 → 3.6 |
| Skills screen | 3.8 → 3.9 |
| Capabilities screen | 4.1 → 4.2 |
| Policy settings + Policy history | 5.3.1 → 5.3.1.1 / 5.3.1.3 |
| App permissions screen | 5.6.1.1 |
| Automation settings + 2FA | 5.2.2 → 5.2.2.1 / 5.2.2.2 |
| Learning settings | 5.2.3 → 5.2.3.1 |
| Ambient Mode card | 5.1.3.1 |
| Wake-word service | 5.1.3.2 |
| Voice authentication | 5.1.3.3 |
| Ghost Mode | 5.2.2 |
| Sync screen | 5.4.1 |
| Pairing flow | 5.1.2.1 |
| Nearby Share screen | 5.4.2 → 5.4.2.1 |
| Backup & Restore screen | 5.5.1 → 5.5.1.1 / 5.5.1.2 |
| Updates screen | 5.6.2 → 5.6.2.1 |
| Dev console (5 tabs, shake) | 5.6.3.2 |
| Crash reporter activity | 9.5 |
| Model import + status | 5.2.1 → 5.2.1.1 (1.2's status line is a mirror) |
| Profile fields (no screen today) | 5.1.1 |
| Device identity + telemetry | 5.1.2 |
| Desktop `StatusScreen` / `AppsScreen` | 4.1 / 4.3 |
| Desktop `GoalsScreen` | 3.1 |
| Desktop `PolicyScreen` | 5.3.1 |
| Desktop `AuditScreen` | 5.3.1.3 |
| macOS `SyncScreen` | 5.4.1 + 5.1.2 |

### 11.4 · Four-body matrix

Each route × {Android, iOS, Windows, macOS} = shared / adapted / not
applicable, with a stated reason for every "not applicable" (for example, 1.10
Voice capture on Windows depends on a dictation seam that may not exist).

### 11.5 · Accessibility

The §3.6 checklist, run per phase.

---

## 12 · Open decisions & risks

1. **Compose Multiplatform version.** Desktop pins 1.7.1 while the AGENTS.md
   baseline says 1.11.1 (docs/OVERVIEW.md §B10, drift #1). Shared UI cannot
   start until this is settled deliberately. Resolved by the phase-1 PR.
2. **Conversation persistence.** There is no conversation or message table
   among the 24 Room DAOs; messages today are an in-memory
   `mutableStateListOf` wiped on process death (`MainViewModel.kt:97`). Routes
   1.1, 1.6, 1.11, and 1.12 all depend on a persistence schema. It is a
   prerequisite for phase 3 and gets its own PR.
3. **Navigation.** Use a hand-rolled drawer plus push stack in shared code — no
   new dependency — unless a suitable library is already present. Check before
   adding.
4. **Risk vocabularies.** Three coexist: `MainActivity.kt`'s local three-tier
   `Risk` enum, the shared `RiskLevel { LOW, MEDIUM, HIGH, CRITICAL }`, and
   `PolicyMode { AUTO, CONFIGURABLE, APPROVAL, STRONG_CONFIRMATION }`. §6 is
   written against `PolicyMode`. Unifying them is a phase-4 prerequisite.
5. **Model state vocabularies.** `ModelProvider.ModelState` and
   `ModelManager`'s six-value enum coexist. The three-state green / yellow /
   red status line must be derived from one of them, decided in phase 1.
6. **Apple compilation.** Apple targets cannot be compiled from the Linux
   sandbox; phase 8 requires a macOS host with Xcode.
7. **Scaffold order.** Screens do not move into shared code while the Android
   app must stay green at every slice.
8. **Scope guard.** This document is a specification. PLAN is never EXECUTE — a
   design doc grants no authority to skip policy or approval in the
   implemented app.
