# Built but not wired

An inventory of code that compiles and is never reached, or is reached and does
nothing useful. Produced by static analysis and then **verified case by case** —
the raw scans had false positives, and those are recorded here too so nobody
repeats them.

Nothing in this document is a compile error. Everything here builds, ships, and
does less than its name suggests.

---

## How this was measured, and where it lies

Three scans, each verified before reporting:

| Scan | Raw result | After verification |
|---|---|---|
| Explicit markers (`TODO`, `NotImplementedError`, "stub") | 14 TODO | **11** — 3 were `.toDouble()` matching "toDo" |
| Functions appearing exactly once repo-wide | 275 | real, but includes DAO methods Room calls via generated code |
| Classes never referenced outside their own file | 176 | **~174** — `NewaxSmsReceiver` and `NewaxQSTileService` are declared in `AndroidManifest.xml`, which the `.kt`-only scan cannot see |

**The manifest false positive is the one to remember.** Any Android component
registered in XML — receivers, services, activities, tile services — looks dead
to a source scan. Check the manifest before deleting anything.

A second caveat: a data class returned only by its own file's functions is not
"dead" if the enclosing object is used. The counts below deliberately key on the
**enclosing object**, not its return types.

---

## 1 · The dev console — ~24 backends, 5 tabs, 1 wired

The largest single block of unreached code in the project.

`apps/android/.../ui/devconsole/tabs/` ships **five** tabs — `DbTab`, `FilesTab`,
`LogsTab`, `StateTab`, `TriggersTab` — and across all of them exactly one backend
is referenced: **`DevLogger`**.

Everything below is built, compiles, and is called by nothing:

```
engine/dev/jobs/JobInspector.kt                engine/dev/apps/AppInspector.kt
engine/dev/notification/NotificationDebugger   engine/dev/db/DatabaseInspector.kt
engine/dev/accessibility/AccessibilityInspector engine/dev/dashboard/SensorDashboard.kt
engine/dev/macro/InputMacroRecorder.kt         engine/dev/dashboard/ConnectivityDashboard.kt
engine/dev/network/NetworkInspector.kt         engine/dev/dashboard/AudioDashboard.kt
engine/dev/files/FileIntelligenceTool.kt       engine/dev/people/PersonInspector.kt
engine/dev/profiler/HeapProfiler.kt            engine/dev/profiler/ResourceProfiler.kt
engine/dev/replay/ProcedureReplayer.kt         engine/dev/search/SearchLaboratory.kt
engine/dev/ws/DebugWebSocketServer.kt          engine/dev/adb/AdbBridge.kt
engine/dev/procedure/ProcedureDebugger.kt      engine/dev/trace/DecisionInspector.kt
engine/dev/log/AnrWatchdog.kt                  engine/dev/log/CrashReporter.kt
```

`DebugWebSocketServer` and `AnrWatchdog` are not in the manifest either — checked.

**This is a decision, not a bug list.** Either the tabs get built out, or the
backends get deleted. What is not defensible is keeping ~24 inspector
implementations that no code path can reach: they carry maintenance and
compile cost, and they make the dev console look far more capable than it is.

---

## 2 · The AI layer — four objects, none reached

| Object | State |
|---|---|
| `engine/ai/PromptBuilder.kt` | **Entire object dead.** 10 prompt builders: `intentDetection`, `factExtraction`, `tripleExtraction`, `goalDecomposition`, `procedureGeneration`, `entityExtraction`, `personProfile`, `conflictResolution`, `customSystem`, `qa` |
| `engine/ai/ResponseValidator.kt` | **Entire object dead.** Includes `isHallucination` and `extractFactualClaims` |
| `engine/ai/ReasoningPlanner.kt` | **Entire object dead**, along with `ReasoningChain` / `ReasoningStep` |
| `engine/ai/ContextBuilder.kt` | **Entire object dead**, with `ContextWindow` |
| `engine/ai/ToolSelector.kt` | Referenced **only** from `ReasoningPlanner:41` — which is itself dead, so transitively unreachable |
| `engine/ai/MemoryRanker.kt` | `scoreRelevance`, `diversify` dead |

The whole `engine/ai` package is an island. Worth stating plainly:
**hallucination detection is written and never runs.**

This matters more than the dev console because it is easy to believe otherwise —
a reviewer seeing `ResponseValidator.isHallucination` reasonably assumes model
output is validated somewhere. It is not.

---

## 3 · Safety-relevant: `ExecutionGuard.checkWithContext` — ✅ FIXED

It had **zero references anywhere, including its own file** — the pre-flight
check from `COMPUTER_USE.md` §5, written and never called. `check()` (the
`PROTECTED_PACKAGES` block) was wired, so the protection that existed was "never
touch Settings" and the protection that did not was "confirm the screen did not
change under you".

Now called from `ProcedureExecutor` on every step. Wiring it surfaced three more
faults inside it:

1. **A `Context` parameter neither function used** — which made the guard look
   like it needed a device to test. It did not. Removed; the guard is now pure
   and has **11 unit tests**, its first ever.
2. **A package mismatch reported `WRONG_PERSON`.** An app switching under you is
   not a person mix-up, and an audit row saying so actively misleads. Now
   `UNEXPECTED_PACKAGE`.
3. **`GuardContext` carried `expectedPersonEntityId`, `expectedFileId` and
   `isDestructiveAction` that nothing read.** Setting them looked like protection
   and bought none — the same failure mode as the unwired function itself, one
   level down. Removed rather than left silently ignored; re-add them with the
   code that enforces them.

**The one subtlety worth keeping:** enforcement starts only once execution has
*arrived* in the expected app. A procedure's own `LaunchApp` step legitimately
runs from somewhere else, so checking before arrival would abort every procedure
on its first step. Once arrived, any later step finding a different foreground
app aborts — it does not adapt, because adapting to an unexpected screen is
improvising against an adversary.

---

## 4 · Silent stubs — return a plausible answer, do nothing

Distinguish these from the honest stubs in §5. These fail *quietly*.

| Function | Behaviour |
|---|---|
| `PersonProfiler.recognizeFacesInBitmap` | `return emptyList()` — "Stub: returns empty list until ML Kit Face Detection is wired". A caller cannot tell "no faces" from "not implemented" |
| `SkillGuard.sandboxProvider` | `= { false }`. **Correct and safe** — no sandbox ships, so `sandboxRequired` skills demote to human approval. Verified wired at `SkillGuard:90`. Listed here only so nobody mistakes the seam for an implementation |

`VoiceAuthenticator` is a whole dead object (`verifyIdentity` unreferenced),
which is *fail-secure* — voice cannot authenticate anything because nothing asks
it to. Consistent with `AUTH_DESIGN.md`, which caps voice at `PRESENCE` anyway.

---

## 5 · Honest stubs — fail loudly, correctly

**36 `NotImplementedError` returns**, all in two files:

- `platform-impl/macos/.../MacOSPlatformCapabilities.kt` — **19**
- `platform-impl/ios/.../IOSPlatformCapabilities.kt` — **17**

Every capability — files, processes, shell, secrets, system, desktop — returns
`Result.failure(NotImplementedError(...))`. Both entire capability surfaces are
placeholders, and the classes themselves are never instantiated outside their
own files.

**These are the right kind of stub.** They fail with a named reason rather than
returning a plausible-looking empty result, and `Result.failure` is in the type
so a caller cannot ignore it. Track 4 owns filling them in; nothing here is
misleading anyone in the meantime.

`apps/ios/src/main/kotlin/Main.kt` is 17 lines that print `SwiftUI (TBD)` and
`Thread.sleep(Long.MAX_VALUE)`. Honest, and already slice T4.4.

---

## 6 · Declared contracts with no production caller

| Seam | State |
|---|---|
| `ModelProvider.stream()` | Declared, implemented in `FallbackModelProvider`, covered by `ModelProviderContractTest` — and **called from no production code**. `MainViewModel` uses `complete()`, so replies arrive whole and there is no stop button to design around. Already slice 9 / T2.5 |

A contract with a test and no caller is the most deceptive shape in this
inventory: coverage reports count it, and it does nothing.

---

## 7 · Large unreached subsystems

Whole objects with no external reference, grouped by area. Each needs a
wire-it-or-delete-it decision:

**Registries** — `ModelRegistry`, `ServiceRegistry`, `DeviceRegistry`,
`GoalRegistry`, `TaskRegistry`, `SensorRegistry`, `PermissionRegistry`,
`IntentRegistry`, `WorkflowRegistry`

**Managers** — `IndexManager`, `ExportManager`, `CacheManager`, `ProfileManager`
(12 dead functions — note the name collision `TENANCY_DESIGN.md` §9 flags)

**Learning** — `FailureLearner`, `SnapshotEngine`, `ForgettingEngine`,
`MemoryCompiler`, `ConfidenceEngine`

**Other** — `ContextEngine`, `CanonicalStore`, `ProcedureOptimizer`,
`HardwareBenchmark`, `GraphCompiler`, `CheckpointedJob`, `MetricsEngine`
(`LatencyTracker`, `ModuleMetrics`)

---

## What to do with this

Ordered by consequence, not by size:

1. **`ExecutionGuard.checkWithContext`** — call it. A written safety check that
   nothing invokes is the worst item here, because the code reads as protected.
   Track 5, C-3.
2. **`engine/ai`** — decide. If hallucination detection and prompt templating are
   wanted, wire them; if the model path moved on, delete the package. Leaving it
   implies validation that does not happen.
3. **`ModelProvider.stream()`** — Track 2, T2.5. Already scheduled.
4. **Dev console** — 5 tabs or ~24 backends; pick one.
5. **Registries / managers / learning** — audit per object during their owning
   track's slices. Some are genuinely pre-wired for work that has not landed;
   some are abandoned. They should not be treated as one bucket.
6. **`platform-impl/{ios,macos}`** — Track 4, already planned. No action beyond
   keeping the failure honest.

## The standing guard — ✅ ADDED

`scripts/check-unwired.sh` now runs this scan on demand and in CI once Track 1
wires it into `invariants.yml`.

It is **baselined, not zero-tolerance**: `scripts/unwired-baseline.txt` freezes
today's 173 entries so the debt cannot grow, while a hard failure on all of them
would simply get the check disabled. New unwired classes fail; entries that
disappear are reported as progress so the baseline can be trimmed.

Manifest-declared components are allow-listed by **reading the manifest**, not by
a hand-maintained list — that was the false positive this scan produced first
time, and hand-lists rot.

**Verified failing**, not merely written: a deliberate unreferenced object was
added, the check exited 1 and named it, and the check returned to OK when it was
removed. A guard nobody has seen fail is not a guard.
