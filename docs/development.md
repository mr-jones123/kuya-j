# Development

## Requirements

- JDK 17 or newer
- Android SDK with API 37 (`compileSdk`/`targetSdk`), min SDK 26
- An Android device: a real phone, a Qualcomm Device Cloud phone, or an emulator ([choosing a device](#choosing-a-device)).
  LiteRT-LM ships native code for `arm64-v8a` and `x86_64` only.
- For the iOS compile check: Xcode command-line tools (Kotlin/Native downloads its own toolchain, about 2.4 GB)

Versions: Kotlin 2.4.20, AGP 9.5.0, Compose Multiplatform 1.12.2, Gradle 9.7.0 (wrapper).

## Common tasks

| Task | Command |
|---|---|
| Build and install a debug APK | `./gradlew :androidApp:installDebug` |
| Unit tests (44 tests: pipeline, follow-ups, safety, search, sync, record cards) | `./gradlew :androidApp:testDebugUnitTest` |
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

- **From the app:** Settings → AI model → *Download on Wi-Fi* (3.0 GB, Kuya J v3).
- **A fine-tuned model:** push it as `custom.litertlm` on a debug build; see
  [On-device model](on-device-model.md#testing-a-fine-tuned-model).

On an emulator, the model runs on CPU and may hit the 60-second deadline, so you'll mostly see records as written.
Use a real phone to judge AI answers.

## Choosing a device

Kuya J's model needs a phone GPU (or NPU) to answer quickly. Pick by what you're checking:

| Device | Model speed | Good for |
|---|---|---|
| Real Android phone | First word in about 2-3 s, full answer in 4-8 s (Snapdragon, GPU) | Everything, especially judging answers and languages |
| Qualcomm Device Cloud | Same as a real phone | Real-hardware testing without owning the phone |
| Android emulator | CPU only; answers often take over a minute and fall back to the record | Screens, layout, records-only answers |

### A real phone

1. Turn on *Developer options* (tap *Build number* seven times in *About phone*), then *USB debugging*.
2. Connect it by USB and accept the prompt on the phone. `adb devices` should list it.
3. `./gradlew :androidApp:installDebug`, open Kuya J, then Settings → *Download on Wi-Fi*.

The phone needs about 3.5 GB free for the model.

### Qualcomm Device Cloud

[Qualcomm Device Cloud](https://qdc.qualcomm.com) (QDC) lends you a real Snapdragon phone in a browser session and
exposes its adb over SSH.

1. Sign in, start an interactive session on a phone, and download the session's SSH key. Each session has its own
   key and device address.
2. Stop your local adb server, because the tunnel uses the same port: `adb kill-server`.
3. Open the tunnel QDC shows for the session, and leave it running:
   ```bash
   ssh -i <session-key>.pem -L 5037:<device-address>:5037 -N sshtunnel@ssh.qdc.qualcomm.com
   ```
4. In another terminal, `adb devices` now lists the cloud phone. Install as usual:
   `./gradlew :androidApp:installDebug`.
5. **Open Kuya J once before pushing any files**, so Android creates the app's folder with the right owner; a folder
   that adb creates first is unreadable to the app.
6. Download the model from Settings on the phone's Wi-Fi, or push it (see
   [testing a fine-tuned model](on-device-model.md#testing-a-fine-tuned-model)).

When the session ends, the key stops working; start a new session for a new one. Restart your local adb server
(`adb start-server`) to use local devices again.

### Android emulator

Use an arm64 system image on Apple silicon (x86_64 elsewhere). The emulator's network is mobile data by default and
the model only downloads on Wi-Fi, so connect it to its built-in Wi-Fi first:

```bash
adb shell cmd wifi connect-network AndroidWifi open
```

Then download the model from Settings. Expect slow AI answers; for screens and records-only answers it's fine.

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

## Measuring on a phone

Debug builds include `BenchReceiver` (`androidApp/src/debug/`), which runs questions through the app's real
answering path and logs timings. It isn't in release builds. Keep the app open while it runs.

**Single questions** (speed, and accuracy of one-turn answers):

```bash
adb push questions.txt /sdcard/Android/data/ph.bettergov.kuyaj/files/bench.txt   # one question per line
adb shell am broadcast -n ph.bettergov.kuyaj/.BenchReceiver
adb logcat -s KuyaJ.Bench KuyaJ.Llm       # one JSON line per answer; first-token time and tokens/s per generation
```

Each question runs as a new chat. `kuya-j-evals/evals/history_device.py` scores a logcat dump of the 40 history
questions and summarises first-word time and rate.

### Scoring conversations

`androidApp/src/test/resources/conversations.jsonl` holds scripted multi-turn conversations with pass/fail checks
per turn: follow-ups, topic switches, free chat, no-record honesty, refusals, language. One JSON object per line:

```json
{"id": "fu-passport", "tags": ["followup", "fees"], "turns": [
  {"user": "Magkano ang passport?", "all": ["950"]},
  {"user": "how about the requirements?", "source": "passport", "any": ["birth certificate", "psa", "id"]}]}
```

| Check | Passes when |
|---|---|
| `any` / `all` / `none` | The reply contains one of / all of / none of these (lowercase, accents and commas removed) |
| `kind` | The reply is one of these kinds (`MODEL`, `RECORD`, `SYSTEM`, `REFUSAL`) |
| `source` | One of the reply's records has this in its id |
| `no_digits` | The reply states no number (nothing invented where there's no record) |
| `chat` | A real conversational reply: not the fallback, not "no record", no money, no source line |

On a phone, with whatever model the app has loaded:

```bash
adb push androidApp/src/test/resources/conversations.jsonl /sdcard/Android/data/ph.bettergov.kuyaj/files/conversations.jsonl
adb shell am broadcast -n ph.bettergov.kuyaj/.BenchReceiver         # multi-turn mode when that file exists
adb shell cat /sdcard/Android/data/ph.bettergov.kuyaj/files/conversations.out.jsonl > out.jsonl
python3 ../kuya-j-evals/evals/conversations_device.py androidApp/src/test/resources/conversations.jsonl out.jsonl
```

Delete `conversations.jsonl` from the phone afterwards, or the next broadcast runs it again instead of `bench.txt`.

On a computer, `ConversationEval` runs the same set through the same `Orchestrator` against a model server. It is
skipped unless `KUYAJ_EVAL_URL` is set:

```bash
llama-server -m kuyaj-v3-Q4_K_M.gguf --port 8091 --jinja -c 32768 -np 4
KUYAJ_EVAL_URL=http://127.0.0.1:8091/v1 ./gradlew :androidApp:testDebugUnitTest --tests '*ConversationEval*'
cat androidApp/build/eval/conversations.txt
```

The phone is the reference: it runs the model file and runtime people actually get. A second set that fixes are
never tuned on lives in `kuya-j-evals/evals/conversations_holdout.jsonl`; run it at the end of a round of changes,
and before it for a baseline. Current scores are in [Where Kuya J stands](status.md).

## Adding or changing records

BetterGov.ph records come from the [records pipeline](records-pipeline.md): harvest, review, publish, then copy
`kuya-j-records/records/*.json` into `androidApp/src/main/assets/kb/` so the built-in set matches what phones sync.
For a hand-written record:

1. Add it to a file in `kuya-j-records/records/` following [the record format](records.md), publish, and copy the
   files into `androidApp/src/main/assets/kb/`.
2. Run the unit tests: `KbRecord` parsing fails on a missing field, and `KeyFactTest` checks the answer-card values.
3. If you add a category, give it a style in `Categories.kt` and, if it has one clear value, a rule in
   `AnswerFacts.kt`.
4. Check the home screen's `TOPICS` and `EXAMPLES` still ask answerable questions.

## Code style

- Shared code first: if it doesn't need Android, it goes in `shared/commonMain`.
- Keep the answering rules in code (`Orchestrator`, `Safety`), not in prompts. Prompts can be ignored; code can't.
- The system prompts must stay byte-identical to the ones the model was trained with
  (`kuya-j-evals/distill/restructure_v3.py`). Change both together, then retrain. `CHAT_SYSTEM_PROMPT` is the
  exception: free chat wasn't trained, so it can be edited freely.
- Change answering behaviour against the conversation set, on a phone, and say what the score did.
- Comments marked `ponytail:` name a deliberate shortcut and when to upgrade it.
