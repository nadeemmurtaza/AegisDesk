# Newax Aegis — Project Overview

One reference for what the product is and what versions it pins. Split into
**Part A — Features** and **Part B — Versions**. The normative architecture is
`ARCHITECTURE.md`; the operational rules are `AGENTS.md`; coupling rules live in
`docs/rules/compatibility.md`.

**Product thesis:** an offline-first (90%), multiplatform personal assistant —
"a second me" — one universal runtime, one memory fabric, one authority spine,
many thin specialized agents, running on Android + desktop bodies with a shared
KMP brain.

---

# Part A — Feature List

Status is current as of this file's update; every capability has a working
implementation unless marked **planned** or **flagged** (FeatureFlags, default
state in parentheses). The authoritative module map is `ARCHITECTURE.md` (which
slice landed in which phase).

## A1. Core assistant (Android app — `apps/android`)

- **Natural-language chat** against an on-device model (LiteRT-LM), with a
  deterministic command engine as fallback when the model is unavailable.
- **Deterministic offline commands** via the accessibility tree: screen reading
  (no screenshots stored), tap, type, back, scroll, Home, Recents, app opening,
  replying, and message-send primitives.
- **Multi-step plans** using `then` — every step gets its own approval prompt.
- **Mandatory approval card** before any external action (typed `ProposedAction`
  → approval button → accessibility service; model output never executes
  directly).
- **Continuous encrypted device memory**: `remember that ...`, `what do you
  remember`, `clear memory`.
- **Offline-preferred voice recognition** (Vosk; depends on installed device
  language packs) + microphone FGS permissions.
- **Volatile notification inbox** — message content is not persisted; replies
  are never automatic.
- **Screen understanding** via the "Newax: Understand Screen" Quick Settings
  tile (MediaProjection, only when the user explicitly triggers it).
- **Ghost Mode** (accessibility `GhostModeService`) and automation settings.
- **On-device OCR** (`OcrEngine`, ML Kit) feeding screen reading and file
  extraction.

## A2. Deterministic command & intent engine (`engine/registry`, `engine/intelligence`)

- **IntentRegistry** — 50+ built-in natural-language intents (open/apps,
  send/message/msg/text, call/dial/ring, remember/remind/reminder/notify me,
  play/music/song, search, navigate/directions/route/how-to-get, photo/video/
  pdf/document/image, launch/run/start, define/explain/what-is/tell-me, share/
  send-file/send-photo, and per-app: whatsapp, telegram, instagram, facebook,
  twitter, gmail, spotify) with entity extraction (person, app, file_type) and
  confidence scoring; register/unregister at runtime.
- **Planner** — `QueryPlanner` (shared/core) + `ReasoningPlanner`
  (chain-of-thought, **flagged** REASONING_PLANNER) + `DeterministicResolver` +
  `CandidateMerger` decompose queries and pick the cheapest resolver.
- **GoalPlanner / GoalExecutor** — AI goal decomposition, task DAG with live
  statuses, plan pre-flight against policy, execution through the same
  authority spine as any action, goals survive restarts (snapshot in kv_store),
  policy-blocked tasks surfaced amber, execution audit ("Recent runs").
- **Procedure engine** — `ProcedureExecutor` + `ExecutionGuard` +
  `StepSerializer` + `ProcedureCompiler` run versioned learned procedures;
  `ProcedureOptimizer` auto-optimizes them (**flagged** PROCEDURE_OPTIMIZER).
- **TypeaheadTrie** — fast prefix search over people/files for autocomplete.
- **Full catalog** — the complete 10-intent / 50-pattern command table, entity vocabulary, and deterministic primitives live in `FEATURES.md` (repo root).

## A3. Safety & authority spine (`shared/core`)

- **PolicyEngine** — `RiskLevel` → AUTO / CONFIGURABLE / APPROVAL /
  STRONG_CONFIRMATION mapping, user overrides + hard deny per action class,
  machine-origin ceiling (BACKGROUND/AGENT actions never inherit user trust),
  full audit records.
- **AuthorityManager.apply** — the single gate every action passes through;
  PLAN is never EXECUTE (a generated plan / goal grants zero execution authority).
- **Per-action-class policy modes UI** (Capabilities → Policy settings
  section), policy-blocked tasks surfaced in amber on the Goals screen, plan-time
  policy pre-flight warnings.
- **Execution audit trail**: every goal run records goal, outcome, per-task
  tiers/statuses/timestamps (ring-capped), surfaced as "Recent runs"; CSV export
  (`ExecutionCsv`, `PolicyCsv`, `PolicyHistoryScreen`).
- **Biometric/strong confirmation** for high-risk actions (per privacy policy
  and policy modes).
- **Credential references, never content** — the LLM sees `GitHub
  authentication: AVAILABLE`, never a token.
- **App permission management** (`AppPermissionManager` + `AppPermissionScreen`)
  — user-editable per-app permission grants; every privileged operation carries
  caller/origin/capability/audit metadata.

## A4. Offline AI stack (Android + desktop)

- **LiteRT-LM engine** (`platform-impl/android`) — real Kotlin engine lifecycle
  and streaming conversation inference; Gemma model packs (e.g. Gemma 3 1B INT4
  default, optional Gemma 3n E2B quality/vision mode on the S21 8 GB profile).
- **`.litertlm` document importer** — format, size, magic-header, and SHA-256
  verification; private app-storage install, automatic reload, visible status,
  command-engine fallback. Desktop twin: `DesktopModelImporter` (GGUF).
- **Desktop GGUF provider** (`platform-impl/windows`, `platform-impl/macos`) —
  java-llama.cpp (de.kherud:llama) JNI binding, GGUF header parser, RAM/VRAM
  estimation, streaming, cancellation.
- **On-device perception**: Vosk speech-to-text, Vosk speaker embeddings for
  voice authentication, ML Kit text recognition (OCR), MediaPipe Universal
  Sentence Encoder text embeddings.
- **Model manager & registry** (`ModelManager`, `ModelRegistry`) — one
  `ModelProvider` per process, deterministic `FallbackModelProvider`, optional
  multi-model query routing (**flagged** MULTI_MODEL_ROUTING).
- **Prompt pipeline** (`PromptBuilder`, `ContextBuilder`, `MemoryRanker`,
  `ToolSelector`, `ResponseValidator`) — context window built with ranked memory
  (CONTEXT_BUILDER_V2 **flagged**), tools selected per query, responses
  validated before execution.
- **Deterministic-first**: exact lookup / typed tools load the model only when a
  task genuinely needs reasoning; skills tagged `OFFLINE_OK` / `REQUIRES_ONLINE`
  / `DEFER`.

## A5. Memory fabric (`shared/database`, Room KMP)

Five layers, one encrypted on-device database shared across platforms
(schema v13):

- **Episodic** — what happened (conversations, meetings, logs).
- **Semantic** — facts (KnowledgeGraph: triples, entities, predicates).
- **Procedural** — learned how-to (versioned UiProcedures with screen
  signatures, success counts).
- **Personal** — people, relationships, commitments (PersonRegistry).
- **Agent state** — per-agent namespaced `agent:<id>:<key>` state.

Memory lifecycle machinery (`engine/learning`, `engine/memory`):

- **Memory consolidation** — duplicate/contradiction detection
  (**flagged** MEMORY_CONSOLIDATION), `MemoryConsolidator`, `MemoryCompiler`.
- **Forgetting engine** — remove unused old memories (**flagged**
  FORGETTING_ENGINE); **confidence decay** for stale memories (**flagged**
  CONFIDENCE_DECAY); `NightlyCleaners` housekeeping.
- **Fact extraction** — NLP entity extraction (ENTITY_EXTRACTION), LLM fact
  extraction (LLM_FACT_EXTRACTION) and triple extraction from messages
  (LLM_TRIPLE_EXTRACTION); `CanonicalStore` + `GraphCompiler` normalize
  entities/edges.
- **Snapshot compilation** — periodic entity snapshot compilation (**flagged**
  SNAPSHOT_COMPILATION); `SnapshotEngine`, `StateArchiver`.
- **Encrypted at rest** — SQLCipher on Android, bundled/native SQLite on
  desktops; `EncryptedMemory`, `SecureKeyVault` (Android Keystore AES-GCM),
  `DbKeyManager`.

## A6. Knowledge, search & context (`engine/`)

- **KnowledgeGraph + GraphStore + GraphCompiler** — entities, predicates,
  edges; name-keyed; syncable.
- **Semantic search** — `SemanticSearchEngine`, `VectorStore` +
  `VectorMemorySearch` + `EmbeddingEngine` (VECTOR_SEARCH, default on),
  background `EmbeddingIndexWorker` (OPPORTUNISTIC_INDEXING, default on).
- **Memory indexer** — `MemoryIndexer` + `TypeaheadTrie` fast lookup.
- **Context engine** — `ContextEngine` + `ContextCorrelator` build a rich
  context packet correlating entities with memories, communication logs,
  knowledge graph, and project records.
- **Screen grounding** — `ScreenGrounder` grounds accessibility nodes to
  screen state for procedures and actions.

## A7. Intelligence & learning (`engine/learning`, `engine/intelligence`)

- **Habit learning** — `HabitTracker` + `AppScanner` + `AppIntelligence`
  detect app-usage habits (HABIT_LEARNING, default on).
- **Failure learning** — `FailureLearner` records and learns from execution
  failures (FAILURE_LEARNING, default on); `ConfidenceEngine`.
- **Background learner** — `BackgroundLearner` / `LearningWorker` /
  `EvolutionWorker` / `IntelligenceWorker` run extraction + consolidation
  off the critical path; `ScanProgress` tracks state.
- **Tone & style analysis** — `ToneAnalyzer` (sentiment/formality/intent),
  `DocumentClassifier` (doc type + sensitivity, no ML).
- **Person intelligence** — `PersonIntelligence` + `PersonProfiler` build rich
  profiles (traits, relationship type, tone) from SMS + communication history;
  `PersonFactStore`; `ContactNormalizer`.
- **Trigger engine** — `TriggerEngine` (Android + shared) automatic trigger
  rules (TRIGGER_ENGINE, default on); notification triggers from the inbox.
- **Project tracking** — `ProjectTracker` tracks projects/status/notes;
  `GoalsScreen`-adjacent goals persistence (`GoalPersistence`,
  `DbGoalSnapshotStore`).

## A8. Files & documents (`engine/files`, `engine/embedding`)

- **File indexer** — `FileIndexer` scans device files (idle-time,
  OPPORTUNISTIC_INDEXING); `FileRecord` + `PHasher` (perceptual hash for image
  dedup, VISUAL_HASHING) + `TextExtractor` (TEXT_EXTRACTION) + OCR.
- **File intelligence** — `FileIntelligence` + `FileQueryPlanner` answer
  questions over the file index; `WorldRegistry` of indexed locations.
- **Document classifier** — type + sensitivity classification without ML.
- **Backup & restore** — `BackupManager` + `BackupCrypto` (encrypted),
  `BackupRestoreScreen`; `LegacyMigrationWorker` migrates old data.

## A9. People & relationships (`engine/person`, `engine/contacts`)

- **PersonRegistry** — people, relationships, commitments, categories
  (personal / business / education / relationships / goals / pain_points /
  rules) in `PersonProfiler`/`PeopleScreen`.
- **ContactsManager** — Android contacts read/write via
  `AndroidContactsAdapter` + `ContactNormalizer`; `PersonInspector` (dev).
- **CommunicationLog** — inbound/outbound message log per contact (source:
  WhatsApp, Gmail, SMS, …) feeding person intelligence and memory.

## A10. Calendar, reminders & time (`engine/`)

- **Calendar adapter** — `AndroidCalendarAdapter` + `CalendarQueries` read/
  write device calendars; `MeetingScreen` with Meeting/Lecture modes and note
  capture.
- **Reminders & notifications** — `remind me` / `notify me` commands;
  `TriggerEngine` notification rules; `NewaxSystemReceiver` reboot/system
  handling.
- **TOTP / 2FA** — `TotpManager` + zxing QR enrollment.

## A11. Communication & notifications (`accessibility/`, `engine/`)

- **SMS** — `NewaxSmsReceiver` (RECEIVE_SMS), read/send guarded by policy;
  `Answer calls` permission.
- **Notification listener** — `NewaxNotificationListenerService` + `DetoxBuffer`
  (volatile, deduped, ring-capped); drafts & reply suggestions
  (`DraftsScreen`, `DraftStore`) — never automatic.
- **Audio** — `VoiceRecognitionService`; `AudioDashboard` (dev);
  `VoiceAuthenticator` — voice biometric verification via Vosk SpkModel
  embeddings (enroll + cosine-similarity verify).

## A12. Security & privacy (`engine/`)

- **SensitiveInfoDetector** — detects + redacts sensitive values (emails,
  phones, cards, …); raw values only returned to callers that secure them
  (e.g. autofill); never logged.
- **SecurityAuditor** — audits installed apps for sensitive permission combos
  (camera, mic, sms, location, …), produces a report (top-20).
- **DetoxBuffer** — volatile notification buffer (no persistence).
- **Encrypted memory & vaults** — `EncryptedMemory`, `SecureKeyVault`
  (Android Keystore/TEE), `DbKeyManager` (encrypted DB keys), per-device
  keystores for sync (Android Keystore / DPAPI / Keychain).
- **Code sandbox** — `CodeSandbox` (Mozilla Rhino) runs user/agent JS with a
  bounded console; `ExecutionGuard` gates procedure execution.
- **Hardware benchmark** — `HardwareBenchmark` measures device capability for
  model/feature selection (BENCHMARK_MODE **flagged**).

## A13. Background, scheduling & resources (`engine/resource`, `engine/background`)

- **OpportunisticScheduler** — runs background work during idle
  (OPPORTUNISTIC_INDEXING).
- **ResourceGovernor + ResourceClass + CheckpointedJob** — classifies work by
  resource cost, gates concurrent heavy jobs, checkpoints long jobs for resume.
- **NightlyCleaners** — nightly housekeeping scans (deliberately model-free).
- **WorkflowManager + WorkflowRegistry** — named workflows; `ProfileManager` —
  user profiles; `CacheManager`, `IndexManager`, `ExportManager`.
- **NewaxEventBus** — in-app event bus decoupling engines.
- **FeatureFlags** — runtime flags for the above (Dev settings).

## A14. Observability, dev console & diagnostics (`engine/dev`, `ui/devconsole`)

- **Dev console** — shake-to-open (DEVELOPER_CONSOLE, default on), tabs: DB
  (`DbTab`), Files (`FilesTab`), Logs (`LogsTab`), State (`StateTab`), Triggers
  (`TriggersTab`).
- **Dashboards** — Audio, Connectivity, Sensor.
- **Debuggers/inspectors** — AccessibilityInspector, AppInspector,
  DatabaseInspector, NetworkInspector, NotificationDebugger, PersonInspector,
  ProcedureDebugger, JobInspector.
- **Profiling & tracing** — HeapProfiler, ResourceProfiler, TaskTrace,
  DecisionInspector, MetricsEngine (DETAILED_METRICS **flagged**), AnrWatchdog,
  CrashReporter (+ `CrashReporterActivity`), NewaxLogger, DevLogger.
- **Replay & automation tooling** — InputMacroRecorder, ProcedureReplayer,
  HeadlessCi, SearchLaboratory (REGRESSION_CHECKS **flagged**), AdbBridge,
  DebugWebSocketServer, FileIntelligenceTool.

## A15. Cross-device sync — the 4-device mesh (`shared/sync`, `shared/desktop-sync`, `relay/`)

One mind across **I** (iOS) / **A** (Android) / **M** (macOS) / **W** (Windows):
each holds a full private encrypted copy; opportunistic, pairwise, mesh
convergence with no master device.

- **LAN transport**: mDNS auto-discovery (`_aegis-sync._tcp.local.` via JmDNS)
  + direct encrypted channel (Noise-style SessionCrypto: triple-DH, transcript-
  pinned HKDF, AEAD counter framing, replay-proof).
- **Internet relay** (`relay/`): E2E-blind WebSocket rendezvous + ciphertext
  relay, pair-aware GRANT routing, per-device store-and-forward queue with TTL,
  PRESENCE fan-out, `/health`; Dockerfile deployable.
- **Merge**: CRDT (opId-dedup) for append-only logs, LWW + tombstones for
  mutable records, HLC ordering — never wall clocks.
- **Commands are data**: typed, AGENT-origin, journaled store-and-forward, only
  the target dispatches, through the same PolicyEngine path, per-peer class
  allowlists, ttl, signature validation on every hop.
- **Pairing**: QR + human-verified 6-digit SAS; Unpair revokes through the mesh
  (relay drops the peer).
- **Encrypted Quick Share** (Nearby Share screen): BLE discovery + WiFi-Direct
  (P2P) chunked `ProximityTransfer`, accept/decline gate, progress, received
  files to app Downloads.
- **Credentials sync as references only** — secret values stay in per-device
  keystores (Android Keystore/TEE, DPAPI, Keychain).
- **Capture coverage** syncs the full fabric: graph edges, app usage
  (`app_records`), trigger rules, goals + policy (desktop kv records).
- **Continuous listening**: `SyncForegroundService` (dataSync FGS, START_STICKY)
  keeps the transport up between the 15-minute `SyncWorker` windows.

## A16. Agent system (`shared/core` + `apps/android` agents)

- **Registry**: agents (id, domain, version, skills, permissions, model profile,
  memory policy, risk policy, state machine IDLE→PLANNING→EXECUTING→VERIFYING→
  COMPLETED + PAUSED/FAILED/CANCELLED/WAITING_FOR_USER).
- **Skills** — one narrowly defined, app-agnostic capability
  (`CREATE_POST`, `UPLOAD_MEDIA`, `PATCH_FILE`); **procedures** — versioned
  how-to inside a specific app; **tools** — low-level primitives behind guards.
- **One loaded model, many agents** — prompt profiles (context + tool allowlist
  + policy + verification) change; the model does not. Only one heavy cognitive
  agent executes at a time (RAM rule); cheap skills run without loading the LLM.
- **Typed handoffs** between agents (`ResearchResult`, `CampaignBrief`, …) —
  never free-form text.
- **Imported agents** as `.aegis-agent` zips: DENIED-by-default, extension/
  size/zip-slip/manifest checks, SHA-256 fingerprint, tool claims are *requests*
  gated by per-agent user permission grants.
- **Learning engine** + **SkillGuard** (safe skill execution) + **StateArchiver**
  (agent state persistence).
- **Capability resolution ladder**: official API → deep link → Intent → learned
  procedure → accessibility nodes → screen grounding → vision/LLM fallback
  (seeded in `ExecutionRouter`).

## A17. Android screens (all in `apps/android`)

Drawer: Chat · Memory · Drafts · Meeting · People · Backup · Settings · Goals ·
Capabilities · Nearby · Sync · Agent Memory · Agents · Skills · Updates.

Sub-screens / sections: Policy settings + Policy history (per-action-class
modes, hard deny, audit) · App permissions · Automation settings · Learning
settings · Dev console (shake-to-open) · Crash reporter activity · model status
badge (drawer).

## A18. Desktop apps

- **Windows (`apps/desktop` + `platform-impl/windows`)**: Compose Desktop UI —
  Status/Capabilities, Apps search, Goals board, **Policy screen**, **Audit
  screen** — plus `--cli` mode. Capability surface: base-dir-confined files,
  Toolhelp32 process listing/launch/terminate, bounded shell runner, DPAPI
  secrets vault, User32/GDI desktop automation (window activation, SendInput
  click/type/scroll, screenshots), system info / battery (WMI), Start Menu app
  index with fuzzy search, goals execution through the same policy spine.
- **Desktop extras**: `DesktopModelImporter` (GGUF), `GoalsStore`, `DesktopPolicy`,
  `ExecutionAudit`, `AuditExporter`/`PolicyExporter` (CSV), `ProximityCli`
  (Quick Share over LAN from the CLI), `DesktopCommandDispatcher` (typed peer
  commands), `DesktopExecutionRouter` + `DesktopGoalExecutor` (same policy
  ladder as Android).
- **macOS (`apps/macos` + `platform-impl/macos`)**: the mesh's fourth body —
  Compose Desktop window (sync status, pairing, memory — `SyncScreen`) + CLI
  over the shared desktop-sync engine; macOS capability adapters behind the
  contract.
- **Shared desktop sync engine (`shared/desktop-sync`)**: identity
  (load-or-generate), LAN transport loop over the Room journal
  (`~/.aegis/sync.db`, bundled sqlite), memory materialization to
  `~/.aegis/memory.json`, text-code pairing + canonical SAS.

## A19. Platform implementations (`platform-impl/*`)

- **android** — files, processes, shell, desktop, secrets, system adapters +
  LiteRT-LM engine/provider.
- **windows** — full Win32 capability surface (JNA) + GGUF model provider +
  app index.
- **macos / ios** — capability contracts behind `platform-api`; iOS app shell
  **planned** (Track I needs Mac/Xcode + actuals).

## A20. iOS (planned)

`apps/ios` + `platform-impl/ios` targets compile the platform-free brain
(shared:core/platform-api/model-api compile for iosArm64/iosSimulatorArm64);
the app body, Keychain/CryptoKit actuals, and UI are a Mac/Xcode job — not
started.

### Status legend

- **Landed** — implemented, wired, and compiling (per ARCHITECTURE.md slice log).
- **Flagged (default on/off)** — implemented behind `FeatureFlags`; default
  state in parentheses where it matters.
- **Planned** — declared structure/targets exist; body pending (explicitly
  flagged, never a silent stub).
- **Deliberate gaps** — e.g. UIA COM patterns pending on Windows; iOS body
  pending; HarmonyOS NEXT out of scope.

---

# Part B — Version Reference

The single source of truth for every pinned version. The **build files**
(`build.gradle.kts`, `package.json`, workflow YAMLs, wrapper properties) are the
ground truth — this list is generated from them and must be updated in the
**same change** that moves any version (AGENTS.md R8: versions travel in
couples). If this list and a build file disagree, the build file is what
actually compiles; fix this list. The pinned-baseline summary AGENTS.md rules
cite is the "Baseline" table in `AGENTS.md`.

## B1. Build toolchain (root build.gradle.kts, wrapper, CI)

| Component | Version | Declared in |
|---|---|---|
| Gradle wrapper | **9.7.0** | `gradle/wrapper/gradle-wrapper.properties` |
| JDK / JVM target | **17** (all modules, incl. `compileJava`/`targetCompatibility`) | every module `build.gradle.kts` + `actions/setup-java@v4` (`temurin`, 17) |
| Kotlin (`org.jetbrains.kotlin.jvm` / `.multiplatform` / `.plugin.compose`) | **2.4.10** | root `build.gradle.kts` |
| KSP (`com.google.devtools.ksp`) | **2.3.11** | root `build.gradle.kts` — standalone versioning since Kotlin 2.x, no lockstep format |
| AGP (`com.android.application` / `.library` / `.kotlin.multiplatform.library`) | **9.3.1** | root `build.gradle.kts` — couples to Gradle 9.7.0 + JDK 17; built-in Kotlin (`kotlin { compilerOptions { jvmTarget } }`) |
| compileSdk / targetSdk / minSdk | **36 / 36 / 26** | `apps/android`, `platform-impl/android`, every KMP `android {}` block in `shared/**` |
| Compose compiler | from Kotlin plugin (**2.4.10**) | no separate version — Kotlin 2.0+ ships it |
| Room Gradle plugin (`androidx.room`) | **2.8.4** | `shared/database/build.gradle.kts` |

## B2. Compose (UI)

| Artifact | Version | Declared in |
|---|---|---|
| Android Compose BOM (`androidx.compose:compose-bom`) | **2026.06.01** | `apps/android` — `material3`, `material-icons-extended`, `ui`, `ui-tooling-preview` resolve from the BOM; `ui-tooling` is `debugImplementation` |
| Compose Multiplatform plugin (`org.jetbrains.compose`) | **1.7.1** ⚠️ | `apps/desktop`, `apps/macos` — see [Known drift](#b10-known-drift) |

## B3. Room + SQLite storage stack (`shared/database`)

| Artifact | Version | Where used |
|---|---|---|
| `androidx.room:room-runtime` | **2.8.4** | `commonMain` (api) |
| `androidx.room:room-ktx` | **2.8.4** | `androidMain` |
| `androidx.room:room-compiler` | **2.8.4** | KSP configs: `kspAndroid`, `kspDesktop`, `kspIosArm64`, `kspIosSimulatorArm64`, `kspMacosArm64` |
| `androidx.room:room-testing` | **2.8.4** | `apps/android` androidTest (migration tests) |
| `androidx.sqlite:sqlite-bundled` | **2.7.0** | `commonMain` (api) / desktop driver |
| `androidx.sqlite:sqlite-framework` | **2.7.0** | `appleMain` — native driver over OS libsqlite3 (`-lsqlite3` linkerOpt) |
| `androidx.sqlite:sqlite-ktx` | **2.7.0** | `androidMain` |
| `net.zetetic:sqlcipher-android` (SQLCipher) | **4.17.0** | `androidMain` — successor artifact of the deprecated `android-database-sqlcipher` |
| `org.json:json` | **20260719** | `shared/desktop-sync`, `apps/desktop` (memory-profile journal payloads) |

## B4. Kotlinx

| Artifact | Version | Where used |
|---|---|---|
| `kotlinx-coroutines-core` | **1.11.0** | everywhere except `apps/desktop` + `apps/macos` — see [Known drift](#b10-known-drift) |
| `kotlinx-coroutines-android` | **1.11.0** | `apps/android` |
| `kotlinx-coroutines-test` | **1.11.0** | `shared/model-api`, `platform-impl/windows`, `platform-impl/macos` |
| `kotlinx-datetime` | **0.8.0** | `shared/database` commonMain |

## B5. Networking / discovery / native bindings

| Artifact | Version | Where used |
|---|---|---|
| `org.jmdns:jmdns` | **3.6.3** | `shared/sync` jvmAndroidMain (mDNS LAN + proximity discovery) |
| `com.squareup.okhttp3:okhttp` | **5.4.0** | `shared/sync` androidMain (relay WebSocket client) |
| `net.java.dev.jna:jna-platform` | **5.19.1** | `shared/sync` jvmMain (DPAPI), `platform-impl/windows` |
| `net.java.dev.jna:jna` | **5.19.1@aar** | `apps/android` (Vosk native bridge) |

## B6. On-device AI / model stack

| Artifact | Version | Where used |
|---|---|---|
| `com.google.ai.edge.litertlm:litertlm-android` (LiteRT LM) | **0.16.0** | `platform-impl/android` — engine + provider; ships Kotlin 2.3 metadata, read natively by Kotlin 2.4.10 |
| `de.kherud:llama` (java-llama.cpp) | **4.2.0** | `platform-impl/windows`, `platform-impl/macos` — desktop GGUF provider |
| Vosk (local AAR) | **0.3.75** | `apps/android/libs/vosk-android-0.3.75.aar` (vendored file) |
| `com.google.mlkit:text-recognition` | **16.0.1** | `apps/android` — on-device OCR |
| `com.google.mediapipe:tasks-text` | **1.0.0** | `apps/android` — on-device text embeddings |

## B7. Other Android / JVM libraries (`apps/android` unless noted)

| Artifact | Version | Notes |
|---|---|---|
| `androidx.activity:activity-compose` | **1.13.0** | |
| `androidx.lifecycle:viewmodel-compose` / `viewmodel-ktx` / `runtime-compose` | **2.11.0** | |
| `androidx.security:security-crypto` | **1.1.0** | also `platform-impl/android` (vault primitive) |
| `androidx.fragment:fragment-ktx` | **1.8.9** | |
| `androidx.core:core-ktx` | **1.19.0** | |
| `androidx.biometric:biometric` | **1.1.0** | |
| `androidx.work:work-runtime-ktx` | **2.11.2** | background jobs |
| `org.mozilla:rhino` | **1.9.1** | JS sandbox |
| `com.google.zxing:core` | **3.5.4** | 2FA QR |
| `junit:junit` | **4.13.2** | unit tests, all JVM modules |
| `androidx.test:runner` | **1.7.0** | androidTest |
| `androidx.test.ext:junit` | **1.3.0** | androidTest |

## B8. App / package metadata

| Item | Value | Declared in |
|---|---|---|
| Android `applicationId` / namespace | `com.newax.aegis` | `apps/android` |
| `versionCode` / `versionName` | **1 / 0.1.0** | `apps/android` |
| Release signing | `keystore.jks` (RSA 2048), creds in gitignored `keystore.properties` | `keystore.properties.example` template; debug-signing fallback when absent |
| Relay package version | **0.1.0** | `relay/package.json` |
| Node (relay) | **>= 18** engines; **`node:20-alpine`** base image | `relay/package.json`, `relay/Dockerfile` |
| `ws` (relay) | **^8.18.0** | `relay/package.json` |

## B9. CI (GitHub Actions)

| Item | Version | Where |
|---|---|---|
| Runners | `ubuntu-latest`, `windows-latest`, `macos-14` | all workflows |
| `actions/checkout` | **@v4** (opencode.yml uses **@v6**) | all workflows |
| `actions/setup-java` | **@v4**, JDK 17, temurin, gradle cache | android.yml, apple.yml, invariants.yml |
| `actions/upload-artifact` | **@v4** | android.yml (APK artifact, 7-day retention) |
| `reactivecircus/android-emulator-runner` | **@v2**, api-level **29** | android.yml instrumented DB migration tests |
| `anomalyco/opencode/github` | **@latest** (floating) | opencode.yml |

## B10. Known drift / inconsistencies (do not "fix" silently)

1. **Compose Multiplatform 1.7.1 vs AGENTS.md baseline 1.11.1.** The build
   files for `apps/desktop` and `apps/macos` pin `org.jetbrains.compose`
   **1.7.1**, and their comments still say "locked to Kotlin 2.1.0 (the repo
   baseline)" — both stale relative to Kotlin 2.4.10. AGENTS.md's baseline
   table claims **1.11.1** (the lockstep pairing for Kotlin 2.4.10). The build
   files are what actually compiles today. Aligning to 1.11.1 is a version
   decision (R8: touches both apps at once, needs a JDK-17 host to verify) —
   make it deliberately, and update the stale comments in the same change.
2. **kotlinx-coroutines 1.9.0 in `apps/desktop` + `apps/macos`** vs **1.11.0**
   everywhere else (including `kotlinx-coroutines-test:1.9.0` in
   `apps/desktop`). Works today, but a future bump should converge on 1.11.0.
3. **`anomalyco/opencode/github@latest`** is a floating tag — not pinned to a
   stable SHA/version.
4. **AGENTS.md's own text** cites `Compose Multiplatform 1.11.1` in the baseline
   table; the table is updated in the same PR that moves the version, so the
   table will become accurate the day the bump happens.

## B11. Version couplings that matter (summary)

- **Kotlin 2.4.10 ↔ KSP 2.3.11** — standalone versioning, no lockstep format.
- **AGP 9.3.1 ↔ Gradle 9.7.0 ↔ JDK 17** — the AGP/Gradle/JDK triplet; CI and
  local builds must match.
- **Kotlin ↔ Compose Multiplatform** — CMP must track the Kotlin major line
  (see drift item 1); the Compose compiler ships with Kotlin itself.
- **Room 2.8.4 ↔ androidx.sqlite 2.7.0 ↔ per-target KSP** — a Room or sqlite
  bump touches `room-compiler` on every KSP target config at once.
- **LiteRT-LM 0.16.0 ↔ Kotlin** — ships Kotlin 2.3 metadata; Kotlin 2.4.x reads
  it natively (no `-Xskip-metadata-version-check`).

Full detail: `docs/rules/compatibility.md`.
