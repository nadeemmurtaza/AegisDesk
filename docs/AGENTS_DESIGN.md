# Multi-Agent Management System (schema v15)

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

## Wiring

- `AgentRegistry.init` + `SkillManager.init` run at app start (seeds built-ins after DB init).
- `MainViewModel.submit` plans every request, runs `assemble` (episodes +
  handoffs), injects the active-agent block into the LLM prompt, and passes
  the step's dominant-agent context into each part of a multi-step command.
- Agents screen: list + enable/disable + uninstall, zip import/upgrade, and a
  live routing preview showing per-step dominance.
