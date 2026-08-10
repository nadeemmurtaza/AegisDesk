# ARCHITECTURE.md — Aegis Assistant invariants

This document is the architectural contract of the repository. `AGENTS.md` is the operational rules file; this is the "why". Any PR that changes the architecture, the module map, the authority model, or the platform matrix must update this document in the same change. A PR that violates an invariant below is not mergeable — even if it builds.

**Product thesis:** Aegis is an offline-first (90%), multiplatform personal assistant — "a second me" — built as **one universal runtime, one memory fabric, one authority spine, and many thin specialized agents**, on Android + desktop bodies, with a shared KMP brain.

---

## Part 1 — The ten rules (invariants, not guidelines)

1. **Model output NEVER directly executes OS operations.** The LLM produces proposals, never side effects. Every executable operation is a typed `ProposedAction`/ToolCall.
2. **Every executable operation is a typed ToolCall/Action.** There is no free-form "do this string" path. The planner reasons over types, not raw command text.
3. **Every action passes through the authority spine** (`AuthorityManager` today; a richer `PolicyEngine` as it evolves). No bypass exists — including for agents, automation, background jobs, retries, and migrations.
4. **Every privileged operation carries metadata:** caller, origin, requested capability, target, parameters, privilege requirement, audit ID. Credentials are references (`AVAILABLE`/`MISSING`), never prompt content.
5. **Desktop automation uses semantic APIs before coordinates.** Hierarchy: native API/CLI → browser DOM → UI Automation/Accessibility → accessibility nodes → vision → keyboard/mouse coordinates. Coordinates are the last resort, never the first.
6. **The privileged service contains no LLM.** The elevated surface is a narrow typed RPC server, nothing more.
7. **Normal Aegis never requires permanent elevation.** Only the privilege broker runs elevated, with an allowlisted typed surface.
8. **Every modification is auditable.** You can reconstruct what changed, who/what requested it, and the policy decision.
9. **Android remains working throughout the migration.** `:apps:androidApp:assembleDebug` is green on every PR (CI enforces).
10. **PLAN is never EXECUTE.** A generated plan, a task DAG, a goal — none of it grants execution authority. Authority is granted per action, per policy, per approval.

Corollary (the permission/policy distinction): *permission* = can the OS/account do this; *policy* = should Aegis do it automatically. User granting Administrator ≠ model may automatically do everything. `PrivilegeLevel` (READ_ONLY → HIGH_IMPACT_SYSTEM) maps to policy modes (AUTO → CONFIGURABLE → APPROVAL → STRONG CONFIRMATION), and the mapping is user-controllable.

## Part 2 — Agent system contract

**One runtime, not one brain per app.** Agents are thin domain specialists sharing one planner, one memory fabric, one policy engine, one execution system, and — critically — **one loaded reasoning model** served through prompt profiles (context + tool allowlist + policy + verification rules change; the model does not).

Taxonomy (each layer is narrower than the one above):

| Concept | Responsibility | Example |
|---|---|---|
| **Agent** | Understands a domain, plans work, chooses skills, tracks state | WhatsApp Agent, Coding Agent |
| **Skill** | Performs one narrowly defined capability, app-agnostic | `CREATE_POST`, `UPLOAD_MEDIA`, `PATCH_FILE` |
| **Procedure** | How to perform a skill inside a particular app/version (versioned: package, app version, screen signature, steps, pre/postconditions, success criteria, confidence) | Facebook v512 post procedure |
| **Tool** | Low-level primitive behind a guard | file read, Intent, accessibility tap, HTTP, Git |
| **Planner** | Decomposes goals → task DAG, selects agents/skills | Global Planning Agent |
| **Executor/Verifier** | Runs operations, determines success | ExecutionRouter tiers |
| **Memory** | Facts, procedures, failures, preferences, learned patterns | AegisDatabase |

Capability resolution ladder (a skill resolves to the cheapest available implementation): official API → deep link → Intent → learned procedure → accessibility nodes → screen grounding → vision/LLM fallback. The repo already seeds this in `ExecutionRouter` (`ANDROID_API → INTENT → DEEP_LINK → STORED_PROCEDURE → ACCESSIBILITY_SEMANTIC → SCREEN_GROUNDING → VISION → LLM_REASONING`).

Agent rules:
- Agents are registered in a registry (id, domain, version, skills, permissions, model profile, memory policy, risk policy, state machine: IDLE → PLANNING → EXECUTING → VERIFYING → COMPLETED, plus PAUSED/FAILED/CANCELLED/WAITING_FOR_USER).
- Agent-origin actions are **stricter** than user-origin (`ActionOrigin.AGENT` respects the background auto-execute ceiling). Background-generated actions retain stricter rules than direct commands.
- Agent-to-agent communication is **typed handoff objects** (`ResearchResult`, `CampaignBrief`, `ContentPackage`), not free-form text.
- Imported agents arrive as **`.aegis-agent` zips, DENIED-by-default**: extension check, size bounds, zip-slip/path-traversal protection, manifest schema validation, SHA-256 fingerprint, extraction to private storage, registry insert; tool claims in the manifest are *requests* — the user's per-agent permission grant is what enables them. Credentials are declared as references only.
- **Only one heavy cognitive agent executes at a time** (RAM rule). Cheap deterministic skills run without loading the LLM.

Coding Agent specifically: typed skills over a bounded shell (`PATCH_FILE` carries `expectedSha256`; genuine terminal commands go behind a bounded shell runner with policy + audit). Never unrestricted filesystem + shell + network authority handed to the model. Persistent memory scopes: repository memory, task memory, learning memory.

## Part 3 — Offline model & the 90/10 rule

- Every skill is tagged `OFFLINE_OK` (deterministic, or model with local context), `REQUIRES_ONLINE` (messaging APIs, web, live data), or `DEFER` (queue + notify when connectivity returns).
- Deterministic-first: exact lookups, typed tools, procedures — the model is loaded only when a task genuinely requires reasoning.
- `ModelProvider` is an interface with platform implementations: Android → `LiteRtModelProvider`; desktop → llama.cpp/GGUF provider (import, SHA-256 verify, RAM/VRAM estimation, context config, streaming, cancellation, unload/reload). Planner code never couples to GGUF or llama.cpp.
- On-device pieces in use: LiteRT LM (0.14.0), Vosk (speech), ML Kit (OCR), MediaPipe (embeddings). All local.

## Part 4 — Memory fabric

The differentiator: shared, cross-platform memory of *you*.

| Layer | Stores | Repo asset |
|---|---|---|
| Episodic | what happened (conversations, meetings, logs) | `memory_records`, CommunicationLog |
| Semantic | facts about the world | KnowledgeGraph (triples, entities, predicates) |
| Procedural | how to do things (learned procedures) | UiProcedure entities (versioned, screen signature, success counts) |
| Personal | people, relationships, commitments | PersonRegistry, PersonFact, Commitments |
| Agent state | per-agent namespaced state | `agent:<id>:<key>` in the shared DB — shared data, isolated state |

All layers live in `shared:database` (Room KMP), so Android and desktop share one database format and schema (`schemas/12.json` + migrations 1→12).

## Part 5 — Module map & platform matrix

```
AegisAssistant/
├── apps/androidApp/        Android application (Compose UI, LiteRT, Vosk, ML Kit, services) — must stay green — authority spine wired (Track A2): MainViewModel routes actions through the PolicyEngine (PolicyHolder) via AuthorityManager.apply, and the Capabilities screen hosts the Policy settings section (per-action-class modes, hard deny, audit trail); landed (Track A4): GoalExecutor evaluates every privileged task through the policy spine as AGENT origin before running it — non-AUTO_EXECUTE decisions fail the task (rule 10: plans grant zero execution authority) — via SkillRegistry.policyActionFor; landed (Track A5): goals survive restarts — GoalPlanner.snapshot (goal, task graph with live statuses, goal state, plan pre-flight) encoded with org.json into the existing kv_store table (no schema change) via DbGoalSnapshotStore, rehydrated by GoalPlanner.restore at bootstrap (RUNNING tasks revert to PENDING so re-runs pick them up; BLOCKED/COMPLETED/ABANDONED states seed exactly); landed (Track A6): execution-time policy refusals surface distinctly — GoalExecutor classifies failures (TaskFailureKind POLICY/CAPABILITY, persisted with the task), the Goals screen renders policy-blocked tasks in amber with a direct "Policy modes" action that jumps to the Capabilities screen and scrolls to the policy section; landed (Track A7): plan-time policy pre-flight — GoalPlanner.plan evaluates every privileged task through the policy spine as AGENT origin and warns up front (in the plan's warnings, persisted with the goal) when a task will be refused autonomously; landed (Track A8): execution audit trail — every goal run records goal, outcome, per-task tiers/statuses/timestamps (ExecutionAuditHolder → org.json → kv_store, ring-capped), surfaced as a "Recent runs" section on the Goals screen
├── apps/desktopApp/        JVM application (Compose Desktop as it lands) — landed (Phase 5e): runner bootstraps DesktopCapabilitiesHolder (platform capability registry with WindowsDesktopCapability) + DesktopModelProviderHolder (one ModelProvider per process, fallback swap), surfaces capability/model status in the CLI; landed (Phase 5f): planner wired — SkillRegistry + DesktopGoalPlanner resolve skills through CapabilityResolver against the process registry ("plan" and "skills" commands surface missing capabilities with reasons, mirroring Android's Goals screen); landed (Phase 5g): goal lifecycle — StateMachine/GoalState port of Android's engine/state, TaskStatus/TaskNode/TaskGraph with progress, "goals"/"run"/"abandon" commands drive the state machines; landed (Phase 5h): real execution — DesktopGoalExecutor walks the plan's tasks through the DesktopExecutionRouter ladder (PROCESS_LAUNCH → WIN32_AUTOMATION via WindowsDesktopCapability.activateApp) with a live per-task capability gate and the find_app→launch_app output pipe, replacing the manual task command; landed (Phase 5i): app index — find_app resolves targets against WindowsAppIndex (Start Menu enumeration) and launch_app runs the exact .lnk target via the EXACT_TARGET tier, with an "apps [query]" command; landed (Phase B1): Compose Desktop UI — the window surface replaces the CLI as the default (`--cli` keeps the terminal loop); Status/Capabilities, Apps (search), and Goals board screens lift the CLI logic into plain-Kotlin testable state holders
├── shared/core/            KMP (jvm + android): actions, planner, authority, capability — platform-free — landed (Track A1): PolicyEngine (rule 3 corollary — RiskLevel→AUTO/CONFIGURABLE/APPROVAL/STRONG_CONFIRMATION mapping, user overrides + hard deny via PolicyStore, machine-origin ceiling, audit records) + AuthorityManager.apply + SecureSettingsPolicyStore; landed (Track A4): ActionOrigin.AGENT (autonomous-executor origin, stricter than user — machine-origin ceiling applies to both BACKGROUND and AGENT) + PolicyDecision.allowsAutonomousExecution (rule 10)
├── shared/database/        Room KMP 2.7.0-alpha13 (android + jvm("desktop")): entities, DAOs, migrations, expect/actual
├── shared/platform-api/    Platform capability contracts (files, processes, shell, desktop, secrets, system) — contract layer landed (Phase 3); adapters pending
├── shared/model-api/       ModelProvider contract + deterministic fallback — landed (Phase 5a); platform providers: Android LiteRT (Phase 5b, platform/android), desktop GGUF (Phase 5c, platform/windows)
├── platform/android/       Android adapters — landed (Phase 4): files, processes, shell, desktop, secrets, system; Phase 5b: LiteRT-LM engine + LiteRtModelProvider behind the ModelProvider contract
└── platform/windows/       Windows adapters — landed (Phase 5c): GGUF header parser + GgufModelProvider + KherudGgufEngine (de.kherud:llama JNI binding — the Maven artifact for the java-llama.cpp repo); landed (Phase 5d): WindowsDesktopCapability — Win32 native automation bridge (EnumWindows, control messaging, GDI capture) behind a testable seam; landed (Phase 5i): WindowsAppIndex — Start Menu Programs enumeration (.lnk entries: name, category, exact launch path) with fuzzy search, behind a testable seam; UIA COM patterns + DPAPI secrets pending
```

| | Android | Windows | macOS | Linux | iOS |
|---|---|---|---|---|---|
| UI | Compose | Compose Desktop (JVM) | same | same | CMP iOS (shared UI, planned) |
| DB (Room KMP) | ✅ SQLCipher | ✅ bundled | ✅ | ✅ | Native driver (planned) |
| Brain (chat/memory/people/LLM) | ✅ | ✅ | ✅ | ✅ | ✅ |
| Desktop automation | AccessibilityService | UIA (JNA) | AXUIElement | X11 XTest only | ❌ none |
| SMS/notifications | ✅ | partial | partial | partial | ❌ |
| Secrets | Keystore | DPAPI | Keychain | libsecret/KWallet | Keychain |

Rules for platform work: shared code is platform-free (expect/actual seams only); new targets add per-target KSP configs and per-target `actual`s; HarmonyOS NEXT is **not** a Kotlin KMP target (no official ohos target — community bridges only) and is explicitly out of scope until evaluated.

## Part 6 — Migration posture

- Extraction before upgrade: keep Room 2.7.0-alpha13 (alpha) and migrate deliberately; do not bundle a version upgrade with a file move.
- Each migration PR is a small mergeable slice; Android green at every step (invariant 9).
- DB changes require: entity declared in `@Database`, version bumped, migration written, schema exported, existing-install (upgrade) path covered — not just fresh-create.
- The migration plan lives with the user (39-phase plan); this file records the invariants it must not violate.

## Part 7 — How to change this document

Proposing an invariant change requires (a) stating which existing invariant weakens, (b) the concrete scenario that forces it, and (c) the compensating control. Otherwise, treat this file as fixed.