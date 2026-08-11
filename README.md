# Aegis v0.4 — offline-first Android personal assistant

A native Kotlin/Jetpack Compose MVP that can read the current accessibility tree, propose UI actions, require approval, and then tap/type/send on the user's behalf.

## What works now

- Fully offline deterministic commands: screen reading, tapping, typing, replying, app opening, scrolling, Home, Recents, and Back.
- Accessibility-tree screen reading (no screenshots are stored).
- Tap, type, back, and message-send primitives.
- Mandatory approval card before any external action.
- No Internet permission, analytics, account, or cloud storage.
- Continuous encrypted device memory: `remember that ...`, `what do you remember`, `clear memory`.
- Offline-preferred Android voice recognition (availability depends on installed device language packs).
- Volatile notification inbox access; message content is not persisted and replies are never automatic.
- A stable `OfflineModel` interface for later MediaPipe/LiteRT/llama.cpp model packs.
- Multi-step plans using `then`; every step receives a separate approval prompt.
- Galaxy S21 8 GB dual profile: Gemma 3 1B INT4 default plus optional Gemma 3n E2B quality/vision mode.
- Real LiteRT-LM Kotlin engine lifecycle and streaming conversation inference.
- `.litertlm` document importer with format, size, magic-header, and SHA-256 verification.
- Private app-storage model installation, automatic reload, visible status, and command-engine fallback.

## Run

1. Open this folder in Android Studio (JDK 17).
2. Let Android Studio install SDK 35 and sync Gradle.
3. Run on a physical Android 8+ device.
4. In Aegis, tap **Screen access**, enable Aegis, and return to the app.
5. Optionally tap **Inbox** and grant notification access.
6. Tap **Import model** and select an official Gemma `.litertlm` bundle already downloaded to the phone.
7. Wait for **Offline AI ready**, then chat naturally or try `open WhatsApp then tap Search then type Ali`.

Accessibility services are powerful. Install only builds you control. Banking, password managers, permission dialogs, CAPTCHAs, biometric prompts, and apps with protected content should remain blocked.

## Windows desktop (Track A)

A JVM bootstrap (`apps/desktopApp`) implements the shared platform capability contract on Windows via `platform/windows`: real file access, process listing/launch/terminate, a bounded shell runner, User32/GDI desktop automation (window activation, SendInput click/type/scroll, screenshots), a DPAPI-protected secrets vault, and system info/connectivity/battery.

```bash
sh ./gradlew :apps:desktopApp:run   # prints the capability surface + operational statuses
sh ./gradlew :platform:windows:test # adapter unit tests — run on any OS; Win32 paths are OS-guarded
```

On non-Windows hosts the Win32-backed capabilities report `NOT_SUPPORTED` instead of crashing; the pure-JVM ones (files, shell, system) stay operational. The Compose Desktop UI (Track B) will consume this same process-wide registry.

## Honest capability boundary

“Offline AI chat” requires separately licensed model weights that are too large to bundle in the APK. Aegis now includes the LiteRT-LM runtime and importer; the user selects the downloaded `.litertlm` bundle once, it is verified and copied into private storage, and subsequent natural-language chat runs through the on-device model. Deterministic commands remain available if initialization fails.

For this build, use the conservative settings in `MODEL_SETUP_S21.md`. The current Google-recommended production direction is LiteRT-LM; the older MediaPipe LLM API is compatibility-only. Model weights remain a separate user import because of size and model-license acceptance.

## Still required for a production assistant

1. On-device LLM adapter with downloadable, hash-verified model packs.
2. Device-specific offline model pack selection and measured performance tests.
3. Contacts/calendar adapters and a user-editable per-app permission screen.
4. A planner constrained to a typed action allowlist; never let model text directly invoke Android APIs.
5. Notification reply suggestions through Android RemoteInput, with explicit approval.
6. Screenshot understanding via MediaProjection only for screens the user explicitly shares.
7. Automated tests, signed release builds, privacy policy, and Play policy review.

## Safety architecture

The model/parser can only **propose** a typed `ProposedAction`. The UI displays the exact action. Only the approval button calls the accessibility service. Sending a message is never an implicit side effect of generating text.
