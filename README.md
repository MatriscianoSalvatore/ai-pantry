# AI Pantry – Cook from Your Fridge 🥕📷

Demo Android (Droidcon) di una pipeline AI **completamente on-device**: fotografi frigo e dispensa, la computer vision riconosce gli ingredienti, un LLM locale (**Gemma 3n E2B**) genera ricette che usano solo ciò che hai.

**L'inferenza è 100% on-device e l'app non ha nemmeno il permesso INTERNET.** Le foto non lasciano mai il dispositivo. Il modello arriva già col canale di installazione: Google Play (AI pack) o embeddato nell'APK per i tester.

## Flusso demo

1. **Scan** → fotografa il frigorifero (chip *Fridge*), poi "Scan another" per la dispensa (*Pantry*).
2. Gli ingredienti rilevati (con quantità approssimative e confidenza) finiscono nell'inventario locale (Room).
3. **What can I cook?** → l'LLM genera 5 ricette ordinate per copertura degli ingredienti, con tempo, difficoltà, ingredienti usati/mancanti, istruzioni passo-passo e varianti.

## Architettura

```
CameraX → Image capture
        → Ingredient detection ── Gemma 3n E2B vision (LiteRT)  [richiede GPU/OpenCL]
        │                      └─ EfficientNet-Lite2 (bundlato) [fallback, sempre]
        → Room Database
        → Recipe generation ──── Gemma 3n E2B (LiteRT)
        → Jetpack Compose UI

flavor play:  Google Play ──(AI pack ×3, fast-follow)──▶ ModelRepository ──assemble──▶ .task
flavor beta:  assets APK ──(chunk ×3 nell'APK ~3.4GB)──▶ ModelRepository ──assemble──▶ .task
```

Ogni feature segue Clean Architecture + MVI (`data / domain / presentation`), DI con Koin:

- `capture/` — CameraX, `SmartIngredientDetector` (Gemma vision → classificatore bundlato → demo)
- `inventory/` — Room, inventario ingredienti
- `recipes/` — `SmartRecipeGenerator` (Gemma → demo)
- `aisetup/` — stato/gestione del modello (progresso, conferma rete mobile, delete)
- `core/data/ai/` — `LlmCatalog`, `ModelSource` (AiPacks | BundledAssets), `ModelRepository`, `LlmEngineHolder`

## Un solo modello, due canali di consegna

**Gemma 3n E2B int4** (3.1 GB, multimodale) per tutti i flavor — mai download HTTP in-app:

| Flavor | Canale | Note |
|--------|--------|------|
| `play` | **Play for On-device AI**: 3 AI pack `fast-follow` ≤1.5GB, ricomposti al primo avvio, pack rimossi dopo l'assemblaggio | AAB ~3.3GB (limite Play: 1.5GB/pack, 4GB totali — E4B non ci starebbe) |
| `beta` | **Embeddato nell'APK** in 3 chunk negli assets (AGP non impacchetta asset >2GB), ricomposti al primo avvio | APK ~3.4GB da mandare a mano ai tester; zero azioni richieste |

In entrambi i casi il primo avvio mostra "Preparing…" per ~20-60s e poi l'app è **pronta e offline per sempre**. Il modello va preparato una tantum da chi builda:

```bash
# Il .task ufficiale richiede account HF con licenza Gemma accettata:
#   https://huggingface.co/google/gemma-3n-E2B-it-litert-preview
./scripts/prepare_model_packs.sh path/to/gemma-3n-E2B-it-int4.task   # → AI pack (flavor play)
./scripts/prepare_beta_model.sh  path/to/gemma-3n-E2B-it-int4.task   # → assets APK (flavor beta)
```

I chunk sono gitignorati. La ridistribuzione (Play o APK ai tester) è coperta dalla [Gemma Terms of Use](https://ai.google.dev/gemma/terms) con passthrough delle condizioni.

### Build & distribuzione

```bash
# Tester (APK da mandare a mano):
./gradlew assembleBetaDebug     # → app/build/outputs/apk/beta/debug/app-beta-debug.apk (~3.4GB)

# Play Store:
./gradlew bundlePlayRelease     # → AAB con AI pack

# Test locale della delivery Play senza store:
./gradlew bundlePlayDebug
bundletool build-apks --bundle=app/build/outputs/bundle/playDebug/app-play-debug.aab \
  --output=aipantry.apks --local-testing --overwrite
bundletool install-apks --apks=aipantry.apks
```

⚠️ Le build APK del flavor `play` (`assemblePlayDebug`, Run di Android Studio) **non contengono gli AI pack**: per sviluppare c'è il sideload `adb push gemma-3n-E2B-it-int4.task /data/local/tmp/llm/` (ha priorità sul modello provisionato). Il flavor `beta` invece funziona anche da Android Studio.

### Detection senza attese

La detection ingredienti funziona **out-of-the-box**: EfficientNet-Lite2 (~24 MB) è bundlato nell'APK base (classi ImageNet, mapping in `IngredientLabels`). Quando Gemma è pronto, la detection passa alla **vision multimodale** — che richiede **GPU/OpenCL** (device reale; sull'emulatore il vision encoder non si inizializza e la cascata ripiega sul classificatore). Il demo mode (script Droidcon) resta come ultima rete di sicurezza e la UI mostra sempre il motore che ha realmente prodotto il risultato.

## Requisiti

JDK 17+, Android SDK 36, AGP 8.10+. `minSdk 31`, device con camera; per Gemma E2B: 6+ GB di RAM consigliati, GPU per la vision. Storage: ~6.5GB per la build beta (APK + modello provisionato), ~3.5GB per quella Play (i pack vengono rimossi dopo l'assemblaggio).

## Stack

Kotlin 2.2 · Jetpack Compose (M3) · CameraX · MediaPipe Tasks (Vision + GenAI/LiteRT) · Play AI Delivery · Room · Koin · Navigation Compose · kotlinx-serialization
