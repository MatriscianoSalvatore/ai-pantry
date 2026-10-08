# AI Pantry – Cook from Your Fridge 🥕📷

🇬🇧 English · 🇮🇹 [Italiano](README.it.md)

Android demo app from the talk *AI On-Device*: a **fully on-device** AI pipeline. Photograph your fridge and pantry, the app recognises the ingredients and generates recipes that use only what you have. Three AI engines cooperate, each on the job it was built for:

- **Gemini Nano** (AICore, via ML Kit GenAI) — ingredient detection on the devices that support it and, where it is Gemini Nano 4 (the production Gemma 4), the recipes too: zero MB in the APK
- **MobileCLIP-S2** (LiteRT) — zero-shot detection on *any* Android 12+ device: the everywhere-fallback and the default engine
- **Gemma 4 E2B** (LiteRT-LM) — recipe generation on every other phone: the only genuinely generative task

**Inference is 100% on-device: photos and prompts never leave the device.** The app's own code makes no network calls; the `INTERNET` permission you will find in the merged manifest is added by ML Kit's telemetry library (`com.google.android.datatransport`), which sends usage metrics, not photos or prompts (if you publish on Play, declare it in the Data safety form). Models arrive through the install channel: Google Play (AI packs) or embedded in the APK, in the beta debug build.

## The talk

The slides (in Italian, with speaker notes) are in this repo: [AI On-Device - ita.pptx](AI%20On-Device%20-%20ita.pptx). The talk follows the path that led to this architecture:

1. **Gemini Nano** was the obvious choice, but it runs only on recent flagships: a bonus, never the base.
2. **Gemma 4 E2B doing everything** (detection and recipes) worked, but a single scan took minutes on a Pixel 7: the multimodal prefill and the token-by-token decode cost too much on a phone.
3. **MobileCLIP-S2 zero-shot**: recognition does not need a model that writes, but one that compares. Same phone, same photo: from minutes to seconds.
4. **Gemma is back** for the only task that really needs an LLM: generating recipes, text only.

The lesson: before asking how to make a model faster, ask whether it is the right model.

## Demo flow

The whole flow, with the app in Italian, is recorded in [demo.mp4](demo.mp4).

1. **Scan** → photograph the fridge (*Fridge* chip), then "Scan another" for the pantry (*Pantry*). Instead of taking a picture you can pick a photo from the gallery. While MobileCLIP works, the photo shows the zones it is looking at as a grid, one pass after the other (coarse, then fine), with a count of the areas scanned, and each ingredient appears on the photo as soon as it is recognised. The results sheet shows which engine did the work; once dismissed, it reopens from the "Review N ingredients" pill. The scans add up: after the pantry the sheet lists the ingredients of both, and "Add to fridge and pantry" saves them all.
2. The detected ingredients land in the local inventory (Room). With MobileCLIP each one has a confidence and anything below 22% is discarded; Gemini Nano returns no confidence, so its results show a fixed default.
3. **What can I cook?** → Gemma writes 4 recipes with time, difficulty and used ingredients, each one on screen as soon as it is written; until the first is ready, a progress screen shows the model loading, then the writing. The first list for the current inventory is written ahead in the background while the app is open, so it is often ready, or nearly, when you ask for it. The missing ingredients are rechecked by the app against the inventory (the model alone is unreliable at this) and the recipes with more missing ingredients go last. Opening a recipe generates on demand its quantities, the step-by-step instructions and the variations, again shown part by part as they are written.
4. **More recipes**, at the end of the list, adds 4 new ones below; **Regenerate** (top right) replaces the list. Every new round uses a different two thirds of the inventory, and the dishes the list already has are dropped, even under another name. While the app runs, lists are kept per ingredient set: change the inventory and you get a new list, put it back and the old one returns.

A hidden diagnostics page (tap the logo on the home screen 6 times) shows the thermal status and headroom, CPU frequencies and throttling, battery, memory, the model state and the recipe lists kept in memory, which it can clear.

The app follows the device language: **Italian if the phone is set to Italian, English otherwise**. That covers the UI (`res/values-it`), the detection and recipe prompts, and the names of the ingredients recognised by MobileCLIP, so the recipes come out in the same language. The JSON keys in the prompts stay in English: they are the parser's contract.

## Architecture

```
CameraX / Photo picker → Image capture
        → Ingredient detection ── AdaptiveIngredientDetector
        │       ├─ Gemini Nano (AICore · ML Kit GenAI) if checkStatus() == AVAILABLE
        │       └─ MobileCLIP-S2 zero-shot (LiteRT) everywhere — and safety net if Nano fails
        → Room Database
        → Recipe generation ──── LlmRecipeGenerator
        │       ├─ Gemini Nano 4 (AICore · ML Kit GenAI) if the base model is nano-v4 or later
        │       └─ Gemma 4 E2B text-only (LiteRT-LM) everywhere else — and safety net if Nano fails
        │       stage 1: recipe list · stage 2: on-demand instructions
        → Jetpack Compose UI

play flavor:  Google Play ──(AI pack ×3, fast-follow)──▶ ModelRepository ──assemble──▶ .litertlm
beta flavor:  APK assets ──(chunk ×3 inside the APK)───▶ ModelRepository ──assemble──▶ .litertlm
MobileCLIP:   APK assets (~140 MB, gitignored — fetched by scripts/prepare_clip_assets.py)
EmbeddingGemma 2: copied in with adb, for the hidden page only (388 MB)
```

Every feature follows Clean Architecture + MVI (`data / domain / presentation`), DI with Koin:

- `capture/` — CameraX + photo picker; `AdaptiveIngredientDetector` (runtime selection), `NanoIngredientDetector` (ML Kit GenAI Prompt API), `ClipZeroShotIngredientDetector` (LiteRT) and `EmbeddingGemmaIngredientDetector` (LiteRT-LM, picked on the hidden page only) on the shared `ZeroShotScan` (crops, scores, results), `LlmVisionIngredientDetector` (detection via Gemma vision: present in the code as a third route, off the default path — minutes per scan when it ends up on CPU)
- `inventory/` — Room, ingredient inventory, model status banner on home, keyword emoji resolver
- `recipes/` — `RecipeRepositoryImpl` (lists per ingredient set, one generation at a time with what is on screen first, work ahead in the background, resume after an interruption), `LlmRecipeGenerator` (two-stage streaming generation + retries, on Gemini Nano 4 or Gemma), `RecipeTitleRules` (Italian titles tidied, odd combinations dropped), `SameDish` (repeated dishes), tolerant `RecipeJsonParser`
- `diagnostics/` — the hidden page: heat and throttling, battery, memory, model, recipe cache
- `core/data/ai/` — `LlmCatalog`, `ModelSource` (AiPacks | BundledAssets), `ModelRepository` (automatic provisioning), `LlmEngineHolder` (engine cache + GPU/CPU policy), `GeminiNanoWriter` (Gemini Nano 4 for the recipes, where the phone has it)
- `core/domain/` — `AppLanguage` (Italian or English, from the device locale), read by prompts and detectors

## Ingredient recognition

`AdaptiveIngredientDetector` picks the engine **on every scan**:

**Gemini Nano** (devices supported by the ML Kit GenAI Prompt API: Pixel 9/10/11 series except the "a" models, Galaxy S26 and a few dozen other recent flagships; the Galaxy S25 has Nano only for the ready-made GenAI APIs, not for the Prompt API) — multimodal photo+text prompt via the ML Kit GenAI Prompt API (`com.google.mlkit:genai-prompt`, still in beta). Availability is a runtime question: `checkStatus()` on every use; if the model is downloadable the download starts in the background and the fallback is used meanwhile. Even when the status is `AVAILABLE`, AICore can refuse a request (per-app quota, inference allowed only for the app in the foreground), and Nano is not supported on devices with an unlocked bootloader nor on the emulator: any Nano exception falls back to CLIP silently.

**MobileCLIP-S2 zero-shot** (everywhere, Android 12+, the default included) — the image encoder (~140 MB, LiteRT) embeds multi-scale crops of the photo (a grid at 50% and 33% of the short side, pool of interpreters running in parallel) and compares them by cosine similarity against the text embeddings of **863 ingredients + 12 "distractor" labels** precomputed offline. The "classifier" is a text file:

- `scripts/ingredient_labels.txt` — one label per line; `label|display` syntax for aliases ("lactose-free milk|milk"), a `~` prefix for distractors (they absorb the food-free crops, and foods that aren't recipe ingredients like snacks, and are never reported)
- `scripts/ingredient_names_it.txt` — the Italian name of every display name (`english|italiano`), shown on devices set to Italian. Matching stays on the English labels, since the text encoder was trained on English captions; the script stops if a translation is missing
- `scripts/prepare_clip_assets.py` — downloads the encoder from Hugging Face if missing (it is gitignored), computes the embeddings with open_clip (4 averaged templates) and writes `assets/clip/label_embeddings.json` (~4.5 MB); with `--verify-image` it checks open_clip ↔ TFLite parity (≥0.99 expected)

Adding an ingredient = adding a line (and its Italian name) and re-running the script. Confidence threshold 22%, max 15 results per scan. CLIP classifies but does not count, so no quantity is shown in the app: the field survives in the data model (Nano does fill it in) but a placeholder count is worse than none. The vocabulary is closed: CLIP only recognises what is listed in the file.

**EmbeddingGemma 2 zero-shot** (only when picked on the hidden page, to try it out) — the same scan as MobileCLIP (`ZeroShotScan`: same crops, labels, templates and 22% threshold), with crops and labels embedded by EmbeddingGemma 2 (text + vision, 440M, Apache 2.0) on LiteRT-LM, Gemma's runtime. The app never picks it by itself and doesn't deliver the model: copy `embeddinggemma-2-text-vision-440m.litertlm` (388 MB, from [litert-community](https://huggingface.co/litert-community/embeddinggemma-2-text-vision-440m-litert-lm)) to the path the hidden page shows, `adb push embeddinggemma-2-text-vision-440m.litertlm /sdcard/Android/data/com.smatrisciano.aipantry/files/embedding/`. The label embeddings are in the repo, written by `scripts/prepare_embeddinggemma_assets.py` (it downloads the model into `build/embeddinggemma/` if missing; `--check-image fridge.png` prints what the app's scan finds). On a Pixel 7 a photo takes ~65–75 s on the GPU (43 crops at 70 vision tokens), against ~21 s for MobileCLIP, with ~1.4 GB of memory while it scans, given back when the camera closes.

## The LLM: Gemma 4 E2B on LiteRT-LM, two delivery channels

On phones with Gemini Nano 4, the production version of Gemma 4 in AICore, the recipes are written by Nano 4 instead, with the same prompts, on the phone's AI accelerator: Gemma isn't loaded at startup there, and takes over if Nano fails (a quota, an AICore error). An earlier Nano (nano-v3, of the Gemma 3n generation) stays with the ingredients.

**Gemma 4 E2B, multimodal** (`.litertlm` format, ~2.6 GB — mixed 2/4/8-bit quantisation) for every flavor — never an in-app HTTP download:

| Flavor | Channel | Notes |
|--------|---------|-------|
| `play` | **Play for On-device AI**: 3 `fast-follow` AI packs (~0.85 GB each), reassembled on first launch, packs removed after assembly | Play limits: 1.5 GB/pack, 4 GB total |
| `beta` | **Embedded in the APK** as 3 chunks in the assets (AGP does not package assets >2 GB), reassembled on first launch | ~2.9 GB APK to hand out to testers; zero action required |

On first launch the app shows "Preparing Gemma 4 E2B…" for a few seconds/minutes (copying/reassembling the chunks) and then it is **ready and offline forever**. The model has to be prepared once by whoever builds (the chunks are gitignored):

```bash
# The .litertlm (gemma-4-E2B-it.litertlm, Apache 2.0, no HF login needed):
#   https://huggingface.co/litert-community/gemma-4-E2B-it-litert-lm
./scripts/prepare_model_packs.sh path/to/gemma-4-E2B-it.litertlm   # → AI packs (play flavor)
./scripts/prepare_beta_model.sh  path/to/gemma-4-E2B-it.litertlm   # → APK assets (beta flavor)

# MobileCLIP encoder + label embeddings (one-off / whenever the vocabulary changes):
python3 scripts/prepare_clip_assets.py
```

Licenses: Gemma 4 is released under **Apache 2.0**, so redistributing it (via Play or APKs to testers) only requires shipping the license and attribution. MobileCLIP is different: the code is MIT, but the **weights are under the Apple Machine Learning Research Model License (research purposes only, no commercial use)**, and this also applies to the community TFLite export that `prepare_clip_assets.py` downloads. Fine for this demo; for a commercial product swap in a CLIP-style encoder with a permissive license (e.g. SigLIP 2, Apache 2.0): the pipeline stays the same, only the `.tflite` and the label embeddings change.

### GPU/CPU backend and resilience

The LiteRT-LM runtime is **GPU-first**. A broken GPU shows up in two ways: a **hang** (a native call that cannot be interrupted) or an **immediate exception** on the first inference (e.g. no OpenCL, where engine init succeeds anyway). `LlmEngineHolder` catches both — a watchdog with a timeout on a detached job for the hangs, an exception catch for the rest — and marks the GPU broken **persistently** for that model: subsequent launches go straight to CPU (up to 4 threads, to limit heat and throttling) without paying for the attempt again. A micro-inference probe at warm-up triggers the fallback at startup already, so the user's first generation does not pay the wait. The XNNPack caches LiteRT-LM writes next to the model (the weights already laid out for the CPU) are kept across launches: on a Pixel 7 the engine is ready in under 2 seconds instead of ~13.

Recipe generation retries automatically (up to 4 attempts in total) before surfacing an error: small models occasionally emit malformed JSON, and a list left short after dropping odd or repeated dishes gets another attempt that adds to it (meanwhile the UI shows "Output not parseable, retrying…" or "Looking for a few more ideas…"); `RecipeJsonParser` and `DetectionJsonParser` are tolerant by construction.

## Getting started

> ⚠️ **The models are not in the repo** (too big for git): steps 2 and 3 are required. If you skip them the build still succeeds — quickly, with a ~100 MB APK instead of ~2.9 GB — but the app shows "AI model unavailable" and, on phones without Gemini Nano (e.g. a Pixel 7), it cannot even scan the ingredients.

1. **Tools**: JDK 17+, Android SDK 36 (Python 3 only if you use the CLIP preparation script).
2. **MobileCLIP encoder** (gitignored, ~140 MB). The label embeddings are already in the repo, so downloading the encoder is enough:
   ```bash
   curl -L -o app/src/main/assets/clip/mobileclip_s2_image.tflite https://huggingface.co/plainhub/mobileclip-s2-tflite/resolve/main/mobileclip_s2_image.tflite
   ```
   Alternatively, the script downloads the encoder and also regenerates the embeddings (needed only when you change the vocabulary):
   ```bash
   pip install torch open_clip_torch ai-edge-litert pillow numpy
   python3 scripts/prepare_clip_assets.py
   ```
3. **Gemma 4 E2B** (~2.6 GB, Apache 2.0, no HF login needed):
   ```bash
   curl -L -o ~/Downloads/gemma-4-E2B-it.litertlm https://huggingface.co/litert-community/gemma-4-E2B-it-litert-lm/resolve/main/gemma-4-E2B-it.litertlm
   ```
   then pick a channel:
   - `beta` flavor (everything inside the APK, simplest for a device). The APK is ~2.9 GB: if the install fails with `INSTALL_FAILED_VERIFICATION_FAILURE`, see the install note below.
     ```bash
     ./scripts/prepare_beta_model.sh ~/Downloads/gemma-4-E2B-it.litertlm
     ./gradlew assembleBetaDebug
     ```
   - `play` flavor during development: build and run from Android Studio, then sideload the model with the name the app expects:
     ```bash
     adb push ~/Downloads/gemma-4-E2B-it.litertlm /data/local/tmp/llm/gemma4-e2b-it.litertlm
     ```
4. Without Gemma the app still scans and builds the inventory (MobileCLIP, or Nano where available); only the recipes need the LLM, unless the phone has Gemini Nano 4. Without the MobileCLIP encoder, scanning works only on devices with Gemini Nano.

## Build & distribution

```bash
# Testers (APK handed out manually):
./gradlew assembleBetaDebug     # → app/build/outputs/apk/beta/debug/app-beta-debug.apk (~2.9GB)

# Play Store:
./gradlew bundlePlayRelease     # → AAB with AI packs

# Local test of the Play delivery without the store:
./gradlew bundlePlayDebug
bundletool build-apks --bundle=app/build/outputs/bundle/playDebug/app-play-debug.aab \
  --output=aipantry.apks --local-testing --overwrite
bundletool install-apks --apks=aipantry.apks
```

⚠️ APK builds of the `play` flavor (`assemblePlayDebug`, Android Studio Run) **do not contain the LLM AI packs**: for development there is the sideload `adb push gemma-4-E2B-it.litertlm /data/local/tmp/llm/gemma4-e2b-it.litertlm` (the file name must be `gemma4-e2b-it.litertlm`; it takes priority over the provisioned model). CLIP detection, on the other hand, works in every build (the encoder lives in the shared assets). The `beta` flavor works in full from Android Studio too.

Install note: APKs with the embedded model (~2.9 GB) can exceed the system verifier timeout (`INSTALL_FAILED_VERIFICATION_FAILURE`). On test devices: `adb shell settings put global package_verifier_user_consent -1`.

## Requirements

JDK 17+, Android SDK 36, AGP 8.10+. `minSdk 31`, a device with a camera. CLIP detection: any device (Android 12+). Gemini Nano detection: devices supported by the ML Kit GenAI Prompt API (Pixel 9/10/11 except the "a" models, Galaxy S26, other recent flagships). Recipes with Gemma 4 E2B: 6+ GB of RAM, GPU recommended. Storage: ~5.5 GB for the beta build (the ~2.9 GB APK keeps its chunks, plus the ~2.6 GB reassembled model), ~2.6 GB for the Play one (the packs are removed after assembly).

## Stack

Kotlin 2.4 · Jetpack Compose (M3) · CameraX · ML Kit GenAI Prompt API (`com.google.mlkit:genai-prompt`, Gemini Nano/AICore) · LiteRT (`com.google.ai.edge.litert`, MobileCLIP-S2) · LiteRT-LM (`com.google.ai.edge.litertlm`, Engine/Conversation) · Play AI Delivery · Room · Koin · Navigation Compose · kotlinx-serialization
