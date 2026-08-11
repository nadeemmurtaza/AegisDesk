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

## Wiring

- `AgentRegistry.init` runs at app start (seeds built-ins after DB init).
- `MainViewModel.submit` plans every request, runs `assemble` (episodes +
  handoffs), injects the active-agent block into the LLM prompt, and passes
  the step's dominant-agent context into each part of a multi-step command.
- Agents screen: list + enable/disable + uninstall, zip import/upgrade, and a
  live routing preview showing per-step dominance.
