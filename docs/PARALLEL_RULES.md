# Rules for Parallel Agents — Newax Aegis

How multiple agents work this repo at once without producing conflicts,
duplicates, or two of everything.

`docs/PARALLEL_WORKSPLIT.md` is a *specific* split (four platform bodies, with
per-agent briefs). This document is the *general* rule set that applies to any
split.

**Every rule here is stated as a guard, not a convention.** Conventions decay
under parallel work — nobody violates them on purpose, they just don't know.
Guards don't decay. Where a rule has no mechanical check yet, it says so, and
that gap is the work.

Every example below is a **real defect found in this repository**, not a
hypothetical.

---

## Rule 0 — the gate must exist and be green

**Nothing else on this page is enforceable without it.**

`main` has never had a green CI run: `android.yml` 30/30 failures, `apple.yml`
24/24. That is not a coincidental fact sitting alongside the divergence
catalogued below — **it is the cause.** Every duplicate constant, every second
vocabulary, every version drift accumulated because nothing could detect them.

Before any parallel work starts:

1. Fix Gate 0 (`ENGINEERING.md` slice 0b).
2. Confirm every gate runs on every PR.
3. **Only then** fan out.

Parallelising onto a red build multiplies unverifiable work. Four agents
producing code nobody can compile is worse than one, not better.

---

## Rule 1 — some things cannot be parallelised

The most common cause of conflict is not bad discipline. It is two agents
sensibly working on things that turn out to be the same thing.

**Serialize these. One agent, finished and merged, before anyone else starts:**

| Work | Why it cannot be shared |
|---|---|
| Gate 0 / build fixes | Everyone rebases onto it |
| **Room schema migrations** | Version numbers collide. Two agents both writing "v20" is unmergeable, and the loser's migration must be rewritten, not rebased |
| `MainActivity` / `MainViewModel` decomposition (slice 6) | 1403 and 1207 lines that nearly every feature touches. Any parallel edit conflicts |
| Tenancy T-1/T-2 (profile scope + per-profile DB) | Changes the shape every data path reads |
| The design-token layer (`shared:ui`) | Everything renders through it |
| Anything renaming a widely-used symbol | See Rule 5 |

**These parallelise well** — disjoint files, stable interfaces:

- Platform adapters (`platform-impl/android|windows|macos|ios`)
- Per-screen work **after** decomposition
- Independent test infrastructure
- Independent documents
- Per-agent skills and agent packages

**The test:** if two tracks would edit the same file, they are one track.

---

## Rule 2 — one owner per file

Every file has exactly one agent who may edit it during a wave. Not "should
coordinate" — **may edit**.

- Declare ownership in `CODEOWNERS` (per module and per high-traffic file).
- An agent needing a change in someone else's file **requests it**; it does not
  edit. The owner makes the change, or ownership transfers explicitly.
- Shared files (`AGENTS.md` baseline table, `settings.gradle.kts`, CI workflows)
  are **append-only during a wave**, and conflicts there are resolved by the
  integrator, not by whoever pushes last.

**Guard:** `CODEOWNERS` + branch protection requiring owner review.

---

## Rule 3 — one declaration per fact

Every fact is declared **once**. Divergence is impossible if there is only one
place to diverge from. This is the single highest-leverage rule, and this repo
is a catalogue of what happens without it.

### 3.1 Versions → a version catalog

**Evidence:** AGP `9.3.1` is declared in 3 build files, Kotlin `2.4.10` in 3.
Compose Multiplatform was pinned `1.7.1` inline in two apps while the AGENTS.md
baseline said `1.11.1` — a drift that blocked shared UI and was only found by
reading the files. `compileSdk` was declared in **7 modules** independently.

**Rule:** `gradle/libs.versions.toml` becomes the only place a version appears.
Modules reference aliases. No inline version strings, ever. The AGENTS.md
baseline table cites the catalog rather than restating numbers.

**Guard:** CI grep — a version literal in any `build.gradle.kts` fails the build.
*(The catalog does not exist yet. Creating it is a serialized Rule-1 task.)*

### 3.2 Design tokens → `shared:ui`

**Evidence:** 189 private colour constants across 18 files — the same palette
re-declared per file, with the *same* concept under different names (`ReadyCol`,
`AccentGreen`, `Green`, `U_Green`) and different values (`#22C55E` vs `#16A34A`
both meaning "success").

**Rule:** colour, type, spacing, shape come from `NewaxTheme`. No `Color(0x…)`
outside `shared:ui`.

**Guard:** lint rule banning `Color(0x` outside `shared/ui/`.

### 3.3 User-visible strings → resources

**Evidence:** every screen hardcodes its strings, which is why RTL and Urdu
cannot be verified.

**Rule:** no user-facing literal in Kotlin.

**Guard:** lint rule; pseudolocale (`en-XA`) render check in CI.

### 3.4 Schema → one migration per version, claimed before writing

**Rule:** an agent claims the next schema version in the tracker *before*
writing the migration, and migrations merge serially.

**Guard:** exported schema JSON in the repo — two agents claiming v20 produce a
merge conflict in a file, which is exactly what you want, instead of two
migrations that both "work" locally.

---

## Rule 4 — one name per concept

This is the "methods/functions/variables differences" problem, and it is the
hardest to guard mechanically because the compiler is perfectly happy with two
names for one idea.

**Evidence, all real:**

- **Three risk vocabularies:** `MainActivity`'s local `Risk {Routine, Sensitive,
  HighImpact}`, the shared `RiskLevel {LOW..CRITICAL}`, and `PolicyMode
  {AUTO..STRONG_CONFIRMATION}` — three names for how dangerous an action is. A
  mis-mapping between them silently downgrades a safety requirement.
- **Two model-state vocabularies:** `ModelProvider.ModelState` and
  `ModelManager`'s six-value enum.
- **A name collision waiting to happen:** the existing `ProfileManager` means
  *persona settings* (name, language, tone). The tenancy design introduces
  *Profile* meaning *an isolation boundary with its own key*. Two very different
  things, one word. The tenancy doc renames the old one to `PersonaSettings`
  for exactly this reason.

**Rules:**

1. **A concept registry** — a table in `ARCHITECTURE.md` mapping each domain
   concept to its **one** canonical type name. Before introducing a type, check
   it. Before introducing a *synonym*, don't.
2. **Introducing a second name for an existing concept requires deleting the
   first, in the same commit.** No "we'll unify later". Later is how you get
   three.
3. **Check for collisions on the word, not just the symbol.** `Profile` was free
   as a type name and still ambiguous as a word.

**Guard:** a banned-symbol list in CI (once unified, `Risk` and the old
`ModelState` are compile errors). Registry review at PR time for new types.

---

## Rule 5 — renames are surgical, never search-and-replace

**Evidence:** an `Aegis` → `Newax` rename produced
`android:theme="@style/Theme.NewaxNewax"` — a theme that does not exist, in the
shipped manifest — and `android:label="Newax Newax"`. Both survived because
nothing compiled and nothing checked.

**Rules:**

- A rename is one commit, does nothing else, and is verified by a build.
- Never blind `sed` across a repo. Enumerate call sites, change them, compile.
- **Frozen identifiers are frozen** (AGENTS.md R15): `com.newax.aegis.*`,
  `~/.aegis/`. A product rename never touches package names, paths, or wire
  formats.

**Guard:** manifest/resource reference validation in CI; `assembleDebug` as the
arbiter.

---

## Rule 6 — change a contract and all its call sites in one commit

Half-migrated interfaces are the worst parallel-work artifact: they compile,
they look intentional, and the next agent copies the wrong half.

**Evidence:** `ModelProvider.stream()` exists and is called from nowhere;
`MainViewModel` uses `complete()`. `AgentStream` is a typed event bus whose only
consumer renders `takeLast(5)`. Both look like live capability and are not.

**Rules:**

- Adding an interface method means wiring at least one real caller in the same
  commit, or not adding it.
- Changing a signature means updating every call site in that commit.
- **Nothing lands as "for future use."** Unused capability is indistinguishable
  from broken capability at review time.

**Guard:** unused-public-API detection; explicit API mode on `shared/*`.

---

## Rule 7 — docs and code move together

**Evidence:**

- `REFINED_THEME.md` was cited as the token source of truth in **6 files** and
  never existed in the repo. Agents wrote against a spec they could not read.
- `README.md:11` claims "No Internet permission" while `AndroidManifest.xml:6`
  declares it. A false claim, and a privacy one.
- Build-file comments claimed a "Kotlin 2.1.0 baseline" two majors out of date.
- `MEMORY_DESIGN.md` assumed the swarm shares one database; `TENANCY_DESIGN.md`
  gives each profile its own. Written months apart, silently contradictory.

**Rules:**

- A value and its documentation change in the **same commit**.
- Never cite a document without confirming it exists.
- A stale comment is worse than no comment — update or delete it.
- When a new design invalidates an older doc, **say so in the older doc**, in
  the same change.

**Guard:** CI link-check that every `docs/*.md` referenced from code or docs
exists. Doc-drift is otherwise invisible.

---

## Rule 8 — small, rebased, frequently merged

- **Branch lifetime under a day.** Long-lived branches are where conflicts breed.
- **Rebase onto `main` before every push**, not at the end.
- One concern per commit; commit messages explain *why*.
- Never merge a red branch, and never merge onto a red `main` — see Rule 0.

---

## Rule 9 — verify before claiming, and mark what is unverified

"Done" means the gate passed. Not "it looks right", not "it should compile".

Where verification is genuinely impossible — as it is right now in a sandbox
with no Android SDK — **the work is marked unverified in the commit message,
the PR body, and the slice status**, prominently. An unmarked assumption is how
the next agent inherits a bug as a fact.

**Guard:** the slice status legend in `ENGINEERING.md` (✅ landed / 🟡 written
but unverified / ⬜ not started / 🔒 blocked). Nothing sits at ✅ without a
passing gate.

---

## Rule 10 — the handoff record

Every agent finishing a task writes down, in the PR:

1. What was verified, **and by what** (the command, the test, the gate).
2. What was **not** verified, and why.
3. Interfaces added or changed, and who now depends on them.
4. Anything discovered that invalidates an existing doc.
5. Decisions taken under assumption — stated as assumptions.

Point 5 matters most. An assumption written down is a question the next agent
can answer; an assumption in someone's head is a bug with a delay fuse.

---

## The guard table

The rules that matter are the ones a machine enforces. Current state:

| Difference class | Guard | Exists? |
|---|---|---|
| Version drift | Version catalog + no-inline-version check | ❌ **build it** |
| Duplicated tokens | Lint: no `Color(0x` outside `shared:ui` | ❌ build it |
| Hardcoded strings | Lint + pseudolocale render | ❌ build it |
| Colliding schema versions | Exported schema JSON conflicts on merge | ✅ schemas dir exists |
| Two names per concept | Concept registry + banned-symbol list | ❌ build it |
| Broken renames | `assembleDebug` + resource reference check | 🔒 blocked on Gate 0 |
| Half-wired interfaces | Explicit API mode + unused-API detection | ❌ build it |
| Doc/code drift | Link-check + doc-referenced-file existence | ❌ build it |
| Platform-import leakage | `scripts/check-invariants.sh` | ✅ **works today** |
| Expect/actual imbalance | `scripts/check-invariants.sh` | ✅ works today |
| Accessibility regressions | `ContrastTest` (84 assertions) | 🟡 written, needs Gate 0 |
| Anything compiling at all | CI | 🔒 **Gate 0** |

Two guards work today. One is written. **The rest are the actual work of making
parallel development safe**, and they are cheap compared to the divergence they
prevent — this repo accumulated every failure mode in the left column while they
were missing.

---

## Recommended wave structure

```
WAVE 0 — one agent, nobody else running
  Gate 0 (build green) → version catalog → CODEOWNERS → the guard table's
  cheap checks (lint rules, link-check)

WAVE 1 — one agent each, serialized where Rule 1 says so
  slice 6 decomposition  ─────▶ then everything else can fan out
  (parallel-safe alongside: docs, platform adapters, test infra)

WAVE 2 — fan out, disjoint file ownership
  per-screen UI · per-platform adapters · agent packages · test suites

WAVE 3 — serialize again
  tenancy T-1/T-2 (schema + profile scope), then fan out T-3…T-19
```

The pattern: **serialize what changes shape, parallelise what fills it in.**

---

## The short version

If an agent reads only one thing:

1. Don't start until the build is green.
2. Don't edit a file you don't own.
3. Don't declare a fact twice — find where it already lives.
4. Don't invent a second name for something that has one.
5. Don't leave an interface half-wired.
6. Don't let a doc and the code it describes disagree.
7. Don't say "done" without naming the gate that passed.
