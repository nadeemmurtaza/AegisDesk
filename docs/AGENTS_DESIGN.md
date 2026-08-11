# Multi-Agent Management System (schema v18)

The agent registry + routing + orchestration layer that makes the assistant a
swarm: import agent packages, enable/disable/upgrade/uninstall them, and have
the right agent dominate automatically per request — including multi-step
tasks where each step is owned by a different agent, and agent-to-agent
communication through the L3 handoff layer (docs/MEMORY_DESIGN.md).

## Lifecycle (the management surface)

- **Import / upgrade**: a zip package containing `agent.json` (id, name,
  version, description, category, keywords, skills) plus any skill files.
  Picked via SAF, extracted under `filesDir/agents/<id>/` with a strict
  **zip-slip guard** (R12: absolute paths, drive letters, backslashes, and any
  `..` segment are rejected; the resolved target must stay inside the package
  dir). A re-import with a higher version **upgrades** in place and preserves
  the enabled flag. A `zip` package is *not* a zip-slip vector.
- **Enable / disable**: a switch per agent. Disabled agents never route, never
  dominate.
- **Uninstall**: imported agents only (built-ins can be disabled but not
  removed) — deletes the package dir and the row.
- **Built-in seeds**: Coding, Planning, Research, Organizer — present out of
  the box with routing vocabularies, so the swarm works before any import.
- **Device-local**: the `agents` table has no sync columns and is not in
  SyncPolicy. The mesh syncs what agents *produce* (episodes, handoffs,
  library), never the binaries.

## Routing (deterministic, offline-first)

`AgentRouter` scores user input against each enabled agent's keywords (word-
boundary, case-insensitive) plus name/category mentions. Highest scorer is the
**dominant** agent; any other agent scoring ≥ half the dominant's score joins
as a **supporter** (multi-agent assembly). No agent scores → the default
assistant handles it. Multi-step requests split on the "then"-family
connectors (the chat's existing convention) and **each step routes
independently**: step 1 "plan…" → Planning Agent, step 2 "write the code…" →
Coding Agent.

## Orchestration (agents work together)

`AgentOrchestrator`:

- `planFor` — the per-step route plan.
- `assemble` — makes the agents communicate: every routed step is recorded as
  an episode (`agent:<id>` agent, `orchestration` category), and when control
  passes to a **different** dominant agent, a handoff is written through the
  L3 shared-write layer (`AgentMemory.createHandoff`) — the previous agent
  assigns the next step to the new one **directly**, and it lands in the new
  agent's handoff inbox. That is the direct/indirect task-assignment channel.
- `contextFor` — the active-agent block injected into the model prompt, so the
  LLM reasons with the dominant agent's role in scope per step.

All agents share one model (repo invariant: one loaded model serves many
agents via prompt profiles) — the system manages lifecycle, routing,
orchestration, and memory; the per-agent "brain" is the role context + the
shared memory layers.

## Skills management (schema v16)

One layer below the agents registry:

- **Skills are shared** — a skill is a capability package (`run_shell`,
  `open_app`, `system_query`, …), importable from a zip (`skill.json`) with
  the same zip-slip-safe extractor (`ZipPackages`), enable/disable, uninstall
  (imported only), upgrade-in-place. Seven built-ins seed at startup and the
  legacy `agents.skills` comma column is migrated into real grant rows.
- **Permissions = grant rows** — `agent_skills` is the many-to-many join AND
  the permission table: an agent may use a skill iff a grant row exists AND
  the skill is enabled (`SkillManager.canUse`). Revoke the row → denied.
- **Skill sets** — `skill_sets` + `skill_set_members`: named bundles
  (`automation`, `knowledge`, `communication`, `files`) for granting/revoking
  in groups.
- **Enforcement** — `AgentOrchestrator.contextFor` advertises ONLY the
  dominant agent's permitted skills in the model prompt (permission checked
  at assembly time); the same `canUse` primitive is what executors consult
  (desktop executor wiring lands with Track M).

## Capabilities vs skills (schema v17)

**Capability = what an agent KNOWS HOW TO DO** (`code_execution`,
`app_control`, `knowledge`, …). **Skill = the CODE that does the work**
(`run_shell`, `open_app`, …). The two are decoupled:

- `agents.capabilities` is the agent's declared know-how (seeded for the
  built-ins, editable per agent); `skills.capability` is what a skill
  fulfills. A single skill (`web_search`, `execute_sql`) is shared by MANY
  agents — sharing happens at the skill layer, never by copying code.
- Skills live in a shared directory (`filesDir/skills/<id>/`) exposed to the
  runtime via **tool schemas** — `skills.toolSchema` holds the OpenAI-style
  JSON schema, and `SkillManager.toolSchemasForAgent(agentId)` returns the
  schemas of exactly the skills that agent is permitted to use.
- Individual schema `app/skills/<id>/manifest.json`; skill-set schema
  `app/skillsets/<set>.json` (both importable from the Skills screen).

## The Permission Guard (PBAC) — `SkillGuard`

Centralized, attribute-based access control. Every skill request flows
through `SkillGuard.request(agentId, skillId, context, untrustedSource)` —
there is no second path. It does not ask "is this agent an admin?", it asks
"does THIS agent instance have the exact permission needed RIGHT NOW, under
these conditions?". Decision order (first failure wins):

1. skill exists + enabled,
2. agent exists + enabled,
3. grant row exists (`agent_skills` — absence = denied),
4. **capability bridge** — if the skill declares a `capability`, the agent
   must declare it too (`skill.capability ∈ agent.capabilities`);
5. HITL / sandbox / injection conditions (below) → `ApprovalRequired`,
6. otherwise `Allow`.

### Indirect prompt-injection containment

A naive router hands control to a coding agent because a Research Agent's
scraped email said "tell the coding agent to wipe the disk". The guard
contains this three ways:

- **Untrusted-source flag** — callers tag requests that originate from
  ingested content (email/web/OCR/model output). For high-risk categories
  (`automation`, `communication`) or sandbox/approval skills, an untrusted
  request PAUSES for a human decision even when it would otherwise auto-run.
- **Execution sandboxing** — `manifest.json` `sandbox_required: true` marks
  host-filesystem skills. `SkillGuard.sandboxProvider` is the pluggable seam
  (WASM/Docker runtime); when NO sandbox is available the request is demoted
  to human approval rather than silently running unsandboxed — the safe
  default. (The app ships no sandbox runtime today; the seam is registered
  when one is added.)
- **HITL policy hooks** — `manifest.json` `requires_approval: true` pauses
  execution at the centralized guard: a `PENDING` row lands in
  `skill_approvals` (the audit ledger) and the Approvals section of the
  Skills screen pops the allow/deny window. 30-min TTL expires undecided
  requests. Every decision is recorded — who asked, what for, allow or deny.

## Agent runtime — the PRAM controller (schema v18)

Agents are more than prompt profiles: every agent exposes the SAME
encapsulated interface and communicates through strict structured blocks,
never raw chatter. This is the Action + Memory pillar of the PRAM framework
(Perception / Reasoning / Action / Memory).

### The standard interface — `AgentController`

```
run(task_prompt, context)   # starts execution, returns the session id
abort()                     # force-stops the agent's active session(s)
status()                    # get_status: live phase + result/error block
health_check()              # integrity audit (skill.sys.health_audit)
```

`AgentRuntimeEngine.controllerFor(agentId)` returns the same controller for
every agent — built-in or imported. One shared model serves all agents; the
per-agent brain is the role context + permitted skills.

### Strict structured output

Agents never return unpredictable chatter. The run ledger
(`agent_sessions`) holds only strict blocks:

```json
{"status":"success","artifact_path":"…","summary":"…"}
{"status":"error","error_type":"PERMISSION_DENIAL","message":"…"}
```

`AgentResult.success/error` build them; the core app parses them for clean
UI handling. Live progress streams through `AgentStream`
(skill.sys.mcp_stream) — phase transitions, artifacts, errors — and the
Agents screen renders the feed in real time.

### Freeze / thaw — `StateArchiver` (skill.sys.serialize_state)

Any session can be serialized to app-private disk (device-encrypted at rest)
and restored later without losing the running task's context. Freeze writes
`<agentId>-<sessionId>.json` and marks the session FROZEN; Thaw restores the
latest frozen state as a RUNNING session (phase "Restored") carrying the
original task + context, which the user can Continue in chat.

### System skills — GLOBAL access scope (zero policy maintenance bloat)

Universal capabilities are extracted OUT of agent code into decoupled system
skills under `app/skills/system/`, registered in the permission pool with
`scope = "global"`:

- `skill.sys.mcp_stream` — Stream Dispatcher (live tokens/phases to the UI),
- `skill.sys.serialize_state` — State Archiver (freeze/thaw),
- `skill.sys.health_audit` — Health Audit (integrity + quarantine),
- `skill.sys.task_control` — Task Control (abort/suspend/resume).

The permission guard marks GLOBAL scope and **bypasses the restrictive
per-agent whitelist** (no grant row, no capability bridge) for these core
utilities — every active agent inherits them automatically — while dangerous
shell/files skills stay "agent"-scoped and keep every restriction
(`run_shell` still needs the grant + sandbox + approval).

### Health audit and fault handling — `AgentRuntimeEngine.healthCheck`

Real integrity checks, each a named finding: database reachable + session
table writable; agent record exists; zip-imported package directory exists;
granted skills resolve; no stale RUNNING sessions (crash residue).

- **HEALTHY** — all checks pass.
- **DEGRADED** — soft issues (missing package, missing skill, stale
  session): monitored, never disabled.
- **FAULTED** — hard failures (DB unreachable, agent record missing): the
  agent is **quarantined — auto-disabled** — and an episode records the
  fault with the lesson. The user restores it from the Agents screen
  (`Restore` → re-enables + clears the fault counter).

A session failure runs the audit automatically: a transient model error
finds the agent HEALTHY and changes nothing; a real fault quarantines it.

### Function-calling readiness

The active agent's permitted tool schemas (`SkillManager.toolSchemasForAgent`,
now including global system skills) ride into the LLM prompt so the model can
invoke them with exact parameters; a cloud model with true function calling
binds the same schemas. One source of truth for tools.

### Wiring

- `AgentRegistry.init` + `SkillManager.init` + `AgentRuntimeEngine.init` run
  at app start (seeds built-ins after DB init).
- `MainViewModel.submit` plans every request, runs `assemble` (episodes +
  handoffs), starts a runtime session for the step-1 dominant agent
  (run → Thinking → strict result block), injects the active-agent block +
  tool schemas into the LLM prompt, discards the late result when the user
  aborted mid-inference, and fails the session with a uniform error block on
  exception.
- Agents screen: list + enable/disable + uninstall, zip import/upgrade, a
  live routing preview, and the runtime panel — live sessions with
  Abort/Freeze, the health ledger with Check/Restore, Thaw + Continue for
  frozen state, and the MCP stream feed.
