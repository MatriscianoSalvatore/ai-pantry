# AI Pantry – Cook from Your Fridge 🥕📷

Demo Android (Droidcon) di una pipeline AI **completamente on-device**: fotografi frigo e dispensa, l'app riconosce gli ingredienti e genera ricette che usano solo ciò che hai. Tre motori AI cooperano, ognuno sul compito per cui è fatto:

- **Gemini Nano** (AICore, via ML Kit GenAI) — detection ingredienti sui device che lo supportano: zero MB nell'APK
- **MobileCLIP-S2** (LiteRT) — detection zero-shot su *qualsiasi* device: è il fallback-ovunque e il motore di default
- **Gemma 4 E2B** (LiteRT-LM) — generazione ricette: l'unico compito davvero generativo

**L'inferenza è 100% on-device e l'app non ha nemmeno il permesso INTERNET.** Le foto non lasciano mai il dispositivo. I modelli arrivano col canale di installazione: Google Play (AI pack) o embeddati nell'APK.

## Flusso demo

1. **Scan** → fotografa il frigorifero (chip *Fridge*), poi "Scan another" per la dispensa (*Pantry*). In alternativa allo scatto puoi scegliere una foto dalla galleria. La sheet dei risultati mostra quale engine ha lavorato; chiusa, si riapre dal pill "N ingredients in this scan session".
2. Gli ingredienti rilevati (con quantità e confidenza; sotto il 22% vengono scartati) finiscono nell'inventario locale (Room).
3. **What can I cook?** → l'LLM genera 5 ricette ordinate per copertura degli ingredienti (quelle con più ingredienti mancanti vanno in fondo), con tempo, difficoltà, ingredienti usati/mancanti. Aprendo una ricetta, le istruzioni passo-passo e le varianti vengono generate on-demand.

## Architettura

```
CameraX / Photo picker → Image capture
        → Ingredient detection ── AdaptiveIngredientDetector
        │       ├─ Gemini Nano (AICore · ML Kit GenAI) se checkStatus() == AVAILABLE
        │       └─ MobileCLIP-S2 zero-shot (LiteRT) ovunque — e safety net se Nano fallisce
        → Room Database
        → Recipe generation ──── Gemma 4 E2B text-only (LiteRT-LM)
        │                        stage 1: lista ricette · stage 2: istruzioni on-demand
        → Jetpack Compose UI

flavor play:  Google Play ──(AI pack ×3, fast-follow)──▶ ModelRepository ──assemble──▶ .litertlm
flavor beta:  assets APK ──(chunk ×3 nell'APK)────────▶ ModelRepository ──assemble──▶ .litertlm
MobileCLIP:   assets APK (~140 MB, gitignorato — lo scarica scripts/prepare_clip_assets.py)
```

Ogni feature segue Clean Architecture + MVI (`data / domain / presentation`), DI con Koin:

- `capture/` — CameraX + photo picker; `AdaptiveIngredientDetector` (selezione runtime), `NanoIngredientDetector` (ML Kit GenAI Prompt API), `ClipZeroShotIngredientDetector` (LiteRT), `LlmVisionIngredientDetector` (detection via Gemma vision: nel codice come terza via, fuori dal percorso di default — minuti per scan quando finisce su CPU)
- `inventory/` — Room, inventario ingredienti, banner di stato del modello in home, resolver emoji a keyword
- `recipes/` — `LlmRecipeGenerator` (generazione a due stadi + retry), `RecipeJsonParser` tollerante
- `core/data/ai/` — `LlmCatalog`, `ModelSource` (AiPacks | BundledAssets), `ModelRepository` (provisioning automatico), `LlmEngineHolder` (cache motore + policy GPU/CPU)

## Riconoscimento ingredienti

`AdaptiveIngredientDetector` sceglie il motore **a ogni scan**:

**Gemini Nano** (dove c'è AICore: Pixel 9/10, Galaxy S25/S26, …) — prompt multimodale foto+testo via ML Kit GenAI Prompt API (`com.google.mlkit:genai-prompt`). La disponibilità è una domanda runtime: `checkStatus()` a ogni uso; se il modello è scaricabile il download parte in background e intanto si usa il fallback. Se Nano fallisce a runtime, si ripiega su CLIP in silenzio.

**MobileCLIP-S2 zero-shot** (ovunque, incluso il default) — l'image encoder (~140 MB, LiteRT) embedda crop multi-scala della foto (griglia al 50% e 33% del lato corto, pool di interpreter in parallelo) e li confronta via cosine similarity con gli embedding testuali di **864 ingredienti + 6 label "distrattore"** precomputati offline. Il "classificatore" è un file di testo:

- `scripts/ingredient_labels.txt` — una label per riga; sintassi `label|display` per gli alias ("lactose-free milk|milk"), prefisso `~` per i distrattori (assorbono i crop senza cibo, mai riportati)
- `scripts/prepare_clip_assets.py` — scarica l'encoder da Hugging Face se manca (è gitignorato), calcola gli embedding con open_clip (4 template mediati) e scrive `assets/clip/label_embeddings.json` (~4.4 MB); con `--verify-image` controlla la parità open_clip ↔ TFLite (atteso ≥0.99)

Aggiungere un ingrediente = aggiungere una riga e rilanciare lo script. Soglia di confidenza 22%, max 15 risultati per scan; CLIP classifica ma non conta, quindi la quantità è un "1 pc" di cortesia.

## Il modello LLM: Gemma 4 E2B su LiteRT-LM, due canali di consegna

**Gemma 4 E2B, multimodale** (formato `.litertlm`, ~2.5 GB — quantizzazione mista 2/4/8-bit) per tutti i flavor — mai download HTTP in-app:

| Flavor | Canale | Note |
|--------|--------|------|
| `play` | **Play for On-device AI**: 3 AI pack `fast-follow` (~0.85 GB ciascuno), ricomposti al primo avvio, pack rimossi dopo l'assemblaggio | limite Play: 1.5 GB/pack, 4 GB totali |
| `beta` | **Embeddato nell'APK** in 3 chunk negli assets (AGP non impacchetta asset >2 GB), ricomposti al primo avvio | APK ~2.9 GB da mandare a mano ai tester; zero azioni richieste |

Al primo avvio l'app mostra "Preparing Gemma 4 E2B…" per qualche secondo/minuto (copia/ricomposizione dei chunk) e poi è **pronta e offline per sempre**. Il modello va preparato una tantum da chi builda (i chunk sono gitignorati):

```bash
# Il .litertlm richiede account HF con licenza Gemma accettata:
#   https://huggingface.co/litert-community/gemma-4-E2B-it-litert-lm
./scripts/prepare_model_packs.sh path/to/gemma4-e2b-it.litertlm   # → AI pack (flavor play)
./scripts/prepare_beta_model.sh  path/to/gemma4-e2b-it.litertlm   # → assets APK (flavor beta)

# Encoder MobileCLIP + embedding delle label (una tantum / quando cambia il vocabolario):
python3 scripts/prepare_clip_assets.py
```

La ridistribuzione (Play o APK ai tester) è coperta dalla [Gemma Terms of Use](https://ai.google.dev/gemma/terms) con passthrough delle condizioni; i pesi MobileCLIP sono di Apple con licenza permissiva.

### Backend GPU/CPU e resilienza

Il runtime LiteRT-LM gira **GPU-first**. Una GPU rotta si manifesta in due modi: **hang** (chiamata nativa non interrompibile) oppure **eccezione immediata** alla prima inferenza (es. OpenCL assente, dove l'init dell'engine riesce comunque). `LlmEngineHolder` li intercetta entrambi — watchdog con timeout su job scollegato per gli hang, catch dell'eccezione per il resto — e marca la GPU rotta **in modo persistente** per quel modello: dai lanci successivi si va dritti su CPU (multi-thread) senza ripagare il tentativo. Una micro-inferenza di probe al warm-up fa scattare il fallback già all'avvio, così la prima generazione dell'utente non paga l'attesa.

La generazione ricette ritenta in silenzio (fino a 4 volte) prima di mostrare un errore, perché i modelli piccoli ogni tanto producono JSON malformato; `RecipeJsonParser` e `DetectionJsonParser` sono tolleranti per costruzione.

## Build & distribuzione

```bash
# Tester (APK da mandare a mano):
./gradlew assembleBetaDebug     # → app/build/outputs/apk/beta/debug/app-beta-debug.apk (~2.9GB)

# Play Store:
./gradlew bundlePlayRelease     # → AAB con AI pack

# Test locale della delivery Play senza store:
./gradlew bundlePlayDebug
bundletool build-apks --bundle=app/build/outputs/bundle/playDebug/app-play-debug.aab \
  --output=aipantry.apks --local-testing --overwrite
bundletool install-apks --apks=aipantry.apks
```

⚠️ Le build APK del flavor `play` (`assemblePlayDebug`, Run di Android Studio) **non contengono gli AI pack** del modello LLM: per sviluppare c'è il sideload `adb push gemma4-e2b-it.litertlm /data/local/tmp/llm/` (ha priorità sul modello provisionato). La detection CLIP invece funziona in ogni build (l'encoder sta negli assets comuni). Il flavor `beta` funziona completo anche da Android Studio.

Nota install: gli APK con modello embeddato (~2.9 GB) possono superare il timeout del verifier di sistema (`INSTALL_FAILED_VERIFICATION_FAILURE`). Su device di test: `adb shell settings put global package_verifier_user_consent -1`.

## Requisiti

JDK 17+, Android SDK 36, AGP 8.10+. `minSdk 31`, device con camera. Detection CLIP: qualsiasi device. Detection Gemini Nano: device con AICore (Pixel 9/10, Galaxy S25/S26, …). Ricette con Gemma 4 E2B: 6+ GB di RAM, GPU consigliata. Storage: ~2.9 GB per la build beta (APK + modello ricomposto), ~2.5 GB per quella Play (i pack vengono rimossi dopo l'assemblaggio).

## Stack

Kotlin 2.2 · Jetpack Compose (M3) · CameraX · ML Kit GenAI Prompt API (`com.google.mlkit:genai-prompt`, Gemini Nano/AICore) · LiteRT (`com.google.ai.edge.litert`, MobileCLIP-S2) · LiteRT-LM (`com.google.ai.edge.litertlm`, Engine/Conversation) · Play AI Delivery · Room · Koin · Navigation Compose · kotlinx-serialization
