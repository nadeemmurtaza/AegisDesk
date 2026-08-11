# Verification commands

Read this when a toolchain is available. Run cheapest-first, **stop at the first failure, fix, then restart from the top** — fixes routinely break the step above. Report what was confirmed, never what was inferred: "should compile" and "compiles" are different claims.

## Kotlin / Android / Gradle (this repo)

```bash
./gradlew :shared:database:compileCommonMainKotlinMetadata   # fastest pure-Kotlin signal
                                                            # ⚠ does NOT verify expect/actual balance
./gradlew :shared:database:compileDesktopKotlin              # per-target compile — catches missing actuals
./gradlew :shared:database:compileDebugKotlin
./gradlew :shared:database:kspDesktopKotlin                  # Room query verification (KSP)
./gradlew :shared:database:kspAndroidKotlin
./gradlew :apps:androidApp:compileDebugKotlin
./gradlew :apps:androidApp:assembleDebug                     # full Android gate (invariant: must stay green)
./gradlew :apps:androidApp:lintDebug
./gradlew :apps:androidApp:testDebugUnitTest                 # JVM unit tests
./gradlew :apps:androidApp:connectedDebugAndroidTest         # needs a device/emulator — Room MigrationTest
./gradlew :platform:windows:test                             # Windows adapter tests (any OS; Win32 paths OS-guarded)
./gradlew :apps:desktopApp:run                               # desktop bootstrap smoke — prints capability statuses
./gradlew :apps:androidApp:assembleRelease                   # only this exercises R8/minify/signing
```

**Critical:** annotation processors and KSP only run on a full compile. Room's query verifier, KSP-generated `_Impl`, and expect/actual wiring all fail at this stage, not at typecheck. A green editor means nothing here. And **editing `build.gradle.kts` is itself a change that must be verified** — an invalid DSL call fails every task in the module, so a broken build script looks like a broken codebase. `./gradlew tasks` is a fast way to confirm the script itself evaluates.

**Environment notes for this repo:**
- On Windows: `$env:JAVA_HOME="C:\Program Files\Android\Android Studio\jbr"; ./gradlew <task>`.
- The sandbox has **no JDK on PATH by default** — Gradle cannot run until one is provided. A Temurin 17 tarball under `/tmp` (`export JAVA_HOME=/tmp/jdk-17.0.20+8`) was used to verify Track A. A build you could not run is an `UNVERIFIED` handoff line, never a skipped step.
- `gradle.properties` must not contain `org.gradle.java.home=...` for CI/sandbox builds to work.
- CI (`.github/workflows/android.yml`) is the machine gate once its path/branch issues are fixed.

## Other ecosystems (for future platform/script work)

- **JVM (plain):** `./gradlew compileJava build test` · `mvn -q compile` / `mvn -q verify`
- **TypeScript/JS/Node:** `npx tsc --noEmit` → `npx eslint .` → `npm test` (or vitest/jest) → `npm run build`. **`tsc --noEmit` passing does not mean the app builds** — bundlers resolve paths/aliases/dynamic imports differently. Run the build. In a monorepo, verify the package touched and anything importing it.
- **Python:** `python -m compileall -q .` → `ruff check .` → `mypy .` → `pytest -q` → `python -c "import yourpackage"` (catches broken `__init__` and circular imports — the most common failure that passes review).
- **Go:** `go vet ./...` → `go build ./...` → `go test ./...` (build catches unused imports/vars).
- **Rust:** `cargo check` → `cargo clippy -- -D warnings` → `cargo test` → `cargo build --release`.
- **Swift:** `swift build` → `xcodebuild -scheme <Scheme> -destination 'platform=iOS Simulator,name=iPhone 15' build` → `xcodebuild test ...`.
- **C#/.NET:** `dotnet build` → `dotnet test` → `dotnet publish -c Release` (trimming/AOT issues appear only here).
- **Shell:** `bash -n script.sh` (syntax) → `shellcheck script.sh`. **Never verify a destructive shell script by running it** — read it, shellcheck it, dry-run against a scratch directory.
- **SQL/migrations:** fresh-create proves almost nothing. (1) empty DB → confirm schema; (2) copy at the oldest supported version, walk forward through every intermediate version; (3) confirm FTS/triggers/views/indexes created outside the migration framework exist after upgrade, not only fresh create; (4) confirm the rollback path if claimed.
- **Infra:** `terraform validate && terraform plan` (never apply as verification) · `kubectl apply --dry-run=server -f .` · `docker build .` · `docker compose config`.

## The smoke run

After the toolchain is green, execute the thing once with realistic input. Call the endpoint, run the CLI, open the screen. Compilation proves the shapes match; only running proves the wiring does. If a smoke run is impossible in your environment, that is an `UNVERIFIED` line, not a skipped step.

## Paths that need exercising twice

- fresh install **and** upgrade from an existing version
- interactive/foreground **and** automated/background (user-origin **and** agent-origin!)
- feature flag off **and** on
- first request **and** the retry
- empty dataset **and** populated

These pairs are where "it worked on my machine" comes from. Most shipped regressions live on the second item of one of these pairs — and for Aegis, the automated/background side is exactly the side with stricter policy, so it is the one most likely to be skipped and most dangerous to skip.
