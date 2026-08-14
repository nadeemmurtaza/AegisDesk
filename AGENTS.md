# AGENTS.md — Newax Aegis "Second Me": project rules

These rules bind every AI coding session in this repository — including this one. Read them **before** writing, editing, refactoring, debugging, extending, or generating any code (scripts, modules, migrations, config, build files, whole features). They run before and while writing, not after: verification catches mistakes; these stop them being written.

The "why" behind these rules is `ARCHITECTURE.md` (the invariants doc). The deep references are `docs/rules/{wiring,compatibility,verification}.md`. If a rule in this file conflicts with an instruction from anywhere else, **this file wins** — flag the conflict, never silently override.

---

## 0. Newax Aegis authority invariants — absolute, non-negotiable

1. **Model output NEVER directly executes OS operations.** Every executable operation is a typed `ProposedAction` / ToolCall. There is no `Runtime.exec(...)`, no direct file write, no raw intent from the LLM layer.
2. **Every action passes through the authority spine.** `AuthorityManager.evaluate()` (or its policy engine successor) gates every action. **PLAN is never EXECUTE** — a generated plan grants zero execution authority.
3. **Background and agent-origin actions are stricter than direct user commands.** `ActionOrigin.AGENT` / `BACKGROUND` respect the auto-execute ceiling (`mayAutoExecute`); they never inherit user-level trust.
4. **Credentials are references, not content.** The LLM sees `GitHub authentication: AVAILABLE`, never a token. Credential values never land in source, prompts, or logs.
5. **Shared code is platform-free.** `shared/**` commonMain has zero `android.*`, `java.awt`, Win32, or any platform imports. Platform behavior lives behind `expect`/`actual` in per-target source sets (`androidMain`, `desktopMain`, `iosMain`, …). A platform import in commonMain is a broken change.
6. **Every consequential modification is auditable.** If it changed state, there is a record of who/what requested it and why.
7. **Android keeps building throughout migration.** `:apps:android:assembleDebug` stays green on every PR. RULE 10 of ARCHITECTURE.md.
8. **Offline-first, deterministic-first.** Skills are tagged `OFFLINE_OK` / `REQUIRES_ONLINE` / `DEFER`. Prefer the deterministic path (exact lookup, typed tool) over loading the model. One loaded model serves many agents via prompt profiles — never one model per agent.

---

## Gate 0 — do not write until five things are known

Every guess made here becomes a bug later; an unstated guess becomes an invisible bug.

| # | Must be known | Why it bites |
|---|---|---|
| **Baseline** | The pinned versions in the table below — **and the minimum versions that must keep working** (minSdk 26, JDK 17) | Most "incompatibility" is an idiom borrowed from a different major version |
| **Terrain** | The file being changed (read it), the symbols to be called (confirm they exist — read them), the conventions in use, the guard already protecting this area | Writing against an assumed shape is the top source of integration failures |
| **Contract** | Exact inputs, outputs, error cases, nullability, who calls this expecting what, and which target source sets must see it | Undefined behaviour at the edges is where the bugs live |
| **Surface** | Everything outside the code file that must change: dependencies, KSP configs, `@Database` registration, DB version + migration, manifest, schema export, expect/actuals, CI | Code that compiles but was never declared to its runtime fails silently — in production |
| **Authority** | Which `ActionOrigin` reaches this, what `RiskLevel` it carries, what approval/biometric path it needs | Code that skips `AuthorityManager` is a bypass, even if "just for the automated case" |

If any is unknown: **ask, or state the assumption in one line and take the conservative option.** One line of "assuming Room 2.7.0-alpha13, no schema change" costs nothing and prevents a whole category.

### Current baseline (update this table in the same PR that changes a version)

| Thing | Pinned value | Notes |
|---|---|---|
| Gradle wrapper | **9.7.0** | `gradle/wrapper/gradle-wrapper.properties` |
| JDK / JVM target | **17** (all modules) | `jvmTarget = 17`; on Windows set `$env:JAVA_HOME="C:\Program Files\Android\Android Studio\jbr"` |
| Kotlin | **2.4.10** | root `build.gradle.kts`; KSP 2.3.11 is standalone (no longer `<kotlin>-<ksp>` coupled) |
| KSP | **2.3.11** | standalone versioning since Kotlin 2.x; no lockstep format anymore |
| AGP | **9.3.1** | couples to Gradle 9.7.0 + JDK 17; AGP 9 has built-in Kotlin (`kotlin { compilerOptions { jvmTarget } }`, not `kotlinOptions`) |
| compileSdk / targetSdk / minSdk | **37 / 37 / 26** | Android app + every module with an `android {}` block (7 total — keep them identical); every API used must exist at 26 or be guarded. 37 is the floor required by androidx `core[-ktx]:1.19.0` and `lifecycle-*-compose:2.11.0` — below it AGP's AAR-metadata check fails the build |
| Compose | **BOM 2026.06.01** (Android); **Compose Multiplatform 1.11.1** for `shared:ui` + the windows/macos apps | Compose compiler comes from the Kotlin plugin (Kotlin 2.0+); CMP 1.11.1 is the lockstep pairing for Kotlin 2.4.10 — keep them coupled. The CMP version is declared **once**, root `build.gradle.kts` (`apply false`); modules apply `id("org.jetbrains.compose")` with no version. Two CMP versions in one build do not resolve |
| Room | **2.8.4 (stable KMP)** | `shared:database`; stable since 2.8.x — the 2.7.0-alpha13 pre-release line is gone |
| sqlite driver | **androidx.sqlite:sqlite-bundled 2.7.0** (desktop); **sqlite-framework 2.7.0 / NativeSQLiteDriver** (appleMain, Track M2); SQLCipher 4.17.0 (androidMain) | stable line; per-target KSP configs (`kspAndroid`, `kspDesktop`, `kspIosNative`, …) — the `ksp()` KMP shorthand is deprecated |
| coroutines / datetime | **1.11.0 / 0.8.0** | |
| LiteRT LM | **0.16.0** (platform-impl:android — engine + provider; app consumes via shared:model-api) | offline model runtime |
| Modules | `:apps:android`, `:apps:desktop`, `:apps:macos`, `:apps:ios`, `:platform-impl:android`, `:platform-impl:windows`, `:platform-impl:macos`, `:platform-impl:ios`, `:shared:core`, `:shared:database`, `:shared:platform-api`, `:shared:model-api`, `:shared:sync`, `:shared:desktop-sync`, `:shared:ui` | add new modules to `settings.gradle.kts` in the same change |
| `:shared:ui` targets | `androidTarget`, `jvm()`, `iosArm64`, `iosSimulatorArm64` — **no `macosArm64`** | CMP has no macosArm64 UI artifacts; macOS renders via Compose Desktop on `jvm()`. Unlike `shared:core`, which is platform-free logic and does declare `macosArm64` |

**Known blockers (do not silently inherit, do not silently "fix" unasked):**
- ✅ RESOLVED — machine-specific `org.gradle.java.home` removed from `gradle.properties` (f1617ac); builds are portable across OSes again.
- ✅ RESOLVED — `.github/workflows/android.yml` now triggers on `main` and uploads `apps/android/build/outputs/apk/debug/app-debug.apk` (f1617ac); APK named `app-debug.apk` via `base { archivesName }` (90a56a8).
- ✅ RESOLVED — release signing no longer commits plaintext passwords; credentials come from gitignored `keystore.properties` (template: `keystore.properties.example`), with debug-signing fallback when absent.
- ✅ RESOLVED — `-Xskip-metadata-version-check` removed from apps/android; LiteRT-LM 0.16.0 ships Kotlin 2.3 metadata and Kotlin 2.4.10 reads it natively (version alignment, per platform-impl/android comment).

---

## The rules

### Writing order — this is what prevents placeholders

**R1 — Size the change to what can be finished.**
Placeholders are what happens when the change is larger than the room left to write it: signatures laid down early, bodies filled in order, the last ones become stubs. A complete vertical slice that runs beats a full skeleton that does not. On this repo, that means one mergeable slice per PR from the migration plan — never a module of empty shells. When the request genuinely needs more, deliver the first slice complete and describe exactly what the next contains — that is a plan, not a stub.

**R2 — Write depth-first, leaves before callers.**
Finish each unit before starting the next. A function already written cannot be stubbed. Breadth-first generation is the single most reliable way to produce a file full of `NotImplementedError`.

**R3 — No placeholder. Ever.**
No `TODO`, `FIXME`, `pass`, `...`, `NotImplementedError`, `// add your logic here`. No invented sample data standing in for a real call. No function returning a hardcoded value so a caller will typecheck. No `expect` with a missing `actual`. No `ProposedAction` variant that never reaches a policy decision.

Declared-but-empty surface is worse than nothing: it looks finished, so callers get written against it and the gap is discovered from a crash rather than from the code. If the body cannot be written, do not write the signature — say what is blocking instead.

The single exception is a value only the user can supply — a secret, an account ID, a hostname. It goes in config under an obvious name (`WHATSAPP_ACCESS_TOKEN`, …), it is wired through the Keys/API keys UI or `freebuff-deploy env`, and it is called out in the handoff. Never buried mid-file, never hardcoded.

**Deliberate scaffolding is allowed** (e.g. `:platform-impl:android`/`:platform-impl:windows` existing as empty adapters ahead of their consumers) — but it is named in the handoff as a known gap, not discovered later.

### Completeness

**R4 — Close the call graph.**
Every identifier referenced must be exactly one of: defined in this change, confirmed to exist in the codebase (having read it), or public API of a declared dependency. There is no fourth category. In this repo, additionally: every `expect` has its `actual` in **every** target source set that compiles that code; every entity is declared in `NewaxDatabase`; every action type resolves through `AuthorityManager`.

**R5 — Import and dependency in the same breath.**
Add the manifest line at the moment the import is written, never at the end. At the end you are working from memory — and memory is what failed. Concretely here: new Room DAO/entity → registered in `@Database(entities = [...])` in the same change, DB version bumped, migration written; new KMP target → `ksp<Target>` config added at the same moment; new module → `settings.gradle.kts` include in the same change.

Prefer what is already present. A new dependency is a permanent commitment to its maintenance, licence, size, and future CVEs — it needs a reason beyond convenience. Check `docs/rules/compatibility.md` before adding anything.

**R6 — Write it wired.**
A new symbol gets its caller in the same change. New config gets its registration. New route gets its mount. New entity gets its migration. New tool gets its `ToolDescriptor` + policy entry + audit path. The dangerous case is a guard nobody calls: the protection sits in the source, reads correctly, and never executes. `AuthorityManager`'s `evaluate()` is exactly such a guard — if an action path does not route through it, that is a bug, not an omission.

**R13 — No headless capability. Backend never ships without its frontend.**
No backend code, module, function, agent, tool, or setting is left without a proper, professional UI/UX. A user-facing capability is **not done** until the user can reach and operate it from the UI — it ships with its screen, not just its API.

Concretely on this repo: every user-facing capability in `shared/**` (agents, tools, permissions, memory views, reports, settings) must be surfaced and usable in the Compose UI of `apps/android` (and `apps/desktop` as it grows). A new DAO query that powers a new screen is wired; a new agent that only exists in the registry is a stub. The UI itself must meet the bar: real screens, not placeholders — with loading, empty, error, and approval/confirmation states — following the app's design system, matching the quality of the rest of the interface. Declared-but-unreachable capability is the UI twin of R3's declared-but-empty code: it looks finished and is discovered only from user confusion.

The single exception is internal infrastructure with no user interaction surface (e.g. a background migration, a policy decision internal to `AuthorityManager`) — but any capability whose existence a user could reasonably want to see, configure, invoke, or audit gets its UI in the same change as its logic.

### Compatibility

**R7 — Pin the target, then write only to it.**
Fix the versions before the first line (use the baseline table above), then write to that and nothing else. Where unsure whether an API exists in the pinned version, use the older, broader form — it works on both.

**R8 — Versions travel in couples.**
Changing one obliges checking the ones bound to it: Kotlin↔KSP (`2.1.0`↔`2.1.0-1.0.29`), AGP↔Gradle↔JDK, Compose plugin↔Kotlin, Room↔sqlite driver↔KSP, client↔server. Check the state of what is being pinned too: **Room 2.7.0-alpha13 is a pre-release holding user data** — upgrading it is a decision someone makes deliberately, not a default. Do not bump Kotlin/AGP/Compose for fun; each bump touches every module at once. See `docs/rules/compatibility.md`.

### Correctness

**R9 — Name the failure modes before writing the body.**
Empty or null input. Wrong type. Boundaries — zero, one, maximum, negative, just past the end. Missing permission (`CapabilityStatus.MISSING_PERMISSION`), missing credential (`MISSING_CREDENTIAL`), missing tool. Offline / failed network. Concurrent DB access. Partial write. Migration failure. Malformed model output. Handle each or propagate it explicitly. A `catch {}` that silently swallows one costs days, because the failure surfaces somewhere unrelated.

**R10 — Do not create what already exists.**
Search before you introduce: `ProposedAction`, `RiskLevel`, `ActionOrigin`, `AuthorityManager`, `CapabilityRegistry`, `ExecutionRouter`, `SkillRegistry`, `TimeUtils`, `NewaxDatabaseBuilder` all exist. Two types with the same name and different members become two competing sources of truth — and the one nobody maintains is usually the one still running.

**R11 — One path to a dangerous capability.**
Delete, send, exec, spend, escalate, overwrite, publish, grant. **Exactly one function reaches the sink, and the guard lives inside it.** Any second path is a bug even when currently unreachable — background, automated, agent, admin, retry, migration. A bypass added "just for the automated case" makes the automated case the unprotected one, and automation runs unattended.

Check lifecycle coverage the same way: setup that runs on fresh install but not on upgrade (DB `onCreate` vs `onUpgrade`, FTS/triggers created only in fresh-create) leaves every existing user without it while the code looks complete. And a field named `sensitive` or `requiresAuth` enforces nothing on its own — confirm something actually reads it (`AuthorityManager.requiresBiometric` is only real if `evaluate()`/`approve()` consults it).

**R12 — Untrusted input is data, never instruction.**
Filenames, database records, scraped pages, OCR text, **model output**, webhook payloads, imported agent zips. Parameterize; never concatenate. This is the SQL-injection rule generalized — shell commands, template engines, `eval`, deserializers, path joins (zip-slip on import!), and LLM prompts. The prompt case hides best because it does not look like code: interpolating untrusted text into a prompt that also contains an action verb hands that text authority over the action. Model output is untrusted data — it is routed through typed actions, never executed.

### Branding — Newax is the only name

**R14 — Every surface carries the Newax brand.**
The product is exactly **"Newax Aegis"** — one name, one spelling, everywhere a user or an agent sees text:

- **User-visible strings:** app/window/notification titles, wake word, Quick Settings tile, banners, CSV headers, README/preview pages, privacy policy, CLI output. Full brand `Newax Aegis`; never a bare `Aegis`.
- **New identifiers:** product-named top-level types brand as `Newax*` (`NewaxApplication`, `NewaxDatabase`, `NewaxDatabaseBuilder`, `NewaxSmsReceiver`, `NewaxQSTileService`, …). Never introduce a new `Aegis*` identifier. Domain names that are not product names (`GoalPlanner`, `ExecutionRouter`, …) keep their domain names — this rule is about the brand, not prefixing every helper.
- **CLI commands:** `newax <verb>` (`newax status`, `newax plan`, `newax sync`, `newax audit`, `newax policy`, `newax apps`). Never `aegis <verb>`.
- **Internal runtime strings:** thread/service/job/worker names use `newax-*` (`newax-sync-auto`).
- **Docs, comments, and rules:** prose names the product `Newax Aegis`, and every symbol a doc references exists under its current name. A stale old name in a doc (`AegisDatabase`, `AegisDatabaseBuilder`) is a bug — fix it in the same change, including the rule that cites it.

**R15 — Frozen compatibility names are not branding violations.**
These keep their existing lowercase `aegis`/repo forms **by design** — renaming breaks installed apps, user data, or sync identity. They are not "missed" branding; do not rename them in a branding sweep, and treat any proposed rename as a breaking change that needs a migration decision, not a search-and-replace:

- Package root: `com.newax.aegis.*` — every new file declares under it. Never `com.aegis.*` or a bare `com.newax.*`.
- On-disk paths and file names: `~/.aegis/`, `~/Library/Application Support/Aegis/`, DB file names (`aegis.db`, `sync.db`), Room schema export dirs, bundled asset/`.litertlm`/spk names.
- The GitHub repository is `nadeemmurtaza/AegisDesk` — the repo name is a fact, not a brand; a reference to it stays.

---

## The read-back pass, before output

All static. No toolchain needed. Run these against your own output before sending it:

1. Resolve every identifier — walk the code and point each name at its definition.
2. Search your own output for `TODO`, `FIXME`, `XXX`, `pass`, `...`, `NotImplemented`, `your-`, `example.com`, `changeme`. Any hit is R3.
3. Every import has its dependency line (and its Room/KSP/registration side, if applicable).
4. Every new symbol has a caller, or a handoff line saying it does not.
5. Every version-specific API matches the pinned version in the baseline table.
6. Every dangerous sink has one path, with the guard inside it — and the guard is actually called.
7. The surroundings are written — config, registration, migration, permissions, ignore rules.
8. **Newax Aegis-specific:** no `import android.` (or any platform import) in `shared/**/commonMain`; every `expect` has actuals; DB version bumped + migration + schema export when entities changed; Android still builds.
9. **R13 (UI):** every user-facing capability added this change has its screen — navigation entry, loading/empty/error/approval states, design-system styling. If a capability is internal-only, say so in one line.
10. **Branding (R14/R15):** every new user-visible string, identifier, and CLI verb says `Newax`; no new `Aegis*` identifier; no frozen compatibility name (`com.newax.aegis.*` packages, `~/.aegis/`, DB file names, schema dirs) renamed.

## Verification (then, if a toolchain exists)

Cheapest first: **typecheck → build → lint → test → smoke run.** Stop at the first failure, fix, restart from the top — a fix routinely breaks the step above it.

```bash
# Kotlin/KMP type signal (fast)
./gradlew :shared:database:compileCommonMainKotlinMetadata   # pure Kotlin — does NOT verify expect/actuals
# Expect/actual + KSP (the real gates). KGP task naming is compileKotlin<Target> /
# <target>Jar, NOT compileDesktopKotlin. For a jvm("desktop") target: desktopJar
# (compiles + runs its Room KSP). For KMP androidTargets: assembleDebug (compiles
# debug variant + runs its Room KSP).
./gradlew :shared:database:desktopJar :shared:database:assembleDebug
./gradlew :shared:core:compileKotlinJvm :shared:core:assembleDebug
# Full app gates
./gradlew :apps:android:assembleDebug :apps:android:testDebugUnitTest
./gradlew :apps:android:lintDebug
./gradlew :apps:android:connectedDebugAndroidTest   # emulator only — Room migration tests
```

Critical notes for this repo:
- **KSP and Room's query verifier only run on a full compile.** A green editor means nothing. `compileCommonMainKotlinMetadata` does **not** verify expect/actual balance — the per-target compiles do.
- **This Freebuff sandbox has no JDK** — Gradle cannot run here. That is an `UNVERIFIED` line in your handoff, never a skipped step and never a claim of success. The machine gates are your Windows machine (`$env:JAVA_HOME="C:\Program Files\Android\Android Studio\jbr"`) and CI.
- `gradle.properties` `org.gradle.java.home` must be gone before CI can be the gate.
- Report what was **confirmed**, never what was inferred. "Should compile" and "compiles" are different claims.
- Full command list per ecosystem: `docs/rules/verification.md`.

## Handoff

Close every change with this block. Omit genuinely empty sections; **never omit UNVERIFIED when something is unverified.**

```
VERIFIED    <command run> → <result>
WIRED       <new symbol> ← <caller>
NEEDS YOU   <changes outside what could be edited>
UNVERIFIED  <what was not run> — run: <exact command>
```

If a terse or code-only output style is active, honour it — no prose, no comments — but keep this block, because it carries the only information terse output deletes that the user actually needs. If even that is unwanted, the one line that cannot be dropped is `UNVERIFIED: <what>`.

---

## Reference index

- `ARCHITECTURE.md` — the invariants: 10 rules, authority spine, agent/skill/procedure/tool taxonomy, module map, platform matrix, trust tiers, offline model.
- `docs/rules/wiring.md` — the "is it wired?" checklist per ecosystem (Universal, Android, KMP/Room, DB/migrations, CI, deployment).
- `docs/OVERVIEW.md` — the single overview reference: Part A = feature list (what the product does, per surface, with landed/planned status); Part B = the exhaustive version reference (every pinned version, where it is declared, and known drift between build files and the baseline table).
- `docs/UI_DESIGN.md` — the UI/UX specification for all four bodies (Android, iOS, Windows, macOS): the sequenced workflow tree, a normative WCAG 2.2 AA accessibility spec, corrected design tokens with measured contrast ratios, the adaptive IA, the complete route tree (every page, its contents in sequence, every control resolved to one destination), the content-block system, shared component library, platform seams, delivery phases, and coverage matrices.
- `docs/rules/compatibility.md` — version couplings that actually break (Kotlin↔KSP↔AGP↔Compose↔Room↔sqlite) + dependency-liability signals.
- `docs/rules/verification.md` — full verification commands per ecosystem + smoke-run and "exercise twice" paths.
