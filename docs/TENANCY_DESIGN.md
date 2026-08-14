# Newax Aegis — Tenancy, Identity & Multi-Device Design

How Newax Aegis supports companies, organizations and individuals — each person
with a Work and a Personal profile, present across Android, iOS, Windows and
macOS — without discarding the property that makes it worth using.

---

## 1. The model

Four concepts. Getting these separate is what makes the rest work.

```
Organization  (a company — optional; exists only for managed deployments)
    ╎  governs LINKED Work profiles only, by signed tighten-only policy
    ╎
    └╌╌ Person  (the identity that "logs in" — an Ed25519 keypair, not an account)
          │
          ├── Profile: WORK       ← isolation boundary: own key, own database
          │     ├── UNLINKED  → self-governed; the user sets their own policy
          │     └── LINKED    → a bounded set of controls is org-owned (§4)
          │
          └── Profile: PERSONAL   ← isolation boundary: own key, own database
                └── never linkable. No org, ever, under any policy.
                          │
                          └── each profile present on N devices
                              (Android / iOS / Windows / macOS)
```

**The link between Organization and Person is dashed because it is optional and
severable.** A Work profile is fully usable with no organization at all — a
freelancer, a sole trader, or an employee whose company does not deploy Newax
Aegis has a Work profile that is simply self-governed. Linking is a state the
user enters, sees, and can leave (§4.4).

| Concept | What it is | Isolation? |
|---|---|---|
| **Organization** | A company or org. Governs policy for enrolled Work profiles | Governance boundary, not a data boundary — it holds no data |
| **Person** | An individual. The thing that authenticates and spans devices | Identity, not storage |
| **Profile** | Work or Personal. **The isolation boundary** — own root key, own encrypted database | **Yes — this is the security boundary** |
| **Device** | A physical body hosting one or more profiles | Custody boundary (see §6) |

**The tenant, in the sense of "the thing that is isolated", is the Profile.**
A company is a tenant in the governance sense; a person is a tenant in the
identity sense; but the thing with its own key and its own database — the thing
a bug cannot leak across — is the profile.

### Why exactly two profiles

Every person gets **Work** and **Personal**, created together, permanently
distinct. Not configurable, not merge-able.

This is the BYOD answer, and it is the strongest property in this design:

> An organization can require anything it likes of the Work profile — policy,
> audit, hard-deny lists, remote revocation — and is **cryptographically unable
> to see the Personal profile.** Different key. Never enrolled. Never
> transferred. Not "filtered out" — unreadable.

The underlying mechanism generalises to N profiles (a contractor with three
clients), but the product ships two, because two is the distinction that
actually needs a guarantee.

---

## 2. Isolation: by key, not by query

The single most important decision here.

The conventional approach is a `tenant_id` column and `WHERE tenant_id = ?` on
every query. **Do not.** It makes correctness depend on never forgetting a
clause, across 24 DAOs and every query anyone writes in future. One omission is
a silent cross-profile leak that reads as a perfectly normal result.

**Instead: one database, one key, per profile.**

```
Keystore / Secure Enclave / TPM
  ├── profile.<person>.work.root       ← user-auth-bound, non-exportable
  └── profile.<person>.personal.root

Storage
  ├── profiles/<work-id>/newax.db      (SQLCipher; passphrase = HKDF(root key))
  ├── profiles/<work-id>/memory/
  ├── profiles/<work-id>/audit.json
  └── profiles/<personal-id>/…
```

- A missing filter cannot leak — the other profile's bytes are unreadable.
- Blast radius of any query bug, injection, or deserialization flaw is one profile.
- "Remote wipe the Work profile" = destroy one key. Cryptographic erasure,
  immediate, complete, and it provably does not touch Personal.
- Backup and export are naturally per-profile.

Cost: N open databases instead of one, and migrations run per profile. Both are
mechanical, and both are worth it.

---

## 3. Multi-device: enrollment, not login

"Log in on my other device" normally means credentials → server → session. There
is no server here, and adding one would mean adding the `INTERNET` permission
the product refuses (§7).

**It isn't needed.** `shared/sync` already contains the entire toolkit:
`Identity.kt` (Ed25519), `Pairing.kt` (QR + SAS), `SessionCrypto.kt`
(Noise-style handshake), `Hkdf.kt`, `BlobCrypto.kt`, `CommandSigning.kt`,
`KeyStore.kt`. Multi-device is a **scoping and key-transfer problem over an
already-authenticated channel**, not new cryptography.

### Enrollment flow

```
Device 1 (genesis)
   create Person identity (Ed25519)  →  create Work + Personal profiles
   →  generate RECOVERY KIT (§5)  ← mandatory, not skippable

Device 2..N
   Device 1: Settings → Devices → Add a device      (biometric-gated)
   Device 2: scan QR  →  Noise handshake  →  SAS 6-digit confirm on BOTH
   →  user chooses WHICH PROFILES to place on this device
   →  selected profile root keys transferred over the verified channel only
   →  device recorded in the person's device roster, signed
```

**Rules:**

- The SAS confirmation is the only interception defence. Never auto-confirm,
  and a mismatch is a hard stop — as it already is in `Pairing.kt`.
- Profiles are enrolled **individually**. Putting Work on a work laptop does not
  put Personal there. This is a per-device choice, and it is the point.
- A device never derives one profile's key from another's.
- Enrollment requires an already-enrolled device. There is no password path, so
  there is no password to phish.

### Cross-platform

All four bodies use the same handshake; only key custody differs (§6). A Windows
desktop and an iPhone enroll by the same flow.

### Enterprise scale

QR-pairing 500 employees is not viable. For managed deployments the
**organization's existing MDM is the trusted channel**:

```
MDM pushes managed configuration
  → signed org enrollment token (org public key, policy bundle URL/blob, org id)
  → device verifies the signature against a pinned org key
  → creates a WORK profile enrolled to that org
  → Personal profile is created locally and is NOT part of enrollment
```

We still ship no backend. The enterprise's MDM — which they already run and
already trust — is the delivery mechanism. Android Enterprise
`RestrictionsManager`, Apple managed app config, Windows/macOS MDM payloads.

---

## 4. Organization governance — optional, and bounded

### 4.1 Governance states

A Work profile is always in exactly one state. Personal has no state — it is
permanently ungoverned.

| State | Meaning |
|---|---|
| `UNLINKED` | Self-governed. Governance is identical to Personal. The default, and a fully supported end state — not a half-configured one |
| `LINK_PENDING` | An enrollment token has arrived (via MDM or QR). **Nothing is applied yet.** The user sees exactly what the org would control and must consent |
| `LINKED` | The §4.2 surface is org-owned; everything else stays user-owned |
| `UNLINKING` | User- or org-initiated separation in progress; §4.4 runs |

**Consent is required to enter `LINKED`.** An enrollment token that arrives by
MDM does not silently take control — it produces a request the user approves or
declines, showing the full control surface first. An org that could link without
consent could take control of a personal device.

### 4.2 The controlled surface — exhaustive

When `LINKED`, the organization owns exactly these and nothing else. This list
is the specification; anything not on it is user-owned by definition.

| Org-controlled | Direction | Note |
|---|---|---|
| Policy modes per action class | **Tighten only** | Never toward `AUTO` |
| Hard-deny list | **Add only** | Cannot remove a user's own entries |
| Biometric / STRONG_CONFIRMATION requirement | **Raise only** | Cannot disable an approval |
| Minimum device custody tier | **Raise only** | §6 — can require hardware-backed |
| Sync scope | Restrict to org peers | Cannot widen to unknown peers |
| Agent / skill allowance | **Deny only** | Can forbid; cannot force-install |
| Audit export | May require | Pull is user-initiated or scheduled and visible |
| Remote revoke of **this** profile | Yes | Key destruction; §4.4 |

**User-retained, even when `LINKED`:**

- Persona settings — name, language, communication style. It speaks in their
  voice, not the company's.
- Memory and conversation **content**. The org sets rules, never reads data.
- Which devices they enroll (subject to the custody tier above).
- Full visibility of every applied restriction, and who applied it.
- **The right to unlink.** Always. See §4.4.

### 4.3 Enforcement, not trust

- **Tighten-only is verified at application.** A bundle that would loosen any
  setting is rejected, logged, and surfaced to the user. An admin silently
  granting `AUTO` to dangerous action classes is precisely the attack this
  product exists to prevent.
- **Bundles are signature-verified** against a pinned org key before parsing.
- **Every applied restriction is visible** in route 5.7.2 — what changed, which
  org, when. Silent administration is prohibited.
- An unlinked Work profile applies no org policy at all; there is no residue.

### 4.4 Linking and unlinking

```
UNLINKED ──enrollment token (MDM or QR)──▶ LINK_PENDING
                                              │
                    user reviews the full control surface
                    ├── Decline ──▶ UNLINKED (token discarded, logged)
                    └── Approve ──▶ LINKED

LINKED ──┬── user unlinks         ──▶ UNLINKING ──▶ UNLINKED or wiped
         └── org revokes remotely ──▶ key destroyed, profile gone
```

**The user can always unlink.** An organization that could prevent it would
effectively own the person's device. What unlinking *costs* is set by the org's
retire policy, declared in the bundle and shown before confirmation:

| Org retire policy | On unlink |
|---|---|
| `WIPE` (default) | The Work profile is destroyed — key destruction, per §2 |
| `RETAIN` | The profile survives, org policy lifts, it becomes `UNLINKED` |

**Why `WIPE` is the default.** Once linked, a Work profile holds a mixture of
org-provisioned and user-created content. Separating them on the way out means
classifying every row — which is the `tenant_id`-filtering trap from §2, with
the same failure mode: one mis-classification and company data walks out, or the
user's own work is destroyed. Cryptographic erasure of the whole profile is the
only operation that is reliably correct.

**Export before unlink** is offered when the org bundle permits it
(`allowExportOnUnlink`), producing the standard per-profile encrypted backup.
When the org forbids it, the UI says so plainly *before* the user commits —
never after.

**Personal is untouched by every path above.** Remote revoke, unlink, wipe: all
operate on one key, and it is not Personal's.

---

## 5. Recovery — the honest weak point

No server means no "forgot password" and no account recovery. If every enrolled
device is lost, **the profile keys are gone and the data is unrecoverable.**

This is a real trade, and the design handles it explicitly rather than
discovering it in a support ticket:

- **A recovery kit is generated at genesis and cannot be skipped.** A
  high-entropy code that wraps the profile root keys into an encrypted blob.
- The user stores it in a password manager, prints it, or both. The UI states
  plainly what is lost without it.
- The encrypted backup path already exists (AES-256-GCM); its KDF must be
  **Argon2id**, since a backup blob is offline-attackable and the KDF is the
  entire defence.
- **Organizations get org-level escrow for Work profiles only** — the org may
  hold a recovery key for the profile it governs. **Never for Personal.** An org
  that could recover a Personal profile could read it.

---

## 6. Device custody tiers — and why they differ

Key protection is not equal across the four bodies, and pretending otherwise
would be the dishonest kind of security claim.

| Platform | Custody | Tier |
|---|---|---|
| Android (StrongBox) | Hardware security module, keys non-exportable | **Hardware** |
| Android (TEE) | Trusted Execution Environment | **Hardware** |
| iOS / macOS (Apple Silicon) | Secure Enclave | **Hardware** |
| macOS (Intel) | Keychain, no Secure Enclave | Software |
| Windows | **DPAPI is user-account-scoped, not hardware-backed** — the current `platform-impl/windows` vault. TPM via CNG / Windows Hello is the upgrade | Software today, hardware-capable |

**Consequence, stated plainly: a profile is only as protected as the weakest
device holding it.** Syncing a Work profile to an Intel Mac or a
DPAPI-only Windows box lowers that profile's real protection.

**Design response — minimum custody tier per profile.** A profile (or an org
policy) may require hardware-backed custody. A device that cannot provide it is
**refused at enrollment**, with the reason shown, rather than silently accepted.
Default: Work requires hardware where the org sets it; Personal warns and lets
the user decide.

Raising Windows to TPM-backed custody via CNG is the highest-value
platform-security work available, and it is a prerequisite for any org that sets
a hardware requirement on a fleet including Windows.

---

## 7. What we do not build

**Hosted server tenancy** — a shared backend holding many customers' data.

Costed so it is a decision rather than a drift:

- `android.permission.INTERNET` in the main manifest — discarding a guarantee
  currently enforced by the OS rather than by a promise.
- Accounts, identity provider, session management, password reset — and every
  attack that comes with them.
- Cloud storage of memory and conversations: the most sensitive data the product
  holds.
- A GDPR data-processor role, SOC 2 scope, breach-notification duty, and lawful
  access exposure. Data you do not hold cannot be subpoenaed from you.
- `PRIVACY_POLICY.md` and the README become false and need rewriting.

If hosted capability is ever genuinely required, the correct shape is the
existing `relay/` service: an **encrypted dumb pipe** routing ciphertext between
paired devices, able to decrypt nothing. A transport tenant, not a data tenant.

---

## 8. What this does *not* protect against

On-device isolation is strong at rest and bounds blast radius. It is not
equivalent to separate hardware, and the product must not imply it is.

- **The accessibility service is device-level.** Whatever is on screen it can
  read, in any profile. This is inherent to what the assistant does. It must be
  said in the UI, not buried in a doc.
- **A rooted or compromised OS.** Hardware custody raises the bar; it is not a
  boundary against a kernel-level attacker.
- **Other device-scoped services**: wake-word listener, notification access,
  clipboard. Each must be cleared or re-scoped on profile switch, with the
  residual risk disclosed.
- **A coerced user.** Someone who can compel authentication gets that profile.
- **Genuinely hostile separation** — regulated client data, adversarial parties.
  The honest answer there is a second device, and the docs should say so.

**Leak vectors that must be destroyed on switch, not reassigned:** the model KV
cache and conversation context, the `VoiceAuthenticator` `FloatArray` embedding,
the ambient transcript buffer, and clipboard staging. `FLAG_SECURE` on
profile-bearing screens, and the recents snapshot must not survive a switch.

---

## 9. What must change in the code

| Area | Today | Change |
|---|---|---|
| `NewaxDatabase` | `@Volatile INSTANCE` singleton | `Map<ProfileId, NewaxDatabase>`; only active profiles open |
| 8 `object` holders | Process-wide singletons (`PolicyHolder`, `ModelProviderHolder`, `PlatformCapabilitiesHolder`, `ExecutionAuditHolder` + desktop twins) | A `ProfileScope` resolved from the active profile |
| `EncryptedMemory` | One namespace | Per profile |
| `ProfileManager` | One profile (name/language/style) | Per profile — note the name collision; rename to `PersonaSettings` |
| Policy + audit | `~/.aegis/policy-settings.json` | `profiles/<id>/…` |
| Desktop paths | Flat `~/.aegis/*` | `~/.aegis/profiles/<id>/*`; model packs stay shared at root |
| `Identity.kt` | One device identity | Device identity stays; add **Person** identity + signed device roster |
| `Pairing.kt` | Device↔device pairing | Extend to carry profile-key transfer + profile selection |
| Model provider | Process-wide | Weights shared (large, non-sensitive); **context per profile, destroyed on switch** |
| Secrets capability | Single vault | Per-profile namespace + a **custody tier** query for §6 |

The holder refactor is the bulk of it, and is worth doing regardless — those
singletons are what block unit testing today (`ENGINEERING.md` §B11).

---

## 10. UI surface

New routes for `UI_DESIGN.md` §6:

| Route | Contents |
|---|---|
| **1.0 Profile switcher** | Drawer header: current profile + colour + org badge if governed; switch (re-auth); Personal / Work |
| **5.7 Profiles** | The two profiles, storage per profile, auto-lock, Export, custody tier of this device |
| **5.7.1 Profile detail** | Name/colour, lock timeout, minimum custody tier, Export, Wipe this profile |
| **5.7.2 Organization** | **Work profile only, and shown in all four states.** `UNLINKED`: "Not linked to an organization" + Link with a code. `LINK_PENDING`: the full control surface with Approve / Decline. `LINKED`: org identity, **every applied restriction with what and who**, retire policy (what unlinking will cost), Leave organization. Never rendered for Personal |
| **5.7.3 Recovery kit** | View/regenerate, last-verified date, plain statement of what is lost without it |
| **5.1.2 Devices** *(exists — extend)* | Per-device: platform, custody tier, which profiles are present, last synced, **Revoke** |

**Rules:**
- The active profile is **always visible** in the drawer header with its colour.
  Acting in the wrong profile is the primary usability failure.
- Switching always re-authenticates. No "recently used, skip auth".
- A governed Work profile shows its org badge everywhere the profile is named.
- Accessibility: the profile name is part of the drawer header's accessible
  name, and a switch is announced assertively. A silent context change is both
  an accessibility failure and a safety one.

---

## 11. Slices

Insert into `ENGINEERING.md` Part A after slice 8.

| Slice | Goal | Depends on | Gate |
|---|---|---|---|
| **T-1** | `ProfileId` + `ProfileScope`; retire the 8 holders | Slice 6 | Holders gone; state holders unit-tested |
| **T-2** | Per-profile keys + per-profile SQLCipher DB; migrate existing data into a default Personal profile | T-1, Gate 0 | Migration test proves no loss; two profiles provably cannot read each other |
| **T-3** | Namespace memory, persona settings, policy, audit, desktop paths | T-2 | Per-profile round-trip tests |
| **T-4** | Person identity + signed device roster; Work/Personal creation | T-3 | Roster signature verified; tamper rejected |
| **T-5** | Profile lifecycle: switch, lock, wipe, export | T-4 | Switch destroys KV cache, transcripts, voiceprint, clipboard — asserted |
| **T-6** | Multi-device enrollment: profile-key transfer over the SAS channel | T-4 | Key never leaves the verified channel; SAS mismatch aborts; per-profile selection honoured |
| **T-7** | Recovery kit + Argon2id backup KDF | T-4 | Restore from kit on a clean device |
| **T-8** | Custody tiers + minimum-tier enforcement; Windows TPM via CNG | T-6 | Sub-tier device refused with a stated reason |
| **T-9** | UI: switcher, Profiles, Organization, Recovery, Devices | T-5, slice 10 | TalkBack announces switch; profile always visible |
| **T-10** | Org link lifecycle: MDM/QR token → `LINK_PENDING` → consent → `LINKED`; signed tighten-only bundles; unlink with retire policy | T-9 | Loosening bundle rejected, logged, surfaced; linking without consent impossible; unlink always available; `WIPE` proven not to touch Personal |
| **T-11** | Per-profile sync scoping + device revocation | T-6 | A peer enrolled for Work never sees Personal |
| **T-12** | Tamper-evident per-profile audit export (hash-chained) | T-3 | Chain break detectable |

**T-2 is the highest-risk slice in the project** — it moves every existing
user's data. Migration test, backup-before-migrate, and a rollback path before
it ships.

**T-6 and T-8 are the highest-value security slices.** T-6 is where profile keys
move between devices; T-8 is what stops them moving onto a device that cannot
protect them.

---

## 12. Refused by design

- `tenant_id` row filtering as the isolation mechanism.
- Cross-profile data movement, automated or manual.
- Any org visibility into a Personal profile, including escrow.
- Linking a Personal profile to an organization, under any policy or token.
- Linking a Work profile **without explicit user consent**, however the token
  arrived.
- Preventing a user from unlinking. The cost of unlinking is the org's to set;
  the right to unlink is not.
- Org policies that loosen.
- Silent org administration.
- Selective wipe of "org data only" inside a profile — that is §2's
  `tenant_id` trap wearing a different hat.
- Password-based login (there is no password to phish, and no server to hold one).
- Claiming on-device isolation equals device separation.
- Adding `INTERNET` to the main manifest for tenancy.

---

## 13. Sequencing

**Prerequisites, in order:** Gate 0 (the build compiles) → slice 6 (decompose
the god objects) → T-1.

Tenancy layered onto eight process-wide singletons and a 1400-line Activity
would have to be redone. The identity and multi-device work (T-4, T-6) can be
designed in parallel but cannot land before the profile boundary exists, because
there is nothing to enroll a device *into* until then.
