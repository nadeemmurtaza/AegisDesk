# Track 4 — Platform Bodies

Your complete brief. Read `AGENTS.md` and `docs/PARALLEL_RULES.md` before your
first commit.

---

## 1. Who you are

You own the three bodies that are not Android: Windows, macOS, iOS — and the
per-OS capability adapters all four bodies stand on.

**You own:**

```
platform-impl/android/**     the Android adapter (NOT the Android app)
platform-impl/windows/**
platform-impl/macos/**
platform-impl/ios/**
apps/desktop/**              Windows body (Compose Desktop)
apps/macos/**                macOS body (Compose Desktop)
apps/ios/**                  iOS body (Compose Multiplatform)
docs/SYNC_DESIGN.md
```

**You never touch:** `apps/android`, `shared/**`, build files.

Note the split: `platform-impl/android` is yours (the capability adapter);
`apps/android` is Track 3's (the app). Different things, similar names.

---

## 2. Hard prerequisite — sort this before writing code

You need **real hardware**:

- **A Mac with Xcode** — for iOS/macOS app work and framework linking.
- **A Windows machine** — for TPM work and the DPAPI/Toolhelp32 paths.

**What you get for free from Linux:** Kotlin/Native cross-compiles Apple
*targets*, so `:shared:*:compileKotlinIosArm64` and friends verify here. Only
linking a final framework and running on a device need a Mac.

**What you cannot fake:** `platform-impl:windows`'s Windows-only tests are
`Assume`-gated — they compile everywhere and *skip* the OS-bound paths. A green
run on Linux proves nothing about DPAPI. The `windows-adapter-tests` CI job is
where they actually execute.

Without the hardware, your work must be marked unverified under Rule 9. Say so
plainly rather than letting a green Linux build imply coverage it does not have.

---

## 3. Set up

```bash
export JAVA_HOME=/usr/lib/jvm/java-17-openjdk-amd64      # NOT 21
export ANDROID_HOME=$HOME/android-sdk
echo "sdk.dir=$ANDROID_HOME" > local.properties          # gitignored

./gradlew :platform-impl:windows:test :apps:desktop:test
```

---

## 4. Rules that bind you hardest

- **Adapters implement contracts; they do not define them.** `shared/platform-api`
  is Track 2's. Need a contract change? Request it.
- **A capability that cannot work on your OS returns `NOT_SUPPORTED`** — it does
  not crash and does not silently no-op. The Win32 adapters already do this
  correctly on non-Windows hosts; match that.
- **CMP has no `macosArm64` UI target.** macOS renders through Compose Desktop
  on the JVM. If you find yourself adding `macosArm64` to a UI module, stop —
  that is why `shared:ui` deliberately omits it.

---

## Slice T4.0 — ~~`:apps:desktop` does not compile~~ ✅ RESOLVED

**Nothing to do. Kept as a record of what unbuilt code costs.**

The Windows body did not compile at `HEAD`, and its 113 tests had never run,
because **no CI workflow builds `:apps:desktop`**. Six faults, all ordinary API
drift:

| File | Fault |
|---|---|
| `build.gradle.kts` | `Episode` unresolved — `shared:database` never declared, though the file's own comment already described that exact pattern for `shared:core` |
| `ProximityCli.kt:49` | `discovery.error` — promoted to the `ProximityDiscovery` interface with a `null` default, which also collapsed Android's separately-named `discoveryError` into one name |
| `planner/DesktopGoalPlanner.kt:284` | `it.id` on a `TaskGraph`, which is keyed by `goalId` everywhere else |
| `ui/GoalsScreen.kt:539` | `RunLogCard` built a Compose tree without `@Composable` |
| `ui/state/GoalsScreenState.kt` | imported `DesktopGoalPlanner` from the wrong package |
| `PolicyExporterTest.kt:31` | `RiskLevel.HIGH_IMPACT_SYSTEM` — a `PrivilegeLevel` value on a `RiskLevel` field |

Then the tests ran, and eight failed. **One was a real bug:**
`DesktopPolicyHolder.init` constructed the audit store but never called its
`load()`. Every policy decision was written to disk and never read back, so the
desktop policy audit silently started empty on every launch. An audit trail that
forgets across restarts is not an audit trail.

The other seven were tests that had never executed: they asserted quote-always
CSV with no trailing terminator against an RFC-4180 renderer that quotes
minimally and terminates with CRLF — and they recovered records by splitting on
newlines, which cannot work when a field legally contains one. Rewritten to
assert exact output, which is stricter than the `contains` checks they replaced.

**Your first job is now T4.1.** But ask Track 1 to add `:apps:desktop` and
`:apps:macos` to a workflow before you build on top of this — everything above
happened because nothing checked.

---

## Slice T4.1 — Desktop parity (slice 18) ← start here

**Goal:** the Windows body gets a chat surface.

**Why:** `apps/desktop` today is five `NavigationRail` items — Status, Apps,
Goals, Policy, Audit — and **no chat at all**. Desktop chat is a `--cli` REPL.
The product's main surface does not exist on desktop.

**Steps:**

1. Consume `shared:ui`. It is already wired as a dependency and its `jvm()`
   target exists precisely to serve you. `NewaxTheme` gives you the same tokens
   Android uses.
2. Replace `apps/desktop/.../ui/NewaxTheme.kt`'s local `lightColorScheme` with
   the shared theme. Keep the file only if it carries desktop-only wiring.
3. Build the thread surface from `UI_DESIGN.md` §6.3 (routes 1.1, 1.2).
4. Map the existing five screens onto the new IA per `UI_DESIGN.md` §5.1:
   Status/Apps → Capabilities, Goals → Tasks, Policy/Audit → Settings.

**Verify:** `./gradlew :apps:desktop:test :apps:desktop:run`

**Do not** re-implement components that exist in `shared:ui`. If one is missing,
ask Track 3 to add it there rather than writing a desktop-only twin — that is
exactly how 189 duplicated constants happened.

---

## Slice T4.2 — Expanded layout (slice 16)

**Goal:** three panes, menu bar, keyboard shortcuts, command palette.

**Why it is greenfield:** the repo has **zero** key handling. No `onKeyEvent`,
no `KeyShortcut`, no `MenuBar` — not even in the desktop app.

**Layout** (`UI_DESIGN.md` §5): sidebar (conversations + sections) │ thread │
artifact/context panel, at ≥1024 dp.

**The shortcut table is specified** in §5.2. Implement it as given.

**One rule you must not relax:** approve and reject are keyboard-reachable but
**never single-key**. An accidental keystroke must not authorize an action.
`Ctrl/Cmd+Enter` and `Ctrl/Cmd+Backspace`, as specified.

**Also:** window state persistence — size, position, pane widths, sidebar state.

---

## Slice T4.3 — Windows custody tier (tenancy T-8)

**Goal:** raise Windows key custody from software to hardware.

**Why this is the highest-value platform-security work available:** DPAPI —
what `platform-impl/windows` uses today — is **user-account-scoped, not
hardware-backed**. Under the tenancy design a profile is only as protected as
the weakest device holding it, so a Work profile synced to a DPAPI-only Windows
box silently lowers its real protection.

Read `docs/TENANCY_DESIGN.md` §6 before starting.

**Steps:**

1. Implement TPM-backed key storage via CNG / Windows Hello.
2. Expose a **custody tier** query on the secrets capability: `HARDWARE` vs
   `SOFTWARE`. Both the enrollment flow and the UI need to read it.
3. Enforce the minimum-tier check: a device that cannot meet a profile's
   required tier is **refused at enrollment, with the reason shown** — not
   silently accepted.

**Verify:** the `windows-adapter-tests` CI job. Local Linux runs skip these
paths entirely.

---

## Slice T4.4 — iOS body (slice 17)

**Goal:** `apps/ios` becomes a real app.

**Status:** unblocked — the Apple targets compile now, including `shared:ui`'s
`iosArm64` and `iosSimulatorArm64`. `apps/ios/src/main/kotlin/Main.kt` is
currently 17 lines that print to stdout and say `SwiftUI (TBD)`.

**Steps:**

1. Wire the CMP iOS app shell consuming `shared:ui`.
2. Implement the platform seams in `UI_DESIGN.md` §9 — `PlatformSafeArea`,
   `SystemBackHandler`, `Haptics`, `Clipboard`, `PermissionLauncher`,
   `BiometricPrompt`, `FilePicker`, `VoiceRecognizer`, `BarcodeScanner`,
   `ReducedMotion`.
3. `ReducedMotion`'s iOS actual already exists
   (`shared/ui/src/iosMain/.../ReducedMotion.ios.kt`) using
   `UIAccessibilityIsReduceMotionEnabled` — **it compiles but has never run on a
   device.** Verify it first; it is the smallest possible test of the seam.
4. Secure Enclave for key custody (`HARDWARE` tier).

**Verify:** `apple-compile` in CI, plus a real device or simulator run for
anything behavioural.

---

## Slice T4.5 — Multi-device enrollment (tenancy T-6)

Read `docs/TENANCY_DESIGN.md` §3 first.

**The good news: no new cryptography.** `shared/sync` already has `Identity.kt`
(Ed25519), `Pairing.kt` (QR + SAS), `SessionCrypto.kt` (Noise handshake),
`Hkdf.kt`, `BlobCrypto.kt`. Enrollment is profile-key transfer over an
already-authenticated channel.

**Your part:** the flow on all four bodies, with the same handshake and per-OS
key custody.

**Two rules that are not negotiable:**

- **SAS mismatch is a hard stop.** It is the only interception defence. Never
  auto-confirm.
- **Profiles enroll individually.** Putting Work on a work laptop must not put
  Personal there. Step 2b in §6.7 of `UI_DESIGN.md` — nothing preselected, and a
  profile whose custody tier exceeds the device's is shown **disabled with the
  reason**, not hidden.

---

## When you are blocked

- **Need a capability contract changed:** Track 2 owns `shared/platform-api`.
- **Need a component:** Track 3 owns `shared:ui`. Ask for it there — never build
  a desktop-only twin.
- **Need a dependency:** Track 1.
- **No Mac / no Windows box:** say so early and loudly. Work that cannot be
  verified must be marked unverified, and four tracks planning around your
  "done" deserve to know which kind it is.

---

## Before every PR

- [ ] Title starts `[T4]`
- [ ] Only your files — **not `apps/android`, not `shared/**`**
- [ ] `./gradlew :platform-impl:windows:test :apps:desktop:test` passes
- [ ] Apple work: `apple-compile` green
- [ ] Windows-only paths: verified on the `windows-adapter-tests` job, not just Linux
- [ ] Anything needing hardware you lack is **explicitly marked unverified**
- [ ] No component duplicated that `shared:ui` already provides
