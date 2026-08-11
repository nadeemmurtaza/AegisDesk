# SYNC DESIGN — the 4-device encrypted mesh (I / A / M / W)

Date: 2026-08-11. Status: design + slice plan (S0–S7 + P1–P2). **Landed: S0 (DB substrate), S1 (engine core), S2 (identity/pairing/session crypto), S3 (LAN transport), S4 (relay), P1 (encrypted Quick Share protocol + discovery seam), P2 (Android BLE/WiFi-Direct proximity actual + Nearby Share UI + desktop CLI listener), W1 (Room-backed JournalStore + automatic loops on Android/Windows/macOS), capture slice 1 (graph + people record tables sync end-to-end).** Remaining: S5 wiring/surfaces, S6 commands, S7 files, P2-iOS (Multipeer Connectivity actual), and the rest of the syncable-table capture coverage. **S5 (unpair revocation) landed**: `peer_trust` journal records on pair/unpair (Android + desktops), re-applied via materialize; the relay's REG is now authoritative (resets the grant allowlist — the relay-side revocation mechanism, test-verified). **S4 (relay) now covers Android too**: the relay transport moved to jvmAndroidMain behind a `WsClient` seam (java.net.http on JVM, OkHttp on Android) and is wired into the Android `SyncWorker` relay phase + the SyncScreen relay URL field — WAN sync works wherever a relay is reachable. **Deploying the relay:** `relay/` is a bare Node service, already deploy-shaped (binds 0.0.0.0, reads `PORT`; install `npm --prefix relay install`, start `node relay/server.js` — see its header). The platform deploy CLIs are not available in the Linux sandbox, so the actual hosting deploy is a platform-side action; decide hosting per §14.2. **Track I (iOS) status:** audit + driver research done — `shared/sync` + `shared/database` commonMain verified platform-free (zero java./android./platform imports); Room 2.7.0-alpha13 KMP officially supports iOS (bundled sqlite driver); the remaining work — declaring the iOS targets, the 4 iosMain `actual`s (Keychain/CommonCrypto/CryptoKit-shim + Network.framework/POSIX transport + CoreBluetooth proximity), the `shared/database` iOS builder, and the `apps/iosApp` shell — **requires a Mac + Xcode and cannot be compiled or verified in the Linux sandbox**; exact steps in §15.
This document specifies how the four Aegis devices — **I** (iOS), **A**
(Android), **M** (macOS), **W** (Windows) — keep one shared reality across
four private, encrypted databases.

Decisions locked with the user:
- **Transport: Both.** LAN auto-discovery when devices are together; an
  internet rendezvous/relay when they are apart. Data is end-to-end encrypted
  on every path; the relay never sees plaintext.
- **Conflicts: CRDT for logs + LWW for the rest.** Append-only structures
  (episodic memory, command queues, the sync journal itself, audit) merge by
  unique op id — idempotent, order-stable, no data loss. Mutable records
  (people, facts, settings, goals) merge last-writer-wins per record with
  tombstones for deletes.
- **Deliverable: this design doc + slice plan.** Implementation begins with
  S0 (the one deliberate `shared/database` unfreeze) when the user approves.

---

## 1. Goal — the user's mental model, made precise

Four devices, four databases, one mind. Every device holds a full private
copy of the Aegis data (memory fabric + files + settings + commands),
encrypted at rest. Each device also maintains an **encrypted outbox** — a
change journal of everything it has done since the last sync, ready to be
sent automatically the moment any other paired device comes online.

Sync is **opportunistic and pairwise, but the mesh converges**:

- Whenever any 2 of the 4 devices are online, they compare journals and
  exchange what the other is missing, automatically.
- Data propagates through intermediate devices, so nobody has to be online
  with the origin. Example: A and I offline; W changes something → W syncs to
  M. I comes online → I pulls from M **or** W (whoever is reachable); it does
  not need W. M and I and W now all have the new data. A comes online → A
  pulls from any of I/M/W. There is no master device and no master copy.
- **Commands are data too.** A can send a *command* to I ("send the email")
  while I is offline. The command is stored and relayed by W and M. When I
  comes online it receives the command from any reachable peer and — because
  the command targets I — **only I processes it**. Every other device
  stores-and-forwards, never executes (rule 11: one path to a dangerous
  capability).

**Hard constraints that shape everything below:**

| Constraint | Why |
|---|---|
| Offline-first (90%) | Product thesis (ARCHITECTURE.md Part 3). Sync is background, never a gate on local function. |
| The relay is never trusted | It coordinates rendezvous and relays ciphertext only. No plaintext, no keys, no data access. |
| Remote data is untrusted input | R12 — every incoming record/command is validated on ingest, never executed as instruction. |
| Remote commands are AGENT-origin | Rule 10 — a command from another device grants **zero** execution authority by itself; it passes the policy spine like any agent-origin action, at the strictest ceiling. |
| Credentials are references, never content | Invariant 4 — secret *values* never sync; references (AVAILABLE/MISSING) do. |
| Pairing + sync settings are sensitive | They live in the sensitive-settings surface, biometric-gated, alongside the existing keys/settings UI. |

---

## 2. Topology & transport ("Both")

```
                ┌─────────────── internet ───────────────┐
                │                                        │
          [ relay (rendezvous + relayed ciphertext) ]    │   ← sees only E2E ciphertext
                │          ▲        │        ▲           │
     paired-control-channel  │        │   paired-control-channel
                │          │        │        │           │
   ┌────────────┴───┐  ┌───┴─────────┴──┐  ┌─┴───────────┐
   │  Device I      │  │  Device A      │  │  Device M   │ ... Device W
   │  (iOS)         │  │  (Android)     │  │  (macOS)    │
   └───────┬────────┘  └───────┬────────┘  └──────┬──────┘
           └────── LAN path: mDNS discovery ──────┘
                  (direct TCP, same E2E crypto, no relay)
```

- **LAN path (when together):** each device announces itself via mDNS/DNS-SD
  (Android + JVM desktops) carrying its identity fingerprint; a paired peer
  that discovers it opens a direct TLS-equivalent encrypted channel (section
  8). Zero configuration, works with no internet at all.
- **Internet path (when apart):** each device holds a **long-lived control
  connection to the relay** (a WebSocket). The relay keeps presence
  (who is online) and — only when two *paired* devices are both present —
  (a) helps them hole-punch to a direct encrypted connection, and (b) if
  direct is impossible (NATs), relays ciphertext between them. The relay
  maintains per-device pending-event queues so a device that was offline
  learns, on reconnect, which peers have new data for it.
- **Mobile wake-up:** Android/iOS sync opportunistically — on app open, on
  network change, and via push (FCM/APNs) that the relay triggers when a
  paired peer comes online. Desktop devices (M/W) keep the control channel
  open while the app runs; background sync uses the OS scheduling APIs.
- **The relay is optional infrastructure.** All core function works with the
  LAN path alone; the relay only widens "when 2 devices are online" to
  "online anywhere". It can be self-hosted or deployed as a small Node
  service (section 10) — the wire contract is what matters, not the host.

---

## 3. Device identity & pairing (sensitive settings)

**Identity.** Each device has one long-term **Ed25519 identity keypair**,
generated at first launch, held in the platform keystore:
Android Keystore — the sync actual landed in P2 (`AndroidSyncKeyStore`:
AES-256-GCM key in the Android Keystore/TEE wrapping the identity; the app
has `SecureKeyVault.kt` for other secrets) / iOS+macOS Keychain / Windows
DPAPI (**landed S5**: OsKeyStore — DPAPI via jna-platform Crypt32Util on Windows, JDK KeychainStore-wrapped AES-GCM on macOS, dev FileKeyStore fallback on Linux). The identity fingerprint is
`SHA-256(identityPublicKey)`, displayed as a short human string.

**Pairing (strict — the authentication the user asked for).** Two devices
pair once, then trust each other forever (until revoked):

1. The initiator shows a **QR code** containing its identity key, a
   nonce, and its display name (QR falls back to a typed 24-char key).
2. The responder scans it; both devices compute a **short authentication
   string (SAS)** — 6 digits derived from `SHA-256(keyI || keyR || nonce)` —
   and the user confirms the digits match on **both** screens.
3. Confirmed → each stores the other's identity key in its keystore, tagged
   `paired`. This is the ONLY moment a man-in-the-middle can strike, and the
   SAS is the defense (both screens, human-verified — Signal-style).
4. Pairing records are **per-device sensitive settings**, biometric-gated in
   the existing keys/settings UI: list of paired devices, name, fingerprint,
   last-seen, and **Unpair** (see revocation below).

**Revocation — LANDED (S5).** Unpairing writes a **revocation record** into
the sync journal (LWW, tombstone, `peer_trust` table keyed
`$myDeviceId\u0001$peerDeviceId` — namespaced so different revokers' rows
never collide). Because the journal propagates through the mesh, one unpair
reaches all devices even when the revoked device is offline; each device
re-applies only **its own** trust rows (`materializeTrust` on Android +
DesktopSync): a tombstone re-removes the peer (durable across a reinstall),
a live pairing record restores it, and the LWW guard keeps the newest local
decision. Enforcement of the A-revokes-B relationship is namespaced to A:
A drops B from its keystore (rejects B's handshakes) and stops granting B at
the relay — the relay's REG is authoritative and resets the allowlist, so a
revoked peer loses relay access on the revoker's next registration. A third
device C does **not** sever its own independent relationship with B based on
A's decision. A wiped device re-pairs from scratch (new identity key) — old
data it previously synced remains; it can never decrypt new outboxes without
a fresh pair, and the tombstone persists so a stolen key cannot rejoin
silently.

---

## 4. Sync model — one journal, two merge rules

The core idea: **every syncable fact is one of two kinds, and the kind
decides the merge.** This is the CRDT-for-logs + LWW-for-rest decision.

### 4.1 The journal (CRDT, append-only)

Every device appends to its local **sync journal** every mutation it makes:
one entry per created/updated/deleted record or log line, with

```
JournalEntry {
  opId        : UUID            // unique forever, generated by the writer
  deviceId    : ID              // which device wrote it
  hlc         : HybridLogicalClock  // (wallClock, counter) — causal-ish order, survives clock skew
  kind        : LOG | RECORD    // CRDT log line vs LWW record update
  table       : String          // memory_records, people, kv_store, commands, ...
  key         : String          // record primary key (or log stream id)
  payload     : bytes           // encrypted record/log delta
  tombstone?  : true            // delete marker (RECORD kind only)
  createdAt   : epochMillis     // display time
}
```

- **Merge rule (LOG):** idempotent union by `opId`. The same entry arriving
  via W then via M is deduplicated; order is `hlc` then `deviceId`; nothing
  is ever deleted from a log — new lines only append. This is a pure
  **append-only grow-only CRDT (G-CRDT)**: convergent by construction, no
  conflicts exist, no data is ever lost.
- **Merge rule (RECORD):** last-writer-wins per record. The *state* of a
  record is the entry with the max `hlc` (tie-break `deviceId`); a
  `tombstone` entry wins over all older states. Each RECORD journal entry
  carries the full record state (value-level, not field-level — keeps
  merge simple and predictable; per-field LWW is a later refinement if the
  user asks).

### 4.2 Version vectors — "what do you have that I don't?"

Each device tracks, **per peer**, the high-water mark it has received from
that peer: `deviceId → lastAppliedHlc`. When two devices meet:

1. They exchange `(myDeviceId, versionVector, journalHead)`.
2. Each sends the other every journal entry it has with `hlc > other's
   vector[myDeviceId]` (plus a bounded backlog of recent entries as a
   safety net against lost vectors).
3. Each applies incoming entries to its journal (dedup by `opId`), then to
   its tables (merge rule by `kind`), then advances the vector.

Because the journal is append-only and dedup is by `opId`, **any two devices
that have both seen a write converge to the same journal, in any order, over
any path** — which is exactly the W→M→I→A propagation the user described.
The vector is a fast-path optimization; correctness comes from the CRDT.

### 4.3 Tombstones & garbage collection

Deletes are RECORD tombstones (never physical deletes on syncable tables).
Tombstones propagate like any record and are pruned only after all paired
peers have acknowledged past the tombstone's `hlc` (safe GC: per-peer
vectors known to the writer via ack records). Until then they are cheap
rows. GC is a later-round cleanup, not a correctness concern.

### 4.4 Derived data never syncs

FTS indexes, embeddings, file text extracts, screen-node maps — anything
recomputable from synced source data is rebuilt locally (embedding of a
memory record arrives as text, each device embeds with its own model). This
keeps sync compact and avoids cross-device model-version skew.

---

## 5. What syncs — the data-class policy table

Sync categories are **user-controllable in sensitive settings** (on/off per
category, per the R13 UI in S5). The table is the contract the S1 engine
implements.

| Category | Tables / data | Kind | Notes |
|---|---|---|---|
| **Memory** | `memory_records` | LOG (append) | episodic memory — the flagship |
| **Knowledge** | `triples`, `graph_edges` | RECORD (LWW) | knowledge graph |
| **People** | `people`, `person_facts`, `person_registry`, `person_mentions` (mention ids only), `person_policies` | RECORD (LWW) | personal layer |
| **Procedures** | `ui_procedures` (versioned, success counts) | RECORD (LWW) | learned procedures; counts converge LWW |
| **Apps/triggers** | `app_records`, `triggers` | RECORD (LWW) | app registry + trigger definitions |
| **Settings** | policy overrides (`PolicyStore`), automation toggles, per-category sync prefs | RECORD (LWW), opt-in | user intent follows the user; per-device overrides are the default and syncing them is opt-in |
| **Goals & audit** | goal snapshots (A5 shape), execution audit (A8 shape) | RECORD (LWW) for goals; LOG for audit | goals sync their state machine; **RUNNING reverts to PENDING on arrival** (same rule as A5 restore) — a half-run on another device never claims completion |
| **Commands** | command inbox/outbox (section 6) | LOG | targeted actions, relayed by all |
| **Files** | `file_index` metadata (LWW) + encrypted content (section 7) | RECORD + chunks | per-category opt-in, size caps |
| **Keystore refs** | credential *status* only (AVAILABLE/MISSING) | RECORD (LWW) | invariant 4: values never leave the device |
| **Never synced** | FTS tables, `embeddings`, `file_text_content`, `screen_nodes`/`nav_edges`, secret values, the relay's own state | — | derived or device-local by design |

`kv_store` deserves a note: it is the app's general key-value bag (goal
snapshots, audit, policy store today). **Syncable keys are explicitly
namespaced** (`syncable:...`); everything else stays device-local. Sync
never reaches into arbitrary `kv_store` rows.

---

## 6. Commands — targeted actions across the mesh

A command is a typed envelope appended to the journal like any other entry,
but it carries **execution intent**, so it is the most guarded data class:

```
SyncCommand {
  opId       : UUID                 // dedup + idempotent delivery
  from       : deviceId             // signed by sender identity
  target     : deviceId             // exactly ONE device processes it
  class      : "send_email" | "open_app" | "run_goal" | ...   // allowlisted by target's policy
  payloadRef : blobRef              // typed payload (e.g. SendEmailDraft) in the encrypted blob store
  ttl        : epochMillis          // expire unprocessed commands
  ackRequired: true                 // target reports executed/refused/expired
}
```

**Lifecycle (matches the user's example exactly):**

1. A crafts `command(target=I, class=send_email, payloadRef=…)`, appends it
   to its outbox journal, and signs it.
2. W and M are online; they pull it (or A pushes it to them) and **store it
   in their own journal — never executing it** (rule 11: the sink has one
   path, and that path lives only on the target).
3. I comes online, pulls from M or W, and applies the journal entry. The
   **command dispatcher on I** is the only component that may process it: it
   resolves the payload, builds the typed `ProposedAction`, and hands it to
   the **same policy spine every local agent-origin action uses**
   (PolicyEngine as `ActionOrigin.AGENT` — rule 10: the command grants zero
   authority by itself; a REQUIRE_APPROVAL command is refused, exactly like
   A4's goal-execution gate, unless the user's mode lets it run).
4. I appends an **ack** entry (`executed | refused(reason) | expired`) back
   to its journal; A learns of it the next time any mesh path connects them.
5. Every hop validates: sender signature, target == me before dispatch,
   allowlisted `class` (per-peer command permissions, set at pairing in
   sensitive settings), `ttl` not expired, payload schema-valid (R12 —
   remote payloads are data, never instructions).

**Command permissions are per-peer sensitive settings:** at pairing, each
device grants the other a class allowlist (e.g. "A may send me `open_app`
and `run_goal`, never `send_email`"), changeable anytime. A command whose
class is not allowlisted is refused with an ack and an audit entry, not
silently dropped.

---

## 7. Files

- **Metadata** (`file_index`: name, path, size, mtime, sha256, category)
  syncs as RECORD (LWW). Content is a separate concern.
- **Content** syncs as **encrypted chunks** (1 MiB, AES-256-GCM) with a
  per-file key wrapped by the mesh key (section 8). Chunk bitmaps give
  resumable transfer — a 2 GB video interrupted mid-way resumes, not
  restarts.
- **Policy** (sensitive settings): on/off per category (documents, media,
  downloads…), per-file size cap, "keep both on conflict" vs "newest wins"
  (default: keep both when sizes differ, newest-mtime wins when identical
  size + different content is a true edit conflict).
- **Deletes** are tombstones; content is garbage-collected after peer
  acks. Never delete a file the user hasn't deleted — the tombstone is the
  deletion.

---

## 8. Encryption & trust model

| Layer | Mechanism | Notes |
|---|---|---|
| At rest, DB | SQLCipher (A) / **bundled sqlite today (M/W) — sync must not rely on DB-at-rest encryption** | Desktop DB encryption is a separate W/M-track decision; the sync layer encrypts everything it stores itself |
| At rest, journal/blob store | AES-256-GCM, per-device storage key from the platform keystore | The outbox ("Copy X") is encrypted before it is ever written |
| In transit | **E2E, every path**: X25519 + XChaCha20-Poly1305, ephemeral per-session keys (forward secrecy), identities pinned to the paired Ed25519 keys (Noise-style handshake; SAS already confirmed at pairing) | LAN direct and relayed paths use the same crypto; the relay sees only ciphertext |
| Blobs | per-file/per-command key wrapped under the *target* peer's public key | Only the target can unwrap a command payload |
| Relay | zero knowledge: presence + ciphertext relay + push triggers only | It cannot read, forge (Ed25519 signatures), or replay (opId + ttl) |

The mesh does not need a shared master secret: pairwise identity-key
exchange at pairing is the whole trust graph. No cloud account, no server
password, no third party.

---

## 9. Protocol — message shapes (transport-agnostic)

All messages are CBOR/JSON envelopes, signed where required:

```
HELLO          { deviceId, fingerprint, version, capabilities[] }
VECTOR_EXCH    { deviceId, versionVector, journalHead }        // signed
DELTA          { entries: JournalEntry[] (encrypted), fromHlc } // signed
ACK_VECTOR     { deviceId, ackedHlc }                          // advances peer's GC watermark
PAIR_INIT      { identityKey, nonce, displayName }             // QR payload
PAIR_CONFIRM   { sas, identityKey, nonce }
REVOKE         { revokedDeviceId }                             // journal entry, LWW tombstone
CMD_ACK        { opId, result: executed|refused|expired, reason? }
PRESENCE       { deviceId, online, hasNewSince: hlc }          // via relay only
```

Sync runs as a background **anti-entropy loop**: on any peer connection
(LAN or relayed), exchange `VECTOR_EXCH` → send `DELTA` both ways → apply →
`ACK_VECTOR`. The loop is idempotent and safe to run repeatedly; `PRESENCE`
triggers it when a peer comes online (the user's "ready to send
automatically").

---

## 10. The relay

- **Contract** (the only thing that matters): WebSocket endpoint with
  `PRESENCE`, `PAIR`-aware routing (relay only forwards between *paired*
  peers), pending-event queues per device, and a push trigger hook
  (FCM/APNs) for mobile wake-up. Max message 16 MiB; blobs go direct or
  chunked. No plaintext ever transits it.
- **Deployments:** (a) a minimal Node.js service (the repo's hosting image
  is Node-only, so this is the deployable shape via the Deploy flow), or
  (b) self-hosted. Same contract, either way. The relay is not part of the
  Android CI gate and is developed as its own unit (S4).
- **Threats it must survive:** offline devices (queued events),
  reconnection storms (backoff), a compromised relay (E2E + signatures
  contain it — worst case is denial of service, never disclosure).

---

## 10.1 Proximity transfer — the encrypted Quick Share (P1–P2)

A Quick Share–style capability, but every byte end-to-end encrypted
(`BlobCrypto` per message — the channel and anything sniffing it see only
ciphertext and public ephemeral keys):

- **Discovery is platform-native and transport-agnostic** (`ProximityDiscovery`
  seam): desktops use mDNS `_aegis-proximity._tcp.local.` (landed, P1);
  Android uses BLE advertise/scan to find nearby devices and a WiFi-P2P group
  for the bulk transfer (P2); iOS uses Multipeer Connectivity (P2). The
  transfer itself runs the same protocol over whatever byte channel the
  platform provides — `ProximityTransfer` is pure commonMain.
- **Key exchange** (landed, P2): before the transfer both sides swap their
  long-term ECDH public keys over the channel (`ProximityHandshake` — public
  keys only, marker-framed) so `INIT` can be sealed to the recipient's key;
  the protocol itself stays anonymous-on-the-wire and unchanged.
- **Encrypted transfer protocol** (landed, P1): `INIT` (sealed meta:
  sender id + sender ECDH pubkey, file name/size/chunks, whole-file SHA-256)
  → user-confirmation `ACCEPT`/`reject` → sequential `CHUNK i` (4-byte BE
  index, AEAD-sealed to the recipient's long-term key) → `DONE` (sealed
  whole-file hash) → `COMPLETE`. Wrong key, tamper, reorder, hash mismatch,
  decline, and timeout are explicit `Failed(stage, reason)` results.
- **v1 boundary:** whole-content in memory, files up to 1 GiB, one content key
  per chunk; a streaming variant + wrapped-single-key chunk framing is named
  future work. Files shared via proximity are NOT journaled — they are
  one-shot transfers (a "received file" record in the mesh is an S7 concern).
- **Threats:** a sniffer on the P2P/BLE path sees only ciphertext; the
  receiver's confirmation gate is the UI prompt (Quick Share semantics).

---

## 11. Failure modes (R9 — named before writing)

| Failure | Handling |
|---|---|
| Peer offline at sync time | Journal + outbox stay; sync on next connection (PRESENCE/push). No data loss — CRDT is durable by design. |
| Same record edited on two devices | LWW by HLC (tie-break deviceId) — deterministic, no user prompt for facts/settings. True semantic conflicts are the user's call later (a review queue is a post-MVP nicety, not v1). |
| Logs merged over different paths | G-CRDT: union by opId, order by HLC — same result regardless of path (W→M→I ≡ W→I). |
| Clock skew | HLC (counter + deviceId tie-break) — no wall-clock dependence for merge correctness; wall time is display-only. |
| Corrupt / tampered journal entry | Signature + schema validation on ingest; bad entry rejected with an audit line, never a crash. |
| MITM during first pairing | SAS on both screens (human-verified); failure = user aborts, nothing stored. |
| Device wipe / new identity | Old identity data stays; new identity must re-pair; remaining devices hold the tombstone/revocation so a stolen key can't rejoin silently. |
| Large file, interrupted | Chunk bitmap resume; per-category caps; never blocks the journal. |
| Mesh partition (two pairs) | Each pair converges locally; on rejoin, both journals union — logs append, records LWW — no split-brain loss (this is exactly the user's W/M/I/A example). |
| Command delivered twice | opId dedup on the target; second copy ignored, ack already sent. |
| Command's target never online / TTL expiry | Expired commands are acked `expired` on first contact and GC'd. |
| Sync disabled mid-transfer | Journal is durable; vectors resume where they stopped. |

---

## 12. Authority & invariants mapping

| Repo rule | How sync honors it |
|---|---|
| 3 / 10 (authority spine, PLAN≠EXECUTE) | Remote commands are `ActionOrigin.AGENT`, evaluated by PolicyEngine before dispatch — a command is a *request*, never authority. |
| 11 (one path to dangerous capability) | Only the target's command dispatcher can execute a command; it calls the same executor local commands use. Relays store-and-forward only. |
| 12 (untrusted input) | Every incoming entry validated (signature, schema, allowlist, ttl) before apply; payloads are data, never instructions. |
| 6 / 8 (auditability) | The journal IS an audit trail; command lifecycle (sent → relayed → executed/refused/expired) is journaled end to end. |
| 4 (credentials as references) | Only status syncs; values stay in per-device keystores. |
| 5 (platform-free shared code) | Sync engine is pure KMP (`shared/sync` commonMain, zero platform imports); crypto/keystore/transport live behind expect/actual per target. |
| 9 (Android green) | Every sync slice keeps `:apps:androidApp:assembleDebug` green; S0 is migration + export, verified per AGENTS.md. |
| R13 (no headless capability) | Pairing, per-category toggles, peer list, command permissions, and sync status all ship with screens in S5/S6. |

---

## 13. Slice plan (S0–S7) — mapped to the tracks

`shared/sync` is a **new KMP module** (jvm + android + ios targets, matching
Phase 0 of the 4-track split). The `shared/database` unfreeze is a **single
deliberate change (S0)** — one migration, one schema export, then frozen
again. Each slice is a complete, verifiable unit (AGENTS.md R1–R6).

| # | Track | Slice | Content | Verify |
|---|---|---|---|---|
| **S0** | Lead (all tracks review) | **DB unfreeze: sync substrate — LANDED.** Schema v13: `sync_journal` (append-only CRDT journal) + `sync_vector` (per-peer watermarks) + four sync metadata columns (`syncHcWall`, `syncHcCounter`, `syncDeviceId`, `syncTombstone`) on all 17 syncable tables; migration 12→13 + schema export; existing-install + DAO round-trip tests added. **The one DB change this round.** | `:shared:database:desktopJar :shared:database:assembleDebug`, connected migration tests, check-invariants |
| **S1** | A (shared brain) | **Sync engine core (pure KMP) — LANDED.** `shared/sync` module (jvm + android; ios targets with Phase 0): `Hlc` (hybrid logical clock — tick/receive), `VersionVector` (per-peer watermarks + merge/dominates), `SyncEntry` + `JournalMerge` (opId dedup G-CRDT; LWW winner with deviceId tie-break; tombstone semantics), `SyncPolicy` (the 17 syncable tables + `syncable:` kv namespacing), `WireCodec` (deterministic VECTOR_EXCH/DELTA/ACK envelopes, hex payloads, malformed→null), `AntiEntropy` (outbound delta by watermark, dedup apply, vector advance). **Deliberately independent of shared/database** (Track I cannot use Room this round); the Room mapping lands in the wiring slice. 20 unit tests incl. the W→M→I→A mesh-convergence + concurrent-write scenarios. | `:shared:sync:jvmTest` + compile; check-invariants |
| **S2** | A (shared brain) | **Identity, pairing, session crypto — LANDED.** `shared/sync` gains: pure-Kotlin SHA-256/HMAC/HKDF (RFC 2104/4231/5869 vectors); `Crypto` primitives interface + `JavaCrypto` actual (JDK Ed25519/X25519/AES-GCM — no deps) behind `expect fun platformCrypto()`; `Identity` (Ed25519 + X25519, fingerprint-derived `deviceId`); `KeyStore` contract + `InMemoryKeyStore` + dev-only `FileKeyStore` actual; `Pairing` (QR payload, 6-digit SAS from SHA-256(keyI‖keyR‖nonce), confirm both roles); `SessionCrypto` (Noise-style XX: triple-DH ee/es/se, transcript-pinned HKDF, AEAD framing with 8-byte counters + Finished key-possession proof); `BlobCrypto` (ephemeral ECDH + wrapped content key). CommonTest uses a deterministic `FakeCrypto` (real hash/HMAC/HKDF); jvmTest runs the full handshake on real crypto. Production keystore actuals: Android Keystore (P2), Keychain + DPAPI (S5 — OsKeyStore). | `:shared:sync:jvmTest`; platform actuals compile |
| **S3** | W + M (transport) | **LAN path — LANDED.** Platform-free transport contract (`SyncTransport`/`TransportConnection`/`PeerEndpoint`, `HandshakeWire` codec, `JournalStore` seam + `InMemoryJournalStore`, `AntiEntropyRunner` — one symmetric VECTOR_EXCH→DELTA→apply→ACK round with a ready-made `syncListenerFor` inbound wiring). JVM implementation in `shared/sync` jvmMain: `JvmLanTransport` (JmDNS 3.5.9 advertise/discover of `_aegis-sync._tcp.local.` with deviceId/displayName props, ServerSocket accept loop, unpaired peers rejected with an error frame, mDNS failure degrades gracefully — direct connect survives) and `JvmTransportConnection` (length-prefixed frames, SessionCrypto XX handshake in both roles, every app message AEAD-sealed with transcript-pinned AAD + 8-byte replay-proof counters). jvmTest: loopback handshake + sealed messaging, W→M-style anti-entropy convergence over the wire (both journals reach the union, watermarks advance), unpaired-peer rejection, real-JmDNS discovery (skips on multicast-less hosts). Android + iOS adapters follow the same interface at wiring time. | `:shared:sync:jvmTest`; check-invariants |
| **S4** | Lead/W (infra) | **Relay path — LANDED.** Node service (`relay/`: `server.js` + `protocol.js` + `node --test` suite) implementing the section-10 contract: E2E-blind WebSocket rendezvous + ciphertext relay, pair-aware routing (GRANT allowlists per recipient — frames forwarded only from devices the recipient has paired), per-device in-memory store-and-forward queue (bounded + TTL, flushed on registration), PRESENCE fan-out to granting peers, `/health`, SIGTERM-clean shutdown, 16 MiB cap; env/CLI: PORT/HOST/QUEUE_TTL_MS/MAX_QUEUED_FRAMES/MAX_FRAME_BYTES. Client: `RelayTransport` in `shared/sync` jvmMain over `java.net.http.WebSocket` (zero new deps) — the S3 frame protocol was refactored into a shared seam (`FrameChannel` + `SessionHandshake` + `SealedConnection`) so the LAN socket and the relay WebSocket run the SAME handshake + sealing; relay demux routes handshake frames and sealed app frames per peer, PRESENCE → `onPeerDiscovered`. Tests: 5 server tests (`npm test`) + jvmTest E2E — two `RelayTransport`s converge journals THROUGH the real Node relay, and an un-granted initiator is rejected (skip-guarded when node/ws absent). | `cd relay && npm test`; `:shared:sync:jvmTest`; check-invariants |
| **P1** | A (shared brain) | **Encrypted Quick Share protocol + discovery seam — LANDED.** `ProximityTransfer` in `shared/sync` commonMain: chunked, E2E-encrypted file transfer over any reliable `TransferChannel` — every message a `BlobCrypto` sealed blob (only the recipient's long-term ECDH key can unwrap; anonymous on the wire), flow INIT(sealed meta) → ACCEPT/reject (user-confirmation gate) → CHUNK i (BE index, sequential) → DONE(whole-file SHA-256) → COMPLETE; explicit `Result.Failed(stage, reason)` for wrong key / AEAD tamper / out-of-order / hash mismatch / decline / timeout. `ProximityDiscovery` contract + `expect fun proximityDiscovery()`; jvmAndroidMain actual `LanProximityDiscovery` (mDNS `_aegis-proximity._tcp.local.` — the desktop path and Android fallback). jvmTest: full round-trip (multi-chunk + empty file), wrong-recipient rejection, tampered-chunk AEAD failure, user-decline both sides, progress reporting. | `:shared:sync:jvmTest`; check-invariants |
| **P2** | I + A (proximity) | **Android proximity actual + Quick Share UI — LANDED (Android half + desktop CLI).** `BleProximityDiscovery` (advertise/scan, `ProximityAdCodec` manufacturer-data payload — the byte format iOS's CoreBluetooth actual reuses); `P2pProximityChannel` (receiver = WiFi-Direct group owner on fixed port 47991, sender joins by P2P name `aegis-<deviceId>` via `setDeviceName` — API 30+); `AndroidProximityDiscovery` (the Android `proximityDiscovery()` actual: BLE + P2P + `ProximityHandshake` ECDH key exchange + the same `ProximityTransfer` over `TcpTransferChannel`); `TransferGate` accept/reject confirmation gate; `AndroidSyncKeyStore` (AES-GCM key in the Android Keystore/TEE wrapping the identity — the first production keystore actual; JVM seam moved to jvmMain with `FileKeyStore`); permissions (BLUETOOTH_*/NEARBY_WIFI_DEVICES/ACCESS_FINE_LOCATION) + `uses-feature` guards; Android **Nearby Share screen** (R13 — toggle, nearby list, send w/ progress, accept/decline dialog, received files to Downloads) and desktop CLI (`proximity listen/send/nearby`). iOS Multipeer actual + the desktop Compose Quick Share window remain (Track I / W1). | device tests (A/M/W) |
| **S5** | A + W (UI, R13) | **Sensitive settings surface.** Pairing screen (QR/SAS), paired-device list + Unpair, per-category sync toggles, per-peer command allowlists, sync status/last-synced. Background sync loop wired into each app's bootstrap. | `:apps:androidApp:testDebugUnitTest :apps:androidApp:assembleDebug` (+ desktop) |
| **S6** | A + W | **Commands.** Inbox/outbox, relay-queue semantics, targeted dispatch through the executor + PolicyEngine (AGENT origin), acks, command history UI. | android + desktop unit tests |
| **S7** | W + M (files) | **File sync.** Metadata LWW + encrypted chunk transfer + resume + per-category policy + conflict rule. | platform + desktop tests |

**Sequencing rule:** S0 first (unblocks everything), then S1→S2 (the engine
is the correctness core), then S3/S4 (transport), then S5/S6/S7 (surfaces).
Slices within a track are independent after S0–S2; W and M can build S3 and
S7 in parallel with A's S1/S2 as soon as S0 lands.

**Verification posture** (sandbox has no JDK — every Gradle step is
`UNVERIFIED` until run on a JDK-17 machine/CI): `check-invariants.sh` must
exit 0 after every slice; each slice's table row lists its exact gate.

---

## 14. Decisions still open (need the user)

1. **Embeddings**: confirmed recompute-locally (never sync vectors) — flag if
   you want vector-level sync for cross-device search parity.
2. **Relay hosting**: self-hosted vs the repo's Deploy flow (Node image
   supports the minimal relay either way).
3. **Command semantics beyond send_email/open_app/run_goal**: which command
   classes v1 should ship (the allowlist is per-peer and user-editable, so
   this is just the default set).
4. **Field-level LWW** for long records (people, procedures) — v1 is
   value-level; per-field merge is a refinement if you hit edit collisions.

---

## 15. Track I — iOS execution checklist (Mac + Xcode required)

Everything below is blocked on a macOS host with Xcode: Kotlin/Native can
only target Apple platforms from a Mac, and the actuals need Apple frameworks.
This section is the exact handoff so the Mac session is mechanical. State
verified 2026-08-11: the engine core is platform-free (audited — no
java./android./platform imports in `shared/sync` or `shared/database`
commonMain), and **Room 2.7.0-alpha13 KMP supports iOS** (bundled sqlite
driver — the "Native driver (planned)" matrix cell is stale).

### 15.1 Order of work (each step compiles on a Mac before the next)

1. **`shared/sync`: declare iOS targets.** `iosX64()`, `iosArm64()`,
   `iosSimulatorArm64()` in `kotlin { }`; create `iosMain` source set
   (dependsOn commonMain; `default hierarchy` template puts jvmAndroidMain
   below it — do NOT let iosMain inherit java.*). **CI constraint:** the
   Linux `android.yml` builds must stay green — either macOS-gate the target
   declarations (`if (System.getProperty("os.name").contains("Mac"))`), or
   keep iOS compilation out of every Linux CI task. Unconditional Apple
   targets break Linux configuration/builds (K/N cannot target Apple from
   Linux) and force a ~1 GB toolchain download on every machine.
2. **`shared/sync`: the 4 iosMain actuals.** Every `expect` in commonMain
   needs an iosMain actual before the iOS targets compile (AGENTS.md R3 —
   no expect without actual):
   - `platformCrypto()` → `IosCrypto`. **Design decision: CommonCrypto is
     NOT enough** — it has no Ed25519/X25519/AES-GCM. Wrap CryptoKit via a
     small Swift shim: an `IosCryptoShim.swift` exposing `@objc` functions
     (sign/verify Ed25519, X25519 key agreement, AES-GCM seal/open, HMAC,
     HKDF, SHA-256) compiled into the module and reached through a cinterop
     `.def` (stable pattern: swiftc → static lib → `linkerOpts`). Keep the
     pure-Kotlin hash/HMAC/HKDF already in commonMain as the fallback for
     the non-EC primitives.
   - `platformKeyStore()` → `IosKeyStore`: Keychain (`Security.framework`,
     `SecItemAdd/CopyMatching/Update/Delete` with `kSecClassGenericPassword`
     — same shape as `AndroidSyncKeyStore`).
   - `proximityDiscovery()` → `IosProximityDiscovery`: CoreBluetooth
     (advertise/scan reusing `ProximityAdCodec`'s manufacturer-data byte
     format) + Multipeer Connectivity for the channel (P2-iOS).
   - `acquireMulticastLock()` → iOS has no multicast lock — return the
     no-op handle (the LAN transport must already degrade gracefully when
     multicast is unavailable; verify the mDNS path via Bonjour
     `NWBrowser`/`NWListener` instead of relying on raw multicast).
3. **`shared/sync`: the iOS transport actual.** The LAN transport contract
   is platform-free (`SyncTransport`/`TransportConnection`); the iOS actual
   is `IosTcpTransport` over `platform.posix` BSD sockets (Kotlin/Native
   exposes them; the framing + SessionCrypto handshake from
   `JvmTransportConnection` ports directly) or `Network.framework` via
   cinterop. mDNS discovery via Bonjour. The relay path ports later.
4. **`shared/database`: iOS target + builder.** Add the three iOS targets +
   `iosMain`, and an `AegisDatabaseBuilder` actual over
   `BundledSQLiteDriver` (the same constructor the desktop builder uses —
   `androidx.sqlite:sqlite-bundled:2.5.0-alpha13` ships iOS KMP artifacts).
   No schema change — v13 is frozen; this is a per-target builder only.
   Room KSP: add `kspIosArm64`/`kspIosX64`/`kspIosSimulatorArm64`
   configurations the moment the targets are declared (R5).
5. **`apps/iosApp`: the shell.** Compose Multiplatform iOS app (CMP 1.7.1 +
   Kotlin 2.1.0 already support iOS) mirroring `apps/macosApp`: the
   `SyncScreen` (status, pairing code, SAS confirm, paired list) + the
   `SyncRuntime`-twin coordinator (WorkManager doesn't exist — use
   `UIApplication` lifecycle: sync on foreground + a background task
   permit). Needs an Xcode project / `MainViewController` entry.
6. **Verify on the Mac:** `./gradlew :shared:sync:compileKotlinIosSimulatorArm64
   :shared:database:compileKotlinIosSimulatorArm64 :apps:iosApp:build`,
   then a device smoke test: pair iOS↔Android over LAN, exchange memory +
   graph/people records, confirm convergence.

### 15.2 What is already verified / needs no re-check

- Engine commonMain platform-freedom audit (this slice) — clean.
- Room 2.7.0-alpha13 iOS support (official release page, 2026-08-11).
- All capture/materialize logic is platform-free (`SyncPayload` codec,
  `RoomJournalStore`, the LWW guard, the per-table materializers) — iOS
  reuses it unchanged once the targets + builder exist.
- The iOS module must NOT be added to the Linux CI tasks (android.yml
  builds `:apps:androidApp` + `:platform:*` only — keep it that way).
