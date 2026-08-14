# Authentication — device and user

How Newax Aegis decides that *this device* may hold a profile and *this person*
may open it.

Read `ENGINEERING.md` §B5 first — the security requirements are stated there and
this document does not restate them. This is the design that satisfies them.

---

## 1. What exists today

Almost nothing, and what does exist is the pattern §B5 names as wrong.

| Piece | State |
|---|---|
| `BiometricPrompt` in `MainActivity.kt:1423` | Ad-hoc, **no `CryptoObject`** |
| `BiometricPrompt` in `AutomationSettingsSection.kt:92` | Ad-hoc, **no `CryptoObject`**, second implementation |
| `TotpManager` | Real TOTP, `EncryptedSharedPreferences` — but gates *settings*, not entry |
| `VoiceAuthenticator` | Fail-secure, correctly a fallback |
| `shared/sync` — `Identity`, `Pairing`, `SessionCrypto`, `Hkdf`, `KeyStore` | Solid, and the device half is already built on it |
| An authentication module | **Did not exist** |
| A lock screen, a session, a sign-in | **Do not exist** |

The defect is not that biometrics are missing. It is in the spine's own
signature:

```kotlin
// AuthorityManager.kt — before
fun approve(action: ProposedAction, biometricAuthenticated: Boolean = false)
```

Any caller can pass `true`. Worse, it **defaults to a value**, so a caller who
never thought about authentication still compiles — and gets the insecure path.
That is §B5's "WRONG" example, in the one class whose job is to stand between a
model's output and the user's device.

---

## 2. The naming problem: there is no server to log in to

"Login" normally means a remote party checks a credential and answers yes. This
product has no such party. `relay/` forwards ciphertext between the user's own
devices; it never sees plaintext and holds no accounts. `README.md:11` claims no
account, and **this design keeps that claim true.**

So a local "login" has exactly two possible meanings:

- **A boolean comparison** — does the entered PIN match a stored hash? This is
  worthless against the adversary that matters. An attacker holding the device
  reads the database and never visits the prompt. The check guards a door in a
  wall that isn't there.
- **A key derivation** — the credential *unwraps the profile's data key*. A
  wrong passphrase does not produce a denial; it produces unreadable bytes.

Only the second is authentication in any useful sense, and it is the same
principle `TENANCY_DESIGN.md` §2 already adopted for profile isolation:
**separation by key, never by a check that code can skip.** A missing `WHERE`
clause is a silent leak; a missing key is noise.

> **The design consequence.** "Login" here is not a gate in front of the data. It
> is the derivation that makes the data readable. Everything below follows from
> that.

---

## 3. Two halves, and neither is sufficient

```
   DEVICE (possession)                    USER (knowledge / inherence)
   ──────────────────                     ────────────────────────────
   Ed25519 identity, enrolled             Passphrase  → Argon2id
   into the profile over the              Biometric   → Class 3 only
   SAS-verified pairing channel           TOTP        → second factor only
   (shared/sync/Pairing.kt)               Voice       → convenience signal only
            │                                        │
            │  holds the wrapped key                 │  unlocks the wrapping key
            └────────────────┬───────────────────────┘
                             ▼
                   Profile Data Key (DEK)
                             │
                             ▼
                 SQLCipher passphrase for
                 that profile's database
```

**Device without user factor:** the wrapped key is present, but the hardware
key that unwraps it is declared `setUserAuthenticationRequired(true)` — the
keystore refuses to use it. The blob stays a blob.

**User factor without an enrolled device:** there is no wrapped key on this
device to unwrap. A correct passphrase on a stranger's phone opens nothing,
which is what makes phishing a credential pointless here.

This is why **adding a device is a physical act** between two devices the owner
holds, not a form submission — and why revoking one is a key operation rather
than a database flag.

> **Platform limit affecting this half.** `JavaCrypto` requires Ed25519/X25519
> JCA providers — **Android 12 (API 31)** — so device identity does not exist
> below that, and any A-slice depending on it inherits the constraint.
>
> This was a crash on launch until recently: identity was generated eagerly from
> `Application.onCreate`, so every device from `minSdk = 26` to API 30 died at
> startup. Fixed — `SyncAvailability` in `shared/sync` makes "unsupported" a
> reported state rather than a thrown error, and the Sync screen says so. The
> refusal to fall back to weaker curves stays; only the consequence changed.

---

## 4. The strength ladder

`PolicyMode` already says how much ceremony an action needs. `AuthStrength` says
what that ceremony must actually establish.

| `AuthStrength` | Claim | Produced by |
|---|---|---|
| `NONE` | nothing | — |
| `PRESENCE` | a human is here and acted deliberately | tapping Approve; device enrollment; voice |
| `VERIFIED` | a user factor was checked in software | passphrase, TOTP, Class 2 biometric, device credential |
| `HARDWARE_BOUND` | a hardware-held key was unlocked and is usable | Class 3 biometric via `CryptoObject` |

| `PolicyMode` | Requires |
|---|---|
| `AUTO` | `NONE` |
| `CONFIGURABLE` | `NONE` — the toggle governs |
| `APPROVAL` | `PRESENCE` |
| `STRONG_CONFIRMATION` | **`HARDWARE_BOUND`** |

**Three rules the code enforces rather than documents:**

1. **Combination does not add.** Two `VERIFIED` factors are still `VERIFIED`.
   A passphrase plus a TOTP code does not substitute for the Secure Enclave, and
   `strengthOf()` is a `max`, not a sum. Systems that let factors accumulate into
   a higher tier are how "2FA" becomes a synonym for "hardware-backed" when it
   isn't.
2. **Caps are not tunable.** `VOICE` stops at `PRESENCE` because speaker
   embeddings are replay-spoofable. `BIOMETRIC_WEAK` and `DEVICE_CREDENTIAL` stop
   at `VERIFIED` because Android does not give them Class 3 key semantics.
   Raising either would be a lie told in an enum.
3. **Floors raise and never lower.** `AuthLadder.raiseTo` is written as a `max`,
   so *lowering is not expressible* — there is no argument that weakens the
   result. That is stronger than a check that refuses to lower, because there is
   nothing to forget to check.

---

## 5. `AuthProof` — what replaces the boolean

```kotlin
// after
fun authorize(action: ProposedAction, mode: PolicyMode): AuthOutcome
fun challenge(action: ProposedAction, mode: PolicyMode): AuthOutcome
// → AuthOutcome.Authorized(proof: AuthProof?)
```

A boolean cannot express three things that matter:

| | Boolean | `AuthProof` |
|---|---|---|
| **What was proved** | `true` | `strength` + `factors` — so `STRONG_CONFIRMATION` can reject a passphrase |
| **For how long** | never stale | `expiresAtMs` |
| **For what** | anything | `boundTo` — one specific action |

The third is the one most often missed. **A proof that is not bound to what it
approves is replayable against a different action.** An approval for "send £5"
must not authorize "send £5000", and the component choosing the action is the
planner — the component we trust least.

### What this type honestly does not do

`AuthProof` is not unforgeable. Its constructor is private and issuance runs
through `AuthenticationGate`, so producing one dishonestly is a deliberate,
reviewable act inside `shared:core` rather than a one-character default anywhere
in the tree. But module-local code could still do it.

**The real control is that the action needs the key.** The proof is the spine's
auditable record; `KeyCustody` is the enforcement. Stating this plainly matters
because a type that *looks* like a capability token invites more trust than it
has earned.

---

## 6. Sessions, and the one thing a session may not do

`LockState` is `Locked` or `Unlocked(profile, strength, factors, expiresAt)`.

A live session covers repeated `APPROVAL`-level actions — re-prompting for every
ordinary confirmation trains users to approve reflexively, which is a security
regression wearing a security costume.

**A session never covers `STRONG_CONFIRMATION`.** A five-minute-old unlock is not
consent to spend money. The strong rung always re-prompts and always issues a
proof bound to that one action. This is enforced in `AuthenticationGate.authorize`
before the session is consulted at all, so no future change to session handling
can accidentally widen it.

**Locking is per profile, and profile switch always locks.** Leaving Work
unlocked while Personal is on screen is precisely the cross-contamination the
profile model exists to prevent.

---

## 7. Lockout, scoped honestly

Exponential backoff after 3 free attempts, doubling, capped at 15 minutes.

**Cancelling is not a failed attempt.** Dismissing a prompt is changing your
mind, not guessing wrong; counting it punishes ordinary use.

**The cap is deliberate.** An uncapped backoff is a denial-of-service an attacker
can inflict on the owner by failing on purpose — and the thing being denied is
the owner's own data.

**What lockout is not.** It is not the defence against a determined attacker. An
attacker with the storage never reaches this code; they attack the KDF offline,
where the Argon2id parameters are the entire defence. Lockout defends the
*online* path — someone holding the unlocked device, guessing at a prompt —
where delay genuinely works because humans guess slowly.

This boundary is worth stating because a lockout counter feels like security and
is easy to over-trust. If it lives somewhere a reinstall clears, it is
bypassable, and it was never the control that mattered.

---

## 8. Custody per platform

| Tier | Meaning | Where |
|---|---|---|
| `HARDWARE_ISOLATED` | dedicated security chip; key never enters app memory | StrongBox, Secure Enclave, discrete TPM |
| `HARDWARE` | TEE-backed, shares the main SoC | Android Keystore without StrongBox, TPM via CNG |
| `SOFTWARE` | OS-scoped, bound to a user account — **not** hardware | **Windows DPAPI today**, encrypted file with OS key |
| `NONE` | nowhere safe | enrollment refused, never downgraded |

Two rules:

- **Tell the user their tier.** Silent degradation is how a user believes their
  Work profile is chip-protected when it is a DPAPI blob. `CustodyTier.label`
  exists for exactly this.
- **A profile requiring a tier is refused on a device that cannot meet it, with
  the reason shown** — not hidden, not silently accepted. A profile is only as
  protected as the weakest device holding it.

**Implementations own the hard part.** `KeyCustody` is deliberately thin; the
security lives in the platform calls behind it — `setIsStrongBoxBacked`,
`setUserAuthenticationRequired`, `setUnlockedDeviceRequired`,
`setInvalidatedByBiometricEnrollment`, and a `CryptoObject`-bound prompt. **An
implementation that returns `Unlocked` without those satisfies the interface and
defeats the design.** Reviewing a `KeyCustody` implementation means reading the
platform calls, not the signatures.

---

## 9. Recovery — the honest trade

Key-derived authentication means **a forgotten passphrase with no other enrolled
device is unrecoverable data loss.** That is not a bug to engineer away; it is
the direct cost of there being no server holding a reset link.

Three mitigations, in preference order:

1. **Another enrolled device.** The common case, and why enrolling a second
   device is part of onboarding rather than an advanced setting.
2. **A recovery kit** — the wrapped key exported to paper or a file, protected by
   a high-entropy phrase. Offline-attackable by anyone who obtains it, so
   Argon2id parameters are the whole defence (`TENANCY_DESIGN.md` §7).
3. **Nothing.** A user who declines both is told plainly, once, in words that do
   not soften it.

`KEY_INVALIDATED` is separate and must not be presented as a failed attempt:
enrolling a new fingerprint invalidates the key by design. The user needs the
recovery path, not a retry button.

---

## 10. Refused by design

- **No password authentication to a remote service.** No server, no account, no
  password database. §B5: passkeys/FIDO2 if an account concept ever appears —
  never passwords.
- **No biometric-only profile.** Biometrics change; a passphrase or recovery kit
  must exist or the profile is one fingerprint reset from gone.
- **No "remember me" past a session window.** Indefinite unlock is no unlock.
- **Voice as a primary factor.** Replay-spoofable. Fail-secure today; keep it.
- **Silent tier downgrade.** Fail and say why.
- **A network check as a precondition for opening a profile.** Offline-first
  means the local device decides. Key attestation stays optional and off by
  default because Play Integrity requires network (§B5).

---

## 11. What is landed, and what is not

Honest coverage, matching the convention in `AGENTS_DESIGN.md`.

| Piece | State |
|---|---|
| Factor model, strength ladder, caps | ✅ `shared/core/.../auth/AuthFactor.kt` |
| `AuthProof` with binding + expiry | ✅ `AuthProof.kt` |
| `PolicyMode` → strength, raise-only floors | ✅ `AuthLadder.kt` |
| Session + lock model, strong rung never short-circuited | ✅ `AuthenticationGate.kt` |
| Lockout with backoff, cancel excluded | ✅ `LockoutPolicy.kt` |
| Enrollment guard + refusal reasons | ✅ `DeviceEnrollment.kt` |
| Tests — 19, incl. replay, cross-profile, downgrade, throttle | ✅ `AuthenticationTest.kt`, green on JVM + iosArm64 + macosArm64 + metadata |
| `KeyCustody` **contract** | ✅ `KeyCustody.kt` |
| `KeyCustody` **implementations** | ⬜ **None ship.** No platform code exists yet — see A-2…A-5 |
| `AuthorityManager` still takes `biometricAuthenticated: Boolean` | ⚠️ **Unchanged.** The gate exists; the spine has not been switched to it — A-1 |
| The two ad-hoc `BiometricPrompt` call sites | ⚠️ **Unchanged**, still `CryptoObject`-less — A-6 |
| Passphrase KDF (Argon2id), profile key wrapping | ⬜ Not started — A-2 |
| Lock screen, sign-in, enrollment UI | ⬜ Not started — A-7 |

**Nothing in this design is enforcing anything yet.** The decision logic is
built and verified; it is not wired. Until A-1 and A-2 land, the shipped
behaviour is exactly what §1 describes. Saying otherwise would make this document
the same kind of defect as `README.md:11`.

---

## 12. Slices

Ordered by dependency. Track assignments follow `docs/TRACKS.md`.

| Slice | Goal | Track | Gate |
|---|---|---|---|
| **A-1** | `AuthorityManager.approve` takes `AuthProof?`, not `Boolean`. Delete the defaulted parameter | T2 | Property test: no call path executes a `STRONG_CONFIRMATION` action without a bound, unexpired, hardware-strength proof |
| **A-2** | Android `KeyCustody`: StrongBox where available, `CryptoObject`-bound Class 3, Argon2id passphrase KDF, profile DEK wrapping | T4 | Instrumented test on a real device; key survives reboot, dies on new fingerprint enrollment |
| **A-3** | Wire the SQLCipher passphrase to the wrapped DEK. **Audit for a static secret first** (§B5) | T2 | Wrong passphrase yields unreadable bytes, not a denied dialog |
| **A-4** | iOS `KeyCustody` — Secure Enclave, `LAContext` | T4 | Device run; `apple-compile` alone proves nothing here |
| **A-5** | Windows `KeyCustody` — CNG/TPM + Hello, raising the tier off DPAPI. macOS — Keychain + Touch ID | T4 | `windows-adapter-tests`; tier reported as `HARDWARE`, refusals shown |
| **A-6** | Delete both ad-hoc `BiometricPrompt` call sites; route through the gate | T3 | No `BiometricPrompt` outside the custody adapters — add a guard (T1) |
| **A-7** | Lock screen, first-run enrollment, device list with tier + revoke, recovery-kit flow | T3 | `UI_DESIGN.md` §6 routes; refusal reasons visible, never hidden |
| **A-8** | Per-profile gates; profile switch locks; org floor via `strengthFloor` | T2 + T5 | Unlocking Work leaves Personal locked |
| **A-9** | Every issuance, failure, lockout and revocation into the hash-chained audit log | T2 | Tamper-evident; a deleted entry is detectable |

**A-1 and A-2 are the pair that turns this from a design into a control.**
Everything before them is preparation; everything after them is reach.

### Sequencing note

A-6 must not start before Track 3's T3.1 decomposition lands — both call sites
live in files that slice is restructuring, and this is the one serialized task in
`apps/android`.
