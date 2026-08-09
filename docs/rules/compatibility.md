# Version couplings

For **R7/R8** of `AGENTS.md`, before writing against any dependency or build file.

Versions do not stand alone. Each one below is bound to its neighbours, and changing one without the other is the mechanical cause of most "it worked yesterday" breakage. **Before trusting any version number, read the actual build file / wrapper — not a range you remember.**

## This repo's pinned stack (source of truth: `AGENTS.md` baseline table)

| Change this | Must check |
|---|---|
| **Kotlin (2.1.0)** | **KSP version** — literally `<kotlin>-<ksp>`: `2.1.0-1.0.29`. A mismatch fails at configuration time. Also the Compose compiler (via the Kotlin Compose plugin since Kotlin 2.0 — no `composeOptions` block). |
| **AGP (8.7.3)** | Gradle wrapper (8.11.1) and JDK (17) — each AGP has minimums for both. Also `compileSdk` (35). |
| **Room (2.7.0-alpha13)** | `androidx.sqlite` driver (`sqlite-bundled 2.5.0-alpha13`), the room-compiler version (must match runtime exactly, per-target KSP), KSP2 support, and **schema format** — regenerated schema identity hashes change; refresh migration test assets in the same PR. |
| **Any annotation processor (Room)** | KSP2 compatibility. If an older release misbehaves under KSP2, pin `ksp.useKSP2=false` explicitly rather than relying on a changed default. |
| **targetSdk (35)** | Behaviour changes for that API level: permissions, background limits, foreground service types, `exported` components. |
| **minSdk (26)** | Every API called must exist at 26 or be guarded by a version check or desugaring. |
| **Kotlin metadata** | `-Xskip-metadata-version-check` in `apps/androidApp` is a *symptom* of a version mismatch (Room alpha / mixed Kotlin versions). Remove it only when versions align — never add more workarounds on top. |
| **Any native library (.so)** | 16 KB page size support for 64-bit — required by Play for recent target levels; older prebuilt artifacts do not have it. |

## The danger zones specific to this project

- **Room 2.7.0-alpha13 is a pre-release holding user data.** Do not "just upgrade" it. When moving it, the migration path, schema export, and `MigrationTest` are the first things that break.
- **Adding a KMP target** (iOS/macOS/Linux later) means: new targets in `kotlin {}`, per-target KSP configs, per-target `actual`s for every `expect` (`TimeUtils`, `AegisDatabaseConstructor`), per-target schema directories, and a CI job that can actually compile that target. It is never a one-file change.
- **Build DSL is version-specific and punishes memory:** properties that look assignable are often read-only; Kotlin DSL syntax differs from Groovy. Read the actual file. A broken `build.gradle.kts` fails every task in the module — it looks like a broken codebase.

## General couplings (any ecosystem)

- **Node/web:** Node ↔ `engines` ↔ CI image ↔ deploy runtime (all three); ESM vs CJS; `@types/*` ↔ TypeScript; react-dom must match react exactly; framework majors change routing/config/directive models; peerDependencies warnings are couplings being declared; build target ↔ browserslist/polyfills.
- **Python:** minor version ↔ `python_requires` ↔ wheel availability; numpy/pandas ↔ ABI-compiled deps; async API drift across minors; typing syntax (`X | Y`, `list[int]`, `Self`) has minimum versions.
- **JVM:** JDK ↔ bytecode target ↔ dependency support; Spring Boot BOM governs dozens of transitive versions — don't override one in isolation; javax ↔ jakarta split is not source-compatible.
- **Go:** `go` directive ↔ language features ↔ CI toolchain; modules past v1 carry the major in the import path (`/v2`); `//go:embed` targets must exist in build context.
- **Rust:** edition ↔ idioms/lints; MSRV is the highest of all dependencies' MSRVs; Cargo unifies feature flags across the graph.
- **Databases:** driver version ↔ server version (both directions); dialect SQL must exist in the oldest server still in production; ORM ↔ migration format.
- **Containers:** base image glibc vs musl (Alpine breaks prebuilt wheels/native binaries); arm64 vs amd64 for every prebuilt binary; runtime version in image must match what code was locked against.

## Signals that a dependency is a liability

Check before adding a dependency, and before leaving one in place while touching code around it:

- **End of life or abandoned.** No release in years, archived repo, explicit successor. A superseded artifact still installs cleanly — nothing warns you.
- **Pre-release holding something important.** Alpha/beta/RC guarding secrets, storing user data, or handling payments is a decision, not a default. (Room 2.7.0-alpha13 is exactly this — tracked deliberately.)
- **Deprecated upstream while still resolving fine.**
- **Version drift within a family.** Packages from the same publisher pinned to different minors usually means one was bumped alone.
- **Unmaintained transitive dependency carrying a known advisory.**
- **Large binary artifacts committed to version control** rather than resolved from a repository — effectively permanent, invisible in review.

None of these is automatically disqualifying. All of them should be surfaced to the user rather than absorbed silently.
