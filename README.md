# AI Pantry – Cook from Your Fridge 🥕📷

Android demo (Droidcon) of a **fully on-device** AI pipeline: photograph your fridge and pantry, the app recognises the ingredients and generates recipes that use only what you have. Three AI engines cooperate, each on the job it was built for:

- **Gemini Nano** (AICore, via ML Kit GenAI) — ingredient detection on the devices that support it: zero MB in the APK
- **MobileCLIP-S2** (LiteRT) — zero-shot detection on *any* device: the everywhere-fallback and the default engine
- **Gemma 4 E2B** (LiteRT-LM) — recipe generation: the only genuinely generative task

**Inference is 100% on-device and the app does not even hold the INTERNET permission.** Photos never leave the device. Models arrive through the install channel: Google Play (AI packs) or embedded in the APK, in the beta debug build.

## Demo flow

1. **Scan** → photograph the fridge (*Fridge* chip), then "Scan another" for the pantry (*Pantry*). Instead of taking a picture you can pick a photo from the gallery. The results sheet shows which engine did the work; once dismissed, it reopens from the "N ingredients in this scan session" pill.
2. The detected ingredients (with confidence; anything below 22% is discarded) land in the local inventory (Room).
3. **What can I cook?** → the LLM generates 5 recipes ranked by ingredient coverage (the ones with more missing ingredients go last), with time, difficulty, used/missing ingredients. Opening a recipe generates its step-by-step instructions and variations on demand.

## Architecture

```
CameraX / Photo picker → Image capture
        → Ingredient detection ── AdaptiveIngredientDetector
        │       ├─ Gemini Nano (AICore · ML Kit GenAI) if checkStatus() == AVAILABLE
        │       └─ MobileCLIP-S2 zero-shot (LiteRT) everywhere — and safety net if Nano fails
        → Room Database
        → Recipe generation ──── Gemma 4 E2B text-only (LiteRT-LM)
        │                        stage 1: recipe list · stage 2: on-demand instructions
        → Jetpack Compose UI

play flavor:  Google Play ──(AI pack ×3, fast-follow)──▶ ModelRepository ──assemble──▶ .litertlm
beta flavor:  APK assets ──(chunk ×3 inside the APK)───▶ ModelRepository ──assemble──▶ .litertlm
MobileCLIP:   APK assets (~140 MB, gitignored — fetched by scripts/prepare_clip_assets.py)
```

Every feature follows Clean Architecture + MVI (`data / domain / presentation`), DI with Koin:

- `capture/` — CameraX + photo picker; `AdaptiveIngredientDetector` (runtime selection), `NanoIngredientDetector` (ML Kit GenAI Prompt API), `ClipZeroShotIngredientDetector` (LiteRT), `LlmVisionIngredientDetector` (detection via Gemma vision: present in the code as a third route, off the default path — minutes per scan when it ends up on CPU)
- `inventory/` — Room, ingredient inventory, model status banner on home, keyword emoji resolver
- `recipes/` — `LlmRecipeGenerator` (two-stage generation + retries), tolerant `RecipeJsonParser`
- `core/data/ai/` — `LlmCatalog`, `ModelSource` (AiPacks | BundledAssets), `ModelRepository` (automatic provisioning), `LlmEngineHolder` (engine cache + GPU/CPU policy)

## Ingredient recognition

`AdaptiveIngredientDetector` picks the engine **on every scan**:

**Gemini Nano** (where AICore exists: Pixel 9/10, Galaxy S25/S26, …) — multimodal photo+text prompt via the ML Kit GenAI Prompt API (`com.google.mlkit:genai-prompt`). Availability is a runtime question: `checkStatus()` on every use; if the model is downloadable the download starts in the background and the fallback is used meanwhile. If Nano fails at runtime, it falls back to CLIP silently.

**MobileCLIP-S2 zero-shot** (everywhere, the default included) — the image encoder (~140 MB, LiteRT) embeds multi-scale crops of the photo (a grid at 50% and 33% of the short side, pool of interpreters running in parallel) and compares them by cosine similarity against the text embeddings of **864 ingredients + 6 "distractor" labels** precomputed offline. The "classifier" is a text file:

- `scripts/ingredient_labels.txt` — one label per line; `label|display` syntax for aliases ("lactose-free milk|milk"), a `~` prefix for distractors (they absorb the food-free crops and are never reported)
- `scripts/prepare_clip_assets.py` — downloads the encoder from Hugging Face if missing (it is gitignored), computes the embeddings with open_clip (4 averaged templates) and writes `assets/clip/label_embeddings.json` (~4.4 MB); with `--verify-image` it checks open_clip ↔ TFLite parity (≥0.99 expected)

Adding an ingredient = adding a line and re-running the script. Confidence threshold 22%, max 15 results per scan. CLIP classifies but does not count, so no quantity is shown in the app: the field survives in the data model (Nano does fill it in) but a placeholder count is worse than none.

## The LLM: Gemma 4 E2B on LiteRT-LM, two delivery channels

**Gemma 4 E2B, multimodal** (`.litertlm` format, ~2.5 GB — mixed 2/4/8-bit quantisation) for every flavor — never an in-app HTTP download:

| Flavor | Channel | Notes |
|--------|---------|-------|
| `play` | **Play for On-device AI**: 3 `fast-follow` AI packs (~0.85 GB each), reassembled on first launch, packs removed after assembly | Play limits: 1.5 GB/pack, 4 GB total |
| `beta` | **Embedded in the APK** as 3 chunks in the assets (AGP does not package assets >2 GB), reassembled on first launch | ~2.9 GB APK to hand out to testers; zero action required |

On first launch the app shows "Preparing Gemma 4 E2B…" for a few seconds/minutes (copying/reassembling the chunks) and then it is **ready and offline forever**. The model has to be prepared once by whoever builds (the chunks are gitignored):

```bash
# The .litertlm requires an HF account with the Gemma license accepted:
#   https://huggingface.co/litert-community/gemma-4-E2B-it-litert-lm
./scripts/prepare_model_packs.sh path/to/gemma4-e2b-it.litertlm   # → AI packs (play flavor)
./scripts/prepare_beta_model.sh  path/to/gemma4-e2b-it.litertlm   # → APK assets (beta flavor)

# MobileCLIP encoder + label embeddings (one-off / whenever the vocabulary changes):
python3 scripts/prepare_clip_assets.py
```

Redistribution (via Play or APKs to testers) is covered by the [Gemma Terms of Use](https://ai.google.dev/gemma/terms) with passthrough of the conditions; the MobileCLIP weights are Apple's, under a permissive license.

### GPU/CPU backend and resilience

The LiteRT-LM runtime is **GPU-first**. A broken GPU shows up in two ways: a **hang** (a native call that cannot be interrupted) or an **immediate exception** on the first inference (e.g. no OpenCL, where engine init succeeds anyway). `LlmEngineHolder` catches both — a watchdog with a timeout on a detached job for the hangs, an exception catch for the rest — and marks the GPU broken **persistently** for that model: subsequent launches go straight to CPU (multi-threaded) without paying for the attempt again. A micro-inference probe at warm-up triggers the fallback at startup already, so the user's first generation does not pay the wait.

Recipe generation retries silently (up to 4 times) before surfacing an error, because small models occasionally emit malformed JSON; `RecipeJsonParser` and `DetectionJsonParser` are tolerant by construction.

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

⚠️ APK builds of the `play` flavor (`assemblePlayDebug`, Android Studio Run) **do not contain the LLM AI packs**: for development there is the sideload `adb push gemma4-e2b-it.litertlm /data/local/tmp/llm/` (it takes priority over the provisioned model). CLIP detection, on the other hand, works in every build (the encoder lives in the shared assets). The `beta` flavor works in full from Android Studio too.

Install note: APKs with the embedded model (~2.9 GB) can exceed the system verifier timeout (`INSTALL_FAILED_VERIFICATION_FAILURE`). On test devices: `adb shell settings put global package_verifier_user_consent -1`.

## Requirements

JDK 17+, Android SDK 36, AGP 8.10+. `minSdk 31`, a device with a camera. CLIP detection: any device. Gemini Nano detection: devices with AICore (Pixel 9/10, Galaxy S25/S26, …). Recipes with Gemma 4 E2B: 6+ GB of RAM, GPU recommended. Storage: ~2.9 GB for the beta build (APK + reassembled model), ~2.5 GB for the Play one (the packs are removed after assembly).

## Stack

Kotlin 2.2 · Jetpack Compose (M3) · CameraX · ML Kit GenAI Prompt API (`com.google.mlkit:genai-prompt`, Gemini Nano/AICore) · LiteRT (`com.google.ai.edge.litert`, MobileCLIP-S2) · LiteRT-LM (`com.google.ai.edge.litertlm`, Engine/Conversation) · Play AI Delivery · Room · Koin · Navigation Compose · kotlinx-serialization
