# Development

## Requirements

- JDK 17 or newer
- Android SDK with API 37 (`compileSdk`/`targetSdk`), min SDK 26
- An arm64 phone, or an arm64 emulator. LiteRT-LM ships native code for `arm64-v8a` and `x86_64` only.
- For the iOS compile check: Xcode command-line tools (Kotlin/Native downloads its own toolchain, about 2.4 GB)

Versions: Kotlin 2.4.20, AGP 9.5.0, Compose Multiplatform 1.12.2, Gradle 9.7.0 (wrapper).

## Common tasks

| Task | Command |
|---|---|
| Build and install a debug APK | `./gradlew :androidApp:installDebug` |
| Unit tests (25 tests: pipeline, safety, search, sync, answer cards) | `./gradlew :androidApp:testDebugUnitTest` |
| Check that shared code still compiles for iOS | `./gradlew :shared:compileKotlinIosSimulatorArm64` |
| Release APK (minified) | `./gradlew :androidApp:assembleRelease` |

Run the iOS compile before pushing changes to `shared/`: it catches JVM-only calls (`String.format`,
`HashMap.merge`, `java.time`) that compile fine for Android.

## Configuration

Everything deployment-specific is in `gradle.properties`, so a release needs no code change:

| Property | Meaning |
|---|---|
| `kuyaj.syncBaseUrl` | Record sync server; empty = built-in records only. See [Record sync](sync.md). |
| `kuyaj.syncPublicKeys` | Pinned Ed25519 keys, `keyId:base64`, comma-separated |

The model download list is code: `ModelCatalog` in `androidApp/.../llm/ModelManager.kt` (URL, SHA-256, size,
backends per build).

## Running with a model

The app works without a model (records only). To test AI answers:

- **From the app:** Settings → AI model → *Download on Wi-Fi* (2.6 GB, stock Gemma).
- **A fine-tuned model:** push it as `custom.litertlm` on a debug build; see
  [On-device model](on-device-model.md#testing-a-fine-tuned-model).

On an emulator, the model runs on CPU and may hit the 60-second deadline, so you'll mostly see records as written.
Use a real phone to judge AI answers.

## Driving the app from the command line

Handy for screenshots and quick checks on an emulator or phone:

```bash
adb shell am start -n ph.bettergov.kuyaj/.MainActivity
adb shell input tap 288 1182                  # tap a topic tile (coordinates for 1080x2400)
adb shell input text "SSS%shotline"           # %s is a space
adb exec-out screencap -p > screen.png
adb logcat -s KuyaJ.Llm                       # model loading, search_kb calls, timings
adb shell cmd uimode night yes                # dark mode
```

## Adding or changing records

1. Edit or add a file in `androidApp/src/main/assets/kb/` following [the record format](records.md).
2. Run the unit tests: `KbRecord` parsing fails on a missing field, and `KeyFactTest` checks the answer-card values.
3. If you add a category, give it a style in `Categories.kt` and, if it has one clear value, a rule in
   `AnswerFacts.kt`.
4. Check the home screen's `TOPICS` and `EXAMPLES` still ask answerable questions.

## Code style

- Shared code first: if it doesn't need Android, it goes in `shared/commonMain`.
- Keep the answering rules in code (`Orchestrator`, `Safety`), not in prompts. Prompts can be ignored; code can't.
- The system prompts must stay byte-identical to the ones the model was trained with
  (`kuya-j-evals/distill/restructure_v3.py`). Change both together, then retrain.
- Comments marked `ponytail:` name a deliberate shortcut and when to upgrade it.
