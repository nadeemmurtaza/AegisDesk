# Hierarchical Agent Memory (schema v14)

The three-layer scoped memory for the multi-agent system (docs/SYNC_DESIGN.md
extends the mesh; this doc extends the memory model). Built on the repo's
existing seams: Room (shared:database), the append-only sync journal (event
sourcing), and the encrypted mesh.

## The three layers

| Layer | Table | Scope | Syncs? |
|---|---|---|---|
| **L1 Global "Library"** | `library_entries` | Shared, agent **read-only**. Goals, preferences, project docs, learned fixes. | ✅ (collective learning) |
| **L2 Agent "Scratchpad"** | `agent_scratchpad` | **Private, isolated** per agent. TTL-scoped working state. | ❌ never — isolation is the point |
| **L3 "Handoff" state** | `handoffs` | Shared **write**. Clean summary artifact + pointer (`refId`). Agent A finishes a sub-task → writes the artifact → passes the pointer to B. | ✅ (relayed store-and-forward) |

Supporting layers:

| Purpose | Table | Notes |
|---|---|---|
| **Episodic memory** (the "periodic" layer) | `episodes` | Chronological records with `outcome` + `lesson`. Agents learn from mistakes (FAILURE episodes carry the distilled fix) and keep temporal awareness (`occurredAtMs`, `contextRef`). Syncs. |
| **Zero work duplication** | `work_log` | One `(action, resource)` done once — the swarm skips finished work. Device-local (the swarm shares one DB). |
| **Embeddings** | `embeddings` (v1) | ACTIVE library entries + episodes are indexed into `VectorStore` (types `library`/`episode`); `recall()` merges semantic vector + keyword + lessons. Offline-first: the embedder is best-effort, keyword always answers. |

## The production-memory requirements, mapped

1. **Zero work duplication** — `work_log` unique `(action, resource)`: a
   Researcher that scraped URL X logs it; the Analyst sees `DONE` and moves on.
2. **Collective learning** — FAILURE `episodes` with lessons journal into the
   mesh; every device's `lessonsLearned` feed inherits the fix. Approved
   `library_entries` (ACTIVE) propagate the same way.
3. **Drastically lower costs** — `recall(query, limit)` returns only the tiny
   relevant snippets an agent needs, merging (a) semantic vector search over
   indexed ACTIVE library entries + episodes, (b) keyword match over ACTIVE
   library entries, (c) matching lessons. Agents never pull whole chat
   histories. The assistant body consumes this at inference time through
   `VectorMemorySearch.search` (MainViewModel's chat + command path) — memory
   is read where the model reasons, not just in the UI.
4. **Conflict resolution primitives** — background `distill()` (three passes;
   runs on every 15-min sync cycle AND from the Distill button):
   - exact duplicate of an ACTIVE claim (same category + title + content) →
     the new PENDING copy is REJECTED (zero duplication, original stays
     authoritative);
   - high-confidence (≥90) non-conflicting claim → auto-APPROVED (non-critical
     learning flows without a human round-trip);
   - everything else — including any same-category conflicting claim — stays
     PENDING for the human gate;
   - **episodic → semantic consolidation** — a lesson repeated across ≥ 2
     FAILURE episodes is promoted to an ACTIVE `learned` library entry
     (confidence 90, source `consolidation`), journaled + indexed;
   - **forgetting/decay** — ACTIVE claims older than 90 days with no
     re-confirmation lose 10 confidence per pass; below 40 they are REJECTED
     (forgotten) and dropped from the vector index. Episodic memory never
     decays (chronological records persist by design). Recall is recency-
     boosted: claims confirmed in the last 30 days rank above stale ones.
5. **Atomic operations** — every syncable write goes through the append-only
   journal (event sourcing): opId dedup, LWW per key, tombstones. No bare
   table writes for synced layers.
6. **Human-in-the-loop verification** — `submitKnowledge` lands as
   `PENDING_APPROVAL`; agents never see it until `approve`/`distill` promotes
   it to `ACTIVE`. Rejected entries stay in the journal for audit.

## LWW keys

- `episodes`, `handoffs`, `library_entries` → journal key = the entry id
  (`episodeId` / `handoffId` / `entryId`). RECORD kind, full-state payloads.
- Status-only entries (handoff ack, library decision) merge into the existing
  row on materialize — never clobber the full record with defaults.

## What stays local (deliberately)

`agent_scratchpad` and `work_log` are NOT in `SyncPolicy.SYNCABLE_TABLES`:
scratchpad is private working state (a Coding Agent's raw thoughts are nobody
else's business), and work-log dedupe is device-scoped.

## Producers / surfaces

- **Android**: `AgentMemory` service + the Agent Memory screen (Library gate,
  Episodic timeline + lessons, Scratchpad, Handoffs, Work log, Recall).
- **Desktop**: `DesktopSync` producer methods (recordEpisode / submitKnowledge /
  createHandoff / approve / ack) + materialize handlers + CLI (`sync episodes`,
  `sync library`, `sync know`, `sync approve`, `sync lesson`, `sync handoff`,
  `sync ack`).
