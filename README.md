# AI Pantry – Cook from Your Fridge 🥕📷

Demo Android (Droidcon) di una pipeline AI **completamente on-device**: fotografi frigo e dispensa, la computer vision riconosce gli ingredienti, un LLM locale (**Gemma 3 1B**) genera ricette che usano solo ciò che hai.

**L'inferenza è 100% on-device e l'app non ha nemmeno il permesso INTERNET.** Le foto non lasciano mai il dispositivo. Il modello arriva già col canale di installazione: Google Play (AI pack) o embeddato nell'APK per i tester.

## Flusso demo

1. **Scan** → fotografa il frigorifero (chip *Fridge*), poi "Scan another" per la dispensa (*Pantry*). In alternativa allo scatto puoi scegliere una foto dalla galleria.
2. Gli ingredienti rilevati (con quantità approssimative e confidenza) finiscono nell'inventario locale (Room).
3. **What can I cook?** → l'LLM genera 5 ricette ordinate per copertura degli ingredienti (quelle con più ingredienti mancanti vanno in fondo), con tempo, difficoltà, ingredienti usati/mancanti. Aprendo una ricetta, le istruzioni passo-passo e le varianti vengono generate on-demand.

## Architettura

```
CameraX / Photo picker → Image capture
        → Ingredient detection ── EfficientDet-Lite2 (COCO, object detection multi-oggetto)
        │                      └─ EfficientNet-Lite2 (ImageNet, classificazione soggetto)
        → Room Database
        → Recipe generation ──── Gemma 3 1B (LiteRT, GPU-first → CPU fallback)
        │                        stage 1: lista ricette · stage 2: istruzioni on-demand
        → Jetpack Compose UI

flavor play:  Google Play ──(AI pack ×3, fast-follow)──▶ ModelRepository ──assemble──▶ .task
flavor beta:  assets APK ──(chunk ×3 nell'APK)────────▶ ModelRepository ──assemble──▶ .task
```

Entrambi i detector sono bundlati nell'APK (~24 MB e ~23 MB) e girano **sempre**, senza dipendere dall'LLM. Il provisioning del modello LLM parte **automaticamente al primo avvio** — nessuna schermata di setup, nessun tap.

Ogni feature segue Clean Architecture + MVI (`data / domain / presentation`), DI con Koin:

- `capture/` — CameraX + photo picker, `MediaPipeIngredientDetector` (EfficientDet + EfficientNet)
- `inventory/` — Room, inventario ingredienti, banner di stato del modello in home
- `recipes/` — `LlmRecipeGenerator` (generazione a due stadi + retry), `RecipeJsonParser` tollerante
- `core/data/ai/` — `LlmCatalog`, `ModelSource` (AiPacks | BundledAssets), `ModelRepository` (provisioning automatico), `LlmEngineHolder` (cache motore + policy GPU/CPU)

## Il modello: Gemma 3 1B, due canali di consegna

**Gemma 3 1B int4** (~554 MB, text-only) per tutti i flavor — mai download HTTP in-app:

| Flavor | Canale | Note |
|--------|--------|------|
| `play` | **Play for On-device AI**: 3 AI pack `fast-follow` (~185 MB ciascuno), ricomposti al primo avvio, pack rimossi dopo l'assemblaggio | limite Play: 1.5 GB/pack, 4 GB totali |
| `beta` | **Embeddato nell'APK** in 3 chunk negli assets (AGP non impacchetta asset >2 GB), ricomposti al primo avvio | APK ~806 MB da mandare a mano ai tester; zero azioni richieste |

Perché 1B e non un modello più grande: Gemma 3n E2B (3.1 GB) era stato provato ma è troppo lento sui device reali (minuti per generare); l'1B q4 dà latenze accettabili mantenendo qualità sufficiente per le ricette. La detection **non** usa più l'LLM (nessuna vision on-device), quindi il modello text-only basta.

Al primo avvio l'app mostra "Preparing Gemma 3 1B…" per pochi secondi (copia/ricomposizione dei chunk) e poi è **pronta e offline per sempre**. Il modello va preparato una tantum da chi builda (i chunk sono gitignorati):

```bash
# Il .task richiede account HF con licenza Gemma accettata:
#   https://huggingface.co/litert-community/Gemma3-1B-IT  (file q4 ekv2048)
./scripts/prepare_model_packs.sh path/to/gemma3-1b-it-q4.task   # → AI pack (flavor play)
./scripts/prepare_beta_model.sh  path/to/gemma3-1b-it-q4.task   # → assets APK (flavor beta)
```

La ridistribuzione (Play o APK ai tester) è coperta dalla [Gemma Terms of Use](https://ai.google.dev/gemma/terms) con passthrough delle condizioni.

### Backend GPU/CPU e resilienza

Il motore LiteRT gira **GPU-first** (più veloce, scalda meno). Su alcuni driver la GPU degrada dopo uso prolungato ed emette token spazzatura: `LlmEngineHolder` lo rileva, ricrea un engine fresco (di nuovo GPU), e solo dopo 2 corruzioni nella stessa sessione ripiega su CPU — al riavvio si riparte da GPU (auto-guarigione). La generazione ricette ritenta fino a 3 volte in silenzio prima di mostrare un errore, perché un 1B ogni tanto produce JSON malformato. `RecipeJsonParser` è tollerante per costruzione (alias snake_case, `steps`/`variants` sia stringhe sia oggetti, scarto dei singoli elementi invalidi).

## Build & distribuzione

```bash
# Tester (APK da mandare a mano):
./gradlew assembleBetaDebug     # → app/build/outputs/apk/beta/debug/app-beta-debug.apk (~806MB)

# Play Store:
./gradlew bundlePlayRelease     # → AAB con AI pack

# Test locale della delivery Play senza store:
./gradlew bundlePlayDebug
bundletool build-apks --bundle=app/build/outputs/bundle/playDebug/app-play-debug.aab \
  --output=aipantry.apks --local-testing --overwrite
bundletool install-apks --apks=aipantry.apks
```

⚠️ Le build APK del flavor `play` (`assemblePlayDebug`, Run di Android Studio) **non contengono gli AI pack**: per sviluppare c'è il sideload `adb push gemma3-1b-it-q4.task /data/local/tmp/llm/` (ha priorità sul modello provisionato). Il flavor `beta` invece funziona anche da Android Studio.

Nota install: gli APK con modello embeddato (~800 MB) possono superare il timeout del verifier di sistema (`INSTALL_FAILED_VERIFICATION_FAILURE`). Su device di test: `adb shell settings put global package_verifier_user_consent -1`.

## Riconoscimento ingredienti

Due modelli MediaPipe bundlati nell'APK, entrambi con soglia 0.25:

- **EfficientDet-Lite2** (COCO): object detection multi-oggetto sulla scena intera — banana, apple, orange, broccoli, carrot, bottle, bowl…
- **EfficientNet-Lite2** (ImageNet): classificazione del soggetto dominante per le classi assenti da COCO (funghi, peperoni, limoni, zucchine…).

Il mapping label → ingrediente è in `IngredientLabels`. **Limite noto**: sono modelli generici, le classi cibo di COCO/ImageNet sono poche e non coprono pomodori, latticini, salumi, ecc. — un frigo pieno viene riconosciuto solo in parte. Per superarlo è in corso un **fine-tuning** su un dataset custom (vedi sotto).

### Fine-tuning del detector (in `scripts/`)

Pipeline per riaddestrare un object detector su classi alimentari, con MediaPipe Model Maker (Python 3.11):

- `build_dataset.py` — costruisce un dataset COCO (~4.150 immagini, 31 classi) da Open Images V7, scaricando direttamente da S3 (bypassa FiftyOne).
- `train_model.py` — fine-tuning MobileNetV2-SSD, 30 epoch, export `.tflite`.

Il `.tflite` risultante sostituisce `app/src/main/assets/models/detector.tflite`. Per aggiungere classi in futuro si riaddestra da capo sul dataset cumulativo (vecchie + nuove immagini annotate), non si continua il training esistente.

## Requisiti

JDK 17+, Android SDK 36, AGP 8.10+. `minSdk 31`, device con camera; per Gemma 1B: 4+ GB di RAM, GPU consigliata. Storage: ~1.4 GB per la build beta (APK + modello ricomposto), ~1 GB per quella Play (i pack vengono rimossi dopo l'assemblaggio).

## Stack

Kotlin 2.2 · Jetpack Compose (M3) · CameraX · MediaPipe Tasks (Vision + GenAI/LiteRT) · Play AI Delivery · Room · Koin · Navigation Compose · kotlinx-serialization
