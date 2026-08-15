# Track 1 — Build, CI & Release

Your complete brief. You should not need another document to start, though you
must read `AGENTS.md` and `docs/PARALLEL_RULES.md` before your first commit.

---

## 1. Who you are

You own the build. Every other track writes code; you own whether it compiles,
whether CI can tell, and whether a release is trustworthy.

**You own — nobody else may edit these:**

```
build.gradle.kts                 (root)
settings.gradle.kts
*/build.gradle.kts               (every module)
gradle/                          (wrapper + the version catalog you will create)
.github/workflows/
scripts/
gradle.properties
AGENTS.md  — the "Current baseline" table only
```

**You never touch:** any `src/` directory. If a build change requires a code
change, you request it from the owning track.

**Why you own all build files:** every track needs dependencies added, and build
files are the most conflict-prone surface in a multi-module repo. They request;
you add. One round trip removes an entire class of merge conflict — and this
repo already has the scars: AGP and Kotlin are each declared in three separate
files, which is exactly how Compose Multiplatform drifted to `1.7.1` against a
`1.11.1` baseline.

---

## 2. Set up, and prove it works

```bash
# JDK 17 — NOT 21. CI uses 17 and the project targets it.
export JAVA_HOME=/usr/lib/jvm/java-17-openjdk-amd64

# Android SDK with platform 37
export ANDROID_HOME=$HOME/android-sdk
echo "sdk.dir=$ANDROID_HOME" > local.properties     # gitignored — never commit it

# Prove the toolchain works before writing anything
./gradlew :shared:core:compileKotlinJvm :shared:ui:jvmTest
```

That last command must print `BUILD SUCCESSFUL`. If it fails on your machine but
passes in CI, fix your machine first.

**If `sdkmanager` says `Failed to find package 'platforms;android-37'`:** your
`cmdline-tools` is too old and its repository index is stale. Update
`cmdline-tools` itself. This is a tooling problem, not a project problem — and
you will be asked about it, so know the answer.

---

## 3. Rules that bind you hardest

- **Append-only on shared files.** `settings.gradle.kts` and the workflows get
  additions from you; when another track needs a module registered, add the line,
  do not reorganise the file.
- **A new module is invisible to CI until you add it.** The workflows name tasks
  explicitly. `shared:ui` had to be added to two workflows by hand. When any
  track creates a module, adding it to CI is your job and it is not optional —
  an unbuilt module is an unverified module.
- **Never change a version because it "looks old."** Versions here are coupled
  (Kotlin ↔ KSP ↔ AGP ↔ Compose ↔ Room). Read `docs/rules/compatibility.md`
  first.

---

## Slice T1.1 — Version catalog

**Goal:** every dependency version declared exactly once.

**Why:** AGP `9.3.1` appears in 3 build files, Kotlin `2.4.10` in 3, and
`compileSdk` in 7 modules. Compose Multiplatform drifted to `1.7.1` in two apps
against a `1.11.1` baseline and blocked shared UI for weeks. One declaration
means divergence is impossible, not merely discouraged.

**Files:** `gradle/libs.versions.toml` (new) · every `*/build.gradle.kts` ·
`AGENTS.md` baseline table

**Steps:**

1. Inventory what exists — do not work from memory:
   ```bash
   grep -rhoE '"[0-9]+\.[0-9]+\.[0-9]+"' --include=build.gradle.kts . | sort | uniq -c | sort -rn
   grep -rn 'compileSdk\|targetSdk\|minSdk' --include=build.gradle.kts .
   ```
2. Create `gradle/libs.versions.toml` with `[versions]`, `[libraries]`,
   `[plugins]`. Start from the AGENTS.md baseline table — it is the intended
   truth; where a build file disagrees, the build file is the drift.
3. Convert modules **one at a time**, compiling after each. Do not convert all
   sixteen and then build.
4. `compileSdk`/`minSdk` are not dependency versions — put them in
   `[versions]` anyway and reference them, so all seven modules cannot drift.
5. Update the AGENTS.md baseline table to **cite the catalog** rather than
   restate numbers.

**Verify:**
```bash
./gradlew :apps:android:assembleDebug :shared:database:assemble \
          :shared:ui:jvmTest :platform-impl:windows:test
```

**Done when:** no version literal remains in any `build.gradle.kts`, and the
above passes.

**Do not:** change any version's *value* in this slice. It is a pure move. If a
version is wrong, that is a separate commit with its own reasoning — mixing them
means a broken build has two candidate causes.

---

## Slice T1.2 — Guard: no inline versions

**Goal:** make T1.1 permanent.

**Steps:**

1. Add `scripts/check-no-inline-versions.sh` — fail if any `build.gradle.kts`
   contains a `"N.N.N"` literal outside the catalog.
2. Wire it into `invariants.yml`'s `static-invariants` job, beside
   `check-invariants.sh` (which is fast and toolchain-free — match that).
3. Prove it works: temporarily reintroduce a literal, watch it fail, revert.

**Done when:** a PR adding an inline version fails CI. **A guard you have not
seen fail is not a guard.**

---

## Slice T1.3 — CODEOWNERS

**Goal:** file ownership enforced by review, not by memory.

**Steps:**

1. Create `.github/CODEOWNERS` from the ownership map in `docs/TRACKS.md`.
2. Be careful with the `apps/android` split: Track 3 owns UI files, Track 5 owns
   `agents/` and `engine/`, in the same module. Path patterns must reflect that:
   ```
   /apps/android/src/main/java/com/newax/aegis/agents/   @track5
   /apps/android/src/main/java/com/newax/aegis/engine/   @track5
   /apps/android/                                        @track3
   ```
   More specific patterns win; order matters.
3. Enable branch protection requiring owner review.

**Done when:** a PR touching another track's files requests that owner.

---

## Slice T1.4 — The lint guards

**Goal:** the guard table in `PARALLEL_RULES.md` stops being aspirational.

Build these, cheapest first:

| Guard | Catches |
|---|---|
| No `Color(0x` outside `shared/ui/` | Token duplication — 189 constants were re-declared across 18 files |
| No hardcoded user-facing string in Kotlin | Blocks i18n; Track 3 needs this before slice 4 |
| Doc link-check: every referenced `docs/*.md` exists | `REFINED_THEME.md` was cited in 6 files and never existed |
| Banned-symbol list | Empty now; Track 2 fills it after unifying the risk vocabularies |

**Steps:** grep-based `scripts/*.sh` in `static-invariants` is enough and runs in
seconds. Do not reach for a full lint framework yet.

**Do not** turn on the string guard before coordinating with Track 3 — it will
fail on every existing screen. Ship it disabled with a `TODO`-free comment, or
ship it after their slice 4. Talk to them.

---

## Slice T1.4b — Build the bodies nobody builds

**CI builds `:apps:android` and nothing else.** `:apps:desktop`, `:apps:macos`
and `:apps:ios` appear in no workflow — and `:apps:desktop` had rotted all the
way to non-compilation before anyone noticed. `:apps:macos:compileKotlin` passes
today; protect it before it goes the same way.

Sequence matters: **Track 4 fixes the desktop compile first** (their slice T4.0),
then you add the job. Adding a job that fails on arrival trains people to ignore
red.

**Also: Android Lint had never been run.** It found 18 errors, two of them real
crashes on Android 8–9. 16 are fixed; the last two are deleted by slice A-6.
Add `lintDebug` to CI **after A-6 lands** — and prefer precise
`@SuppressLint` with justification over a blanket baseline, which would hide new
violations in the same files.

---

## Slice T1.5 — Supply chain (ENGINEERING.md slice 22)

1. **Gradle dependency verification** — `gradle/verification-metadata.xml` with
   checksums *and* signatures. Highest-value control available, bootstrapped
   with one command.
2. **SBOM** (CycloneDX) per release.
3. **Reproducible builds** — makes "is the shipped binary the reviewed source?"
   answerable.
4. Staged Play rollout with crash-rate halt criteria.

---

## When you are blocked

- **A track needs a dependency:** they open an issue naming the module, the
  coordinate, and why. You add it. Do not let them add it themselves "just this
  once" — that is how the version catalog rots.
- **CI is red and it is not your change:** find the owning track from
  CODEOWNERS, post the failing job link and the error line, and say plainly it
  is theirs. Do not fix another track's code.
- **You need a code change:** request it. You own build files, not `src/`.

---

## Before every PR

- [ ] Title starts `[T1]`
- [ ] Only files from your ownership list changed
- [ ] `./gradlew :apps:android:assembleDebug :shared:database:assemble :shared:ui:jvmTest` passes
- [ ] Any new guard has been **seen to fail** on a deliberate violation
- [ ] PR body records: what was verified and by which command; what was not, and why; any interface change and who depends on it
