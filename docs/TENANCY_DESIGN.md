# Newax Aegis — Multi-Tenancy Design

How Newax Aegis supports multiple isolated users and organizations without
discarding the property that makes it worth using.

---

## 1. The conflict, stated plainly

Newax Aegis today is architecturally single-user:

- No Internet permission on Android, no accounts, no cloud storage.
- One encrypted database (`NewaxDatabase`, a `@Volatile INSTANCE` singleton).
- Eight process-wide `object` holders — `PolicyHolder`, `ModelProviderHolder`,
  `PlatformCapabilitiesHolder`, `ExecutionAuditHolder`, and their desktop twins.
- Flat, unscoped paths: `~/.aegis/sync.db`, `~/.aegis/policy-settings.json`,
  `~/.aegis/keys`, `~/.aegis/memory.json`.

Conventional multi-tenancy means a server holding many customers' data with
isolation between them. Building that here means adding accounts, a backend, and
the network boundary this product deliberately refuses — which would trade away
its entire differentiator.

**That trade is not necessary.** Most of what "multi-tenant" is actually wanted
for — separate users, separate organizations, isolated data, central policy,
per-org audit — is achievable without a shared data store. This document
specifies three tiers, recommends two of them, and states exactly what the third
would cost.

---

## 2. Three tiers

### T1 — Workspace (on-device tenant) — **recommended, build first**

Multiple isolated workspaces on one device: *Personal*, *Work*, *Client A*.

| Property | Value |
|---|---|
| Isolation boundary | A distinct root key per workspace |
| Data store | One SQLCipher database file per workspace |
| Network required | **None** |
| Who it serves | Individuals separating contexts; contractors with client separation; anyone handing a device between roles |

Each workspace owns: its database, encrypted memory namespace, profile, policy
configuration, audit log, goals, agents, and model context. Nothing crosses.

### T2 — Organization (fleet tenant) — **recommended, build second**

An organization owns many devices. The org is a tenant; its data is not.

| Property | Value |
|---|---|
| Isolation boundary | Signed org enrollment + per-device keys |
| Data store | **Still on each device.** No central store |
| Network required | Optional — policy delivery and audit export only |
| Who it serves | Enterprises needing policy control and compliance evidence |

The org can: push signed policy bundles (**tighten-only**), define hard-deny
lists, scope the sync mesh to org devices, and receive audit exports. The org
cannot: read memory, read conversations, or silently loosen a policy.

### T3 — Hosted (server tenant) — **not recommended; specified so the cost is explicit**

A shared backend with per-tenant data isolation.

**What it costs, itemised:**

- `android.permission.INTERNET` in the main manifest — discarding a guarantee
  that is currently enforced by the OS rather than by a promise.
- An account system, identity provider, session management.
- Cloud storage of memory and conversations — the most sensitive data the
  product holds.
- A data-processor role under GDPR, plus SOC 2 scope, breach-notification
  obligations, and lawful-access exposure. Data you do not hold cannot be
  subpoenaed from you.
- The `PRIVACY_POLICY.md` and README claims become false and must be rewritten.

If some hosted capability is genuinely required, the correct shape is the
existing `relay/` service: an **encrypted dumb pipe** that routes ciphertext
between paired devices and can decrypt nothing. That is a transport tenant, not
a data tenant, and it keeps the guarantee intact.

---

## 3. The isolation model — key, not query

This is the single most important decision in the document.

The conventional approach is a `tenant_id` column and `WHERE tenant_id = ?` on
every query. **Do not do this.** It makes correctness depend on never forgetting
a clause, across 24 DAOs and every future query. One omission is a silent
cross-tenant leak, and it will read as a normal result rather than an error.

**Instead: one database file per tenant, one key per tenant.**

```
T1 workspace isolation

  Keystore (StrongBox where available)
    ├── tenant.<uuid-personal>.root     ← user-auth-bound, non-exportable
    └── tenant.<uuid-work>.root

  Storage
    ├── tenants/<uuid-personal>/newax.db      (SQLCipher, key = derived above)
    ├── tenants/<uuid-personal>/memory/       (EncryptedMemory namespace)
    ├── tenants/<uuid-personal>/audit.json
    └── tenants/<uuid-work>/…
```

Why this is stronger:

- A missing filter cannot leak, because the other tenant's bytes are
  **unreadable** without a key held in hardware.
- Blast radius of any query bug, injection, or deserialization flaw is one
  tenant.
- "Delete this tenant" becomes "destroy the key" — cryptographic erasure,
  instant and complete, with no vacuum or row sweep to get wrong.
- Backup and export are naturally per-tenant.

The cost is real and worth paying: N open databases instead of one, and a
migration must run per tenant. Both are mechanical.

**Key derivation.** Each tenant root key is generated in the Keystore/Secure
Enclave, never exported, and requires user authentication
(`setUserAuthenticationRequired`, `setUnlockedDeviceRequired`,
`setInvalidatedByBiometricEnrollment`). The SQLCipher passphrase is derived from
it via HKDF — never a static secret and never stored. See `ENGINEERING.md` §B5.

---

## 4. What must change in the code

| Area | Today | Change |
|---|---|---|
| `NewaxDatabase` | `@Volatile INSTANCE` singleton | Keyed cache: `Map<TenantId, NewaxDatabase>`; only the active tenant is open |
| 8 `object` holders | Process-wide singletons | A `TenantScope` holding policy, model, audit, capabilities; resolved from the active tenant |
| `EncryptedMemory` | One namespace | Namespaced per tenant |
| `ProfileManager` | One profile | Per tenant |
| Policy + audit | `~/.aegis/policy-settings.json` | `tenants/<id>/…` |
| Desktop paths | Flat `~/.aegis/*` | `~/.aegis/tenants/<id>/*`; shared items (model packs) stay at root |
| Sync identity | One device identity | Device identity stays device-level; **sync scope is per tenant** — a peer paired in Work never sees Personal |
| Model provider | Process-wide | Weights shared (they are large and non-sensitive); **context/KV cache is per tenant and cleared on switch** |

The holder refactor is the bulk of the work and is worth doing regardless:
process-wide mutable singletons are the same thing blocking unit testing today
(see `ENGINEERING.md` §B11).

**Ordering note.** This depends on slice 6 (decomposing the god objects) in
`ENGINEERING.md`. Attempting tenancy before that means threading a tenant
through a 1207-line ViewModel.

---

## 5. Tenant lifecycle

```
CREATE    name + colour/icon → generate root key → create DB → seed policy
             → run onboarding (routes 0.x) inside the new tenant

SWITCH    lock current: flush, close DB, clear model KV cache, wipe in-memory
             transcripts + voiceprint + clipboard staging
          → authenticate against target tenant's key (CryptoObject-bound)
          → open target

LOCK      automatic on: timeout, screen off (configurable), app background
             (default on for non-active tenants), device lock

DELETE    destroy the Keystore key → data is cryptographically unreadable
          → then remove files as hygiene, not as the security boundary

EXPORT    per-tenant encrypted backup (existing AES-256-GCM path, Argon2id KDF)

There is no "merge tenants" and no cross-tenant copy. If a user wants data in
both places they re-enter it. An automated bridge is the exact hole tenancy
exists to close.
```

---

## 6. Security analysis — including what this does *not* protect

**What T1 gives you:**

- Data at rest for tenant B is unreadable while tenant A is active.
- Cryptographic erasure per tenant.
- Per-tenant policy — Work can require STRONG_CONFIRMATION where Personal does not.
- Bounded blast radius for query bugs and deserialization flaws.
- Per-tenant audit trails for compliance evidence.

**What it does not give you, and must not be sold as giving:**

- **Protection from a compromised OS or rooted device.** Keystore raises the
  bar; it is not a boundary against a kernel-level attacker.
- **Isolation from the accessibility service.** It is device-level by
  construction. Whatever is on screen, it can read — in any tenant. This is
  inherent to what the product does and must be stated in the UI, not buried.
- **Isolation from other device-level services**: the wake-word listener,
  notification access, and the clipboard are all device-scoped. Each must be
  explicitly cleared or re-scoped on tenant switch, and the residual risk
  disclosed.
- **Protection against a coerced user.** Someone who can compel authentication
  gets that tenant. Do not imply otherwise.
- **Separation equivalent to separate devices.** For genuinely hostile
  separation — regulated client data, adversarial parties — the honest answer is
  a second device, and the docs should say so rather than overselling.

**Model context is a real leak vector.** Weights are shared, which is fine. The
KV cache, conversation context, and any in-flight embedding are not: they must
be destroyed on switch, not merely reassigned. Same for the `VoiceAuthenticator`
`FloatArray` embedding and the ambient transcript buffer.

**UI leakage:** `FLAG_SECURE` on tenant-bearing screens, and the recents/task
snapshot must not show the previous tenant after a switch.

---

## 7. UI surface

New routes for `UI_DESIGN.md` §6, following its existing conventions:

| Route | Contents | Notes |
|---|---|---|
| **1.0 Tenant switcher** | Current tenant header in the drawer; list of tenants with lock state; Add workspace | Reached from the drawer header, not buried in Settings — it is a context switch, not a setting |
| **5.7 Workspaces** | List, create, rename, colour, lock policy, delete | New Settings category |
| **5.7.1 Workspace detail** | Name, colour, auto-lock timeout, per-tenant storage, Export, Delete | Delete is `⊞9.3` type-to-confirm plus `⊞9.4` biometric |
| **5.7.2 Organization** | Enrollment state, applied policy bundle (read-only), org contact, Leave organization | T2 only; every org-applied restriction is **visible**, never silent |

**Design rules:**

- The active tenant is **always visible** in the drawer header, with its colour.
  A user acting in the wrong workspace is the primary usability failure mode.
- Switching always requires authentication. No "recently used, skip auth".
- An org-applied policy shows *what* changed and *who* applied it. Silent
  administration is prohibited (`ENGINEERING.md` §B9).
- Accessibility: the tenant name is part of the drawer header's accessible name,
  and the switch announces the new tenant assertively — a silent context change
  is an accessibility failure and a safety one.

---

## 8. Slices

Insert into `ENGINEERING.md` Part A after slice 8 (conversation persistence).

| Slice | Goal | Depends on | Gate |
|---|---|---|---|
| **T-1** | `TenantId` + `TenantScope`; retire the 8 process-wide holders | Slice 6 | Holders gone; state holders unit-tested |
| **T-2** | Per-tenant keys + per-tenant SQLCipher DB; migrate the existing single DB into a default tenant | T-1, Gate 0 | Migration test proves no data loss; two tenants provably cannot read each other |
| **T-3** | Namespace memory, profile, policy, audit, and desktop paths | T-2 | Per-tenant round-trip tests |
| **T-4** | Lifecycle: create, switch, lock, delete, export | T-3 | Switch clears model KV cache, transcripts, voiceprint — asserted in tests |
| **T-5** | UI: switcher (1.0), Workspaces settings (5.7.x) | T-4, slice 10 | TalkBack announces tenant switch; active tenant always visible |
| **T-6** | Per-tenant sync scoping | T-3 | A peer paired in one tenant cannot see another |
| **T-7** | T2 org enrollment + signed tighten-only policy bundles | T-5 | Signature verified; a loosening bundle is rejected and logged |
| **T-8** | Tamper-evident per-tenant audit export (hash-chained) | T-3 | Chain break detectable in tests |

**T-2 carries the highest risk in the project**: it moves every existing user's
data. It needs a migration test, a backup-before-migrate step, and a rollback
path before it ships.

---

## 9. What this design refuses

- **`tenant_id` row filtering as the isolation mechanism.** One missing clause
  is a silent leak.
- **Cross-tenant data movement**, automated or otherwise.
- **Silent org administration.** Every applied policy is visible to the user.
- **Org policies that loosen.** Tighten-only, enforced at verification.
- **Any claim that on-device tenancy equals device separation.** The
  accessibility service is device-level and the docs say so.
- **Adding `INTERNET` to the main manifest** for tenancy. If hosted capability
  is ever needed it goes behind the relay's encrypted-pipe boundary.

---

## 10. Recommendation

Build **T1**, then **T2**. Together they deliver isolated users, isolated
organizations, central policy control, and per-tenant compliance evidence —
with no server, no accounts, and the offline guarantee intact.

Treat **T3** as a separate product decision requiring its own threat model,
privacy-policy rewrite, and compliance program. It is not a refactor of this
one, and it should not be arrived at by drift.

**Prerequisites, in order:** Gate 0 (the build compiles) → slice 6 (decompose
the god objects) → T-1. Tenancy layered onto process-wide singletons and a
1400-line Activity would have to be redone.
