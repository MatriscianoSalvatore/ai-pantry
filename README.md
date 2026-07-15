# AI Pantry – Cook from Your Fridge 🥕📷

Demo Android (Droidcon) di una pipeline AI **completamente on-device**: fotografi frigo e dispensa, un unico LLM multimodale locale (**Gemma 4 E2B**) riconosce gli ingredienti dalla foto e genera ricette che usano solo ciò che hai — approccio "solo Gemma", senza detector ausiliari.

**L'inferenza è 100% on-device e l'app non ha nemmeno il permesso INTERNET.** Le foto non lasciano mai il dispositivo. Il modello arriva già col canale di installazione: Google Play (AI pack) o embeddato nell'APK per i tester.

## Flusso demo

1. **Scan** → fotografa il frigorifero (chip *Fridge*), poi "Scan another" per la dispensa (*Pantry*). In alternativa allo scatto puoi scegliere una foto dalla galleria.
2. Gli ingredienti rilevati (con quantità approssimative e confidenza) finiscono nell'inventario locale (Room).
3. **What can I cook?** → l'LLM genera 5 ricette ordinate per copertura degli ingredienti (quelle con più ingredienti mancanti vanno in fondo), con tempo, difficoltà, ingredienti usati/mancanti. Aprendo una ricetta, le istruzioni passo-passo e le varianti vengono generate on-demand.

## Architettura

```
CameraX / Photo picker → Image capture
        → Ingredient detection ── Gemma 4 E2B vision (LiteRT-LM, GPU-first → CPU fallback)
        → Room Database
        → Recipe generation ──── stesso Gemma 4 E2B, text-only
        │                        stage 1: lista ricette · stage 2: istruzioni on-demand
        → Jetpack Compose UI

flavor play:  Google Play ──(AI pack ×3, fast-follow)──▶ ModelRepository ──assemble──▶ .litertlm
flavor beta:  assets APK ──(chunk ×3 nell'APK)────────▶ ModelRepository ──assemble──▶ .litertlm
```

Un solo modello per tutta la pipeline: la stessa istanza LiteRT-LM (`Engine`/`Conversation`, libreria `com.google.ai.edge.litertlm`) fa detection (vision) e generazione ricette (text-only), quindi non ci sono detector ausiliari da bundlare. Il provisioning del modello LLM parte **automaticamente al primo avvio** — nessuna schermata di setup, nessun tap.

Ogni feature segue Clean Architecture + MVI (`data / domain / presentation`), DI con Koin:

- `capture/` — CameraX + photo picker, `LlmVisionIngredientDetector` (Gemma vision modality)
- `inventory/` — Room, inventario ingredienti, banner di stato del modello in home
- `recipes/` — `LlmRecipeGenerator` (generazione a due stadi + retry), `RecipeJsonParser` tollerante
- `core/data/ai/` — `LlmCatalog`, `ModelSource` (AiPacks | BundledAssets), `ModelRepository` (provisioning automatico), `LlmEngineHolder` (cache motore + policy GPU/CPU)

## Il modello: Gemma 4 E2B su LiteRT-LM, due canali di consegna

**Gemma 4 E2B, multimodale** (formato `.litertlm`, ~2.5 GB — quantizzazione mista 2/4/8-bit) per tutti i flavor — mai download HTTP in-app:

| Flavor | Canale | Note |
|--------|--------|------|
| `play` | **Play for On-device AI**: 3 AI pack `fast-follow` (~0.85 GB ciascuno), ricomposti al primo avvio, pack rimossi dopo l'assemblaggio | limite Play: 1.5 GB/pack, 4 GB totali |
| `beta` | **Embeddato nell'APK** in 3 chunk negli assets (AGP non impacchetta asset >2 GB), ricomposti al primo avvio | APK ~2.8 GB da mandare a mano ai tester; zero azioni richieste |

Perché un modello unico multimodale e non la coppia detector + LLM text-only: l'approccio "solo Gemma" evita di bundlare e mantenere detector ausiliari — un solo modello da aggiornare, un solo engine LiteRT-LM da scaldare. Il compromesso è la dimensione (~2.5 GB contro i ~554 MB di un modello 1B text-only) e una detection via vision più lenta di un classificatore dedicato.

Al primo avvio l'app mostra "Preparing Gemma 4 E2B…" per qualche secondo/minuto (copia/ricomposizione dei chunk) e poi è **pronta e offline per sempre**. Il modello va preparato una tantum da chi builda (i chunk sono gitignorati):

```bash
# Il .litertlm richiede account HF con licenza Gemma accettata:
#   https://huggingface.co/litert-community/gemma-4-E2B-it-litert-lm
./scripts/prepare_model_packs.sh path/to/gemma4-e2b-it.litertlm   # → AI pack (flavor play)
./scripts/prepare_beta_model.sh  path/to/gemma4-e2b-it.litertlm   # → assets APK (flavor beta)
```

La ridistribuzione (Play o APK ai tester) è coperta dalla [Gemma Terms of Use](https://ai.google.dev/gemma/terms) con passthrough delle condizioni.

### Backend GPU/CPU e resilienza

Il runtime LiteRT-LM (`Engine`/`Conversation`, libreria `com.google.ai.edge.litertlm`) gira **GPU-first** (più veloce, scalda meno). Su alcuni driver la GPU degrada dopo uso prolungato ed emette token spazzatura: `LlmEngineHolder` lo rileva, ricrea un engine fresco (di nuovo GPU), e solo dopo 2 corruzioni nella stessa sessione ripiega su CPU — al riavvio si riparte da GPU (auto-guarigione). La generazione ricette ritenta fino a 3 volte in silenzio prima di mostrare un errore, perché il modello ogni tanto produce JSON malformato. `RecipeJsonParser` è tollerante per costruzione (alias snake_case, `steps`/`variants` sia stringhe sia oggetti, scarto dei singoli elementi invalidi).

## Build & distribuzione

```bash
# Tester (APK da mandare a mano):
./gradlew assembleBetaDebug     # → app/build/outputs/apk/beta/debug/app-beta-debug.apk (~2.8GB)

# Play Store:
./gradlew bundlePlayRelease     # → AAB con AI pack

# Test locale della delivery Play senza store:
./gradlew bundlePlayDebug
bundletool build-apks --bundle=app/build/outputs/bundle/playDebug/app-play-debug.aab \
  --output=aipantry.apks --local-testing --overwrite
bundletool install-apks --apks=aipantry.apks
```

⚠️ Le build APK del flavor `play` (`assemblePlayDebug`, Run di Android Studio) **non contengono gli AI pack**: per sviluppare c'è il sideload `adb push gemma4-e2b-it.litertlm /data/local/tmp/llm/` (ha priorità sul modello provisionato). Il flavor `beta` invece funziona anche da Android Studio.

Nota install: gli APK con modello embeddato (~2.8 GB) possono superare il timeout del verifier di sistema (`INSTALL_FAILED_VERIFICATION_FAILURE`). Su device di test: `adb shell settings put global package_verifier_user_consent -1`.

## Riconoscimento ingredienti

Approccio "solo Gemma": **nessun detector ausiliario**. `LlmVisionIngredientDetector` passa la foto (JPEG) al modello attivo (Gemma 4 E2B) tramite `Conversation.sendMessage(Contents.of(Content.Text(prompt), Content.ImageBytes(jpeg)))` di LiteRT-LM, con un prompt che chiede un JSON con nome, quantità approssimativa e confidenza per ogni ingrediente visibile — parsing tollerante via `DetectionJsonParser`.

**Trade-off noto**: essendo un modello generalista multimodale (non un classificatore addestrato ad-hoc), la detection è più lenta di un detector dedicato e la qualità dipende dal prompt e dal modello, non da un dataset di training controllato.

## Requisiti

JDK 17+, Android SDK 36, AGP 8.10+. `minSdk 31`, device con camera; per Gemma 4 E2B multimodale: 6+ GB di RAM, GPU consigliata. Storage: ~2.8 GB per la build beta (APK + modello ricomposto), ~2.5 GB per quella Play (i pack vengono rimossi dopo l'assemblaggio).

## Stack

Kotlin 2.2 · Jetpack Compose (M3) · CameraX · LiteRT-LM (`com.google.ai.edge.litertlm`, Engine/Conversation multimodale) · Play AI Delivery · Room · Koin · Navigation Compose · kotlinx-serialization
