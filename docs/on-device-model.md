# On-device model

Kuya J's AI runs entirely on the phone with [LiteRT-LM](https://github.com/google-ai-edge/LiteRT-LM), Google's
runtime for language models on mobile. Nothing is sent to a server. The code is in `androidApp/.../llm/`.

## Which model

| Build | File | Size | Runs on |
|---|---|---|---|
| Stock Gemma 4 E2B (current download) | `gemma-4-E2B-it.litertlm` | 2.6 GB | GPU, then CPU |
| Gemma 4 E2B for Tensor G5 / G6 | `gemma-4-E2B-it_Google_Tensor_G*.litertlm` | 3.2-3.5 GB | That chip's NPU |
| **Kuya J v3** (fine-tuned) | `kuyaj-v3.litertlm` | 3.0 GB | GPU, then CPU |

`ModelCatalog.forThisDevice()` picks the NPU build matching the phone's chip (`Build.SOC_MODEL`), else the generic
GPU/CPU build. The [v3 model](model-v3.md) is trained to search and answer in Philippine languages; it currently
loads as the debug-only custom model (below) until it's published for download.

Gemma 4 E2B has about 2 billion effective parameters, reads images, and handles a 4,096-token context here.

## Downloading the model

`ModelManager` handles it:

1. **Wi-Fi only.** The download goes through Android's `DownloadManager` with metered and roaming networks
   disallowed, so it never uses mobile data. It shows a system notification and survives the app closing.
2. **Checked before use.** When the download finishes, the file's size and SHA-256 must match the catalog, or the
   file is deleted. A file pushed by hand is checked the same way.
3. **Checked once.** Hashing 2.6 GB takes a while, so a verified file is remembered by its size and timestamp.

The states (`ModelState`): Missing → Downloading (progress) → Verifying → Ready, or Failed with a reason. The
settings sheet shows each one.

## Loading and backends

`LiteRtLmEngine.load()` tries the variant's backends in order, **NPU → GPU → CPU**, and keeps the first that works.
On an emulator it goes straight to CPU, because emulated "GPU" is far slower. The vision encoder runs on GPU,
or CPU when the model does.

## Generating

- `generate(system, user, image)` streams an answer; used for context mode and for reading photos.
- `generateWithSearch(system, user, search)` gives the model a `search_kb` tool (an OpenAPI function with one
  `query` string). LiteRT-LM runs the tool call automatically: the model writes the call, the app's `search`
  function returns the records, the model continues. Only models marked `toolSearch = true` get this; others
  return null and the app searches for them.

Sampling is near-deterministic (temperature 0.2, top-k 40, top-p 0.95, fixed seed), so the same question gets
the same wording in practice.

## The chat-template fix

Gemma 4's built-in chat template drops tool results the way LiteRT-LM sends them. LiteRT-LM passes a result as a
content part of type `tool_response`, but the template only joins parts of type `text`, so the model saw an empty
result (`response:unknown{value:<|"|><|"|>}`) and answered "no record".

`assets/gemma4_tool_template.jinja` is the model's own template with one added branch (marked *Kuya J patch*)
that passes `tool_response` parts through. Tool mode uses it via `ConversationConfig(chatTemplate = ...)`.

## Testing a fine-tuned model

Debug builds load a model pushed to `models/custom.litertlm` without a checksum, as a searching model:

```bash
# Start the app once first, so Android creates its folder with the right owner.
adb push kuyaj-v3.litertlm /sdcard/Android/data/ph.bettergov.kuyaj/files/models/custom.litertlm
adb shell am force-stop ph.bettergov.kuyaj   # reopen the app; Settings shows "Custom model (debug)"
```

If the folder was created by `adb` before the app ran, the app can't read it; delete the folder, open the app,
then push again.

## Performance

On a Qualcomm test phone with v3 on GPU: first word in 1.9-3.0 s, a full answer in 3.9-7.5 s. On an emulator's
CPU, answers can take over the 60-second deadline; Kuya J then shows the record as written.
