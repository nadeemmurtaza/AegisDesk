# Newax Aegis — Shared Android/iOS UI Design Plan (ChatGPT-style)

A plan, not code. This document specifies the design for the mobile app —
**one design, one codebase, on both Android and iOS** — modeled on the ChatGPT
mobile app's information architecture and visual language. It maps every
existing Newax Aegis capability onto the new layout (R13: no headless
capability), and it is the spec that a future implementation PR builds against.

---

## 1. Goal & hard constraints

- **One design on both platforms**: a single shared Compose Multiplatform (CMP)
  UI in `commonMain` (Android + iOS render the same screens). This is the
  architecture `ARCHITECTURE.md` already declares for `apps/ios` ("Compose
  Multiplatform iOS app, shared UI") — this plan defines what that UI is.
- **ChatGPT-like pages**: side-menu app shell (drawer), **chat-first** — the
  chat thread is the single main surface; everything else lives behind the
  side menu. Searchable history lists, minimal full-screen detail flows,
  near-black-on-off-white palette, thin borders, generous whitespace, no heavy
  chrome.
- **R13**: every user-facing capability shipped today (chat, memory, goals,
  people, agents, skills, policy, sync, nearby share, backup, permissions,
  updates, dev console) keeps a reachable, usable screen in the new IA.
- **Platform-free shared code**: the shared UI lives in `commonMain` with
  `expect`/`actual` seams only where the OS demands (safe areas, back gesture,
  haptics, clipboard, permission launchers, biometric prompt, model import
  picker, voice recognition).
- **Branding (R14)**: the product name is exactly **Newax Aegis** in every
  title, label, and empty state; no bare `Aegis` anywhere user-visible.

## 2. Design language (extends the current tokens)

The Android app already uses a ChatGPT-like palette; codify it once in a shared
theme instead of per-file `Color(...)` constants (today they live inline in
`MainActivity.kt` and mirror in desktop `NewaxTheme.kt`):

| Token | Value | Usage |
|---|---|---|
| `bg` | `#F7F7F5` | app background |
| `surface` | `#FFFFFF` | cards, sheets, composer |
| `surfaceSelected` | `#EFEFEC` | selected rows/chips |
| `primary` | `#1B1B1A` | text, icons, buttons |
| `textSecondary` | `#686864` | secondary text |
| `textTertiary` | `#8D8D87` | hints, timestamps |
| `border` | `#D8D8D3` | hairlines, dividers |
| `accent` | `#10A37F` (ChatGPT green) | online status, focus ring, verified |
| `warning` | `#F59E0B` | policy-blocked tasks |
| `error` | `#EF4444` | failures, hard-deny |

- **Dark mode**: same scale inverted (bg `#212121`, surface `#171717`,
  primary `#ECECEC`, border `#2E2E2E`) — a later slice, behind system theme.
- **Shape**: 12 dp cards / 20 dp sheets, 8 dp grid spacing, 1 dp hairlines.
- **Type**: system font; 28/22/17/15/13/12 scale; titles Semibold, body Regular.
- **Motion**: 200 ms fades/slides for bubbles; composer elevation on focus;
  sheet spring on open. No decorative animation.

## 3. Information architecture

```
┌────────────────────────────────────────────────────────────┐
│  Side menu (drawer — hamburger, top-left)      │
│  ▸ Chat · Memory · Tasks · Settings                      │
└────────────────────────────────────────────────────────────┘

Chat (the main surface — no bottom nav, no tab chrome)
├── Conversation list ──(tap)──▶ Chat thread
│     ├─ search bar (top)              ├─ message bubbles (streaming)
│     ├─ "New chat" button             ├─ approval card (inline, in-thread)
│     ├─ pinned: Today / Yesterday     ├─ composer: text + mic + add files
│     └─ conversation rows             └─ model chip (tap → model sheet)
└── overflow menu per row: rename / delete / export

Memory (drawer route)
├── Search bar (semantic + keyword, hits top results)
├── Timeline of memory entries (grouped by day)
└── Sections: People · Facts · Procedures · Connections (graph)

Tasks (drawer route)
├── Goals (board/list, policy-blocked amber)
├── Agents (installed, import .aegis-agent)
└── Skills (registry, enable/disable)

Settings (drawer route) — THE single settings page: every setting in the app
lives here, exactly once, grouped by logical domain in the fixed sequence
§4.5 (General → Model & Intelligence → Safety & Privacy → Connectivity →
Data & Storage → System). No settings surface anywhere else (see §4.5
rules).
```

Every non-chat section (Memory, Tasks, Settings and its sub-pages, Sync,
Nearby Share, …) is a **full-screen route pushed from the side menu**, returned
from with a back chevron to the chat surface. The chat surface is the home —
never a tab bar.

## 4. Page specs (ChatGPT-mapped)

### 4.1 Chat — Conversation list
- Top: large "Newax Aegis" title; right: "New chat" (`+`) button.
- Search field (rounded, grey) filters conversations by title + first message.
- Rows: conversation title, last-message preview (2 lines max), relative
  timestamp, hover/overflow menu (rename · delete · export). Selected row =
  `surfaceSelected`.
- Empty state (the new-chat welcome, centered on the page):
  - Brand mark + "Your private, on-device assistant." headline.
  - **6 first-message suggestion buttons** centered beneath it — tapping one
    sends it as the first message and starts the thread (no typing needed):
      1. "What's on my screen?" — read the current screen
      2. "Open an app" — launch an app by name
      3. "Draft a reply" — compose a reply draft
      4. "What do you remember?" — read back encrypted memory
      5. "Set a reminder" — schedule a reminder/alert
      6. "Find a file" — locate a document/photo/video
  - Layout: 2-column grid on phones, 3-column on wide screens; each button is
    a pill/rounded card (`surface`, hairline border, 12 dp radius) with icon +
    label, `surfaceSelected` on press. Tap = insert the prompt into the
    composer and submit immediately.
  - Buttons map to real intents (FEATURES.md §1): screen-read → `search_info`/
    accessibility tree, open app → `open_app`, draft → drafts, memory →
    `create_note`/memory read, reminder → `set_reminder`, file → `find_file`.
- New chat starts an empty thread with this suggestion set; the list reorders
  by recency.

### 4.2 Chat — Thread (the core screen — the app's main surface)
- Header: hamburger (opens side menu), conversation title, back chevron to the
  list, and **top-right corner: a "New chat" button** — icon: **a pencil in
  a square** (square-pen / pencil-square glyph) — always visible once a chat
  has started; tapping it opens a fresh empty
  thread immediately (same behavior as the list's New chat), discarding
  nothing (current conversation stays in the list). Beside it the overflow
  menu (rename/delete/export · model picker).
- Messages: assistant bubbles left (surface, hairline), user bubbles right
  (primary text on `surfaceSelected`); avatars/brand dot for assistant.
- **Rich content blocks inside a message** — an assistant message can render
  several stacked blocks; each special block gets its own box:
  - **Copyable text box** — bordered `surface` card (hairline, 12 dp radius),
    text at body size, with a **Copy button directly beneath it** (copy icon +
    "Copy"; on tap → clipboard write + brief "Copied ✓" feedback, ~1.5 s).
    Used for generated snippets, phone numbers, addresses, quotes.
  - **Code output box** — dark box (`#171717` bg, light mono text, 14 sp
    monospace, 12 dp radius), header row with language label (kotlin / bash /
    json / …) + a **Copy button** on the right of the header; long lines wrap
    or scroll horizontally; used for code, shell output, config, SQL, JSON.
  - **Image box** — rounded card (12 dp radius) rendering the image at natural
    width (max 80% of bubble), with filename/alt caption below; tap opens a
    full-screen viewer (pinch-zoom, save/share actions); used for generated
    images, attached screenshots, OCR'd captures.
  - **Image generation block** — an in-thread working block shown on the
    assistant side while an image is being generated: the prompt line, a
    progress state (indeterminate shimmer, or % when the engine reports
    progress), and a Cancel affordance; on success it collapses into the
    Image box result (natural width, caption = the prompt, tap → full-screen
    viewer with save/share); on failure a red error line with a Retry
    button. A user's image request is a plain message — only the generated
    output carries this block.
  - **Documents container** — a compact hairline card listing small documents
    (pdf / docx / xlsx / txt / md / csv …) as rows: type icon + filename +
    size (plus page/word count when known); tap opens the document viewer,
    row overflow offers Copy / Share; up to ~8 documents per container,
    scrollable when more. Used for generated reports, extracted text, and
    attached files the model summarizes or cites.
  - **MCQ / choice container** — rendered whenever the assistant needs a
    decision mid-thread (ambiguous request, "which app/file/person?",
    confirm an option): a bordered `surface` card with the question line and
    2–4 selectable option rows (radio-style; tap = select and submit
    immediately, tap-then-confirm for risky choices). The **last option is
    always `Custom…`**, which expands into a single-line text input
    (autofocus, soft keyboard) for a free-text answer; Cancel dismisses the
    question back to the message. The chosen answer is sent as the user's
    next message; only one MCQ per message.
  - **Thought container** — a collapsible block showing the assistant's
    reasoning for that reply, rendered above the answer while thinking (and
    kept once complete): header row `⟶ Thinking` / `Thoughts` with a chevron,
    collapsed by default to a single line; expanded shows the step-by-step
    reasoning in `textSecondary` at 13 sp, with a subtle `surfaceSelected`
    background and 12 dp radius; it never interrupts the stream — the
    thinking text streams inside it while the model reasons, then the answer
    streams below. Tapping the header toggles; collapsed state is remembered
    per conversation.
  - Blocks stack with 8 dp spacing inside the bubble; a plain text paragraph
    is not boxed (only special content gets a box).
- **Streaming**: partial text renders inline; typing indicator (3 dots) while
  the model is busy; stop button replaces the send button while streaming.
- **Approval card renders inside the thread** exactly where the action was
  proposed (R2: PLAN is never EXECUTE): typed action summary, target app/UI
  element, risk level chip (AUTO/CONFIGURABLE/APPROVAL/STRONG_CONFIRMATION),
  Approve / Reject buttons, "why" line when policy blocked the action.
- Composer (bottom, sticky, `surface` + hairline top, 20 dp radius) — the only
  input interface; deliberately minimal:
  - **left: add files** — paperclip button → file picker (multi-select;
    images/docs/audio); selected files render as thumbnail chips above the
    field (tap chip to remove); attached files go with the message
  - center: text field (grows to 5 lines)
  - **right: mic** (offline Vosk / system recognizer) ⇄ send / stop
  - no extra buttons: commands stay natural language (`open WhatsApp`, `remind
    me`, `send file` — FEATURES.md §1); model switching lives in the thread
    header overflow → **model sheet** (available models, size, provider,
    unload/reload, import).
- **Model status line — directly under the composer**: the model name with a
  small status dot, always visible on the chat surface:
  - **Green dot** — model loaded and ready (all set); e.g. `• Gemma 3 1B`
  - **Yellow dot** — model is loading, installing, or downloading (`.litertlm`
    import in progress, engine initializing, provider warming up)
  - **Red dot** — failed to load or install (import verification failed,
    engine error, missing model); tap opens the **model sheet** to retry/import
  - Style: 8 dp dot + 13 sp name in `textTertiary`, centered under the
    composer field, 4 dp above the field bottom; tap anywhere on the line
    opens the model sheet. No model present yet → show `No model — tap to
    import` with a red dot (deterministic command engine still works).
- `then`-chained plans appear as numbered step chips in the message; each step
  gets its own approval card when reached.

### 4.3 Memory (drawer route; folds Memory + People screens)
- Semantic search bar with a "vector" affordance; results rank by relevance
  (VectorMemorySearch) with matched text highlighted.
- Timeline grouped by day (`Today / Yesterday / <date>`), each entry shows
  source (chat, SMS, meeting, file) and category chip (personal, business,
  relationships, goals, pain_points, rules).
- Sections: People (from PeopleScreen/PersonRegistry — tap → person detail with
  profile, relationship, commitments, communication history), Facts
  (knowledge-graph triples), Procedures (learned UiProcedures with success
  counts), Connections (graph edges).
- Entry overflow: pin, edit, delete, "forget" (tombstone).

### 4.4 Tasks (drawer route; folds Goals + Agents + Skills)
- Goals: active goal cards (title, progress, per-task statuses, "Recent runs"
  from execution audit), policy-blocked tasks in amber with a direct link to
  Policy modes; + New goal (planner pre-flight warnings shown up front).
- Agents: installed agents list (name, domain, version, state), import
  `.aegis-agent` (deny-by-default), per-agent permission grants.
- Skills: registry list with enable/disable and OFFLINE_OK / REQUIRES_ONLINE /
  DEFER tags.

### 4.5 Settings — the single settings page (no missing, no duplication)

**One page, one place.** Every setting in the app lives on this page — nothing
settings-like is reachable from anywhere else. Chat's overflow menu keeps only
conversation actions (rename/delete/export) + the transient model sheet;
Memory / Tasks / People are content pages whose actions are data actions
(pin/edit/delete/forget), never configuration.

Settings are **grouped by logical domain** — related groups sit together under
one category header, each group a section in a fixed sequence, top → bottom
(ChatGPT Settings style: category headers, section headers, chevron rows,
inline switches):

**1 · General** — who this device is, how it listens
  1. **Profile & Devices** — one section header with two chevron rows:
     **Profile** → §4.5.2 (name, language, style, persona, wake word,
     interests/dislikes) and **Devices** → §4.5.3 (this device + paired
     devices). The two pages own every profile/device setting; sub-lines
     summarize the profile name + device count.
  2. **Ambient & Voice** — hub §4.5.5 with three pages: **Ambient Mode** →
     §4.5.6, **Wake word** → §4.5.7, **Voice authentication** → §4.5.8.
     One owner of mic-based input (absorbs today's loose Ambient Mode
     card).
  3. **About** — version, storage, network (offline — no data sent),
     licences.

**2 · Model & Intelligence** — the brain: which model, how it acts, what it
learns
  4. **Offline AI Model** — the one model control: status dot (same 3-state
     green/yellow/red as the composer line), name / provider / size,
     **Import model** (`.litertlm` / GGUF picker), Benchmark, Unload /
     Reload. The composer `ModelStatusLine` is a read-only status mirror
     that navigates here — this page owns the controls (explicitly not
     duplication).
  5. **Automation** — per-group automation toggles (app, messaging, media,
     navigation, …) with TOTP/2FA + biometric gating for dangerous groups
     (today's `AutomationSettingsSection` + `AutomationToggle` groups);
     Ghost Mode toggle (accessibility service).
  6. **Learning & Memory** — self-learning engine (today's
     `LearningSettingsSection`): enabled, sources (chat / SMS / meeting /
     files), batch interval, Run one batch now, reset scan progress, clear
     drafts; memory consolidation, forgetting engine; memory export / clear
     links → Data & Storage. Nothing memory-config lives elsewhere.

**3 · Safety & Privacy** — guardrails: what the assistant may do, what it
may know
  7. **Policy & Capabilities** — per-action-class modes (AUTO / CONFIGURABLE /
     APPROVAL / STRONG_CONFIRMATION), hard-deny list, require-biometric
     switch, recent decisions (last ~10) + See all → Policy history
     (full-screen route). The only place policy modes are set (today's
     `PolicySettingsSection` + `CapabilitiesScreen` policy block fold in
     here).
  8. **Privacy & Security** — sensitive-info redaction, biometric
     requirement, security audit, encryption status.

**4 · Connectivity** — how this device talks to other devices
  9. **Sync** — master toggle, LAN / relay status, per-category toggles
     (memory, agents, settings); its pairing row is a shortcut that pushes
     the Pair-a-device page §4.5.4 (the only pairing route).
  10. **Nearby Share** — BLE + WiFi-Direct transfers to nearby devices.

**5 · Data & Storage** — your data
  11. **Data & Backup** — Backup & restore (AES-256-GCM, Google Drive /
      device file), memory export / clear, CSV audit export.

**6 · System** — platform-level control
  12. **Permissions** — Screen Access, Inbox (Notifications), SMS, Calendar,
      Contacts, Mic, per-app App Permissions; each row shows grant state
      inline and opens the system or in-app permission flow.
  13. **Updates** — model / agent update channel (offline bundle checks).
  14. **Advanced (Dev)** — feature flags, developer console (shake-to-open),
      diagnostics/metrics; shown collapsed by default, expanded in debug
      builds.

**Grouping rules** — connectivity is its own domain, and nothing else bleeds
into it: Sync + Nearby Share are the only Connectivity groups (a network
status line in Profile & Devices is a mirror, not a setting); learning exports
link to Data & Storage rather than duplicating backup there; permissions stay
in System because they are platform grants, not assistant behavior.

**Rules that make it "no missing, no duplication":**
- Every capability with a setting has exactly one row here; rows never repeat
  across groups, and no setting is scattered onto another screen.
- Status indicators elsewhere (composer model line, drawer sync badge, People
  entry) are read-only mirrors of state owned by this page — they navigate
  here, they do not contain settings.
- Deep sub-pages (policy history, sync pairing, backup flow, model import
  sheet, dev console) are full-screen routes pushed from their group;
  back always returns to this page.

### 4.5.1 General — page design (category 1)

The General category renders inline on the Settings page (it is shallow enough
not to need a pushed route). Fixed sequence, top → bottom; every row is a
`SettingsRow`. Three sections:

**A · Profile & Devices** — one section header with two chevron rows:
**Profile** → §4.5.2 (name, language, style, persona, wake word,
interests/dislikes) and **Devices** → §4.5.3 (this device + paired
devices). Sub-lines summarize `Newax · 2 devices · Encrypted on device`.
Nothing here is a separate inline row anymore — all profile/device settings
moved into the two pages (not deleted).

**B · Ambient & Voice** — one section header with three chevron rows:
**Ambient Mode** → §4.5.6, **Wake word** → §4.5.7, **Voice
authentication** → §4.5.8. Sub-lines reflect live state (`Off` /
`Listening for 'Hey Newax'` / `Enrolled`). All mic-input settings moved
into the three pages (not deleted); nothing is inline here anymore.

**C · About** — information only; no settings live here
8. **Version** — read-only (0.1.0).
9. **Storage & encryption** — read-only `1.2 GB · Encrypted on device
   (AES-256-GCM)`; taps → Safety & Privacy → Privacy & Security (the owner
   of encryption status — mirror, not duplication).
10. **Network** — read-only badge `Offline — no data sent` (green).
11. **Privacy policy** — row → in-app privacy policy screen.
12. **Licences** — row → open-source attributions list (LiteRT · Vosk ·
    SQLCipher · ML Kit · MediaPipe · zxing · Rhino · jmdns · OkHttp · JNA ·
    coroutines · Room · Compose).

Sequence logic: identity first (who), then input (how it listens), then
information (About, always last). Ambient & Voice sits in General — it is
about this device's own mic, not about other devices (Connectivity owns
Sync/Nearby Share only).

### 4.5.2 Profile — page design

Full-screen route pushed from Settings → General → **Profile**; back chevron
returns to the Settings page (per §4.5 sub-page rule). The single owner of
identity editing — no other screen changes name, language, style, persona,
wake word, or interests; chat greetings and CSV export headers read these
values.

Every field is backed by `ProfileManager`
(`apps/android/.../engine/manager/ProfileManager.kt`) and stored
**encrypted** via `EncryptedMemory` (raw scalar keys + `interests`/`dislikes`
categories) — so the page shows a brief loading state on entry while the
encrypted values are read, and every edit saves immediately (inline save
feedback, no Save button).

**Header** — `ProfileHeader`: avatar (initial of profile name on `primary`,
brand mark), profile name, persona sub-line. Tapping the name opens the Name
edit sheet (same as row 2).

**A · Identity** — who you are
1. **Name** — `SettingsRow` → `EditValueSheet`; used in greetings + CSV
   headers. Empty allowed → header falls back to "Newax".
2. **Language** — row → searchable language `Sheet` (default `en`); drives
   the "Respond in <lang>" prompt addition.
3. **Timezone** — row → picker sheet (default = device timezone).

**B · Assistant style** — how it talks to you
4. **Communication style** — `ChoiceChips` Formal / Casual / Balanced /
   Technical (default Balanced); maps 1:1 to
   `ProfileManager.CommunicationStyle`.
5. **Response length** — `ChoiceChips` Short / Medium / Long / Adaptive
   (default Medium); maps to `ProfileManager.ResponseLength`.
6. **Persona** — text row → `EditValueSheet` (default `helpful assistant`);
   shown under the header.

**C · Voice** — the phrase it listens for
7. **Wake word** — text row → `EditValueSheet` (default `Newax`). **Value
   lives here; the on/off switch stays in Ambient & Voice §4.5.1 B.6** —
   this page owns the phrase, Ambient & Voice owns the listening toggle
   (explicit non-duplication).

**D · Personalization** — what it knows you like
8. **Interests** — `TagEditor` chips (add / remove via
   `ProfileManager.addInterest/removeInterest`); the top 5 go into the
   model prompt.
9. **Dislikes** — `TagEditor` chips (`addDislike/removeDislike`); matched
   to steer topics away.

**How the assistant uses this** — a live preview card at the bottom renders
`ProfileManager.systemPromptAdditions()` (the actual string the model is
told: `User's name: … · Respond in … · style/length rules · interests`),
updating in real time as fields change. A mirror, not a setting.

States: loading spinner while `EncryptedMemory` values are read; tag edits
and chips apply instantly with inline feedback; empty name → "Newax"
fallback in the header and the preview.

Sequence logic: identity (who), style (how it talks), voice (the phrase),
personalization (what it knows). Devices are a sibling page (§4.5.3), not
part of this one.

### 4.5.3 Devices — page design

Full-screen route pushed from Settings → General → **Devices**; back chevron
returns to the Settings page. Two sections: **This device** (the phone/tablet
running the app) and **Paired devices** (the other devices synced to it).
This page owns device identity + listing only; pairing/transfer lives in
Connectivity → Sync (no duplication).

**A · This device** — read-only telemetry from `ConnectivityDashboard`
(snapshot) + device identity; the only editable row is the device name.

1. **Device name** — editable `SettingsRow` → `EditValueSheet`; this is the
   **sync display name** other paired devices see
   (`DeviceIdentity.displayName`, `shared/sync Identity.kt`). Renaming here
   updates the identity the sync advertisement/QR carries — the same name
   shown in Connectivity → Sync.
2. **Device ID** — read-only monospace `dev-abcd1234ef` (derived from the
   Ed25519 key fingerprint — stable across restarts, unforgeable) with a
   `CopyButton`.
3. **Fingerprint** — read-only `abcd1234` (`shortFingerprint`); the human
   check shown during pairing.
4. **Network** — live row from `ConnectivityDashboard`: `WiFi · HomeNet ·
   ▂▄▆ signal` or `LTE · Carrier` or `Offline`; read-only mirror (network
   *settings* live in System → Permissions / OS, not here).
5. **Battery & storage** — read-only line (`82% · 1.2 GB used`); storage
   details are a mirror of Privacy & Security (owner of encryption/storage
   status).
6. **Model & OS** — read-only line (device model, Android version, app
   version).

**B · Paired devices** — from the sync peer store (`PairedPeer` list);
pairing controls stay in Connectivity → Sync (no duplication).

1. **This device** is never listed here (it is section A).
2. Each row (`PairedDeviceRow`): display name, short device id, last-synced
   relative time, and a **status dot** — green `In sync now` / grey
   `Last synced 2 h ago` / red `Never synced` (mirrors the drawer sync
   badge, owned by Sync).
3. Row action: **Forget device** → `ConfirmDialog` (destructive — removes
   the peer key; data already synced stays, future sync stops).
4. **Pair a new device** button → pushes the Pair-a-device page §4.5.4
   (biometric-gated; QR / typed key / nearby discovery → SAS → confirm) —
   the only pairing entry into that page.
5. Empty state: `No paired devices — pair from Sync` with a link to the
   Sync page. Loading state while the peer list loads; error state with
   retry if the peer store is unavailable.

Sequence logic: this device (identity + live state) first, then the other
devices. Everything pairing/transfer-related points at Sync; everything
encryption-related points at Privacy & Security.

### 4.5.4 Pair a device — page design

Full-screen route pushed from **Devices §4.5.3 → Pair a new device** (and
as a shortcut from Connectivity → Sync's pairing row — one route, two
entrances; this page is the **only** place pairing happens). Back chevron
returns to the page it was pushed from. The flow is a state machine; the
page re-renders per step. Grounded in `shared/sync Pairing.kt`
(`createRequest` / `sas` / `confirmInitiator` / `confirmResponder`) and
SYNC_DESIGN.md §3 — QR → SAS → confirm, Signal-style.

**Entry gate — biometric** (pairing is per-device *sensitive* settings,
SYNC_DESIGN §3): a `BiometricPrompt` runs before the page renders. Denied →
stay on the previous page with a toast `Biometric required to pair
devices`.

**Step 0 · Role** — two `PairRoleCard`s:
- **Show my code** — this device is the initiator; renders the QR (below).
- **Scan a code** — this device is the responder; scans the other device's
  QR (or types its key, or picks it from nearby discovery).

**Step 1a · Initiator (Show my code)**
1. **QR card** (`PairQrCard`) — large (≈ 240 dp) QR rendering
   `Pairing.createRequest(...)` (payload `aegis-pair-v1|1|<name>|<signKey>|
   <ecdhKey>|<nonce>`, fresh nonce per request). Caption line: display name
   + `shortFingerprint` of **this** device. Expiry `This code expires in
   5:00` with a live countdown + **Refresh** (new nonce) button.
2. **Type-the-key fallback** — a chevron expands a copyable monospace box
   with the full encoded payload + `CopyButton`, matching SYNC_DESIGN's
   "QR falls back to a typed key": the responder pastes it into its
   **Type a key** field.
3. Waiting state: `Waiting for the other device to scan…` spinner; when the
   responder's keys arrive in the first session message, the card flips to
   the SAS step showing the responder's name.

**Step 1b · Responder (Scan a code)** — three paths, one outcome:
1. **Scan QR** — camera view (platform seam `BarcodeScanner`; zxing core on
   Android) with a torch toggle and `Camera permission needed` red chip →
   `PermissionLauncher` when missing. Successful scan → initiator's name +
   fingerprint card → SAS step.
2. **Type a key** — text field accepting the full encoded payload, parsed
   by `PairingRequest.decode()`; invalid payload → inline error `Not a
   valid pairing code`.
3. **Nearby devices** — `ProximityDiscovery` list (BLE + WiFi-Direct on
   Android, mDNS LAN): rows (`NearbyDeviceRow`) with name, short
   fingerprint, signal strength; tap → SAS step with that peer. Empty
   state: `No devices found — make sure the other device is showing its
   code`.

**Step 2 · SAS confirm (both roles converge)** — `SasConfirmCard`: the
6-digit code from `Pairing.sas(initiatorSign, responderSign, nonce)`
(identical on both devices) rendered large (monospace ≈ 48 sp,
letter-spaced, in a bordered card) with the instruction `Check that both
devices show the same code.` Two buttons: **Codes match → Confirm** and
**Codes don't match → Cancel**. On **Codes don't match**, show a warning
card and abort: `Codes don't match — possible interception. Do not confirm.`
(SAS is the only MITM defense point; never auto-confirm). A 2:00 SAS
countdown; expiry → restart with a fresh nonce.

**Step 3 · Result**
- **Success** (`PairSuccessCard`): `Paired with <name> ✓` + short
  fingerprint + `Done` → returns to Devices §4.5.3, where the new peer
  row now appears (`In sync now` / `Never synced`).
- **Already paired**: if the peer's deviceId is already in the store, show
  `This device is already paired` with **Re-pair** / **Unpair** actions
  (unpair = revocation record, SYNC_DESIGN §5).
- **Errors** (each honest, no silent swallow): pair-with-self
  (`You can't pair a device with itself` — mirrors the `require` in
  `confirmInitiator/confirmResponder`); timeout (`No response — try
  again` + Retry); camera denied (typed-key path stays available);
  biometric denied (entry gate).

Sequence logic: gate (sensitive) → role → code exchange → human-verified
SAS → peer stored. The page never auto-confirms and never executes anything
else — pairing is the only capability here.

### 4.5.5 Ambient & Voice — hub

Full-screen route pushed from Settings → General → **Ambient & Voice**;
back chevron returns to the Settings page. Three chevron rows under one
section header — the hub is the **single owner of mic-based input**; each
row pushes its own page, and no mic-input setting lives anywhere else:

1. **Ambient Mode** → §4.5.6 (Off / Meeting / Lecture continuous
   transcription). Sub-line: `Off` or `Meeting — listening`.
2. **Wake word** → §4.5.7 (always-on listening toggle + the phrase).
   Sub-line: `Off` or `Listening for 'Hey Newax'`.
3. **Voice authentication** → §4.5.8 (enroll / verify voiceprint).
   Sub-line: `Not enrolled` or `Enrolled`.

Mic permission itself stays in System → Permissions (mirror, not a
setting here). Each page is grounded in `VoiceRecognitionService` (Vosk,
foreground service, START_STICKY) and `VoiceAuthenticator` (speaker
embedding + cosine-similarity threshold, fail-secure).

### 4.5.6 Ambient Mode — page design

Full-screen route pushed from **Ambient & Voice §4.5.5 → Ambient Mode**;
back chevron returns to the hub. The only place a continuous-transcription
session can be started or stopped. Grounded in
`VoiceRecognitionService.ambientMode` ("None" / "Meeting" / "Lecture") +
`ambientTranscript` + `endAmbientMode()`.

**A · Mode picker** — segmented `ChoiceChips`, one active at a time
(`ambientMode`):
- **Off** — `No continuous transcription` (default; picking it ends any
  active session).
- **Meeting** — `Transcribe the conversation, then summarize and extract
  action items to memory`.
- **Lecture** — `Transcribe, then create study notes and key concepts to
  memory`.

**B · Live session** — rendered only while a mode is active (Meeting /
Lecture):
- Header row: `ListeningIndicator` — `Listening… 12:04` (elapsed) + a
  **Stop** chip; the chip label names the running mode.
- `TranscriptPreview` card at the bottom: the running
  `ambientTranscript`, read-only, word-counted (`1,240 words so far`) —
  exactly what gets summarized when the mode ends.
- **Stop** → `ConfirmDialog` when the transcript is long: `Stop and
  summarize?` (Yes / Keep listening). Confirm ends the session, sends the
  transcript through `TriggerEngine` (meeting → summary + action items,
  lecture → study notes + key concepts) and saves the result to memory;
  the card then shows `Saved to memory ✓`.
- A **Mode active** status chip is a read-only mirror on the chat thread
  header — it navigates here, it does not control the session (no
  duplication).

**C · States**
- Idle (Off): picker only; each option's sub-line explains its outcome.
- Active: picker disabled, session UI visible.
- Mic permission missing: red `Permission needed` chip on the picker →
  `PermissionLauncher`; session can't start.
- Loading: Vosk models unpacking (`StorageService`) — spinner on the
  picker.
- Error: model unpack failure stops the service — error card + **Retry**.

Sequence logic: pick a mode → session runs (live indicator + transcript) →
stop → summary to memory. One session at a time, always.

### 4.5.7 Wake word — page design

Full-screen route pushed from **Ambient & Voice §4.5.5 → Wake word**; back
chevron returns to the hub. The single owner of always-on listening — there
is no separate "continuous listening" setting anywhere.

**A · Master switch** — one toggle running the Vosk wake-word foreground
service (START_STICKY):
- On → status line `Listening for 'Hey Newax'` + the service notification
  (`Newax Voice · Listening for 'Hey Newax'`); the process survives
  backgrounding.
- Off → service stops, notification clears.
- Sub-line: `Continuously listens for your wake phrase (uses battery)`.

**B · Wake phrase** — read-only mirror row showing the current phrase
value (from Profile §4.5.2 C.7, default `Newax`); chevron → Profile page
to edit it. **Value lives on Profile; this page only shows it** (explicit
non-duplication).

**C · How it works** — info card: `Say the phrase, then speak your
command — Newax Aegis responds. Works fully offline (Vosk).`

**D · Test the phrase** — a `Test` button opens a short listening window
(≈ 10 s) using the live listener: result `Detected ✓` (phrase heard) or
`Not detected — try again` (timeout). Read-only exercise of the real
matcher, not a setting.

**E · States**
- Off: toggle off; Test disabled.
- On: toggle on, status line + notification live.
- Mic missing: red `Permission needed` chip → `PermissionLauncher`;
  toggle can't enable.
- Model loading: Vosk model unpack in progress — spinner on the toggle.
- ⚠️ Known gap (honest, not a UI fix): the service matcher today hardcodes
  `hey aegis` / `ہیلو ایجس` and does not read `ProfileManager.wakeWord`;
  wiring the phrase value into the matcher (and rebranding it to
  "Hey Newax") is a follow-up code change, not a page change.

Sequence logic: toggle first (does it listen), phrase (what it hears),
then test (prove it works).

### 4.5.8 Voice authentication — page design

Full-screen route pushed from **Ambient & Voice §4.5.5 → Voice
authentication**; back chevron returns to the hub. The owner of voice auth:
your voice confirms sensitive actions when device biometric is unavailable
or fails (the strong-confirmation fallback). Grounded in
`VoiceAuthenticator` (`isEnrolled`, `enroll`, `verify` — cosine similarity
≥ threshold, fail-secure, `clearEnrollment`).

**A · Enrollment card** — status-led card:
- `Not enrolled` (empty state): `Enroll` primary button → `VoiceEnrollSheet`
  (repeat the phrase ~3×; the Vosk speaker vector becomes the voiceprint).
- `Enrolled` (with ✓): `Re-enroll` (clear + re-enroll) and `Remove
  voiceprint` (→ `ConfirmDialog` → `clearEnrollment()`).
- Sheet outcomes: success → `Enrolled ✓`; insufficient/quiet audio →
  error line + retry; mic denied → red `Permission needed` chip →
  `PermissionLauncher`.

**B · Use voice to confirm** — switch, enabled only while enrolled (disabled
state shows `Enroll first`); sub-line: `Voice confirms sensitive actions
when biometric is unavailable or fails`. Feeds the strong-confirmation
path — when the switch is on and `verify()` succeeds, the approval flow
accepts the voice instead of device biometric.

**C · How it's used** — info card: `Actions at STRONG_CONFIRMATION level
(policy) can be approved by your voice when the device biometric is
unavailable or fails. Fail-secure: no voiceprint → verification always
fails → the action stays blocked.`

**D · States & notes**
- Loading: embedding check spinner.
- Fail-secure: `verify()` returns false with no enrollment — the UI shows
  `Not enrolled` and the confirm switch stays off; nothing ever
  auto-enables.
- Honest note: the voiceprint is held in memory for the session
  (`VoiceAuthenticator` keeps a `FloatArray` embedding); re-enroll after an
  app restart — persistence is a backend item, not this page.

Sequence logic: enroll first (who you are), then enable (when it applies),
then understand (how it's used). Owner rule unchanged: Safety & Privacy's
biometric row covers device biometric, not this voice model.

## 5. Shared component library (built once, used by both platforms)

`NavDrawer` (side menu: brand header, section items, status badge) ·
`ChatBubble` · `StreamingText` · `TypingIndicator` · `ApprovalCard` (with risk
chip + policy-blocked variant) · `Composer` (text + mic + add-files +
send/stop, attachment thumbnails) · `AttachmentChip` · `ConversationRow` ·
`SearchBar` · `ModelChip` + `ModelSheet` · `ModelStatusLine` (model name +
green/yellow/red dot, under the composer) · `CopyableTextBox` + `CodeBlock`
(language label + copy) + `ImageBlock` (viewer) + `ImageGenBlock` (prompt +
progress + cancel/retry) + `DocumentsContainer` (small-document rows with
type icon, size, open/copy/share) + `McqCard` (question + option rows +
`Custom…` text input, one per message) + `ThoughtContainer` (collapsible
reasoning block: chevron header + streaming thought text, collapsed by
default) + shared `CopyButton` ·
`EmptyState` + `SuggestionGrid`
(6 first-message prompt buttons) · `SectionHeader` ·
`ChevronRow` · `StatusChip` (Ready/Warning/Error/Offline) · `Sheet` (bottom,
20 dp radius) · `ConfirmDialog` · `ListSwipeActions` · `TimelineItem` ·
`AmberTaskCard` · `AgentCard` · `SkillRow` · `SettingsGroup`
(section header + card) · `SettingsRow` (icon + label + inline switch /
status dot / chevron — the single row type for every setting on the one
Settings page) · `EditValueSheet` (rename profile/device) ·
`VoiceEnrollSheet` (wake-phrase repeat + enroll/redo, used by Voice
authentication §4.5.8) · `ListeningIndicator` (live `Listening… 12:04` +
Stop, used by Ambient Mode §4.5.6) · `TranscriptPreview` (live running
`ambientTranscript`, read-only, used by §4.5.6) · `ProfileHeader` (avatar + name +
persona line, used by the Profile page §4.5.2) · `ChoiceChips` (segmented
single-select chips: communication style / response length) · `TagEditor`
(chip add/remove list: interests / dislikes) — all used by the Profile page
§4.5.2 · `DeviceCard` (this device: name, device id + copy, fingerprint,
network/battery/storage lines) · `PairedDeviceRow` (name, short id,
last-synced, status dot, Forget) — used by the Devices page §4.5.3 ·
`PairRoleCard` (Show my code / Scan a code) · `PairQrCard` (QR + expiry
countdown + refresh + type-the-key fallback) · `SasConfirmCard` (6-digit
code + codes-match/mismatch, warning on mismatch) · `NearbyDeviceRow`
(discovered peer: name, fingerprint, signal) · `PairSuccessCard` (Paired ✓
+ Done) — all used by the Pair-a-device page §4.5.4 (confirmations reuse
`ConfirmDialog`, `CopyButton` reuses the shared one).

## 6. Platform seams (the only expect/actual surface)

`PlatformSafeArea` (insets) · `SystemBackHandler` (Android back / iOS swipe) ·
`Haptics` (light/medium/heavy) · `Clipboard` · `PermissionLauncher` (screen
access, notifications, SMS, calendar, contacts, mic) · `BiometricPrompt` ·
`FilePicker` (model/agent import) · `VoiceRecognizer` (offline Vosk on Android;
system dictation seam on iOS) · `ScreenCaptureRequest` (QS tile on Android;
broadcast intent seam on iOS) · `BarcodeScanner` (zxing core on Android;
AVFoundation capture seam on iOS — Pair-a-device §4.5.4 scan path).

Everything else — layout, state, theming, navigation, business logic — is
shared Kotlin in `commonMain`.

## 7. Implementation phases (each a mergeable slice, Android stays green)

1. **Extract design tokens + theme** — move the palette into a shared
   `NewaxTheme` (Android `MainActivity.kt` constants + desktop
   `NewaxTheme.kt` converge); no visual change.
2. **Shared component library** — port ChatBubble/Composer/ApprovalCard/
   EmptyState/rows into a shared `ui/components` module used by Android.
3. **Chat shell** — side menu (drawer: Chat/Memory/Tasks/Settings + sub-
   routes) + conversation list + thread navigation on Android; existing
   screens move behind the drawer as full-screen routes.
4. **Port screens to shared** — Memory timeline, Tasks (Goals/Agents/Skills),
   Settings group + sub-pages, in the same slices their logic already exists.
5. **iOS targets** — `apps/ios` consumes the shared UI via CMP; wire the
   platform seams (safe areas, back gesture, picker, biometric); Apple
   compiles verified on a macOS host (per AGENTS.md, Apple targets can't
   compile from Linux).
6. **Polish** — dark mode, motion, accessibility labels, RTL.

## 8. Current → new screen mapping (R13 check)

| Today (Android) | New IA |
|---|---|
| Chat (MainActivity ChatScreen) | Chat — the main surface → Thread |
| Memory screen | Memory (drawer) timeline |
| People screen | Memory (drawer) → People section → person detail |
| Meeting screen | Chat attach sheet → Meeting note flow |
| Drafts | Chat attach sheet → Drafts sheet / thread action |
| Goals screen | Tasks (drawer) → Goals |
| Agents + Agent Memory | Tasks (drawer) → Agents → agent detail |
| Skills screen | Tasks (drawer) → Skills |
| Capabilities + Policy settings + Policy history | Settings → Policy & Capabilities (modes, hard deny) + Policy history |
| Sync screen | Settings → Sync (status, pairing, toggles) |
| Nearby Share screen | Settings → Nearby Share |
| Backup & Restore | Settings → Data & Backup |
| App Permissions | Settings → Permissions |
| Automation settings (AutomationSettingsSection + 2FA) | Settings → Automation |
| Self-Learning engine (LearningSettingsSection) | Settings → Learning & Memory |
| Ambient Mode card (VoiceRecognitionService.ambientMode) | Settings → General → Ambient & Voice §4.5.5 → Ambient Mode §4.5.6 |
| Wake-word service (Vosk, always-on listening) | Settings → General → Ambient & Voice §4.5.5 → Wake word §4.5.7 (phrase value on Profile §4.5.2) |
| Voice authentication (VoiceAuthenticator enroll/verify) | Settings → General → Ambient & Voice §4.5.5 → Voice authentication §4.5.8 |
| Ghost Mode | Settings → Automation (Ghost Mode toggle) |
| Updates screen | Settings → Updates |
| Dev console (shake) | Settings → Advanced (Dev) |
| Model import + status | Settings → Offline AI Model (chat composer line is a read-only status mirror) |
| Profile fields (ProfileManager — name, language, style, interests, no dedicated screen today) | Settings → General → Profile §4.5.2 (new route; wake-word value here, toggle stays in Ambient & Voice) |
| Device identity + telemetry (DeviceIdentity, ConnectivityDashboard — dev console data today) | Settings → General → Devices §4.5.3 (this-device section) |
| Paired devices (Sync peer list) | Settings → General → Devices §4.5.3 → paired list; Pair a new device → §4.5.4 (biometric-gated, QR/SAS) |
| Pairing flow (Pairing.kt: QR → SAS → confirm; today a raw sheet on the Sync screen) | Settings → General → Devices §4.5.3 → Pair a device §4.5.4 (also a shortcut from Sync) |

## 9. Risks & decisions to make

- **CMP version**: shared iOS UI requires the Compose Multiplatform plugin;
  current desktop apps pin 1.7.1 while the AGENTS.md baseline says 1.11.1
  (see docs/OVERVIEW.md B10 drift #1). The shared-UI slice must settle this
  deliberately before writing UI code.
- **Navigation**: use a simple hand-rolled drawer + push stack in shared code
  (no new dependency) unless a library is already present — check before
  adding (R5/R10).
- **Scaffold order**: do NOT move screens into shared while the app must stay
  green in this Linux sandbox — Android keeps building at every slice, and
  Apple compiles happen on a macOS host only.
- **Scope guard**: this plan is the spec; each phase is a separate PR. PLAN is
  never EXECUTE — a plan doc grants no authority to skip policy/approval in
  the implemented app.
