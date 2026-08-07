# Galaxy S21 8 GB model profile

## Locked profile

- Fast/default model: Gemma 3 1B Instruction, INT4
- Optional quality/vision model: Gemma 3n E2B, INT4
- Runtime target: LiteRT-LM Kotlin
- Compatibility fallback: MediaPipe LLM Inference 0.10.27
- Context window: 2,048 total tokens
- Maximum answer: 256 tokens
- Concurrent model sessions: 1
- Temperature: 0.25
- Required free storage before dual-model import: 5 GB
- Default backend order: CPU, then benchmark GPU; retain the faster stable result

The additional 8 GB Samsung RAM Plus setting is swap-backed storage and is not counted as physical model memory. It may reduce crash frequency but can increase latency and storage wear.

## Model installation design

Model weights are not placed in the APK. Aegis imports a user-selected `.litertlm` model pack into private app storage, validates the bundle header and size, records its SHA-256 fingerprint, and initializes it on the CPU backend. If initialization fails, Aegis falls back to the deterministic command engine. GPU benchmarking remains disabled until it is validated on the actual handset.

Gemma access may require accepting Google's model terms at the official distribution source. Do not redistribute model weights inside this project archive.

## Expected limits

The S21 predates the devices Google identifies as the reliable target for the older MediaPipe API. Thermal throttling and GPU-driver differences between Snapdragon 888 and Exynos 2100 variants mean performance must be measured on the actual phone. Gemma 3n E2B is permitted as an optional foreground quality/vision mode, not the always-on automation model. Models above this class are rejected.
